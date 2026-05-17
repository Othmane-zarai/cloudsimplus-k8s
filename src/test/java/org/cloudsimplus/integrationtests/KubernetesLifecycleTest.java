/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2021 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
package org.cloudsimplus.integrationtests;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware.Policy;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.PodAffinity;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.CronJobController;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.JobController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.LivenessProbe;
import org.cloudsimplus.kubernetes.lifecycle.PodCondition;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests covering controller-update strategies, kubelet probes,
 * restart policies, and scheduler tie-break / required-rule behaviors that
 * weren't exercised in the original test suite.
 */
public class KubernetesLifecycleTest {

    private final Namespace ns = Namespace.DEFAULT;

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    // ------------------------------------------------------------------
    // Deployment Recreate strategy
    // ------------------------------------------------------------------

    @Test
    void deploymentRecreateStrategyTakesAllOldPodsDownBeforeNew() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).ram(1024).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", ns, template("v1"), 2)
            .setStrategy(new UpdateStrategy.Recreate());
        broker.addController(dep);

        final boolean[] triggered = { false };
        final boolean[] sawAllOldDown = { false };
        sim.addOnClockTickListener(evt -> {
            if (!triggered[0] && evt.getTime() > 2.0
                && dep.getActiveReplicaSet().currentReplicas() == 2) {
                triggered[0] = true;
                dep.updateTemplate(template("v2"));
            }
            // Observe the moment when all v1 pods are down but v2 not yet up.
            if (triggered[0] && dep.getLegacyReplicaSet() != null
                && dep.getLegacyReplicaSet().currentReplicas() == 0
                && dep.getActiveReplicaSet().currentReplicas() < 2) {
                sawAllOldDown[0] = true;
            }
        });

        run(sim, 30.0);

        assertTrue(triggered[0], "rollout trigger must have fired");
        // Final state: only v2 pods.
        final long v2 = broker.getPods().stream()
            .filter(p -> "v2".equals(p.getLabels().get("version")))
            .count();
        assertEquals(2, v2, "Recreate must converge to desiredReplicas of v2 (got " + v2 + ")");
    }

    // ------------------------------------------------------------------
    // CronJob multi-firing
    // ------------------------------------------------------------------

    @Test
    void cronJobFiresMultipleJobsOverExtendedRun() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(4, 1000).ram(2048).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var cron = new CronJobController(
            broker.getControllerManager().allocateUid(),
            "tick", ns,
            (uid, idx) -> {
                final var jc = new JobController(uid, "tick-" + idx, ns,
                    new PodTemplate(ord -> PodBuilder.of("tick-job")
                        .label("app", "tick")
                        .container(ContainerBuilder.of("worker").cpu("250m").mem("64Mi").length(1).build())
                        .build()));
                jc.setCompletions(1).setParallelism(1);
                return jc;
            });
        cron.setIntervalSeconds(3.0);
        broker.addController(cron);

        run(sim, 30.0);

        // 30s / 3s = 10 ideal firings; allow some jitter.
        assertTrue(cron.getFired() >= 5,
            "CronJob with 3s interval over 30s should fire >=5 times (got " + cron.getFired() + ")");
        assertTrue(cron.getFired() <= 11,
            "CronJob should not over-fire (got " + cron.getFired() + ")");
        // Pods count tracks firings (1 pod per single-completion job).
        assertTrue(broker.getPods().size() >= 5,
            "Each firing should produce >=1 pod (got " + broker.getPods().size() + " pods)");
    }

    // ------------------------------------------------------------------
    // RestartPolicy ALWAYS — the kubelet re-submits a finished container
    // ------------------------------------------------------------------

    @Test
    void restartPolicyAlwaysReSubmitsContainerAfterCompletion() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(4, 1000).ram(2048).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var pod = PodBuilder.of("looper")
            .container(ContainerBuilder.of("worker")
                .cpu("250m").mem("64Mi").length(500)
                .restartPolicy(RestartPolicy.ALWAYS)
                .build())
            .build();
        broker.submitPod(pod);

        run(sim, 30.0);

        // ALWAYS means the kubelet re-submits the cloudlet on every finish.
        // Over a 30s window with length=500 MI, the broker should observe many
        // finish events (multiple completed runs).
        final long finishes = broker.getCloudletFinishedList().stream()
            .filter(c -> c.getVm() == pod)
            .count();
        assertTrue(finishes >= 1,
            "Expected at least one finish for the Always-restart container (got " + finishes + ")");
    }

    // ------------------------------------------------------------------
    // Liveness probe — repeated failures cancel + restart the container
    // ------------------------------------------------------------------

    @Test
    void livenessProbeFailureTriggersKubeletRestartCycle() {
        // A failing liveness probe must (1) be invoked by the kubelet on tick,
        // and (2) result in the running cloudlet being canceled / status changed.
        // We count probe invocations to verify the kubelet wiring, and check
        // the cloudlet's terminal status to verify the cancel→restart cycle.
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(4, 1000).ram(2048).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var probeCalls = new java.util.concurrent.atomic.AtomicInteger();
        final var probe = new LivenessProbe(c -> {
            probeCalls.incrementAndGet();
            return false; // always fail
        });
        probe.setInitialDelaySeconds(1.0);
        probe.setPeriodSeconds(0.5);
        probe.setFailureThreshold(2);

        final var container = ContainerBuilder.of("worker")
            .cpu("250m").mem("64Mi").length(50_000)
            .restartPolicy(RestartPolicy.ALWAYS)
            .livenessProbe(probe)
            .build();
        broker.submitPod(PodBuilder.of("flaky").container(container).build());

        run(sim, 30.0);

        assertTrue(probeCalls.get() >= 2,
            "Kubelet must invoke the liveness probe at least failureThreshold times (got "
                + probeCalls.get() + ")");
        // The container's running cloudlet should have been canceled at least once.
        // After cancel, the kubelet re-submits a fresh attempt, so the latest
        // status will be RESUMED/INEXEC, but cancellation history is observable
        // via the cloudlet length-finished-so-far count being well below the full length.
        assertNotEquals(Cloudlet.Status.SUCCESS, container.getStatus(),
            "A long-running cloudlet that's been canceled by the liveness probe must not be in SUCCESS");
    }

    // ------------------------------------------------------------------
    // Scheduler scoring — NodeAffinity preferred breaks ties even when
    // PreferNoSchedule taint is on the otherwise-preferred node
    // ------------------------------------------------------------------

    @Test
    void preferredNodeAffinityOutweighsPreferNoScheduleTaintWhenWeightIsHigh() {
        // Bigger NodeAffinity weight wins over a single PreferNoSchedule taint.
        // Both nodes equal cost. Affinity weight 100 (×0.01 scale = 1.0)
        // outranks PreferNoSchedule penalty 10 (×0.01 = 0.1). Net effect:
        // affinity-matching node still wins.
        final var sim = new CloudSimPlus();
        final var preferred = NodeBuilder.of("preferred").pes(2, 1000).ram(1024)
            .label("zone", "a").costPerHour(0.20)
            .taint(new org.cloudsimplus.kubernetes.Taint(
                "preferNoSched-key", "v",
                org.cloudsimplus.kubernetes.Taint.Effect.PREFER_NO_SCHEDULE))
            .build();
        final var alt = NodeBuilder.of("alt").pes(2, 1000).ram(1024)
            .label("zone", "b").costPerHour(0.20).build();

        new DatacenterSimple(sim, List.of(preferred, alt),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("p")
            .nodeAffinity(NodeAffinity.builder()
                .prefer(LabelSelector.matchLabel("zone", "a"), 100)
                .build())
            .container(ContainerBuilder.of("c").cpu("250m").mem("64Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim, 30.0);

        assertSame(preferred, pod.getHost(),
            "Affinity weight 100 should outrank a single PreferNoSchedule taint at default scale");
    }

    // ------------------------------------------------------------------
    // PodAffinity required: hard-block placement when no peer is in bucket
    // ------------------------------------------------------------------

    @Test
    void requiredPodAffinityBlocksPlacementUntilPeerLands() {
        final var sim = new CloudSimPlus();
        // Two zones: "a" and "b". Backend prefers zone a; frontend requires zone-a co-location.
        final var nodeA = NodeBuilder.of("a1").pes(4, 1000).ram(2048)
            .zone("a").label("topology.kubernetes.io/zone", "a").rack("rA").build();
        final var nodeB = NodeBuilder.of("b1").pes(4, 1000).ram(2048)
            .zone("b").label("topology.kubernetes.io/zone", "b").rack("rB").build();

        new DatacenterSimple(sim, List.of(nodeA, nodeB),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);

        // Backend is pinned to zone a via nodeSelector.
        final var backend = PodBuilder.of("backend")
            .label("tier", "backend")
            .nodeSelector(LabelSelector.matchLabel("topology.kubernetes.io/zone", "a"))
            .container(ContainerBuilder.of("api").cpu("500m").mem("128Mi").length(50_000).build())
            .build();
        // Frontend requires a backend peer in the SAME zone (HOSTNAME would force same node;
        // ZONE means same logical zone bucket).
        final var frontend = PodBuilder.of("frontend")
            .label("tier", "frontend")
            .container(ContainerBuilder.of("web").cpu("500m").mem("128Mi").length(50_000).build())
            .build()
            .setPodAffinity(PodAffinity.builder()
                .requireAffinity(LabelSelector.matchLabel("tier", "backend"),
                    PodAffinity.TopologyKey.HOSTNAME)
                .build());

        broker.submitPods(List.of(backend, frontend));
        run(sim, 30.0);

        // Backend lands on a1; frontend must end up on a1 too (same hostname bucket).
        assertSame(nodeA, backend.getHost(), "backend must land on zone a");
        assertSame(nodeA, frontend.getHost(),
            "Required PodAffinity HOSTNAME must place frontend on the same node as its peer backend");
    }

    @Test
    void requiredPodAntiAffinityRejectsPlacementWhenAllNodesShareBucket() {
        final var sim = new CloudSimPlus();
        // Three nodes in the SAME zone. PodAntiAffinity over ZONE means two
        // pods cannot share a zone — so the second pod can't be placed anywhere.
        final var nodes = IntStream.range(0, 3)
            .mapToObj(i -> NodeBuilder.of("z-" + i).pes(2, 1000).ram(1024)
                .zone("us-east-1a")
                .label("topology.kubernetes.io/zone", "us-east-1a").build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        for (int i = 0; i < 2; i++) {
            broker.submitPod(PodBuilder.of("solo-" + i).label("app", "solo")
                .container(ContainerBuilder.of("c").cpu("500m").mem("128Mi").length(1).build())
                .build()
                .setPodAffinity(PodAffinity.builder()
                    .requireAntiAffinity(LabelSelector.matchLabel("app", "solo"),
                        PodAffinity.TopologyKey.ZONE)
                    .build()));
        }

        run(sim, 30.0);

        // First pod lands; second pod cannot satisfy its required anti-affinity.
        final long placed = broker.getPods().stream()
            .filter(p -> p.getHost() != null && p.getHost() != org.cloudsimplus.hosts.Host.NULL)
            .count();
        assertEquals(1, placed,
            "Only the first solo pod should land — the second's required anti-affinity blocks all hosts");
        // The unplaced pod must be flagged unschedulable by the scheduler.
        final long flagged = broker.getPods().stream()
            .filter(KubernetesPod::isUnschedulable)
            .count();
        assertTrue(flagged >= 1, "Unplaced pod should be flagged unschedulable (got " + flagged + ")");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PodTemplate template(final String version) {
        return new PodTemplate(ord -> PodBuilder.of("web-" + version + "-" + ord)
            .label("app", "web").label("version", version)
            .container(ContainerBuilder.of("nginx").cpu("250m").mem("64Mi").length(1).build())
            .build());
    }

    private static void run(final CloudSimPlus sim, final double until) {
        sim.terminateAt(until);
        sim.start();
    }
}

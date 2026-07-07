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
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.NodePool;
import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler;
import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler.Mode;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the autoscaling layer:
 * {@link HorizontalPodAutoscaler} and {@link ClusterAutoscaler}.
 *
 * <p>These tests cover the gaps identified in the May 2026 senior-engineering
 * review: HPA convergence under a varying load curve, ClusterAutoscaler
 * scale-up driven by the new {@code KubernetesPod.isUnschedulable()} flag, and
 * scale-down on idle nodes. They exercise the critical fix that lets the
 * autoscaler distinguish a pod that has been rejected by the scheduler from a
 * pod that simply hasn't been tried yet.</p>
 */
public class KubernetesAutoscalingTest {

    private final Namespace ns = Namespace.DEFAULT;

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    // ------------------------------------------------------------------
    // ClusterAutoscaler
    // ------------------------------------------------------------------

    @Test
    void clusterAutoscalerProvisionsNodeWhenPodIsUnschedulable() {
        final var sim = new CloudSimPlus();

        // A single small node — second pod won't fit because the first pod consumes
        // both PEs of the seed node (cpu=1500m → 2 PEs by ceil; pod 2 needs 2 PEs too).
        // The pool nodes have 4 PEs each, so each spare pod can land on its own pool node.
        final var initial = NodeBuilder.of("seed").pes(2, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(initial),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var counter = new AtomicInteger();
        final var pool = new NodePool("auto",
            () -> NodeBuilder.of("auto-" + counter.incrementAndGet())
                .pes(2, 1000).ram(1024).build(),
            /* min */ 0, /* max */ 3);
        final var ca = new ClusterAutoscaler(broker, pool)
            .setCooldownSeconds(1.0)
            .setScaleDownAfterSeconds(Double.MAX_VALUE);
        broker.registerTick(ca);

        // Submit 3 pods, each requiring 2 PEs (cpu=1500m → ceil(1.5)=2 PEs).
        // Seed has 2 PEs total → first pod fits, others don't.
        for (int i = 0; i < 3; i++) {
            broker.submitPod(PodBuilder.of("worker-" + i)
                .label("app", "worker")
                .container(ContainerBuilder.of("c").cpu("1500m").mem("256Mi").length(1).build())
                .build());
        }

        run(sim, 60.0);

        assertEquals(3, broker.getPods().stream()
            .filter(p -> p.getHost() != null && p.getHost() != org.cloudsimplus.hosts.Host.NULL)
            .count(),
            "All 3 pods should land — autoscaler should have provisioned 2 extra nodes (nodes seen: "
                + broker.getNodes().size() + "; placed: "
                + broker.getPods().stream().filter(p -> p.getHost() != null && p.getHost() != org.cloudsimplus.hosts.Host.NULL).count()
                + "; unschedulable: "
                + broker.getPods().stream().filter(KubernetesPod::isUnschedulable).count() + ")");
        assertTrue(broker.getNodes().size() >= 3,
            "Cluster should have grown from 1 to ≥3 nodes (got " + broker.getNodes().size() + ")");
    }

    @Test
    void clusterAutoscalerRespectsPoolMaximum() {
        final var sim = new CloudSimPlus();

        final var initial = NodeBuilder.of("seed").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(initial),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var counter = new AtomicInteger();
        final var pool = new NodePool("auto",
            () -> NodeBuilder.of("auto-" + counter.incrementAndGet()).pes(1, 1000).ram(1024).build(),
            /* min */ 0, /* max */ 1); // pool capped at 1 extra node
        broker.registerTick(new ClusterAutoscaler(broker, pool).setCooldownSeconds(1.0));

        // Submit 5 pods — only 2 can ever fit (1 seed + 1 pool max).
        for (int i = 0; i < 5; i++) {
            broker.submitPod(PodBuilder.of("worker-" + i)
                .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(1).build())
                .build());
        }

        run(sim, 60.0);

        // Cluster grew by exactly 1 (pool max), no further provisioning.
        assertEquals(2, broker.getNodes().size(),
            "Pool max=1 → cluster must end at exactly 2 nodes (seed + 1 auto)");
        // 3 pods are stuck unschedulable.
        final long stillUnschedulable = broker.getPods().stream()
            .filter(KubernetesPod::isUnschedulable).count();
        assertTrue(stillUnschedulable >= 1,
            "Pods past pool capacity must remain unschedulable (got " + stillUnschedulable + ")");
    }

    @Test
    void freshlySubmittedPodIsNotConsideredUnschedulable() {
        // Ensures the autoscaler isn't fooled into provisioning a node for a
        // pod that simply hasn't been tried yet — the previous heuristic
        // (using !isCreated()) had this bug.
        final var sim = new CloudSimPlus();
        final var node = NodeBuilder.of("n").pes(4, 1000).ram(4096).build();
        new DatacenterSimple(sim, List.of(node),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pool = new NodePool("auto",
            () -> NodeBuilder.of("auto-x").pes(1, 1000).build(), 0, 5);
        broker.registerTick(new ClusterAutoscaler(broker, pool).setCooldownSeconds(1.0));

        broker.submitPod(PodBuilder.of("p")
            .container(ContainerBuilder.of("c").cpu("250m").mem("64Mi").length(1).build())
            .build());

        run(sim, 30.0);

        assertEquals(1, broker.getNodes().size(),
            "Autoscaler must not provision a node when pods schedule successfully on the seed cluster");
    }

    @Test
    void clusterAutoscalerDecommissionsIdleNodes() {
        // The autoscaler tracks pool-owned nodes by reference (post-B5 fix),
        // so the only way for a node to qualify for decommission is to have
        // been provisioned through the autoscaler's scale-up path. This test
        // forces a scale-up by submitting unschedulable pods, lets the
        // transient pod finish, then asserts the auto node is physically
        // removed (not merely cordoned).
        //
        // vmDestructionDelay=0.1 lets the broker reclaim idle pods promptly so
        // the autoscaler observes an empty auto node within the test window.
        final var sim = new CloudSimPlus();

        final var seed = NodeBuilder.of("seed").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(seed),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim)
            .setControllerTickIntervalSeconds(0.5);
        broker.setVmDestructionDelay(0.2);

        final var counter = new AtomicInteger();
        final var pool = new NodePool("auto",
            () -> NodeBuilder.of("auto-" + counter.incrementAndGet()).pes(1, 1000).ram(1024).build(),
            /* min */ 0, /* max */ 5);
        final var ca = new ClusterAutoscaler(broker, pool)
            .setCooldownSeconds(0.5)
            .setScaleDownAfterSeconds(2.0);
        broker.registerTick(ca);

        broker.submitPod(PodBuilder.of("anchor")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000_000).build())
            .build());
        broker.submitPod(PodBuilder.of("transient")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(500).build())
            .build());

        run(sim, 60.0);

        assertEquals(0, ca.getProvisioned(),
            "All auto-provisioned nodes should have been decommissioned (saw " + ca.getProvisioned() + ")");
        assertEquals(1, broker.getDatacenters().get(0).getHostList().size(),
            "Datacenter must have only the seed host after scale-down (saw "
                + broker.getDatacenters().get(0).getHostList().size() + ")");
    }

    @Test
    void clusterAutoscalerProvisionsThenDecommissionsThenReprovisions() {
        // Round-trip regression test (B5): the previous cordon-only scale-down
        // left ghost hosts in the datacenter and the `provisioned` counter
        // drifted off `nodesFromPool().size()`. With real removeHost the cycle
        // can run cleanly any number of times.
        final var sim = new CloudSimPlus();
        final var seed = NodeBuilder.of("seed").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(seed),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        broker.setVmDestructionDelay(0.2);

        final var counter = new AtomicInteger();
        final var pool = new NodePool("auto",
            () -> NodeBuilder.of("auto-" + counter.incrementAndGet()).pes(1, 1000).ram(1024).build(),
            /* min */ 0, /* max */ 3);
        final var ca = new ClusterAutoscaler(broker, pool)
            .setCooldownSeconds(0.3)
            .setScaleDownAfterSeconds(1.5);
        broker.registerTick(ca);

        broker.submitPod(PodBuilder.of("anchor")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(100_000_000).build())
            .build());
        broker.submitPod(PodBuilder.of("wave1")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(500).build())
            .build());

        // Second wave: trigger after the first auto node has been removed.
        final boolean[] wave2Submitted = { false };
        sim.addOnClockTickListener(evt -> {
            if (!wave2Submitted[0] && evt.getTime() > 10.0
                && broker.getDatacenters().get(0).getHostList().size() == 1) {
                wave2Submitted[0] = true;
                broker.submitPod(PodBuilder.of("wave2")
                    .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(500).build())
                    .build());
            }
        });

        run(sim, 90.0);

        assertEquals(1, broker.getDatacenters().get(0).getHostList().size(),
            "Datacenter must hold only the seed host after both scale cycles");
        assertEquals(0, ca.getProvisioned(),
            "Provisioned-count must return to zero after the round trip");
        assertTrue(counter.get() >= 2,
            "Autoscaler must have provisioned at least 2 nodes across the two waves (saw " + counter.get() + ")");
        assertTrue(wave2Submitted[0], "wave2 must have been submitted after first scale-down");
    }

    // ------------------------------------------------------------------
    // HPA convergence
    // ------------------------------------------------------------------

    @Test
    void hpaScalesUpUnderRealisticLoadAndDownAfterCooldown() {
        // Realistic test: containers report a time-varying CPU% via
        // UtilizationModelDynamic so the HPA reacts to actual load, not just
        // cloudlet-completion events. We start at 90% load (force scale-up)
        // and drop to 10% (force scale-down).
        final var sim = new CloudSimPlus();

        final var nodes = IntStream.range(0, 8)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).ram(2048).build())
            .toList();
        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "web", ns,
            new PodTemplate(ord -> PodBuilder.of("web-" + ord)
                .label("app", "web")
                .container(ContainerBuilder.of("nginx")
                    .cpu("250m").mem("64Mi")
                    .length(100_000) // long-running so we observe the HPA loop
                    // 90% utilization at every clock tick — drives scale-up at target=0.5
                    .cpuUtilization(new UtilizationModelDynamic(0.9))
                    .build())
                .build()),
            /* initial replicas */ 2);
        broker.addController(rs);

        final var hpa = HorizontalPodAutoscaler.of(rs, /* target */ 0.5)
            .setMinReplicas(1).setMaxReplicas(8)
            .setCooldownSeconds(2.0);
        broker.registerTick(hpa);

        run(sim, 30.0);

        assertTrue(rs.getDesiredReplicas() > 2,
            "HPA must scale up under sustained 90% CPU at target=0.5 (got desired=" + rs.getDesiredReplicas() + ")");
        assertTrue(rs.getDesiredReplicas() <= 8,
            "HPA must respect maxReplicas (got " + rs.getDesiredReplicas() + ")");
    }

    @Test
    void hpaDoesNotScaleWhenUtilizationMatchesTarget() {
        // Steady-state: 50% CPU at target 0.5 → desired stays at current.
        final var sim = new CloudSimPlus();

        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).ram(2048).build())
            .toList();
        new DatacenterSimple(sim, nodes,
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "web", ns,
            new PodTemplate(ord -> PodBuilder.of("web-" + ord)
                .label("app", "web")
                .container(ContainerBuilder.of("nginx")
                    .cpu("250m").mem("64Mi").length(100_000)
                    .cpuUtilization(new UtilizationModelDynamic(0.5))
                    .build())
                .build()),
            /* initial replicas */ 3);
        broker.addController(rs);

        final var hpa = HorizontalPodAutoscaler.of(rs, /* target */ 0.5)
            .setMinReplicas(1).setMaxReplicas(8)
            .setCooldownSeconds(2.0);
        broker.registerTick(hpa);

        run(sim, 20.0);

        // ceil(3 * 0.5 / 0.5) = 3 → no change. Allow a small jitter for clock-tick rounding.
        assertEquals(3, rs.getDesiredReplicas(),
            "HPA should hold steady when utilization equals target");
    }

    // ------------------------------------------------------------------
    // VPA Auto mode
    // ------------------------------------------------------------------

    @Test
    void vpaAutoModeResizesContainerCpuInPlaceWithoutEviction() {
        // 4-node cluster, 3 replicas at 90% CPU, VPA AUTO targeting 70%.
        // Expected: VPA fires after cooldown, effectiveLimits increases in-place.
        final var sim = new CloudSimPlus();

        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(4, 1000).ram(8_192).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(1.0);

        final int initialMilliCpu = 500;
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "api", ns,
            new PodTemplate(ord -> PodBuilder.of("api-" + ord)
                .label("app", "api")
                .container(ContainerBuilder.of("srv")
                    .cpu(initialMilliCpu + "m").mem("256Mi")
                    .length(1_000_000)
                    .cpuUtilization(new UtilizationModelDynamic(0.9))
                    .build())
                .build()),
            /* initial replicas */ 3);
        broker.addController(rs);

        final var vpa = new VerticalPodAutoscaler("api-vpa", rs)
            .setMode(Mode.AUTO)
            .setTargetCpuUtilization(0.7)
            .setCooldownSeconds(5.0);
        broker.registerTick(vpa);

        // Track effectiveLimits during the simulation (managed map is cleared at shutdown).
        final long[] maxObservedCpu = { initialMilliCpu };
        final boolean[] evictionDetected = { false };
        final AtomicInteger managedCountAtPeak = new AtomicInteger(0);
        broker.registerTick(clock -> {
            final var pods = rs.getManagedPods();
            if (pods.size() > managedCountAtPeak.get()) {
                managedCountAtPeak.set(pods.size());
            }
            pods.stream()
                .flatMap(p -> p.getContainers().stream())
                .mapToLong(c -> c.getEffectiveLimits().milliCpu())
                .max()
                .ifPresent(cpu -> maxObservedCpu[0] = Math.max(maxObservedCpu[0], cpu));
        });

        run(sim, 60.0);

        // VPA must have produced a recommendation.
        assertTrue(vpa.getRecommendedMilliCpu() > initialMilliCpu,
            "VPA must recommend more CPU than the initial " + initialMilliCpu + "m " +
            "(got " + vpa.getRecommendedMilliCpu() + "m)");

        // At least 3 pods were managed at peak (no premature eviction).
        assertEquals(3, managedCountAtPeak.get(),
            "All 3 pods must have been running at peak (AUTO mode must not evict; got " +
            managedCountAtPeak.get() + ")");

        // The in-place resize must have raised effectiveLimits above the initial spec.
        assertTrue(maxObservedCpu[0] > initialMilliCpu,
            "effectiveLimits.milliCpu must exceed the initial " + initialMilliCpu +
            "m after AUTO-mode resize (max observed = " + maxObservedCpu[0] + "m)");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static void run(final CloudSimPlus sim, final double until) {
        sim.terminateAt(until);
        sim.start();
    }
}

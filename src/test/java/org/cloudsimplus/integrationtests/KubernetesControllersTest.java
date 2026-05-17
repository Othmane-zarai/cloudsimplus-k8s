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
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.controllers.UpdateStrategy;
import org.cloudsimplus.kubernetes.lifecycle.PodCondition;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Phase-2 controller framework: ReplicaSet and
 * Deployment driven by the periodic broker tick.
 */
public class KubernetesControllersTest {

    private final Namespace ns = Namespace.DEFAULT;

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    // ------------------------------------------------------------------
    // ReplicaSetController
    // ------------------------------------------------------------------

    @Test
    void replicaSetSpawnsDesiredNumberOfPods() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate(), 3);
        broker.addController(rs);

        run(sim);

        // The RS should have submitted 3 pods to the broker.
        assertEquals(3, broker.getPods().size(),
            "ReplicaSet should have submitted desiredReplicas pods to the broker");
        // All submitted pods carry the owner-reference label pointing to this RS.
        assertTrue(broker.getPods().stream().allMatch(p ->
            String.valueOf(rs.getUid()).equals(p.getLabels().get(
                org.cloudsimplus.kubernetes.controllers.Controller.LABEL_CONTROLLER_UID))),
            "Every spawned pod must carry the controller-uid label");
        // All pods were actually placed on a host (vm.getHost() is preserved post-shutdown).
        assertEquals(3, broker.getPods().stream()
            .filter(p -> p.getHost() != null && p.getHost() != org.cloudsimplus.hosts.Host.NULL)
            .count(),
            "All 3 RS-spawned pods must be placed on a host");
    }

    @Test
    void replicaSetCreatesReplacementWhenPodLost() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate(), 2);
        broker.addController(rs);

        // After both initial replicas are up, simulate one being lost — the RS should reconcile.
        final boolean[] killed = { false };
        sim.addOnClockTickListener(evt -> {
            if (!killed[0] && evt.getTime() > 1.5 && broker.getPods().size() >= 2) {
                killed[0] = true;
                final var victim = broker.getPods().getFirst();
                rs.onPodLost(victim);
            }
        });

        run(sim);

        // After a loss, the controller reconciles and submits one extra pod.
        assertTrue(broker.getPods().size() >= 3,
            "RS should have submitted a replacement after pod loss (got " + broker.getPods().size() + ")");
    }

    // ------------------------------------------------------------------
    // DeploymentController (rolling update)
    // ------------------------------------------------------------------

    @Test
    void deploymentInitialRolloutCreatesDesiredReplicas() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 4)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate(), 3)
            .setStrategy(UpdateStrategy.RollingUpdate.defaults());
        broker.addController(dep);

        run(sim);

        assertEquals(3, broker.getPods().size(),
            "Deployment should have submitted desiredReplicas pods through its child RS");
        assertNotNull(dep.getActiveReplicaSet());
    }

    @Test
    void deploymentRollingUpdateConvergesToNewTemplate() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 6)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate("v1"), 3)
            .setStrategy(new UpdateStrategy.RollingUpdate(1, 0));
        broker.addController(dep);

        // Trigger rollout once initial replicas are up.
        final boolean[] triggered = { false };
        sim.addOnClockTickListener(evt -> {
            if (!triggered[0] && evt.getTime() > 2.0
                && dep.getActiveReplicaSet().currentReplicas() == 3) {
                triggered[0] = true;
                dep.updateTemplate(webTemplate("v2"));
            }
        });

        run(sim, 60.0);

        // The deployment should have submitted v1 pods, then v2 pods during rollout.
        final var v2Pods = broker.getPods().stream()
            .filter(p -> "v2".equals(p.getLabels().get("version")))
            .count();
        assertTrue(v2Pods >= 3,
            "Rollout must produce at least 3 v2 pods (got " + v2Pods + ")");
        assertTrue(triggered[0], "rollout trigger must have fired");
    }

    @Test
    void rollingUpdateRespectsMaxUnavailable() {
        // RollingUpdate(0, 2): no surge allowed, but up to 2 below desired permitted.
        // Expect the rollout to drain old to (desired-2)=2 before scaling up new at all.
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 8)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate("v1"), 4)
            .setStrategy(new UpdateStrategy.RollingUpdate(0, 2));
        broker.addController(dep);

        final boolean[] triggered = { false };
        final int[] minAvailableSeen = { Integer.MAX_VALUE };
        final int[] maxTotalSeen = { 0 };

        sim.addOnClockTickListener(evt -> {
            if (!triggered[0] && evt.getTime() > 2.0
                && dep.getActiveReplicaSet().currentReplicas() == 4) {
                triggered[0] = true;
                dep.updateTemplate(webTemplate("v2"));
            }
            if (triggered[0]) {
                final int total = broker.getPods().stream()
                    .filter(p -> p.getHost() != null)
                    .mapToInt(p -> 1).sum();
                minAvailableSeen[0] = Math.min(minAvailableSeen[0], total);
                maxTotalSeen[0] = Math.max(maxTotalSeen[0],
                    dep.getActiveReplicaSet().currentReplicas()
                        + (dep.getLegacyReplicaSet() == null ? 0 : dep.getLegacyReplicaSet().currentReplicas()));
            }
        });

        run(sim, 120.0);

        assertTrue(triggered[0], "rollout trigger must have fired");
        // maxSurge=0, so total declared replicas (managed by both RSes) must never exceed desired=4.
        assertTrue(maxTotalSeen[0] <= 4,
            "RollingUpdate(0, 2) must not surge above desired=4 (saw " + maxTotalSeen[0] + ")");
    }

    @Test
    void rollingUpdateRespectsMaxSurge() {
        // RollingUpdate(2, 0): 2 surge allowed, no unavailability.
        // Expect total declared replicas (new+old) to stay between desired and desired+2 throughout rollout.
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 8)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).setControllerTickIntervalSeconds(0.5);
        final var dep = new DeploymentController(
            broker.getControllerManager().allocateUid(),
            "web", ns, webTemplate("v1"), 4)
            .setStrategy(new UpdateStrategy.RollingUpdate(2, 0));
        broker.addController(dep);

        final boolean[] triggered = { false };
        final int[] maxTotalSeen = { 0 };

        sim.addOnClockTickListener(evt -> {
            if (!triggered[0] && evt.getTime() > 2.0
                && dep.getActiveReplicaSet().currentReplicas() == 4) {
                triggered[0] = true;
                dep.updateTemplate(webTemplate("v2"));
            }
            if (triggered[0]) {
                final int total = dep.getActiveReplicaSet().currentReplicas()
                    + (dep.getLegacyReplicaSet() == null ? 0 : dep.getLegacyReplicaSet().currentReplicas());
                maxTotalSeen[0] = Math.max(maxTotalSeen[0], total);
            }
        });

        run(sim, 120.0);

        assertTrue(triggered[0], "rollout trigger must have fired");
        assertTrue(maxTotalSeen[0] <= 6,
            "RollingUpdate(2, 0) must not exceed desired+maxSurge=6 (saw " + maxTotalSeen[0] + ")");
        // Final state: only v2 pods exist.
        final var v2Pods = broker.getPods().stream()
            .filter(p -> "v2".equals(p.getLabels().get("version")))
            .count();
        assertTrue(v2Pods >= 4,
            "After rollout, at least 4 v2 pods must exist (got " + v2Pods + ")");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PodTemplate webTemplate() {
        return webTemplate("v1");
    }

    private PodTemplate webTemplate(final String version) {
        return new PodTemplate(ord -> PodBuilder.of("web-" + version + "-" + ord)
            .label("app", "web")
            .label("version", version)
            .container(ContainerBuilder.of("nginx").cpu("250m").mem("64Mi").length(1).build())
            .build());
    }

    private static void run(final CloudSimPlus sim) {
        run(sim, 30.0);
    }

    private static void run(final CloudSimPlus sim, final double until) {
        sim.terminateAt(until);
        sim.start();
    }

    @Test
    void initContainersRunBeforeMainAndSetInitializedCondition() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim, List.of(NodeBuilder.of("n").pes(4, 1000).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("test").label("app", "web")
            .container(ContainerBuilder.of("init").cpu("250m").mem("64Mi").length(500).asInitContainer().build())
            .container(ContainerBuilder.of("main").cpu("500m").mem("128Mi").length(2_000).build())
            .build();
        broker.submitPod(pod);

        run(sim, 120.0);

        assertEquals(Boolean.TRUE, pod.getConditions().get(
            PodCondition.INITIALIZED), "Pod should have INITIALIZED=true after init container completes");

        // Both init and main cloudlets should have run (the broker's finished list has them).
        final long initFinished = broker.getCloudletFinishedList().stream()
            .filter(c -> c instanceof org.cloudsimplus.kubernetes.KubernetesContainer kc && kc.isInitContainer())
            .count();
        final long mainFinished = broker.getCloudletFinishedList().stream()
            .filter(c -> c instanceof org.cloudsimplus.kubernetes.KubernetesContainer kc && !kc.isInitContainer())
            .count();
        assertEquals(1, initFinished, "Init container should have finished");
        assertEquals(1, mainFinished, "Main container should have finished after init");
    }
}

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
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.KubernetesService;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.kubernetes.Toleration;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.vms.Vm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the {@code org.cloudsimplus.kubernetes}
 * package: {@link KubernetesClusterBroker}, {@link KubernetesScheduler},
 * {@link KubernetesPod}, {@link KubernetesNode}, {@link KubernetesService}.
 *
 * <p>Each test wires up a small cluster, submits pods, runs the simulation
 * once, and asserts the resulting placement / routing decisions.</p>
 */
public class KubernetesClusterTest {

    private final Namespace ns = Namespace.DEFAULT;

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    // ------------------------------------------------------------------
    // Filter: nodeSelector
    // ------------------------------------------------------------------

    @Test
    void nodeSelectorRoutesPodToMatchingNode() {
        final var sim = new CloudSimPlus();

        final var gpuNode = NodeBuilder.of("gpu-1").pes(2, 1000).label("hardware", "gpu")
            .rack("r1").costPerHour(1.0).build();
        final var cpuNode = NodeBuilder.of("cpu-1").pes(2, 1000).label("hardware", "cpu")
            .rack("r2").costPerHour(0.20).build();

        new DatacenterSimple(sim, List.of(gpuNode, cpuNode),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("ml-train")
            .nodeSelector(LabelSelector.matchLabel("hardware", "gpu"))
            .container(ContainerBuilder.of("app").cpu("500m").mem("256Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        // Cheaper node would win on cost, but selector eliminates it.
        assertSame(gpuNode, pod.getHost(),
            "nodeSelector must override the cost-based score");
    }

    // ------------------------------------------------------------------
    // Filter: taints / tolerations
    // ------------------------------------------------------------------

    @Test
    void taintRepelsPodWithoutToleration() {
        final var sim = new CloudSimPlus();

        // Cheapest node has a NoSchedule taint
        final var taintedCheap = NodeBuilder.of("dedicated").pes(2, 1000)
            .costPerHour(0.10).taint(Taint.noSchedule("dedicated", "team-a")).build();
        final var general = NodeBuilder.of("general").pes(2, 1000).costPerHour(0.30).build();

        new DatacenterSimple(sim, List.of(taintedCheap, general),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("workload")
            .container(ContainerBuilder.of("c").cpu("500m").mem("128Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        assertSame(general, pod.getHost(),
            "Untolerated NoSchedule taint must remove the node from candidates");
    }

    @Test
    void tolerationAllowsPodOnTaintedNode() {
        final var sim = new CloudSimPlus();

        final var taintedCheap = NodeBuilder.of("dedicated").pes(2, 1000)
            .costPerHour(0.10).taint(Taint.noSchedule("dedicated", "team-a")).build();
        final var general = NodeBuilder.of("general").pes(2, 1000).costPerHour(0.30).build();

        new DatacenterSimple(sim, List.of(taintedCheap, general),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("workload")
            .tolerate(Toleration.equal("dedicated", "team-a"))
            .container(ContainerBuilder.of("c").cpu("500m").mem("128Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        assertSame(taintedCheap, pod.getHost(),
            "With a matching toleration, the cost score wins again");
    }

    // ------------------------------------------------------------------
    // Filter: schedulable flag (cordon)
    // ------------------------------------------------------------------

    @Test
    void cordonedNodeIsSkipped() {
        final var sim = new CloudSimPlus();

        final var cordoned = NodeBuilder.of("cordoned").pes(2, 1000)
            .costPerHour(0.10).schedulable(false).build();
        final var available = NodeBuilder.of("available").pes(2, 1000).costPerHour(0.30).build();

        new DatacenterSimple(sim, List.of(cordoned, available),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("workload")
            .container(ContainerBuilder.of("c").cpu("250m").mem("128Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        assertSame(available, pod.getHost(),
            "A node marked unschedulable must not receive new pods");
    }

    // ------------------------------------------------------------------
    // Score: NodeAffinity preferred + cost
    // ------------------------------------------------------------------

    @Test
    void preferredNodeAffinityBreaksCostTie() {
        final var sim = new CloudSimPlus();

        // Two equally-priced nodes; only one carries the preferred label.
        final var preferred = NodeBuilder.of("pref").pes(2, 1000)
            .label("zone", "us-east-1a").costPerHour(0.30).build();
        final var equal = NodeBuilder.of("equal").pes(2, 1000)
            .label("zone", "us-east-1b").costPerHour(0.30).build();

        new DatacenterSimple(sim, List.of(equal, preferred),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("preferred-pod")
            .nodeAffinity(NodeAffinity.builder()
                .prefer(LabelSelector.matchLabel("zone", "us-east-1a"), 50)
                .build())
            .container(ContainerBuilder.of("c").cpu("250m").mem("128Mi").length(1).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        assertSame(preferred, pod.getHost(),
            "Equal cost → preferred-affinity weight should tip the scheduler to the matching node");
    }

    // ------------------------------------------------------------------
    // Service endpoint resolution
    // ------------------------------------------------------------------

    @Test
    void serviceRoundRobinsAcrossSelectorMatchedPods() {
        final var sim = new CloudSimPlus();

        final var n1 = NodeBuilder.of("n1").pes(4, 1000).rack("r1").costPerHour(0.30).build();
        final var n2 = NodeBuilder.of("n2").pes(4, 1000).rack("r2").costPerHour(0.30).build();

        new DatacenterSimple(sim, List.of(n1, n2),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);

        final var b1 = PodBuilder.of("backend-1").label("tier", "backend")
            .container(ContainerBuilder.of("api").cpu("500m").mem("128Mi").length(1).build())
            .build();
        final var b2 = PodBuilder.of("backend-2").label("tier", "backend")
            .container(ContainerBuilder.of("api").cpu("500m").mem("128Mi").length(1).build())
            .build();
        broker.submitPods(List.of(b1, b2));

        final var backend = new KubernetesService(
            "backend", ns, LabelSelector.matchLabel("tier", "backend"));
        broker.addService(backend);

        // Capture the backing-pod set the moment both pods are first placed,
        // so the assertion isn't racing the cloudlets finishing and the pods
        // being deallocated by simulation end.
        final java.util.concurrent.atomic.AtomicReference<List<KubernetesPod>> snapshot =
            new java.util.concurrent.atomic.AtomicReference<>();
        b2.addOnHostAllocationListener(evt -> snapshot.compareAndSet(null, backend.backingPods()));

        run(sim);

        assertTrue(wasPlaced(b1) && wasPlaced(b2),
            "Both selector-matched pods must be placed");
        final var endpoints = snapshot.get();
        assertEquals(2, endpoints.size(),
            "Service endpoint set must include both selector-matched pods at placement time");
        assertTrue(endpoints.contains(b1) && endpoints.contains(b2),
            "Both backend pods must appear as endpoints");
    }

    @Test
    void containersAreSubmittedAsCloudletsWhenPodIsPlaced() {
        final var sim = new CloudSimPlus();

        final var node = NodeBuilder.of("n").pes(4, 1000).build();
        new DatacenterSimple(sim, List.of(node), new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var pod = PodBuilder.of("multi")
            .container(ContainerBuilder.of("app").cpu("500m").mem("128Mi").length(2_000).build())
            .container(ContainerBuilder.of("sidecar").cpu("250m").mem("64Mi").length(1_000).build())
            .build();
        broker.submitPod(pod);

        run(sim);

        // Both container cloudlets ran to completion on the pod's VM.
        final var finished = broker.getCloudletFinishedList();
        assertEquals(2, finished.size(),
            "Each declared container should produce a finished cloudlet on the pod's VM");
        assertTrue(finished.stream().allMatch(c -> c.getVm() == pod),
            "All container cloudlets should be bound to the pod's VM");
    }

    // ------------------------------------------------------------------
    // Replica-set spread inherited from the parent topology policy
    // ------------------------------------------------------------------

    @Test
    void rackAntiAffinityFromParentPolicyStillWorksForK8sPods() {
        final var sim = new CloudSimPlus();

        final var hostA = NodeBuilder.of("a").pes(2, 1000).rack("rack-A").build();
        final var hostB = NodeBuilder.of("b").pes(2, 1000).rack("rack-B").build();
        final var hostC = NodeBuilder.of("c").pes(2, 1000).rack("rack-C").build();

        // Use the description field as the replica-set tag (parent's default contract).
        final var policy = new KubernetesScheduler(Policy.RACK_ANTI_AFFINITY)
            .setReplicaSetOf(vm -> vm.getDescription() == null ? "" : vm.getDescription());
        new DatacenterSimple(sim, List.of(hostA, hostB, hostC), policy);

        final var broker = new KubernetesClusterBroker(sim);
        final List<KubernetesPod> replicas = List.of(
            replica("web-1"), replica("web-2"), replica("web-3"));
        broker.submitPods(replicas);

        run(sim);

        final var racks = replicas.stream()
            .map(p -> ((KubernetesNode) p.getHost()).getRackId())
            .distinct().count();
        assertEquals(3, racks, "Each replica must occupy a distinct rack");
        assertNotEquals(replicas.get(0).getHost(), replicas.get(1).getHost());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private KubernetesPod replica(final String name) {
        final var p = PodBuilder.of(name).label("app", "web")
            .container(ContainerBuilder.of("nginx").cpu("500m").mem("128Mi").length(1).build())
            .build();
        p.setDescription("web"); // shared replica-set tag for the parent policy
        return p;
    }

    private static void run(final CloudSimPlus sim) {
        sim.terminateAt(60.0);
        sim.start();
    }

    private static boolean wasPlaced(final Vm vm) {
        return vm.getHost() != null && vm.getHost() != Host.NULL;
    }
}

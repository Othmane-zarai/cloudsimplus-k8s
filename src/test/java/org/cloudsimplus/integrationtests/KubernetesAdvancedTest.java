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
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.PodAffinity;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.controllers.CronJobController;
import org.cloudsimplus.kubernetes.controllers.DaemonSetController;
import org.cloudsimplus.kubernetes.controllers.JobController;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.StatefulSetController;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for Phase-2 advanced features: PodAffinity, StatefulSet,
 * DaemonSet, Job, CronJob.
 */
public class KubernetesAdvancedTest {

    private final Namespace ns = Namespace.DEFAULT;

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    @Test
    void podAntiAffinityHostnameSpreadsBackendsAcrossNodes() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 3)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(4, 1000).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        for (int i = 0; i < 3; i++) {
            final var pod = PodBuilder.of("backend-" + i)
                .label("app", "backend")
                .container(ContainerBuilder.of("c").cpu("500m").mem("128Mi").length(1).build())
                .build()
                .setPodAffinity(PodAffinity.builder()
                    .requireAntiAffinity(LabelSelector.matchLabel("app", "backend"),
                        PodAffinity.TopologyKey.HOSTNAME)
                    .build());
            broker.submitPod(pod);
        }

        run(sim);

        // Each backend should have landed on a distinct node.
        final long distinctHosts = broker.getPods().stream()
            .filter(p -> "backend".equals(p.getLabels().get("app")))
            .map(KubernetesPod::getHost)
            .filter(Objects::nonNull)
            .distinct()
            .count();
        assertEquals(3, distinctHosts,
            "PodAntiAffinity required hostname must spread the 3 backend pods across 3 distinct nodes");
    }

    @Test
    void statefulSetSpawnsOrdinalNamedPods() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            IntStream.range(0, 4).mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000).build()).toList(),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var ss = new StatefulSetController(
            broker.getControllerManager().allocateUid(),
            "db", ns,
            new PodTemplate(ord -> PodBuilder.of("db")
                .label("app", "db")
                .container(ContainerBuilder.of("postgres").cpu("250m").mem("64Mi").length(1).build())
                .build()),
            3);
        broker.addController(ss);

        run(sim);

        final var podNames = broker.getPods().stream().map(KubernetesPod::getPodName).toList();
        assertTrue(podNames.contains("db-0"), "Expected db-0; got " + podNames);
        assertTrue(podNames.contains("db-1"), "Expected db-1; got " + podNames);
        assertTrue(podNames.contains("db-2"), "Expected db-2; got " + podNames);
    }

    @Test
    void daemonSetPlacesOnePodPerNode() {
        final var sim = new CloudSimPlus();
        final var nodes = IntStream.range(0, 3)
            .mapToObj(i -> NodeBuilder.of("n" + i).pes(2, 1000)
                .label("kubernetes.io/hostname", "n" + i).build())
            .toList();
        new DatacenterSimple(sim, nodes, new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var ds = new DaemonSetController(
            broker.getControllerManager().allocateUid(),
            "logger", ns,
            new PodTemplate(ord -> PodBuilder.of("logger")
                .label("app", "logger")
                .container(ContainerBuilder.of("agent").cpu("100m").mem("32Mi").length(1).build())
                .build()));
        broker.addController(ds);

        run(sim);

        // One pod per node.
        assertEquals(3, broker.getPods().size(),
            "DaemonSet should produce exactly one pod per matching node");
        final long distinctHosts = broker.getPods().stream()
            .map(KubernetesPod::getHost)
            .filter(Objects::nonNull)
            .distinct().count();
        assertEquals(3, distinctHosts, "Each DaemonSet pod must end up on a different node");
    }

    @Test
    void jobRunsToCompletionAndStops() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(4, 1000).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        final var job = new JobController(
            broker.getControllerManager().allocateUid(),
            "batch", ns,
            new PodTemplate(ord -> PodBuilder.of("batch")
                .label("app", "batch")
                .container(ContainerBuilder.of("worker").cpu("250m").mem("64Mi").length(2_000).build())
                .build()));
        job.setCompletions(3).setParallelism(1);
        broker.addController(job);

        run(sim, 60.0);

        assertTrue(job.isComplete(),
            "Job should be complete after running %d completions (succeeded=%d, failures=%d)"
                .formatted(job.getCompletions(), job.getSucceeded(), job.getFailures()));
        assertEquals(3, job.getSucceeded(), "Job should record 3 successful completions");
        // No more pods than needed for the job.
        assertTrue(broker.getPods().size() >= 3 && broker.getPods().size() <= 3 + job.getBackoffLimit(),
            "Job should have spawned roughly desired completions; got " + broker.getPods().size());
    }

    @Test
    void cronJobFiresJobsAtConfiguredInterval() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(4, 1000).build()),
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
        cron.setIntervalSeconds(5.0);
        broker.addController(cron);

        run(sim, 30.0);

        assertTrue(cron.getFired() >= 3,
            "CronJob with 5s interval over 30s should have fired at least 3 times (got " + cron.getFired() + ")");
    }

    @Test
    void priorityClassPlacesHighPriorityFirst() {
        // E6 case study: with `enablePriorityScheduling()` the broker sorts
        // its waiting list by descending pod priority before forwarding to
        // the datacenter. We submit two low-priority pods first, then a
        // single high-priority pod that must claim the only fitting node
        // ahead of the lower-priority queue. If priority were ignored the
        // FIFO submission order would fill the slot first.
        final var sim = new CloudSimPlus();
        // Single 1-PE node: at most one pod can place at any time.
        final var node = NodeBuilder.of("solo").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(node), new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).enablePriorityScheduling();

        // Use enough capacity to fit one pod with cpuUtilization=0 default;
        // length(10_000) at 1000 mips ≈ 10s simulated.
        final var lowA = PodBuilder.of("low-a")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000).build())
            .build();
        lowA.setPriority(1);
        final var lowB = PodBuilder.of("low-b")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000).build())
            .build();
        lowB.setPriority(1);
        final var hi = PodBuilder.of("hi")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000).build())
            .build();
        hi.setPriority(1000);

        // Submit low-priority pods FIRST; high-priority pod LAST.
        broker.submitPod(lowA);
        broker.submitPod(lowB);
        broker.submitPod(hi);

        run(sim, 200.0);

        // The single 1-PE node fits exactly one pod at a time; with priority
        // scheduling the highest-priority pod must be the FIRST to ever
        // occupy the slot. Verify via creation-time ordering on the broker's
        // vmCreatedList, which records placements in the order they happened.
        final var createdInOrder = broker.getVmCreatedList().stream()
            .filter(v -> v instanceof KubernetesPod)
            .map(v -> (KubernetesPod) v)
            .toList();
        assertTrue(!createdInOrder.isEmpty(), "At least one pod must have placed");
        assertEquals(hi, createdInOrder.get(0),
            "Highest-priority pod must be the first to place (saw "
                + createdInOrder.stream().map(KubernetesPod::getPodName).toList() + ")");
    }

    @Test
    void priorityDefaultPreservesFifoForEqualPriorities() {
        // Pods at the same priority retain submission order: this test wires
        // up enablePriorityScheduling but uses the default priority=0 for both
        // pods, so the FIFO contract must hold (pod 'first' places, 'second'
        // waits).
        final var sim = new CloudSimPlus();
        final var node = NodeBuilder.of("solo").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(node), new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim).enablePriorityScheduling();

        final var first = PodBuilder.of("first")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000).build())
            .build();
        final var second = PodBuilder.of("second")
            .container(ContainerBuilder.of("c").cpu("1").mem("256Mi").length(10_000).build())
            .build();
        // Both default to priority=0.
        broker.submitPod(first);
        broker.submitPod(second);

        run(sim, 200.0);

        final var createdInOrder = broker.getVmCreatedList().stream()
            .filter(v -> v instanceof KubernetesPod)
            .map(v -> (KubernetesPod) v)
            .toList();
        assertTrue(!createdInOrder.isEmpty(), "At least one pod must place");
        assertEquals(first, createdInOrder.get(0),
            "First-submitted pod must place first when priorities tie");
    }

    private static void run(final CloudSimPlus sim) {
        run(sim, 30.0);
    }

    private static void run(final CloudSimPlus sim, final double until) {
        sim.terminateAt(until);
        sim.start();
    }
}

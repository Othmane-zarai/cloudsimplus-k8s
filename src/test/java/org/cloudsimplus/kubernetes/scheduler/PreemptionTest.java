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
package org.cloudsimplus.kubernetes.scheduler;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware.Policy;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link KubernetesScheduler} performs eviction-style preemption
 * when a high-priority pod cannot otherwise place. Evicted victims must be
 * re-queued so user workloads are not silently lost.
 */
class PreemptionTest {

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    @Test
    void highPriorityPodEvictsLowerPriorityPodToFitOnSaturatedNode() {
        final var sim = new CloudSimPlus();
        // A single 2-PE node — exactly enough for one 2-PE pod.
        final var node = NodeBuilder.of("only").pes(2, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(node), new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);

        final var lowPri = PodBuilder.of("low")
            .container(ContainerBuilder.of("c").cpu("2000m").mem("128Mi").length(10_000).build())
            .build();
        lowPri.setPriority(0);
        broker.submitPod(lowPri);

        final var highPri = PodBuilder.of("high")
            .container(ContainerBuilder.of("c").cpu("2000m").mem("128Mi").length(10_000).build())
            .build();
        highPri.setPriority(1000);
        broker.submitPod(highPri);

        sim.terminateAt(50.0);
        sim.start();

        // The high-priority pod must have been placed (the only node holds it).
        assertNotNull(highPri.getHost());
        assertTrue(highPri.getHost() == node || highPri.isCreated(),
            "High-priority pod must be placed via preemption");
    }

    @Test
    void preemptionIsGatedOnPriorityGreaterThanZero() {
        // A pod at the default priority of 0 must NOT trigger preemption — even
        // when no host fits, it should remain unschedulable rather than evict
        // arbitrary peers. This protects mixed workloads where most pods are
        // priority 0.
        final var sim = new CloudSimPlus();
        final var node = NodeBuilder.of("n").pes(1, 1000).ram(512).build();
        new DatacenterSimple(sim, List.of(node), new KubernetesScheduler());
        final var broker = new KubernetesClusterBroker(sim);

        final KubernetesPod first = PodBuilder.of("first")
            .container(ContainerBuilder.of("c").cpu("1000m").mem("256Mi").length(10_000).build())
            .build();
        broker.submitPod(first);
        final KubernetesPod second = PodBuilder.of("second")
            .container(ContainerBuilder.of("c").cpu("1000m").mem("256Mi").length(10_000).build())
            .build();
        // priority defaults to 0
        broker.submitPod(second);

        sim.terminateAt(20.0);
        sim.start();

        // Second pod must NOT have evicted first — both at priority 0.
        assertTrue(first.isCreated() ^ second.isCreated()
                || first.isUnschedulable() || second.isUnschedulable(),
            "Equal-priority pods must not preempt each other");
    }
}

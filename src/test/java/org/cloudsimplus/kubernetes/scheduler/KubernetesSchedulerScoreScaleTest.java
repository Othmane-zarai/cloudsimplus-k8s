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
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link KubernetesScheduler#getK8sScoreScale()} actually
 * controls how K8s score contributions combine with the parent topology
 * policy's score. With the default scale, a small NodeAffinity preferred
 * weight does NOT dominate a large cost difference; with the scale set to 1.0,
 * it does. This regression-tests the May 2026 fix that introduced the score
 * normalization factor.
 */
class KubernetesSchedulerScoreScaleTest {

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    /**
     * Default scale (0.01): a NodeAffinity preferred weight of 1 contributes
     * 0.01 to the score — far less than a $0.50/hr cost difference. Cost wins.
     */
    @Test
    void defaultScaleLetsLargeCostDifferenceDominateSmallAffinityWeight() {
        final var sim = new CloudSimPlus();
        final var cheapNoMatch = NodeBuilder.of("cheap").pes(2, 1000).ram(1024)
            .label("zone", "b").costPerHour(0.10).build();
        final var pricyMatch = NodeBuilder.of("pricy").pes(2, 1000).ram(1024)
            .label("zone", "a").costPerHour(1.00).build();

        new DatacenterSimple(sim, List.of(cheapNoMatch, pricyMatch),
            new KubernetesScheduler(Policy.COST_OPTIMIZED)); // default k8sScoreScale = 0.01

        final var broker = new KubernetesClusterBroker(sim);
        broker.submitPod(PodBuilder.of("p")
            .nodeAffinity(NodeAffinity.builder()
                .prefer(LabelSelector.matchLabel("zone", "a"), 1) // weight 1 → 0.01 contribution
                .build())
            .container(ContainerBuilder.of("c").cpu("250m").mem("64Mi").length(1).build())
            .build());

        sim.terminateAt(20.0);
        sim.start();

        // $0.90/hr cost gap >> 0.01 affinity bonus → cheap node wins.
        assertSame(cheapNoMatch, broker.getPods().get(0).getHost(),
            "Default k8sScoreScale should let a $0.90 cost difference outweigh weight=1 affinity");
    }

    /**
     * E5 — when nothing else differentiates two candidate hosts, the
     * lexicographically smaller node name wins. The same simulation must
     * therefore produce the same placement across JVMs and runs.
     */
    @Test
    void tiedScoresBreakLexicallyByNodeName() {
        // Two identical hosts in cost, capacity, and zone — their effectiveName
        // is the only differentiator. With the deterministic tie-break, the
        // pod must always land on the alphabetically-first one ("alpha"),
        // regardless of insertion order.
        final var sim = new CloudSimPlus();
        final var alpha = NodeBuilder.of("alpha").pes(2, 1000).ram(1024).costPerHour(1.0).build();
        final var bravo = NodeBuilder.of("bravo").pes(2, 1000).ram(1024).costPerHour(1.0).build();

        // Insertion order deliberately reversed — without a tie-break the
        // outcome would depend on iteration order.
        new DatacenterSimple(sim, List.of(bravo, alpha),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));

        final var broker = new KubernetesClusterBroker(sim);
        broker.submitPod(PodBuilder.of("p")
            .container(ContainerBuilder.of("c").cpu("250m").mem("64Mi").length(1).build())
            .build());

        sim.terminateAt(20.0);
        sim.start();

        assertSame(alpha, broker.getPods().get(0).getHost(),
            "Tied scores must break in favour of the lexicographically smaller node name");
    }

    /**
     * With the scale tuned up to 1.0, the same weight-1 affinity now contributes
     * 1.0 — overwhelming the same $0.90/hr cost difference. The pricy match wins.
     */
    @Test
    void boostingScaleLetsSmallAffinityWeightOutweighCost() {
        final var sim = new CloudSimPlus();
        final var cheapNoMatch = NodeBuilder.of("cheap").pes(2, 1000).ram(1024)
            .label("zone", "b").costPerHour(0.10).build();
        final var pricyMatch = NodeBuilder.of("pricy").pes(2, 1000).ram(1024)
            .label("zone", "a").costPerHour(1.00).build();

        final var policy = new KubernetesScheduler(Policy.COST_OPTIMIZED).setK8sScoreScale(1.0);
        new DatacenterSimple(sim, List.of(cheapNoMatch, pricyMatch), policy);

        final var broker = new KubernetesClusterBroker(sim);
        broker.submitPod(PodBuilder.of("p")
            .nodeAffinity(NodeAffinity.builder()
                .prefer(LabelSelector.matchLabel("zone", "a"), 1) // weight 1 × scale 1.0 = 1.0
                .build())
            .container(ContainerBuilder.of("c").cpu("250m").mem("64Mi").length(1).build())
            .build());

        sim.terminateAt(20.0);
        sim.start();

        // 1.0 affinity bonus > $0.90 cost gap → pricy node wins.
        assertSame(pricyMatch, broker.getPods().get(0).getHost(),
            "k8sScoreScale=1.0 should let weight-1 affinity beat a $0.90 cost difference");
    }
}

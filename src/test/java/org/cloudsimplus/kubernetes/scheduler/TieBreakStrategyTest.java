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

import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware.Policy;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TieBreakStrategy} implementations (A4).
 *
 * @since CloudSim Plus 9.0.0
 */
class TieBreakStrategyTest {

    /**
     * Builds four KubernetesNodes with known names, attached to a real
     * Datacenter so they receive monotonic ids assigned by the simulation
     * engine. Without the Datacenter wiring all hosts share id=-1 and the
     * rank assertions collapse.
     */
    private static List<Host> fourHosts() {
        final var sim = new CloudSimPlus();
        final KubernetesNode n1 = NodeBuilder.of("delta").pes(1, 1000).ram(1024).build();
        final KubernetesNode n2 = NodeBuilder.of("alpha").pes(1, 1000).ram(1024).build();
        final KubernetesNode n3 = NodeBuilder.of("charlie").pes(1, 1000).ram(1024).build();
        final KubernetesNode n4 = NodeBuilder.of("bravo").pes(1, 1000).ram(1024).build();
        new DatacenterSimple(sim, List.of(n1, n2, n3, n4),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        return List.of(n1, n2, n3, n4);
    }

    // ---------- FirstFit ----------

    @Test
    void firstFitPreservesIterationOrder() {
        final var s = TieBreakStrategy.firstFit();
        final var hosts = fourHosts();
        final Map<Long, Integer> ranks = s.rank(hosts);
        // Host i gets rank i.
        for (int i = 0; i < hosts.size(); i++) {
            assertEquals(i, ranks.get(hosts.get(i).getId()),
                "FirstFit rank for hosts[" + i + "]");
        }
    }

    // ---------- LowestUid ----------

    @Test
    void lowestUidRanksByIdAscending() {
        final var s = TieBreakStrategy.lowestUid();
        final var hosts = fourHosts();
        final Map<Long, Integer> ranks = s.rank(hosts);
        // Sort hosts by id ascending; their ranks must come out 0..3 in order.
        final var byIdAsc = new java.util.ArrayList<>(hosts);
        byIdAsc.sort(java.util.Comparator.comparingLong(Host::getId));
        for (int i = 0; i < byIdAsc.size(); i++) {
            assertEquals(i, ranks.get(byIdAsc.get(i).getId()));
        }
    }

    // ---------- Random ----------

    @Test
    void randomTieBreakIsReproducibleWithFixedSeed() {
        final var hosts = fourHosts();
        final var s1 = TieBreakStrategy.random(42L);
        final var s2 = TieBreakStrategy.random(42L);
        assertEquals(s1.rank(hosts), s2.rank(hosts),
            "Same seed should produce identical ranks across instances");
    }

    @Test
    void randomTieBreakDiffersAcrossSeeds() {
        final var hosts = fourHosts();
        final var s1 = TieBreakStrategy.random(1L);
        final var s2 = TieBreakStrategy.random(2L);
        assertNotEquals(s1.rank(hosts), s2.rank(hosts),
            "Different seeds should produce different orderings on at least one host");
    }

    // ---------- RoundRobin ----------

    @Test
    void roundRobinCursorAdvancesOnNewPass() {
        final var s = TieBreakStrategy.roundRobin();
        final var hosts = fourHosts();
        final Map<Long, Integer> first = s.rank(hosts);
        s.newPass();
        final Map<Long, Integer> second = s.rank(hosts);
        assertNotEquals(first, second, "RoundRobin must rotate ranks after newPass()");
        for (final int r : second.values()) {
            assertTrue(r >= 0 && r < hosts.size(),
                "RoundRobin rank " + r + " out of [0, " + hosts.size() + ")");
        }
    }

    // ---------- Lexical ----------

    @Test
    void lexicalRanksByEffectiveName() {
        final var s = TieBreakStrategy.lexical();
        final var hosts = fourHosts();
        // Names: delta, alpha, charlie, bravo → sorted: alpha(0), bravo(1), charlie(2), delta(3)
        final Map<Long, Integer> ranks = s.rank(hosts);
        assertEquals(0, ranks.get(hosts.get(1).getId()), "alpha must rank 0");
        assertEquals(1, ranks.get(hosts.get(3).getId()), "bravo must rank 1");
        assertEquals(2, ranks.get(hosts.get(2).getId()), "charlie must rank 2");
        assertEquals(3, ranks.get(hosts.get(0).getId()), "delta must rank 3");
    }
}

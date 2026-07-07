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

import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Strategy for selecting one host from a set of hosts tied at the lowest score.
 *
 * <p>The upstream kube-scheduler breaks ties via {@code SchedulingProfile}
 * extensions; the production scheduler currently uses a randomized selection
 * for fairness. CloudSim Plus-K8s mirrors this with a pluggable strategy.
 * Tests that need fully deterministic placement should use
 * {@link #lexical()} (the historical default). Production-fidelity
 * comparisons should use {@link #random(long)} with a fixed seed.</p>
 *
 * <p>A strategy assigns each host an integer rank; the host with the lowest
 * rank among tied candidates is chosen. Ranks are folded into the final
 * score via a small epsilon (see {@code KubernetesScheduler.TIE_BREAK_EPSILON})
 * so they never override genuine score differences.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public interface TieBreakStrategy {

    /**
     * Computes per-host rank values for the given host list. Implementations
     * may use {@link KubernetesNode#effectiveName()}, host id, randomness, or
     * any other criterion. The returned map keys MUST be the hosts'
     * {@link Host#getId() ids}.
     *
     * @param hosts the host list for the current placement pass
     * @return map from host id to rank (lower rank = preferred on ties)
     */
    Map<Long, Integer> rank(List<Host> hosts);

    /**
     * Optional reset hook called once per placement pass (e.g. before a
     * batch of pods is submitted). The default does nothing; {@code RoundRobin}
     * uses it to advance its cursor.
     */
    default void newPass() {}

    // ---------- factory methods ----------

    /**
     * Lexicographic tie-break by {@link KubernetesNode#effectiveName()}. The
     * historical default — fully deterministic across JVMs and runs.
     */
    static TieBreakStrategy lexical() {
        return new Lexical();
    }

    /**
     * First-fit ordering — preserves the host-list order returned by the
     * datacenter. Matches the behaviour of stock kube-scheduler when
     * {@code SchedulingProfile.tieBreak=none}.
     */
    static TieBreakStrategy firstFit() {
        return new FirstFit();
    }

    /**
     * Ascending host id. Stable across processes; matches the behaviour of
     * older kube-scheduler versions and is convenient for cross-run
     * reproducibility.
     */
    static TieBreakStrategy lowestUid() {
        return new LowestUid();
    }

    /**
     * Round-robin across calls within a placement pass. Useful for stress
     * tests that want to spread placement evenly when many pods would
     * otherwise pile onto host[0].
     */
    static TieBreakStrategy roundRobin() {
        return new RoundRobin();
    }

    /**
     * Seeded random ordering — the closest analog to the upstream
     * kube-scheduler's randomized selection. Use a fixed seed for
     * reproducibility.
     */
    static TieBreakStrategy random(final long seed) {
        return new RandomTieBreak(seed);
    }

    // ---------- built-in implementations ----------

    final class Lexical implements TieBreakStrategy {
        @Override
        public Map<Long, Integer> rank(final List<Host> hosts) {
            final var sorted = new java.util.ArrayList<>(hosts);
            sorted.sort(Comparator.comparing((Host h) -> h instanceof KubernetesNode kn
                ? kn.effectiveName()
                : Long.toString(h.getId())));
            final var ranks = new java.util.HashMap<Long, Integer>(sorted.size() * 2);
            for (int i = 0; i < sorted.size(); i++) {
                ranks.put(sorted.get(i).getId(), i);
            }
            return ranks;
        }
    }

    final class FirstFit implements TieBreakStrategy {
        @Override
        public Map<Long, Integer> rank(final List<Host> hosts) {
            final var ranks = new java.util.HashMap<Long, Integer>(hosts.size() * 2);
            for (int i = 0; i < hosts.size(); i++) {
                ranks.put(hosts.get(i).getId(), i);
            }
            return ranks;
        }
    }

    final class LowestUid implements TieBreakStrategy {
        @Override
        public Map<Long, Integer> rank(final List<Host> hosts) {
            final var sorted = new java.util.ArrayList<>(hosts);
            sorted.sort(Comparator.comparingLong(Host::getId));
            final var ranks = new java.util.HashMap<Long, Integer>(sorted.size() * 2);
            for (int i = 0; i < sorted.size(); i++) {
                ranks.put(sorted.get(i).getId(), i);
            }
            return ranks;
        }
    }

    final class RoundRobin implements TieBreakStrategy {
        private final AtomicInteger cursor = new AtomicInteger();

        @Override
        public Map<Long, Integer> rank(final List<Host> hosts) {
            final int offset = cursor.get();
            final var ranks = new java.util.HashMap<Long, Integer>(hosts.size() * 2);
            for (int i = 0; i < hosts.size(); i++) {
                ranks.put(hosts.get(i).getId(), (i + offset) % hosts.size());
            }
            return ranks;
        }

        @Override
        public void newPass() {
            cursor.incrementAndGet();
        }
    }

    final class RandomTieBreak implements TieBreakStrategy {
        private final Random rng;

        RandomTieBreak(final long seed) {
            this.rng = new Random(seed);
        }

        @Override
        public Map<Long, Integer> rank(final List<Host> hosts) {
            final var indices = new java.util.ArrayList<Integer>(hosts.size());
            for (int i = 0; i < hosts.size(); i++) {
                indices.add(i);
            }
            java.util.Collections.shuffle(indices, rng);
            final var ranks = new java.util.HashMap<Long, Integer>(hosts.size() * 2);
            for (int i = 0; i < hosts.size(); i++) {
                ranks.put(hosts.get(i).getId(), indices.get(i));
            }
            return ranks;
        }
    }
}

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
package org.cloudsimplus.kubernetes;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Pod-to-pod affinity / anti-affinity, modelled after Kubernetes'
 * {@code v1.PodAffinity} / {@code v1.PodAntiAffinity}: a list of rules, each
 * pairing a {@link LabelSelector} (matching peer pods) with a
 * {@link TopologyKey} that defines the bucket the rule operates over (same
 * node, same availability zone, same region).
 *
 * <p>Each rule may be:</p>
 * <ul>
 *   <li><b>required + affinity</b> — the candidate node must already host a
 *       matching peer pod in the same topology bucket</li>
 *   <li><b>required + anti-affinity</b> — the candidate node must <i>not</i> host
 *       a matching peer pod in the same topology bucket</li>
 *   <li><b>preferred</b> — soft variants contributing a weight to the score</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class PodAffinity {

    /** Standard topology keys recognised by the scheduler. */
    public enum TopologyKey {
        /** Same node ({@code kubernetes.io/hostname}). */
        HOSTNAME,
        /** Same availability zone ({@code topology.kubernetes.io/zone}). */
        ZONE,
        /** Same region ({@code topology.kubernetes.io/region}). */
        REGION
    }

    /** A single affinity / anti-affinity rule. */
    public record Rule(LabelSelector selector, TopologyKey topologyKey, boolean antiAffinity, int weight) {
        public static final int REQUIRED = -1;

        public Rule {
            if (selector == null || topologyKey == null) {
                throw new IllegalArgumentException("selector and topologyKey are required");
            }
            if (weight != REQUIRED && (weight < 1 || weight > NodeAffinity.MAX_WEIGHT)) {
                throw new IllegalArgumentException(
                    "weight must be REQUIRED or in [1, " + NodeAffinity.MAX_WEIGHT + "]");
            }
        }

        public boolean isRequired() {
            return weight == REQUIRED;
        }

        /** Whether two nodes are in the same topology bucket for this rule. */
        public boolean sameBucket(final KubernetesNode candidate, final KubernetesNode peer) {
            return switch (topologyKey) {
                case HOSTNAME -> candidate == peer;
                case ZONE     -> bucketEquals(candidate.getAvailabilityZone(), peer.getAvailabilityZone());
                case REGION   -> bucketEquals(candidate.getRegion(), peer.getRegion());
            };
        }

        private static boolean bucketEquals(final String a, final String b) {
            return a != null && !a.isEmpty() && a.equals(b);
        }
    }

    /** Empty affinity — never blocks scheduling, contributes 0 to score. */
    public static final PodAffinity NONE = new PodAffinity(List.of());

    private final List<Rule> rules;

    private PodAffinity(final List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> getRules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link PodAffinity}. */
    public static final class Builder {
        private final List<Rule> rules = new ArrayList<>();

        public Builder requireAffinity(@NonNull final LabelSelector sel, @NonNull final TopologyKey key) {
            rules.add(new Rule(sel, key, false, Rule.REQUIRED));
            return this;
        }

        public Builder requireAntiAffinity(@NonNull final LabelSelector sel, @NonNull final TopologyKey key) {
            rules.add(new Rule(sel, key, true, Rule.REQUIRED));
            return this;
        }

        public Builder preferAffinity(@NonNull final LabelSelector sel, @NonNull final TopologyKey key, final int weight) {
            rules.add(new Rule(sel, key, false, weight));
            return this;
        }

        public Builder preferAntiAffinity(@NonNull final LabelSelector sel, @NonNull final TopologyKey key, final int weight) {
            rules.add(new Rule(sel, key, true, weight));
            return this;
        }

        public PodAffinity build() {
            return rules.isEmpty() ? NONE : new PodAffinity(rules);
        }
    }
}

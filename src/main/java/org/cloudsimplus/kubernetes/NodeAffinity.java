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
import java.util.Collections;
import java.util.List;

/**
 * Pod-to-node affinity rules, a strict subset of Kubernetes' {@code NodeAffinity}:
 * a list of <i>required</i> selectors (any one must match — disjunction, like
 * {@code requiredDuringSchedulingIgnoredDuringExecution.nodeSelectorTerms}) and
 * a list of <i>preferred</i> weighted selectors that contribute to the
 * scheduler's score.
 *
 * <p>Pod-to-pod affinity ({@code podAffinity} / {@code podAntiAffinity}) is
 * deliberately out of scope for v1; partial spread guarantees are already
 * available through {@link org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware}'s
 * replica-set policies.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class NodeAffinity {

    /** Maximum weight allowed per Kubernetes' API (mirrored for compatibility). */
    public static final int MAX_WEIGHT = 100;

    /** A selector with an associated scoring weight (1..100). */
    public record Preference(LabelSelector selector, int weight) {
        public Preference {
            if (selector == null) {
                throw new IllegalArgumentException("Preference selector is required");
            }
            if (weight < 1 || weight > MAX_WEIGHT) {
                throw new IllegalArgumentException(
                    "Preference weight must be in [1, %d], got %d".formatted(MAX_WEIGHT, weight));
            }
        }
    }

    /** {@link NodeAffinity} with no rules — never blocks scheduling, contributes 0 to score. */
    public static final NodeAffinity NONE = new NodeAffinity(List.of(), List.of());

    private final List<LabelSelector> required;
    private final List<Preference> preferred;

    private NodeAffinity(final List<LabelSelector> required, final List<Preference> preferred) {
        this.required = List.copyOf(required);
        this.preferred = List.copyOf(preferred);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<LabelSelector> getRequired() {
        return required;
    }

    public List<Preference> getPreferred() {
        return preferred;
    }

    public boolean isEmpty() {
        return required.isEmpty() && preferred.isEmpty();
    }

    /**
     * @return {@code true} if either there are no required rules or at least one
     * required selector matches the supplied node labels.
     */
    public boolean requiredMatches(final LabelSet nodeLabels) {
        if (required.isEmpty()) {
            return true;
        }
        for (final var sel : required) {
            if (sel.matches(nodeLabels)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the sum of {@link Preference#weight() weights} of every preferred
     * selector that matches the supplied node labels (0 when none match).
     */
    public int preferredScore(final LabelSet nodeLabels) {
        int score = 0;
        for (final var pref : preferred) {
            if (pref.selector().matches(nodeLabels)) {
                score += pref.weight();
            }
        }
        return score;
    }

    /** Fluent builder for {@link NodeAffinity}. */
    public static final class Builder {
        private final List<LabelSelector> required = new ArrayList<>();
        private final List<Preference> preferred = new ArrayList<>();

        public Builder require(@NonNull final LabelSelector selector) {
            required.add(selector);
            return this;
        }

        public Builder prefer(@NonNull final LabelSelector selector, final int weight) {
            preferred.add(new Preference(selector, weight));
            return this;
        }

        public NodeAffinity build() {
            if (required.isEmpty() && preferred.isEmpty()) {
                return NONE;
            }
            return new NodeAffinity(
                Collections.unmodifiableList(required),
                Collections.unmodifiableList(preferred));
        }
    }
}

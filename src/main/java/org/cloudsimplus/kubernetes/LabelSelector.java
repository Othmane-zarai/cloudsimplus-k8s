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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Kubernetes-style label selector: an AND of {@code matchLabels} (exact
 * key=value pairs) and {@code matchExpressions} (richer predicates with
 * operators {@code In}, {@code NotIn}, {@code Exists}, {@code DoesNotExist}).
 *
 * <p>An empty selector matches every {@link LabelSet} — same as
 * Kubernetes' {@code spec.selector: {}}.</p>
 *
 * <p>Built fluently:
 * <pre>
 * LabelSelector.builder()
 *     .matchLabel("tier", "backend")
 *     .matchIn("env", "prod", "staging")
 *     .build();
 * </pre>
 * </p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class LabelSelector {

    /** A selector that matches everything (Kubernetes' empty selector semantics). */
    public static final LabelSelector MATCH_ALL = new LabelSelector(Map.of(), List.of());

    /** Operators supported by {@link Expression matchExpressions}. */
    public enum Operator { IN, NOT_IN, EXISTS, DOES_NOT_EXIST }

    /**
     * One {@code matchExpression} entry: a label key, an {@link Operator},
     * and (for {@code In}/{@code NotIn}) a value set.
     *
     * @param key      the label key being tested
     * @param operator the match operator
     * @param values   the value set; ignored for {@code Exists} / {@code DoesNotExist}
     */
    public record Expression(String key, Operator operator, Set<String> values) {
        public Expression {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Expression key must be non-blank");
            }
            if (operator == null) {
                throw new IllegalArgumentException("Operator is required");
            }
            values = values == null ? Set.of() : Set.copyOf(values);
            if ((operator == Operator.IN || operator == Operator.NOT_IN) && values.isEmpty()) {
                throw new IllegalArgumentException("In/NotIn require a non-empty value set");
            }
        }

        /**
         * Evaluates this expression against the given labels. {@code In}
         * implicitly requires the key to exist; {@code NotIn} passes when the
         * key is absent (matching Kubernetes semantics).
         */
        public boolean test(final LabelSet labels) {
            return switch (operator) {
                case EXISTS         -> labels.has(key);
                case DOES_NOT_EXIST -> !labels.has(key);
                case IN             -> {
                    final var v = labels.get(key);
                    yield v != null && values.contains(v);
                }
                case NOT_IN         -> {
                    final var v = labels.get(key);
                    yield v == null || !values.contains(v);
                }
            };
        }
    }

    private final Map<String, String> matchLabels;
    private final List<Expression> matchExpressions;

    private LabelSelector(final Map<String, String> matchLabels, final List<Expression> matchExpressions) {
        this.matchLabels = Collections.unmodifiableMap(new HashMap<>(matchLabels));
        this.matchExpressions = List.copyOf(matchExpressions);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience for the common case of a single-key selector.
     */
    public static LabelSelector matchLabel(final String key, final String value) {
        return builder().matchLabel(key, value).build();
    }

    /** @return read-only view of the {@code matchLabels} map. */
    public Map<String, String> getMatchLabels() {
        return matchLabels;
    }

    /** @return read-only list of {@code matchExpressions}. */
    public List<Expression> getMatchExpressions() {
        return matchExpressions;
    }

    /** @return {@code true} if this selector has no rules (matches everything). */
    public boolean isEmpty() {
        return matchLabels.isEmpty() && matchExpressions.isEmpty();
    }

    /**
     * Evaluates the selector against {@code labels}. An empty selector matches.
     */
    public boolean matches(final LabelSet labels) {
        if (isEmpty()) {
            return true;
        }
        if (!labels.matches(matchLabels)) {
            return false;
        }
        for (final var expr : matchExpressions) {
            if (!expr.test(labels)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "LabelSelector{matchLabels=%s, matchExpressions=%s}"
            .formatted(matchLabels, matchExpressions);
    }

    /** Fluent builder for {@link LabelSelector}. */
    public static final class Builder {
        private final Map<String, String> labels = new HashMap<>();
        private final List<Expression> exprs = new ArrayList<>();

        public Builder matchLabel(@NonNull final String key, @NonNull final String value) {
            labels.put(key, value);
            return this;
        }

        public Builder matchLabels(final Map<String, String> all) {
            if (all != null) {
                labels.putAll(all);
            }
            return this;
        }

        public Builder matchIn(@NonNull final String key, final String... values) {
            exprs.add(new Expression(key, Operator.IN, copyOf(values)));
            return this;
        }

        public Builder matchNotIn(@NonNull final String key, final String... values) {
            exprs.add(new Expression(key, Operator.NOT_IN, copyOf(values)));
            return this;
        }

        public Builder matchExists(@NonNull final String key) {
            exprs.add(new Expression(key, Operator.EXISTS, Set.of()));
            return this;
        }

        public Builder matchDoesNotExist(@NonNull final String key) {
            exprs.add(new Expression(key, Operator.DOES_NOT_EXIST, Set.of()));
            return this;
        }

        public LabelSelector build() {
            if (labels.isEmpty() && exprs.isEmpty()) {
                return MATCH_ALL;
            }
            return new LabelSelector(labels, exprs);
        }

        private static Set<String> copyOf(final String[] values) {
            return values == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(values));
        }
    }
}

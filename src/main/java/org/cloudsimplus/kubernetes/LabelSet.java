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

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable string-to-string label map attached to {@link KubernetesNode}s,
 * {@link KubernetesPod}s, {@link Namespace}s and {@link KubernetesService}s,
 * mirroring Kubernetes' {@code metadata.labels}.
 *
 * <p>Use the builder for ergonomic construction:
 * {@code LabelSet.of().with("app","web").with("tier","frontend").build()}.</p>
 *
 * <p>Matched against requirements by {@link LabelSelector}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class LabelSet {

    /** The empty label set. */
    public static final LabelSet EMPTY = new LabelSet(Collections.emptyMap());

    private final Map<String, String> labels;

    private LabelSet(final Map<String, String> labels) {
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }

    /** @return a fresh builder. */
    public static Builder of() {
        return new Builder();
    }

    /** Convenience: build a single-entry set. */
    public static LabelSet of(final String key, final String value) {
        return of().with(key, value).build();
    }

    /**
     * Wraps an existing map. The map is defensively copied; further mutations
     * on the source do not affect the resulting {@link LabelSet}.
     */
    public static LabelSet from(final Map<String, String> map) {
        return map == null || map.isEmpty() ? EMPTY : new LabelSet(map);
    }

    /** @return the label value for {@code key}, or {@code null} if absent. */
    public String get(final String key) {
        return labels.get(key);
    }

    /** @return {@code true} when this set carries the given key. */
    public boolean has(final String key) {
        return labels.containsKey(key);
    }

    /** @return read-only view of the underlying map. */
    public Map<String, String> asMap() {
        return labels;
    }

    /** @return number of label entries. */
    public int size() {
        return labels.size();
    }

    public boolean isEmpty() {
        return labels.isEmpty();
    }

    /**
     * @return a new {@link LabelSet} containing entries from this set, with
     * entries from {@code other} overriding any colliding keys.
     */
    public LabelSet merge(final LabelSet other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        final var combined = new LinkedHashMap<>(labels);
        combined.putAll(other.labels);
        return new LabelSet(combined);
    }

    /**
     * Returns {@code true} when every entry of {@code required} is present in
     * this set with the matching value. The {@code matchLabels} semantics from
     * Kubernetes.
     */
    public boolean matches(final Map<String, String> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (final var e : required.entrySet()) {
            if (!e.getValue().equals(labels.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return labels.toString();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof LabelSet that && labels.equals(that.labels);
    }

    @Override
    public int hashCode() {
        return labels.hashCode();
    }

    /** Fluent builder for {@link LabelSet}. */
    public static final class Builder {
        private final Map<String, String> map = new HashMap<>();

        public Builder with(@NonNull final String key, @NonNull final String value) {
            map.put(key, value);
            return this;
        }

        public Builder withAll(final Map<String, String> entries) {
            if (entries != null) {
                map.putAll(entries);
            }
            return this;
        }

        public LabelSet build() {
            return map.isEmpty() ? EMPTY : new LabelSet(map);
        }
    }
}

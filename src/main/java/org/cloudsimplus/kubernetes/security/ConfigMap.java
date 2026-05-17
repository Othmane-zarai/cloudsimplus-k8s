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
package org.cloudsimplus.kubernetes.security;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.kubernetes.Namespace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Kubernetes ConfigMap: a namespaced key/value registry used to inject
 * non-sensitive configuration into pods. Pods declare a dependency via
 * {@link org.cloudsimplus.kubernetes.KubernetesPod#mountConfigMap(String)};
 * the kubelet pre-flight refuses to start a pod whose declared ConfigMap is
 * not registered with the {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker} in the same namespace.
 *
 * <p>Two {@code ConfigMap}s are considered equal when their namespace-qualified
 * names match, mirroring the K8s identity contract — useful for use as a
 * {@link java.util.Set} or {@link java.util.Map} key.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class ConfigMap {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    private final Map<String, String> data = new LinkedHashMap<>();

    /**
     * Creates a ConfigMap.
     *
     * @param name      the ConfigMap name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ConfigMap(final String name, final Namespace namespace) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ConfigMap name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Stores a key/value entry. Existing values for the same key are
     * overwritten.
     *
     * @param key   the entry key (non-null)
     * @param value the entry value (non-null)
     * @return this instance, for chaining
     */
    public ConfigMap putData(@NonNull final String key, @NonNull final String value) {
        data.put(key, value);
        return this;
    }

    /** @return read-only view of this ConfigMap's data. */
    public Map<String, String> getData() {
        return Collections.unmodifiableMap(data);
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof ConfigMap that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "ConfigMap[%s, entries=%d]".formatted(qualifiedName(), data.size());
    }
}

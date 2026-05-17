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
 * A Kubernetes Secret: a namespaced key/byte[] registry intended for sensitive
 * configuration (TLS certificates, credentials, tokens) that pods consume by
 * declaring {@link
 * org.cloudsimplus.kubernetes.KubernetesPod#mountSecret(String)}. The kubelet
 * pre-flight refuses to start a pod whose declared Secret is not registered
 * with the {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker} in the same namespace.
 *
 * <p><b>Note:</b> the simulator does <i>not</i> model encryption at rest or
 * in transit. The Secret type is purely a registry, distinct from
 * {@link ConfigMap} only in that its value type is {@code byte[]} rather than
 * {@code String}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class Secret {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    private final Map<String, byte[]> data = new LinkedHashMap<>();

    /**
     * Creates a Secret.
     *
     * @param name      the Secret name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public Secret(final String name, final Namespace namespace) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Secret name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Stores a key/value entry. The value array is referenced directly (not
     * copied) — callers must not mutate it after handing it to the Secret.
     *
     * @param key   the entry key (non-null)
     * @param value the entry value (non-null)
     * @return this instance, for chaining
     */
    public Secret putData(@NonNull final String key, @NonNull final byte[] value) {
        data.put(key, value);
        return this;
    }

    /** @return read-only view of this Secret's data. */
    public Map<String, byte[]> getData() {
        return Collections.unmodifiableMap(data);
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Secret that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "Secret[%s, entries=%d]".formatted(qualifiedName(), data.size());
    }
}

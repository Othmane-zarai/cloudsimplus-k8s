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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A Kubernetes Role: a namespaced collection of free-form rule strings (e.g.
 * {@code "get pods"}, {@code "list services"}). The simulator does <i>not</i>
 * enforce these rules — there is no API-call analogue in a discrete-event
 * cloud simulator — but Roles can be linked to a {@link ServiceAccount} via
 * {@link RoleBinding} for scenario fidelity and to support metric / audit
 * collection over the cluster's RBAC graph.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class Role {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    private final List<String> rules = new ArrayList<>();

    /**
     * Creates a Role.
     *
     * @param name      the Role name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public Role(final String name, final Namespace namespace) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Role name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Appends a free-form rule (e.g. {@code "get pods"}). The string is
     * stored verbatim; no parsing or enforcement is performed.
     *
     * @param rule the rule string (non-null)
     * @return this instance, for chaining
     */
    public Role addRule(@NonNull final String rule) {
        rules.add(rule);
        return this;
    }

    /** @return read-only view of this Role's rules in insertion order. */
    public List<String> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Role that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "Role[%s, rules=%d]".formatted(qualifiedName(), rules.size());
    }
}

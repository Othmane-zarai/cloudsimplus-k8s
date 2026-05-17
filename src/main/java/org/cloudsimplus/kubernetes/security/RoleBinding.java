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

import java.util.Objects;

/**
 * A Kubernetes RoleBinding: links a {@link Role} (the rule set) to a
 * {@link ServiceAccount} (the subject). Like {@link Role} itself, the
 * simulator carries this metadata for scenario fidelity but does not enforce
 * authorisation — a discrete-event cloud simulator has no analogue of an
 * authenticated K8s API call to authorise.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class RoleBinding {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    @NonNull
    private final Role roleRef;

    @NonNull
    private final ServiceAccount subject;

    /**
     * Creates a RoleBinding.
     *
     * @param name      the binding name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @param roleRef   the Role being granted
     * @param subject   the ServiceAccount being granted the Role
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public RoleBinding(
        final String name,
        final Namespace namespace,
        final Role roleRef,
        final ServiceAccount subject)
    {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("RoleBinding name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.roleRef = Objects.requireNonNull(roleRef, "roleRef");
        this.subject = Objects.requireNonNull(subject, "subject");
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof RoleBinding that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "RoleBinding[%s → role=%s sa=%s]"
            .formatted(qualifiedName(), roleRef.getName(), subject.getName());
    }
}

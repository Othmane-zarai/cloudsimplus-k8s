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
 * A Kubernetes ServiceAccount: a namespaced identity assumed by pods that
 * declare it via {@link
 * org.cloudsimplus.kubernetes.KubernetesPod#setServiceAccountName(String)}.
 * The kubelet pre-flight refuses to start a pod whose declared SA is not
 * registered with the {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker} in the same namespace.
 *
 * <p><b>Note:</b> in this simulator, ServiceAccount is metadata only — there
 * is no analogue of an authenticated K8s API call to authorise. The SA model
 * exists for scenario fidelity (e.g. ensuring a workload is wired to a
 * non-default SA before it can run) and as a binding subject for
 * {@link RoleBinding}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class ServiceAccount {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    /**
     * Creates a ServiceAccount.
     *
     * @param name      the SA name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ServiceAccount(final String name, final Namespace namespace) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ServiceAccount name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof ServiceAccount that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "ServiceAccount[" + qualifiedName() + "]";
    }
}

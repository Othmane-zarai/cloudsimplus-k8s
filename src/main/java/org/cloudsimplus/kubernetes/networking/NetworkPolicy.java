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
package org.cloudsimplus.kubernetes.networking;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;

import java.util.Objects;

/**
 * A Kubernetes NetworkPolicy: an ingress/egress filter targeting pods that
 * match {@link #getPodSelector()} in {@link #getNamespace()}. Registered on
 * the {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker} via
 * {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker#addNetworkPolicy(NetworkPolicy)}.
 *
 * <p><b>Enforcement.</b> Callers that drive traffic to a {@link
 * org.cloudsimplus.kubernetes.KubernetesService} should consult
 * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#isIngressAllowed(
 * org.cloudsimplus.kubernetes.KubernetesService)} before dispatching: that
 * check applies every registered policy in the target service's namespace
 * whose {@code podSelector} matches any of the service's currently-backing
 * pods, and returns {@code false} when at least one such policy disallows
 * ingress. This reproduces K8s' default-deny behaviour once a policy targets
 * a pod.</p>
 *
 * <p><b>Modelled simplifications.</b></p>
 * <ul>
 *   <li>Only <i>ingress</i> is enforced. Egress is not modelled because the
 *       enforcement entry point does not carry a source-pod identity —
 *       traffic originates from outside the cluster as far as the broker
 *       knows. Adding egress would require threading peer identity through
 *       the dispatch path, which is a cross-cutting change tracked in
 *       {@code KUBERNETES.md §10.1}.</li>
 *   <li>Allow/deny is a single boolean per policy; per-source pod-to-pod
 *       rules, port-level rules, IP-block rules, and policy-type lists are
 *       not modelled.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
@Setter
@Accessors(chain = true)
public final class NetworkPolicy {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    /** Pods this NetworkPolicy applies to (matched by their labels). */
    @NonNull
    private final LabelSelector podSelector;

    /** Whether ingress traffic to matched pods is permitted. Default: {@code true}. */
    private boolean ingressAllowed = true;

    /**
     * Creates a NetworkPolicy.
     *
     * @param name        the policy name (must be non-blank, namespace-unique)
     * @param namespace   the owning namespace
     * @param podSelector the pod selector identifying targeted pods
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public NetworkPolicy(
        final String name,
        final Namespace namespace,
        final LabelSelector podSelector)
    {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("NetworkPolicy name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.podSelector = Objects.requireNonNull(podSelector, "podSelector");
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof NetworkPolicy that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "NetworkPolicy[%s, ingress=%s]".formatted(qualifiedName(), ingressAllowed);
    }
}

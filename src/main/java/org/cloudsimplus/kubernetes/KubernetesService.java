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

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.vms.Vm;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * A Kubernetes Service: a stable virtual endpoint that load-balances traffic
 * across the {@link KubernetesPod}s in {@link #getNamespace() its namespace}
 * whose labels match {@link #getSelector() the selector}.
 *
 * <p>The backing pod list is <i>resolved dynamically</i> on every
 * {@link #backingPods()} / {@link #selectVm()} call from a pod source supplied
 * by the {@link KubernetesClusterBroker} via {@link #setPodSource(Supplier)}.
 * Newly-created pods (and ones that finish or are removed) flow into and out
 * of the endpoint set without manual registration.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesService {
    public enum ServiceType {
        ClusterIP,
        NodePort,
        LoadBalancer
    }

    private final String name;

    @NonNull
    private ServiceType type = ServiceType.ClusterIP;

    @NonNull
    private LabelSelector selector;

    @NonNull
    private Namespace namespace;

    @NonNull
    private Supplier<List<KubernetesPod>> podSource = Collections::emptyList;

    private int roundRobinIndex;

    public KubernetesService(final String name, final Namespace namespace, final LabelSelector selector) {
        if (name == null) {
            throw new IllegalArgumentException("Name is required for a KubernetesService");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace is required for a KubernetesService");
        }
        if (selector == null) {
            throw new IllegalArgumentException("Selector is required for a KubernetesService");
        }
        this.name = name;
        this.namespace = namespace;
        this.selector = selector;
    }

    /**
     * @return the {@link KubernetesPod}s currently backing this service: created
     * pods in the same namespace whose labels match the selector. The list is
     * recomputed on every call to reflect cluster state.
     */
    public List<KubernetesPod> backingPods() {
        return podSource.get().stream()
            .filter(KubernetesPod::isCreated)
            .filter(KubernetesService::isPodReadyOrPreReadiness)
            .filter(p -> namespace.equals(p.getNamespace()))
            .filter(p -> selector.matches(p.getLabels()))
            .toList();
    }

    /**
     * A pod is eligible as a service endpoint when it is either explicitly
     * Ready (a readiness probe has marked it so) or has no readiness gate at
     * all (no probe configured ⇒ ready by default — this matches the kubelet's
     * behaviour for pods without a readiness probe).
     */
    private static boolean isPodReadyOrPreReadiness(final KubernetesPod pod) {
        final boolean hasReadinessGate = pod.getContainers().stream()
            .anyMatch(c -> c.getReadinessProbe() != null);
        return !hasReadinessGate || pod.isReady();
    }

    /**
     * Round-robins across the dynamically-resolved {@link #backingPods()
     * backing pods}. Returns {@link Vm#NULL} when the selector currently
     * matches no created pod.
     */
    public Vm selectVm() {
        final var pods = backingPods();
        if (pods.isEmpty()) {
            return Vm.NULL;
        }
        final var pod = pods.get(roundRobinIndex % pods.size());
        roundRobinIndex = (roundRobinIndex + 1) % pods.size();
        return pod;
    }

    @Override
    public String toString() {
        return "KubernetesService[%s/%s, selector=%s]"
            .formatted(namespace.getName(), getName(), selector);
    }
}

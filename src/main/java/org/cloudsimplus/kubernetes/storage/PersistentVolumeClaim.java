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
package org.cloudsimplus.kubernetes.storage;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;

import java.util.Objects;

/**
 * A Kubernetes PersistentVolumeClaim: a namespaced storage request the
 * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker}'s first-fit
 * binder pairs with an unbound {@link PersistentVolume} of sufficient
 * capacity at registration time. Pods declare a dependency via
 * {@link
 * org.cloudsimplus.kubernetes.KubernetesPod#requirePersistentVolumeClaim(String)};
 * the kubelet pre-flight blocks pod startup until the claim is bound.
 *
 * <p>Two {@code PersistentVolumeClaim}s are considered equal when their
 * namespace-qualified names match. Reclaim policies, storage classes, and
 * dynamic provisioning are not modelled.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
@Accessors(chain = true)
public final class PersistentVolumeClaim {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    /** Requested capacity, in mebibytes. */
    private final long requestedCapacityMB;

    /**
     * Optional label selector restricting which {@link PersistentVolume}s can
     * satisfy this claim. The broker's first-fit binder requires
     * {@code selector.matches(pv.getLabels())} in addition to capacity and
     * storage-class compatibility. Defaults to
     * {@link LabelSelector#MATCH_ALL}.
     */
    @NonNull
    @Setter
    private LabelSelector selector = LabelSelector.MATCH_ALL;

    /**
     * Optional storage-class name; only {@link PersistentVolume}s offering
     * the same class can satisfy this claim. {@code null} (default) means
     * "any class is fine" but does <i>not</i> match PVs that offer a
     * specific class — mirroring real K8s' {@code ""} versus class-named
     * binding contract requires both sides to be {@code null} (or both
     * non-null and equal).
     */
    @Setter
    private String storageClassName;

    /** {@code true} once a {@link PersistentVolume} has been bound. */
    private boolean bound;

    /** The bound volume, or {@code null} when unbound. */
    private PersistentVolume volumeRef;

    /**
     * Creates a PersistentVolumeClaim.
     *
     * @param name                the claim name (must be non-blank, namespace-unique)
     * @param namespace           the owning namespace
     * @param requestedCapacityMB the requested capacity, in mebibytes (must be {@code > 0})
     * @throws IllegalArgumentException if {@code name} is blank or
     *                                  {@code requestedCapacityMB <= 0}
     */
    public PersistentVolumeClaim(
        final String name,
        final Namespace namespace,
        final long requestedCapacityMB)
    {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PersistentVolumeClaim name must be non-blank");
        }
        if (requestedCapacityMB <= 0) {
            throw new IllegalArgumentException(
                "PVC requested capacity must be > 0 MiB, got " + requestedCapacityMB);
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.requestedCapacityMB = requestedCapacityMB;
    }

    /**
     * Binds this PVC to the given volume. Idempotent if the same volume is
     * already bound; throws if a different volume is already bound.
     *
     * <p>Most callers should use
     * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#addPersistentVolumeClaim}
     * which runs the first-fit binder and keeps both the PVC and PV in sync.
     * Direct invocation is intended for tests or scenario fixtures.</p>
     *
     * @param pv the volume to bind (non-null)
     * @return this instance, for chaining
     * @throws IllegalStateException if this PVC is already bound to a different volume
     */
    public PersistentVolumeClaim bind(@NonNull final PersistentVolume pv) {
        if (bound && volumeRef != pv) {
            throw new IllegalStateException(
                "PVC '" + qualifiedName() + "' is already bound to volume "
                    + volumeRef.getName());
        }
        this.bound = true;
        this.volumeRef = pv;
        return this;
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof PersistentVolumeClaim that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "PersistentVolumeClaim[%s, %dMiB, %s]".formatted(
            qualifiedName(), requestedCapacityMB,
            bound ? "bound→" + volumeRef.getName() : "unbound");
    }
}

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
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.resources.HarddriveStorage;

import java.util.Objects;

/**
 * A Kubernetes PersistentVolume: a cluster-wide storage handle backed
 * physically by a CloudSim Plus {@link HarddriveStorage} of the requested
 * capacity. PVs are claim-bound — exactly one bound
 * {@link PersistentVolumeClaim} per PV — and the {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker}'s first-fit binder
 * pairs PVs with PVCs on PVC registration.
 *
 * <p>PV names are <i>cluster-wide</i>, not namespaced (mirroring real K8s).
 * Two {@code PersistentVolume}s are considered equal when their names match.</p>
 *
 * <p>Capacity is expressed in mebibytes throughout the simulator; the
 * underlying {@code HarddriveStorage} reports the same value via
 * {@link HarddriveStorage#getCapacity()}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
@Accessors(chain = true)
public final class PersistentVolume {

    @NonNull
    private final String name;

    @NonNull
    private final HarddriveStorage storage;

    /**
     * Labels offered by this volume; used by the broker's first-fit binder
     * to match against a {@link PersistentVolumeClaim#getSelector() PVC
     * selector}. Defaults to {@link LabelSet#EMPTY}.
     */
    @NonNull
    @Setter
    private LabelSet labels = LabelSet.EMPTY;

    /**
     * Storage class offered by this volume. Mirrors K8s'
     * {@code spec.storageClassName}: a PVC requesting a class only binds to a
     * PV that offers the same class. {@code null} (default) means "no class"
     * — matches PVCs with a {@code null} request only, mirroring K8s'
     * {@code ""} default-class semantics.
     */
    @Setter
    private String storageClassName;

    /** {@code true} once a {@link PersistentVolumeClaim} has been bound. */
    private boolean bound;

    /** The bound claim, or {@code null} when unbound. */
    private PersistentVolumeClaim claimRef;

    /**
     * Creates a PersistentVolume of the given capacity, backed by a freshly
     * instantiated {@link HarddriveStorage}.
     *
     * @param name       the PV name (must be non-blank, cluster-unique)
     * @param capacityMB the volume capacity in mebibytes (must be {@code > 0})
     * @throws IllegalArgumentException if {@code name} is blank or
     *                                  {@code capacityMB <= 0}
     */
    public PersistentVolume(final String name, final long capacityMB) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PersistentVolume name must be non-blank");
        }
        if (capacityMB <= 0) {
            throw new IllegalArgumentException(
                "PersistentVolume capacity must be > 0 MiB, got " + capacityMB);
        }
        this.name = name;
        this.storage = new HarddriveStorage(capacityMB);
    }

    /** @return the underlying storage capacity, in mebibytes. */
    public long getCapacityMB() {
        return storage.getCapacity();
    }

    /**
     * Binds this PV to the given claim. Idempotent if the same claim is
     * already bound; throws if a different claim is already bound.
     *
     * <p>Most callers should use
     * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#addPersistentVolumeClaim}
     * which runs the first-fit binder and keeps both the PV and PVC in sync.
     * Direct invocation is intended for tests or scenario fixtures.</p>
     *
     * @param pvc the claim to bind (non-null)
     * @return this instance, for chaining
     * @throws IllegalStateException if this PV is already bound to a different claim
     */
    public PersistentVolume bind(@NonNull final PersistentVolumeClaim pvc) {
        if (bound && claimRef != pvc) {
            throw new IllegalStateException(
                "PersistentVolume '" + name + "' is already bound to claim "
                    + claimRef.qualifiedName());
        }
        this.bound = true;
        this.claimRef = pvc;
        return this;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof PersistentVolume that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "PersistentVolume[%s, %dMiB, %s]".formatted(
            name, getCapacityMB(),
            bound ? "bound→" + claimRef.qualifiedName() : "unbound");
    }
}

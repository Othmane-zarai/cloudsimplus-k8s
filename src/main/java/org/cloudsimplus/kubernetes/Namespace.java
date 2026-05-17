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

/**
 * A logical scope grouping {@link KubernetesPod}s and {@link KubernetesService}s,
 * mirroring Kubernetes' {@code v1.Namespace}.
 *
 * <p>For v1 a namespace is essentially a name plus optional labels — quotas,
 * resource limits, and network policies are not yet modelled.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public final class Namespace {

    /** The implicit {@code "default"} namespace used when none is specified. */
    public static final Namespace DEFAULT = new Namespace("default");

    /** The {@code "kube-system"} namespace, conventionally used for cluster-internal pods. */
    public static final Namespace KUBE_SYSTEM = new Namespace("kube-system");

    @NonNull
    private final String name;

    @NonNull
    private LabelSet labels = LabelSet.EMPTY;

    public Namespace(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Namespace name must be non-blank");
        }
        this.name = name;
    }

    public Namespace(final String name, final LabelSet labels) {
        this(name);
        this.labels = labels == null ? LabelSet.EMPTY : labels;
    }

    @Override
    public String toString() {
        return "Namespace[%s]".formatted(name);
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Namespace that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}

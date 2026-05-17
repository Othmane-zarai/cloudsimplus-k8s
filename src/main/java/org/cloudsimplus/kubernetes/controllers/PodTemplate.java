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
package org.cloudsimplus.kubernetes.controllers;

import lombok.NonNull;
import org.cloudsimplus.kubernetes.KubernetesPod;

import java.util.function.IntFunction;

/**
 * A factory for fresh {@link KubernetesPod}s belonging to a controller
 * (ReplicaSet, Deployment, StatefulSet, ...). Mirrors Kubernetes'
 * {@code spec.template}: every time the controller needs a new pod, it calls
 * {@link #create(int)} and gets a fresh instance built from the spec.
 *
 * <p>The {@code ordinal} argument is used by stable-identity controllers
 * ({@link StatefulSetController}) to derive deterministic names like
 * {@code <baseName>-0}, {@code <baseName>-1}; controllers that don't care
 * about ordinals (ReplicaSet, Deployment) typically pass an incrementing
 * counter.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class PodTemplate {

    private final IntFunction<KubernetesPod> factory;

    public PodTemplate(@NonNull final IntFunction<KubernetesPod> factory) {
        this.factory = factory;
    }

    /**
     * Builds a fresh pod. The caller (a {@link Controller}) is expected to
     * stamp owner-reference labels on the returned pod before submission.
     *
     * @param ordinal a controller-supplied index (ignored by stateless controllers)
     */
    public KubernetesPod create(final int ordinal) {
        return factory.apply(ordinal);
    }
}

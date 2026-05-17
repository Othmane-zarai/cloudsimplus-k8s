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
package org.cloudsimplus.kubernetes.lifecycle;

import org.cloudsimplus.kubernetes.KubernetesContainer;

import java.util.function.Predicate;

/**
 * A readiness probe — when failing, the kubelet sets the pod's
 * {@link PodCondition#READY} to {@code false} and the
 * {@link org.cloudsimplus.kubernetes.KubernetesService} stops routing to it.
 * No restart action.
 *
 * @since CloudSim Plus 9.0.0
 */
public class ReadinessProbe extends Probe {
    public ReadinessProbe(final Predicate<KubernetesContainer> check) {
        super(check);
    }
}

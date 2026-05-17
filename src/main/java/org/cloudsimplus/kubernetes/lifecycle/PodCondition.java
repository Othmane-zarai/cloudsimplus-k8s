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

/**
 * Coarse pod-condition flags maintained by the kubelet, mirroring Kubernetes'
 * {@code v1.PodCondition.type}.
 *
 * @since CloudSim Plus 9.0.0
 */
public enum PodCondition {
    /** A node has been chosen for the pod. */
    POD_SCHEDULED,
    /** All init containers completed successfully. */
    INITIALIZED,
    /** All main containers are running and (where configured) passing readiness probes. */
    CONTAINERS_READY,
    /** Pod-level readiness — used by {@link org.cloudsimplus.kubernetes.KubernetesService} to gate endpoints. */
    READY
}

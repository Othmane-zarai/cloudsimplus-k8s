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
 * A periodic tick handler. Anything inside the Kubernetes simulator that wants
 * to run on a clock — controllers, the kubelet, autoscalers — implements this
 * and registers with
 * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#registerTick(Tick)}.
 *
 * <p>Implementations must be cheap and idempotent: the broker fires every
 * registered tick on every interval (default 1.0 s, set via
 * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#setControllerTickIntervalSeconds(double)}).</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@FunctionalInterface
public interface Tick {
    /**
     * @param clockTime the simulation clock at the moment of the tick
     */
    void tick(double clockTime);
}

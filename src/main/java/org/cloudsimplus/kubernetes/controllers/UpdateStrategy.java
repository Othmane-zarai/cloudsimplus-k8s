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

/**
 * Update strategies for a {@link DeploymentController}, mirroring Kubernetes'
 * {@code Deployment.spec.strategy}.
 *
 * @since CloudSim Plus 9.0.0
 */
public sealed interface UpdateStrategy permits UpdateStrategy.RollingUpdate, UpdateStrategy.Recreate {

    /**
     * Gradually replace old replicas with new ones bounded by {@code maxSurge}
     * (extra replicas allowed above {@code desiredReplicas}) and
     * {@code maxUnavailable} (replicas allowed below {@code desiredReplicas}).
     */
    record RollingUpdate(int maxSurge, int maxUnavailable) implements UpdateStrategy {
        public RollingUpdate {
            if (maxSurge < 0 || maxUnavailable < 0) {
                throw new IllegalArgumentException(
                    "maxSurge / maxUnavailable must be >= 0; got " + maxSurge + " / " + maxUnavailable);
            }
            if (maxSurge == 0 && maxUnavailable == 0) {
                throw new IllegalArgumentException(
                    "RollingUpdate requires at least one of maxSurge or maxUnavailable to be > 0");
            }
        }

        /** Sensible default: surge 1 over desired, no unavailability allowed. */
        public static RollingUpdate defaults() {
            return new RollingUpdate(1, 0);
        }
    }

    /** Tear down all old replicas first, then create the new replicas. */
    record Recreate() implements UpdateStrategy { }
}

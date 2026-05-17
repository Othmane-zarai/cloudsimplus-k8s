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
 * Container restart behaviour driven by the kubelet on cloudlet finish,
 * mirroring Kubernetes' {@code v1.RestartPolicy}.
 *
 * @since CloudSim Plus 9.0.0
 */
public enum RestartPolicy {
    /** Always restart the container regardless of exit status. */
    ALWAYS,
    /** Restart only if the container exited with a failure status. */
    ON_FAILURE,
    /** Never restart, even on failure. */
    NEVER;

    /** @return whether a container should be restarted given its exit-failure flag. */
    public static boolean shouldRestart(final RestartPolicy policy, final boolean failed) {
        if (policy == null) {
            return false;
        }
        return switch (policy) {
            case ALWAYS     -> true;
            case ON_FAILURE -> failed;
            case NEVER      -> false;
        };
    }
}

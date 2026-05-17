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

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesContainer;

import java.util.function.Predicate;

/**
 * Base class for {@link LivenessProbe} and {@link ReadinessProbe}: a periodic
 * predicate over a {@link KubernetesContainer} that the kubelet evaluates on
 * each tick. Mirrors the timing knobs of Kubernetes'
 * {@code v1.Probe}: {@code initialDelaySeconds}, {@code periodSeconds},
 * {@code failureThreshold}, {@code successThreshold}.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public abstract class Probe {

    @NonNull
    private final Predicate<KubernetesContainer> check;

    /** Delay before the first evaluation (simulated seconds). */
    private double initialDelaySeconds;

    /** Period between consecutive evaluations (simulated seconds). */
    private double periodSeconds = 10.0;

    /** Consecutive failures required for the probe to be considered failing. */
    private int failureThreshold = 3;

    /** Consecutive successes required for the probe to be considered healthy. */
    private int successThreshold = 1;

    protected Probe(final Predicate<KubernetesContainer> check) {
        this.check = check;
    }

    /** @return {@code true} when the probe currently considers the container healthy. */
    public boolean check(final KubernetesContainer container) {
        return check.test(container);
    }
}

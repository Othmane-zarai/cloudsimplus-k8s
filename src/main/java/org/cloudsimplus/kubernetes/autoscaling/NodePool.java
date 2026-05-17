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
package org.cloudsimplus.kubernetes.autoscaling;

import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesNode;

import java.util.function.Supplier;

/**
 * A pool of identical nodes used by the {@link ClusterAutoscaler} when scaling
 * the cluster up or down. The autoscaler builds new nodes from
 * {@link #getTemplate()} and adds them to the datacenter when a pod can't be
 * scheduled; conversely, it decommissions empty nodes from this pool when
 * idle, down to {@link #getMin()}.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Accessors(chain = true)
public final class NodePool {

    @NonNull
    private final String name;

    /** Factory producing fresh, configured {@link KubernetesNode}s on demand. */
    @NonNull
    private final Supplier<KubernetesNode> template;

    private final int min;
    private final int max;

    public NodePool(final String name, final Supplier<KubernetesNode> template, final int min, final int max) {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("Require 0 <= min <= max; got min=" + min + ", max=" + max);
        }
        this.name = name;
        this.template = template;
        this.min = min;
        this.max = max;
    }
}

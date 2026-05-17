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
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.kubernetes.lifecycle.LivenessProbe;
import org.cloudsimplus.kubernetes.lifecycle.ReadinessProbe;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;

/**
 * A Kubernetes container, modelled as a {@link CloudletSimple} carrying its
 * Kubernetes-specific metadata: container name, image identifier and the
 * {@code resources.requests} / {@code resources.limits} pair.
 *
 * <p>The CPU request determines the underlying cloudlet's {@code pesNumber}
 * (rounded up: {@code ceil(milliCpu/1000)}, with a floor of 1) so that workloads
 * smaller than one core still occupy at least one PE on the pod's VM. Memory
 * fields are carried verbatim and consumed by {@link KubernetesPod} when sizing
 * the underlying VM's RAM.</p>
 *
 * <p>The cloudlet length (in MI) is supplied at construction time — it has no
 * direct Kubernetes counterpart and represents how much simulated work the
 * container performs once started.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesContainer extends CloudletSimple {

    @NonNull
    private String containerName;

    @NonNull
    private String image = "";

    @NonNull
    private final Resources requests;

    @NonNull
    private final Resources limits;

    @NonNull
    private RestartPolicy restartPolicy = RestartPolicy.ALWAYS;

    /** When {@code true}, the kubelet runs this container before any main container. */
    private boolean initContainer;

    private LivenessProbe livenessProbe;
    private ReadinessProbe readinessProbe;

    /**
     * Creates a container.
     *
     * @param containerName the container name
     * @param lengthMI      simulated work in Million Instructions
     * @param requests      resource requests; {@code null} ↔ {@link Resources#ZERO}
     * @param limits        resource limits; {@code null} ↔ {@code requests}
     */
    public KubernetesContainer(
        final String containerName,
        final long lengthMI,
        final Resources requests,
        final Resources limits)
    {
        super(lengthMI, pesFor(requests));
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("Container name must be non-blank");
        }
        this.containerName = containerName;
        this.requests = requests == null ? Resources.ZERO : requests;
        this.limits = limits == null ? this.requests : limits;
        // Real Kubernetes rejects requests > limits at admission time. Mirror that
        // here so the simulator can never quietly under-report node capacity (M3).
        if (this.limits.milliCpu() < this.requests.milliCpu()
            || this.limits.memMiB() < this.requests.memMiB()) {
            throw new IllegalArgumentException(
                "Container '%s': limits must be >= requests (got requests=%s, limits=%s)"
                    .formatted(containerName, this.requests, this.limits));
        }
        setUtilizationModelCpu(new UtilizationModelFull());
    }

    /**
     * Convenience constructor: same {@link Resources} for requests and limits
     * (the {@code Guaranteed} QoS class in Kubernetes terms).
     */
    public KubernetesContainer(final String containerName, final long lengthMI, final Resources resources) {
        this(containerName, lengthMI, resources, resources);
    }

    /**
     * @return number of PEs to reserve for the underlying cloudlet, derived from
     * the CPU request. Floored at 1 so sub-core workloads still occupy a PE.
     */
    private static long pesFor(final Resources requests) {
        if (requests == null || requests.milliCpu() <= 0) {
            return 1;
        }
        return Math.max(1, (requests.milliCpu() + 999) / 1000);
    }

    @Override
    public String toString() {
        return "Container[%s, image=%s]".formatted(containerName, image);
    }
}

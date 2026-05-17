/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2016  Universidade da Beira Interior (UBI, Portugal) and
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
package org.cloudsimplus.utilizationmodels;

import lombok.Getter;
import lombok.NonNull;

import java.util.function.IntSupplier;

/**
 * A {@link UtilizationModel} that distributes a fixed total request-rate
 * across a dynamic number of pod replicas, so that per-pod CPU usage
 * <em>decreases</em> as more replicas are scheduled — exactly the behaviour
 * of a real throughput-bounded HTTP service.
 *
 * <p>The per-pod CPU utilization at any point in time is:
 * <pre>
 *   utilization = clamp(0, (requestsPerSecond / replicas) × cpuCostPerRequest, 1)
 * </pre>
 * where {@code cpuCostPerRequest} is the fraction of one pod's requested CPU
 * consumed by a single request (e.g. {@code 0.014} means each request uses
 * 1.4 % of the pod's CPU budget).</p>
 *
 * <p>The replica count is read via an {@link IntSupplier} so it can be wired
 * to a live {@link org.cloudsimplus.kubernetes.controllers.ReplicaSetController}
 * after the deployment is constructed:</p>
 *
 * <pre>{@code
 * AtomicReference<IntSupplier> ref = new AtomicReference<>(() -> 1);
 * var model = new UtilizationModelThroughput(250, 0.014, () -> ref.get().getAsInt());
 * var dep   = new DeploymentController(...);
 * ref.set(() -> dep.getActiveReplicaSet().currentReplicas());
 * }</pre>
 *
 * <h3>Calibration</h3>
 * Given a desired equilibrium replica count {@code N*} and HPA target {@code T}:
 * <pre>
 *   cpuCostPerRequest = N* × T / requestsPerSecond
 * </pre>
 * Example: N*=7, T=0.5, rps=250 → cpuCostPerRequest = 7×0.5/250 = 0.014
 *
 * @author CloudSim Plus K8s Extension
 * @since CloudSim Plus 9.0.0
 */
public class UtilizationModelThroughput extends UtilizationModelAbstract {

    /** Total request arrival rate shared across all replicas (requests/second). */
    @Getter
    private final double requestsPerSecond;

    /**
     * Fraction of one pod's requested CPU consumed per request.
     * Dimensionless — independent of the pod's actual CPU allocation.
     */
    @Getter
    private final double cpuCostPerRequest;

    /** Supplies the current number of running replicas at query time. */
    private final IntSupplier replicaSupplier;

    /**
     * Creates a throughput-bounded utilization model.
     *
     * @param requestsPerSecond total request arrival rate (≥ 0)
     * @param cpuCostPerRequest fraction of one pod's CPU budget consumed per request (≥ 0)
     * @param replicaSupplier   supplier of the current running replica count; called on
     *                          every {@link #getUtilization()} invocation
     */
    public UtilizationModelThroughput(
        final double requestsPerSecond,
        final double cpuCostPerRequest,
        @NonNull final IntSupplier replicaSupplier)
    {
        super();
        if (requestsPerSecond < 0)
            throw new IllegalArgumentException("requestsPerSecond must be >= 0, got " + requestsPerSecond);
        if (cpuCostPerRequest < 0)
            throw new IllegalArgumentException("cpuCostPerRequest must be >= 0, got " + cpuCostPerRequest);
        this.requestsPerSecond  = requestsPerSecond;
        this.cpuCostPerRequest  = cpuCostPerRequest;
        this.replicaSupplier    = replicaSupplier;
    }

    /**
     * Returns the per-pod CPU utilization as a fraction in [0, 1].
     * The replica count is read from the supplier on every call so changes
     * made by the HPA are reflected immediately.
     */
    @Override
    protected double getUtilizationInternal(final double time) {
        final int replicas = Math.max(1, replicaSupplier.getAsInt());
        final double perPodDemand = (requestsPerSecond / replicas) * cpuCostPerRequest;
        return Math.min(1.0, Math.max(0.0, perPodDemand));
    }
}

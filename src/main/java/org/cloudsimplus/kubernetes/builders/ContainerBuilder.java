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
package org.cloudsimplus.kubernetes.builders;

import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.Resources;
import org.cloudsimplus.kubernetes.lifecycle.LivenessProbe;
import org.cloudsimplus.kubernetes.lifecycle.ReadinessProbe;
import org.cloudsimplus.kubernetes.lifecycle.RestartPolicy;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;

/**
 * Fluent builder for {@link KubernetesContainer} that accepts Kubernetes-style
 * resource quantities ({@code "500m"}, {@code "256Mi"}) and falls back to
 * sensible defaults so most calls can be terse.
 *
 * <pre>
 * var c = ContainerBuilder.of("api")
 *     .image("api:1.4")
 *     .cpu("500m").mem("256Mi")
 *     .length(20_000)
 *     .build();
 * </pre>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class ContainerBuilder {

    private final String name;
    private String image = "";
    private long milliCpu;
    private long memMiB;
    private long limitMilliCpu = -1;
    private long limitMemMiB = -1;
    private long lengthMI = 1_000;
    private RestartPolicy restartPolicy = RestartPolicy.ALWAYS;
    private boolean initContainer;
    private LivenessProbe livenessProbe;
    private ReadinessProbe readinessProbe;
    private UtilizationModel cpuUtilization = new UtilizationModelDynamic(0.5);

    private ContainerBuilder(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Container name is required");
        }
        this.name = name;
    }

    /** Starts a builder for a container with the given name. */
    public static ContainerBuilder of(final String name) {
        return new ContainerBuilder(name);
    }

    public ContainerBuilder image(final String image) {
        this.image = image == null ? "" : image;
        return this;
    }

    /** Sets the CPU request, parsed via {@link Resources#parseCpu(String)}. */
    public ContainerBuilder cpu(final String spec) {
        this.milliCpu = Resources.parseCpu(spec);
        return this;
    }

    /** Sets the memory request, parsed via {@link Resources#parseMem(String)}. */
    public ContainerBuilder mem(final String spec) {
        this.memMiB = Resources.parseMem(spec);
        return this;
    }

    /** Sets the CPU limit (defaults to the request when not set). */
    public ContainerBuilder cpuLimit(final String spec) {
        this.limitMilliCpu = Resources.parseCpu(spec);
        return this;
    }

    /** Sets the memory limit (defaults to the request when not set). */
    public ContainerBuilder memLimit(final String spec) {
        this.limitMemMiB = Resources.parseMem(spec);
        return this;
    }

    /** Sets the cloudlet length in MI (simulated work performed by the container). */
    public ContainerBuilder length(final long mi) {
        if (mi < 0) {
            throw new IllegalArgumentException("length (MI) must be >= 0");
        }
        this.lengthMI = mi;
        return this;
    }

    public ContainerBuilder restartPolicy(final RestartPolicy policy) {
        this.restartPolicy = policy == null ? RestartPolicy.ALWAYS : policy;
        return this;
    }

    /** Marks this container as a Pod init container — runs in declared order before main containers. */
    public ContainerBuilder asInitContainer() {
        this.initContainer = true;
        return this;
    }

    public ContainerBuilder livenessProbe(final LivenessProbe probe) {
        this.livenessProbe = probe;
        return this;
    }

    public ContainerBuilder readinessProbe(final ReadinessProbe probe) {
        this.readinessProbe = probe;
        return this;
    }

    /**
     * Overrides the default {@link UtilizationModelDynamic UtilizationModelDynamic(0.5)}
     * with any other utilization model.
     *
     * <p><b>Default behaviour.</b> Containers start at 50% CPU utilisation so
     * the {@link org.cloudsimplus.kubernetes.autoscaling.HorizontalPodAutoscaler HPA}
     * gets a realistic signal out of the box. Pass a different
     * {@link UtilizationModelDynamic} (or any other model) to drive custom
     * load curves; pass {@link org.cloudsimplus.utilizationmodels.UtilizationModelFull
     * UtilizationModelFull#getInstance()} to restore the previous always-100% behaviour
     * (e.g. in tests that rely on maximum-speed cloudlet completion).</p>
     */
    public ContainerBuilder cpuUtilization(final UtilizationModel model) {
        if (model != null) {
            this.cpuUtilization = model;
        }
        return this;
    }

    public KubernetesContainer build() {
        final var requests = new Resources(milliCpu, memMiB);
        final var limits = new Resources(
            limitMilliCpu < 0 ? milliCpu : limitMilliCpu,
            limitMemMiB < 0 ? memMiB : limitMemMiB);
        final var c = new KubernetesContainer(name, lengthMI, requests, limits);
        c.setImage(image);
        c.setRestartPolicy(restartPolicy);
        c.setInitContainer(initContainer);
        c.setLivenessProbe(livenessProbe);
        c.setReadinessProbe(readinessProbe);
        c.setUtilizationModelCpu(cpuUtilization);
        return c;
    }
}

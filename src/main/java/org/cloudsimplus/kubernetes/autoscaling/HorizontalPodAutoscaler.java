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
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.controllers.DeploymentController;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Horizontal Pod Autoscaler. Periodically samples a controller's pods, computes
 * average CPU utilisation, and adjusts the controller's
 * {@code desiredReplicas} to drive utilisation toward a target.
 *
 * <p>Wraps either a {@link ReplicaSetController} or a
 * {@link DeploymentController} via static {@link #of(ReplicaSetController, double)}
 * / {@link #of(DeploymentController, double)} factory methods. Mirrors the
 * algorithm used by Kubernetes' built-in HPA:
 * {@code desired = ceil(current * avgUtil / target)}, clamped to
 * {@code [minReplicas, maxReplicas]} and gated by a cooldown.</p>
 *
 * <p><b>Important caveat for research use.</b> This HPA reads each pod's CPU%
 * via {@link KubernetesPod#getCpuPercentUtilization()}, which is governed by
 * the container's {@link org.cloudsimplus.utilizationmodels.UtilizationModel}.
 * The default model installed by {@link KubernetesPod}'s containers is
 * {@link org.cloudsimplus.utilizationmodels.UtilizationModelFull}, which reports
 * 100% until the cloudlet length is exhausted and then 0%. Under that default
 * the HPA effectively reacts to <i>cloudlet completion</i> rather than load —
 * useful for smoke-testing the loop, but not realistic. For research scenarios
 * pass a time-varying model (e.g.
 * {@link org.cloudsimplus.utilizationmodels.UtilizationModelDynamic}) to each
 * container via
 * {@link org.cloudsimplus.kubernetes.builders.ContainerBuilder#cpuUtilization(org.cloudsimplus.utilizationmodels.UtilizationModel)
 * ContainerBuilder.cpuUtilization(...)} so the HPA actually tracks workload
 * variation.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class HorizontalPodAutoscaler implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(HorizontalPodAutoscaler.class.getSimpleName());

    @NonNull
    private final String name;

    /** Target CPU utilisation as a fraction in {@code (0, 1]}. */
    private final double targetCpuUtilization;

    private int minReplicas = 1;
    private int maxReplicas = 10;

    /**
     * Legacy single-cooldown setting. When set via {@link #setCooldownSeconds(double)}
     * it overrides both {@link #cooldownScaleUpSeconds} and
     * {@link #cooldownScaleDownSeconds}, preserving pre-K8s-1.18 semantics for
     * existing callers and tests.
     */
    private double cooldownSeconds = 60.0;

    /**
     * Stabilisation window for scale-up events (mirrors
     * {@code behavior.scaleUp.stabilizationWindowSeconds}). Default {@code 0}:
     * K8s 1.18+ allows scale-up without delay. A {@link #setCooldownSeconds(double)}
     * call overwrites this to keep backward compatibility.
     */
    private double cooldownScaleUpSeconds = 0.0;

    /**
     * Stabilisation window for scale-down events (mirrors
     * {@code behavior.scaleDown.stabilizationWindowSeconds}). Default {@code 300}:
     * K8s 1.18+ defers scale-down by five minutes to dampen flapping. A
     * {@link #setCooldownSeconds(double)} call overwrites this for back-compat.
     */
    private double cooldownScaleDownSeconds = 300.0;

    /**
     * K8s tolerance window: do nothing when {@code |avg - target|/target < tolerance}.
     * Default 0.10 mirrors {@code --horizontal-pod-autoscaler-tolerance}; without
     * it, the simulator produces flutter that real clusters do not.
     */
    private double tolerance = 0.10;

    private double lastScaleAt = -1.0;

    /**
     * Legacy single-cooldown setter. Sets both up- and down-cooldowns to
     * {@code seconds} so callers who haven't migrated to the K8s-1.18+
     * split-cooldown API keep their prior behaviour.
     */
    public HorizontalPodAutoscaler setCooldownSeconds(final double seconds) {
        this.cooldownSeconds = seconds;
        this.cooldownScaleUpSeconds = seconds;
        this.cooldownScaleDownSeconds = seconds;
        return this;
    }

    /** Pulls the current backing pods. */
    private final Supplier<List<KubernetesPod>> podsSupplier;
    /** Reads the controller's current desired-replicas value. */
    private final IntSupplier desiredReplicasSupplier;
    /** Updates the controller's desired-replicas value. */
    private final IntConsumer desiredReplicasSetter;

    public HorizontalPodAutoscaler(
        final String name,
        final double targetCpuUtilization,
        final Supplier<List<KubernetesPod>> podsSupplier,
        final IntSupplier desiredReplicasSupplier,
        final IntConsumer desiredReplicasSetter)
    {
        if (targetCpuUtilization <= 0 || targetCpuUtilization > 1) {
            throw new IllegalArgumentException("target CPU utilisation must be in (0, 1]");
        }
        this.name = name;
        this.targetCpuUtilization = targetCpuUtilization;
        this.podsSupplier = podsSupplier;
        this.desiredReplicasSupplier = desiredReplicasSupplier;
        this.desiredReplicasSetter = desiredReplicasSetter;
    }

    /** Convenience: wrap a {@link ReplicaSetController}. */
    public static HorizontalPodAutoscaler of(final ReplicaSetController rs, final double targetCpu) {
        return new HorizontalPodAutoscaler(
            rs.getName() + "-hpa",
            targetCpu,
            rs::getManagedPods,
            rs::getDesiredReplicas,
            rs::setDesiredReplicas);
    }

    /** Convenience: wrap a {@link DeploymentController}. */
    public static HorizontalPodAutoscaler of(final DeploymentController dep, final double targetCpu) {
        return new HorizontalPodAutoscaler(
            dep.getName() + "-hpa",
            targetCpu,
            () -> dep.getActiveReplicaSet().getManagedPods(),
            dep::getDesiredReplicas,
            dep::setDesiredReplicas);
    }

    @Override
    public void tick(final double clockTime) {
        // Sample only Ready pods, matching real Kubernetes HPA semantics: pods
        // that are scheduled but not yet passing readiness are excluded so they
        // neither inflate the denominator on scale-up nor dilute the signal on
        // scale-down. (Pods without a readiness probe are considered Ready by
        // KubernetesPod.isReady() once their READY condition is set by the kubelet.)
        final var readyPods = podsSupplier.get().stream()
            .filter(KubernetesPod::isReady)
            .toList();
        if (readyPods.isEmpty()) {
            return;
        }
        final double avg = readyPods.stream()
            .mapToDouble(KubernetesPod::getCpuPercentUtilization)
            .average()
            .orElse(0.0);
        if (avg < 0) {
            // Genuine undefined-utilisation case (negative is non-physical).
            return;
        }
        // K8s tolerance window: skip scaling decisions inside ±tolerance of target.
        // This is the simulator analog of --horizontal-pod-autoscaler-tolerance.
        if (Math.abs(avg - targetCpuUtilization) / targetCpuUtilization < tolerance) {
            return;
        }
        final int current = desiredReplicasSupplier.getAsInt();
        final int desired = Math.max(minReplicas, Math.min(maxReplicas,
            (int) Math.ceil(current * avg / targetCpuUtilization)));
        if (desired != current) {
            // K8s 1.18+ stabilisation windows are evaluated against the
            // *proposed* direction: scale-up gated by cooldownScaleUpSeconds,
            // scale-down by cooldownScaleDownSeconds. Legacy single-cooldown
            // callers route through setCooldownSeconds, which sets both
            // buckets identically.
            final double cooldown = desired > current ? cooldownScaleUpSeconds : cooldownScaleDownSeconds;
            if (lastScaleAt >= 0 && clockTime - lastScaleAt < cooldown) {
                return;
            }
            LOG.info("{}: HPA '{}': avg CPU={}%, replicas {} -> {}",
                String.format("%.2f", clockTime), name,
                String.format("%.1f", avg * 100), current, desired);
            desiredReplicasSetter.accept(desired);
            lastScaleAt = clockTime;
        }
    }
}

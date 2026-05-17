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
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Resources;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Vertical Pod Autoscaler — samples per-container CPU and memory utilisation
 * across the {@link ReplicaSetController}'s managed pods and produces sizing
 * <i>recommendations</i> for the container limits that would bring average
 * utilisation back to the configured targets.
 *
 * <p>This implementation honours the K8s VPA "<b>Off / Initial</b>" semantics:
 * recommendations are computed and exposed via
 * {@link #getRecommendedMilliCpu()} and {@link #getRecommendedMemMiB()} but
 * pods are <i>not</i> mutated automatically — {@link KubernetesContainer}
 * resource specs are immutable, and rewriting the {@link
 * org.cloudsimplus.kubernetes.controllers.PodTemplate} requires
 * template-aware glue that only the user can supply. Auto-recreate mode is
 * opt-in via {@link #setEvictOnRecommendation(boolean) evictOnRecommendation}:
 * when set, pods are destroyed so the ReplicaSet recreates them; users
 * combining VPA with a custom template can rebuild containers using the
 * latest recommendation.</p>
 *
 * <p>Updates are gated by:</p>
 * <ul>
 *   <li>{@link #getTolerance() tolerance} — recommendations within ±tolerance
 *       of the current limit are suppressed.</li>
 *   <li>{@link #getCooldownSeconds() cooldownSeconds} — minimum interval
 *       between actions, mirroring the K8s VPA updater's rate-limiter.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
@Accessors(chain = true)
public class VerticalPodAutoscaler implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(VerticalPodAutoscaler.class.getSimpleName());

    @NonNull
    private final String name;

    @NonNull
    private final ReplicaSetController target;

    /** Target average CPU utilisation in {@code (0, 1]}. Default 0.7 mirrors the upstream VPA recommender. */
    private double targetCpuUtilization = 0.7;

    /** Target average RAM utilisation in {@code (0, 1]}. */
    private double targetRamUtilization = 0.7;

    /**
     * Relative deadband around the target, in {@code [0, 1)}. A
     * recommendation that would change a limit by less than
     * {@code |limit| * tolerance} is suppressed.
     */
    private double tolerance = 0.10;

    /** Minimum simulated seconds between two consecutive scale actions. */
    private double cooldownSeconds = 60.0;

    /** When true, evict managed pods after a recommendation update so the RS recreates them. */
    private boolean evictOnRecommendation;

    private double lastActionAt = -1.0;
    private long recommendedMilliCpu;
    private long recommendedMemMiB;

    /**
     * Creates a VPA targeting the given ReplicaSet (or a Deployment's active
     * RS, obtained via
     * {@link
     * org.cloudsimplus.kubernetes.controllers.DeploymentController#getActiveReplicaSet}).
     * Register on the broker via
     * {@link
     * org.cloudsimplus.kubernetes.KubernetesClusterBroker#registerTick(Tick)}.
     *
     * @param name   the VPA name (must be non-blank, used in logs only)
     * @param target the controller whose pods are sampled
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public VerticalPodAutoscaler(final String name, final ReplicaSetController target) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("VPA name must be non-blank");
        }
        this.name = name;
        this.target = java.util.Objects.requireNonNull(target, "target");
    }

    /** @param value target CPU utilisation in {@code (0, 1]} */
    public VerticalPodAutoscaler setTargetCpuUtilization(final double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("targetCpuUtilization must be in (0, 1], got " + value);
        }
        this.targetCpuUtilization = value;
        return this;
    }

    /** @param value target RAM utilisation in {@code (0, 1]} */
    public VerticalPodAutoscaler setTargetRamUtilization(final double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("targetRamUtilization must be in (0, 1], got " + value);
        }
        this.targetRamUtilization = value;
        return this;
    }

    /** @param value tolerance in {@code [0, 1)} */
    public VerticalPodAutoscaler setTolerance(final double value) {
        if (value < 0.0 || value >= 1.0) {
            throw new IllegalArgumentException("tolerance must be in [0, 1), got " + value);
        }
        this.tolerance = value;
        return this;
    }

    /** @param value cooldown in simulated seconds (must be {@code >= 0}) */
    public VerticalPodAutoscaler setCooldownSeconds(final double value) {
        if (value < 0.0) {
            throw new IllegalArgumentException("cooldownSeconds must be >= 0, got " + value);
        }
        this.cooldownSeconds = value;
        return this;
    }

    public VerticalPodAutoscaler setEvictOnRecommendation(final boolean value) {
        this.evictOnRecommendation = value;
        return this;
    }

    @Override
    public void tick(final double clockTime) {
        if (lastActionAt >= 0 && clockTime - lastActionAt < cooldownSeconds) {
            return;
        }

        final List<KubernetesPod> pods = target.getManagedPods();
        if (pods.isEmpty()) {
            return;
        }

        double sumCpu = 0.0;
        double sumRam = 0.0;
        long currentMilliCpu = 0;
        long currentMemMiB = 0;
        int n = 0;
        for (final var pod : pods) {
            sumCpu += pod.getCpuPercentUtilization();
            sumRam += pod.getRam().getPercentUtilization();
            for (final var c : pod.getContainers()) {
                currentMilliCpu = Math.max(currentMilliCpu, c.getLimits().milliCpu());
                currentMemMiB = Math.max(currentMemMiB, c.getLimits().memMiB());
            }
            n++;
        }
        if (n == 0 || currentMilliCpu == 0) {
            return;
        }
        final double avgCpu = sumCpu / n;
        final double avgRam = sumRam / n;

        final long newMilliCpu = recommend(currentMilliCpu, avgCpu, targetCpuUtilization);
        final long newMemMiB = recommend(currentMemMiB, avgRam, targetRamUtilization);

        final boolean cpuChanged = outsideTolerance(currentMilliCpu, newMilliCpu);
        final boolean ramChanged = outsideTolerance(currentMemMiB, newMemMiB);
        if (!cpuChanged && !ramChanged) {
            return;
        }

        recommendedMilliCpu = newMilliCpu;
        recommendedMemMiB = newMemMiB;
        lastActionAt = clockTime;

        LOG.info("{}: VPA '{}': avg CPU={}%, avg RAM={}%; recommend cpu={}m mem={}MiB (was {}m / {}MiB)",
            String.format("%.2f", clockTime), name,
            String.format("%.1f", avgCpu * 100),
            String.format("%.1f", avgRam * 100),
            newMilliCpu, newMemMiB, currentMilliCpu, currentMemMiB);

        if (evictOnRecommendation) {
            for (final var pod : pods) {
                pod.getBroker().requestIdleVmDestruction(pod);
            }
        }
    }

    /** Latest sizing recommendation as a {@link Resources} record. */
    public Resources getRecommendation() {
        return new Resources(recommendedMilliCpu, recommendedMemMiB);
    }

    private static long recommend(final long current, final double avgUtil, final double target) {
        if (avgUtil <= 0 || target <= 0) {
            return current;
        }
        return Math.max(1, Math.round(current * avgUtil / target));
    }

    private boolean outsideTolerance(final long current, final long proposed) {
        if (current == 0) {
            return proposed != 0;
        }
        return Math.abs(proposed - current) / (double) current >= tolerance;
    }
}

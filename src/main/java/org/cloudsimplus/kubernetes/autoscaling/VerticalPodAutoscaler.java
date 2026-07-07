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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vertical Pod Autoscaler — samples per-container CPU and memory utilisation
 * across the {@link ReplicaSetController}'s managed pods and produces sizing
 * recommendations for the container limits that would bring average utilisation
 * back to the configured targets.
 *
 * <p>Three operating modes mirror the upstream Kubernetes VPA
 * {@code updatePolicy.updateMode}:</p>
 * <ul>
 *   <li>{@link Mode#OFF} — recommendations are computed and exposed via
 *       {@link #getRecommendedMilliCpu()} / {@link #getRecommendedMemMiB()};
 *       no automatic action is taken.</li>
 *   <li>{@link Mode#INITIAL} (<b>default</b>) — same as Off for running pods.
 *       The {@link org.cloudsimplus.kubernetes.controllers.PodTemplate} can
 *       read the latest recommendation when creating replacement pods, enabling
 *       the user-driven evict-and-recreate pattern.</li>
 *   <li>{@link Mode#AUTO} — applies the recommendation <b>in-place</b>:
 *       {@link KubernetesContainer#applyInPlaceResize(Resources)} patches the
 *       effective limits on each running container without evicting the pod,
 *       mirroring the K8s 1.27+ {@code InPlacePodVerticalScaling} behaviour for
 *       CPU (compressible; no restart required). Memory resize is modelled as a
 *       documented simplification: effective limits are updated without a container
 *       restart (the QoS-class reclassification side-effects of real K8s are not
 *       simulated).</li>
 * </ul>
 *
 * <p>Updates are gated by:</p>
 * <ul>
 *   <li>{@link #getTolerance() tolerance} — recommendations within ±tolerance
 *       of the current effective limit are suppressed.</li>
 *   <li>{@link #getCooldownSeconds() cooldownSeconds} — minimum interval
 *       between actions, mirroring the K8s VPA updater's rate-limiter.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
@Accessors(chain = true)
public class VerticalPodAutoscaler implements Tick {

    /**
     * Operating mode mirroring upstream Kubernetes VPA {@code updatePolicy.updateMode}.
     */
    public enum Mode {
        /** Compute and expose recommendations; take no automatic action. */
        OFF,
        /** Same as Off for running pods (default). Template-driven recreation is user-managed. */
        INITIAL,
        /**
         * Apply recommendations in-place: patch each container's effective limits and
         * resubmit the cloudlet without evicting the pod (mirrors K8s 1.27+
         * {@code InPlacePodVerticalScaling}).
         */
        AUTO
    }

    private static final Logger LOG = LoggerFactory.getLogger(VerticalPodAutoscaler.class.getSimpleName());

    @NonNull
    private final String name;

    @NonNull
    private final ReplicaSetController target;

    /** Operating mode; defaults to {@link Mode#INITIAL}. */
    private Mode mode = Mode.INITIAL;

    /** Target average CPU utilisation in {@code (0, 1]}. Default 0.7 mirrors the upstream VPA recommender. */
    private double targetCpuUtilization = 0.7;

    /** Target average RAM utilisation in {@code (0, 1]}. */
    private double targetRamUtilization = 0.7;

    /**
     * Relative deadband around the target, in {@code [0, 1)}. A
     * recommendation that would change an effective limit by less than
     * {@code |limit| * tolerance} is suppressed.
     */
    private double tolerance = 0.10;

    /** Minimum simulated seconds between two consecutive scale actions. */
    private double cooldownSeconds = 60.0;

    /**
     * When true (and mode is not {@link Mode#AUTO}), evict managed pods after a
     * recommendation update so the ReplicaSet recreates them from a user-updated template.
     */
    private boolean evictOnRecommendation;

    private double lastActionAt = -1.0;
    private long recommendedMilliCpu;
    private long recommendedMemMiB;

    /**
     * Percentile of the recommendation history used as the headline estimate.
     * Default {@code 0.90} mirrors the upstream VPA recommender's "confidence
     * ratio" — the recommender assumes the true demand sits at the 90th
     * percentile of recently observed need.
     */
    private double confidenceRatio = 0.90;

    /**
     * Multiplicative safety margin applied on top of the percentile estimate to
     * leave headroom for memory bursts that would otherwise OOM the container.
     * Default {@code 1.05} mirrors the upstream VPA's {@code --safety-margin-fraction}
     * default (5 % above the observed peak).
     */
    private double oomHeadroom = 1.05;

    /**
     * Sliding window of raw per-tick recommendations (one entry per axis). Length
     * {@code 8} matches the buffer choice in USER_SIDE_QUESTS §A.3. The percentile
     * is computed over this window every tick — that, plus the OOM headroom and
     * confidence ratio, gives the smoothed final recommendation.
     */
    private final CircularBuffer<Double> cpuRecommendationHistory = new CircularBuffer<>(8);
    private final CircularBuffer<Double> memRecommendationHistory = new CircularBuffer<>(8);

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

    /** Sets the operating mode. Defaults to {@link Mode#INITIAL}. */
    public VerticalPodAutoscaler setMode(final Mode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        return this;
    }

    public VerticalPodAutoscaler setEvictOnRecommendation(final boolean value) {
        this.evictOnRecommendation = value;
        return this;
    }

    /**
     * Sets the percentile-of-history used to drive the recommendation. The
     * upstream VPA recommender uses {@code 0.90}; values close to {@code 1.0}
     * pick the recent peak (less smoothing, higher safety), values closer to
     * {@code 0.5} smooth more aggressively.
     *
     * @param value in {@code (0, 1]}
     */
    public VerticalPodAutoscaler setConfidenceRatio(final double value) {
        if (value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException("confidenceRatio must be in (0, 1], got " + value);
        }
        this.confidenceRatio = value;
        return this;
    }

    /**
     * Sets the multiplicative OOM headroom applied above the percentile
     * estimate. Default {@code 1.05} mirrors the upstream VPA recommender's
     * {@code --safety-margin-fraction=0.05}.
     *
     * @param value must be {@code >= 1.0}
     */
    public VerticalPodAutoscaler setOomHeadroom(final double value) {
        if (!(value >= 1.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException("oomHeadroom must be finite and >= 1.0, got " + value);
        }
        this.oomHeadroom = value;
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
                // Use effectiveLimits so subsequent ticks recommend relative to the
                // already-resized allocation, not the original declared spec.
                currentMilliCpu = Math.max(currentMilliCpu, c.getEffectiveLimits().milliCpu());
                currentMemMiB = Math.max(currentMemMiB, c.getEffectiveLimits().memMiB());
            }
            n++;
        }
        if (n == 0 || currentMilliCpu == 0) {
            return;
        }
        final double avgCpu = sumCpu / n;
        final double avgRam = sumRam / n;

        // Push the raw per-tick observation (the K8s VPA "actual usage at target"
        // estimate) into the recommendation history BEFORE reading the percentile.
        // The recommendation buffer absorbs short-lived peaks: the headline
        // estimate uses the configured confidence ratio (default p90) over the
        // last `recommendationHistory.capacity()` samples.
        if (avgCpu > 0 && targetCpuUtilization > 0) {
            cpuRecommendationHistory.add(currentMilliCpu * avgCpu / targetCpuUtilization);
        }
        if (avgRam > 0 && targetRamUtilization > 0) {
            memRecommendationHistory.add(currentMemMiB * avgRam / targetRamUtilization);
        }

        final long newMilliCpu = recommendCpu(currentMilliCpu, cpuRecommendationHistory);
        final long newMemMiB = recommendMem(currentMemMiB, memRecommendationHistory);

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

        if (mode == Mode.AUTO) {
            applyInPlace(pods, new Resources(newMilliCpu, newMemMiB));
        } else if (evictOnRecommendation) {
            for (final var pod : pods) {
                pod.getBroker().requestIdleVmDestruction(pod);
            }
        }
    }

    /**
     * Applies an in-place resize to all non-init containers across the managed pods.
     *
     * <p>Patches each container's {@link KubernetesContainer#getEffectiveLimits() effectiveLimits}
     * without restarting or evicting the pod, mirroring the K8s 1.27+
     * {@code InPlacePodVerticalScaling} behaviour for CPU (a compressible resource): the cgroup
     * quota is adjusted live, the container process is not interrupted. Memory resize would
     * require a container restart in real Kubernetes; as a documented simplification, the
     * simulator applies both CPU and memory limit updates in-place without a restart.</p>
     */
    private void applyInPlace(final List<KubernetesPod> pods, final Resources newRes) {
        for (final var pod : pods) {
            for (final var c : pod.getContainers()) {
                if (c.isInitContainer()) {
                    continue;
                }
                c.applyInPlaceResize(newRes);
            }
        }
        LOG.info("{}: VPA '{}' AUTO: in-place resize applied to {} pod(s) (cpu={}m mem={}MiB)",
            String.format("%.2f", lastActionAt), name, pods.size(),
            newRes.milliCpu(), newRes.memMiB());
    }

    /** Latest sizing recommendation as a {@link Resources} record. */
    public Resources getRecommendation() {
        return new Resources(recommendedMilliCpu, recommendedMemMiB);
    }

    // CPU: history already holds (usage / targetCpu) so p90 is the recommendation directly.
    // Upstream VPA applies no extra multiplier to CPU recommendations.
    private long recommendCpu(final long current, final CircularBuffer<Double> history) {
        return percentileOf(current, history, 1.0);
    }

    // Memory: apply oomHeadroom safety bump (upstream VPA bumps memory to avoid OOM kills).
    private long recommendMem(final long current, final CircularBuffer<Double> history) {
        return percentileOf(current, history, oomHeadroom);
    }

    private long percentileOf(final long current, final CircularBuffer<Double> history,
                              final double multiplier) {
        if (history.isEmpty()) {
            return current;
        }
        final List<Double> sorted = new ArrayList<>(history.snapshot());
        Collections.sort(sorted);
        int index = (int) Math.ceil(confidenceRatio * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        final double percentile = sorted.get(index);
        return Math.max(1, Math.round(percentile * multiplier));
    }

    private boolean outsideTolerance(final long current, final long proposed) {
        if (current == 0) {
            return proposed != 0;
        }
        return Math.abs(proposed - current) / (double) current >= tolerance;
    }
}

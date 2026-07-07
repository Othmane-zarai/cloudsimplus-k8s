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

/**
 * Direction-specific scaling policy mirroring the upstream Kubernetes
 * {@code behavior.{scaleUp,scaleDown}} stanza of the
 * {@code autoscaling/v2 HorizontalPodAutoscaler}.
 *
 * <p>The policy combines a {@link SelectPolicy selection rule} with two
 * candidate magnitudes (absolute pod count and relative percent of current
 * replicas). The HPA evaluates both candidates per direction and resolves
 * them via {@code selectPolicy}:</p>
 * <ul>
 *   <li>{@code MAX} (Kubernetes scale-up default) — picks whichever candidate
 *       yields the larger replica delta.</li>
 *   <li>{@code MIN} (Kubernetes scale-down default) — picks whichever yields
 *       the smaller delta.</li>
 *   <li>{@code DISABLED} — vetoes any change in this direction.</li>
 * </ul>
 *
 * <p>A magnitude of {@code 0} (or negative) for either candidate disables that
 * leg without affecting the other. The percent candidate is rounded up to at
 * least {@code 1} pod when {@code percentPerWindow > 0} and {@code current >= 1},
 * matching the K8s reference behaviour.</p>
 *
 * @param select            how to combine the two candidates
 * @param podsPerWindow     absolute pod-count budget for the next decision
 * @param percentPerWindow  relative budget as a fraction of current replicas (e.g. {@code 1.0} = double)
 * @since CloudSim Plus 9.0.0
 */
public record ScalingPolicy(SelectPolicy select, int podsPerWindow, double percentPerWindow) {

    /**
     * Permissive default mirroring upstream HPA scale-up:
     * {@code Max(4 pods, 100% of current)} per 15-second window.
     */
    public static final ScalingPolicy SCALE_UP_DEFAULT =
        new ScalingPolicy(SelectPolicy.MAX, 4, 1.0);

    /**
     * Permissive default mirroring upstream HPA scale-down:
     * {@code Min(100% of current)} per 15-second window. K8s does not cap by an
     * absolute pod count for the scale-down direction by default.
     */
    public static final ScalingPolicy SCALE_DOWN_DEFAULT =
        new ScalingPolicy(SelectPolicy.MIN, 0, 1.0);

    public ScalingPolicy {
        if (select == null) {
            throw new IllegalArgumentException("select must not be null");
        }
        if (percentPerWindow < 0 || !Double.isFinite(percentPerWindow)) {
            throw new IllegalArgumentException(
                "percentPerWindow must be finite and >= 0, got " + percentPerWindow);
        }
    }

    /**
     * Resolves the maximum {@code |desired - current|} allowed under this policy.
     * Returns {@code Integer.MAX_VALUE} when both legs are non-positive — the
     * caller should treat that as "no cap configured".
     *
     * @param current current desired-replica count ({@code >= 0})
     * @return the resolved magnitude cap; {@code 0} iff {@link #select} is {@link SelectPolicy#DISABLED}
     */
    public int resolveCap(final int current) {
        if (select == SelectPolicy.DISABLED) {
            return 0;
        }
        final int absLeg = podsPerWindow > 0 ? podsPerWindow : Integer.MIN_VALUE;
        final int pctLeg = percentPerWindow > 0
            ? Math.max(1, (int) Math.ceil(current * percentPerWindow))
            : Integer.MIN_VALUE;
        if (absLeg == Integer.MIN_VALUE && pctLeg == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        return switch (select) {
            case MAX -> Math.max(absLeg, pctLeg);
            case MIN -> {
                if (absLeg == Integer.MIN_VALUE) yield pctLeg;
                if (pctLeg == Integer.MIN_VALUE) yield absLeg;
                yield Math.min(absLeg, pctLeg);
            }
            case DISABLED -> 0;
        };
    }
}

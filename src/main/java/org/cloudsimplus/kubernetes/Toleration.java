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

import java.util.Collection;

/**
 * A toleration declared on a {@link KubernetesPod}, allowing the pod to be
 * scheduled on a {@link KubernetesNode} that carries a matching {@link Taint}.
 *
 * <p>Mirrors Kubernetes' {@code v1.Toleration}: a {@code key} matched against
 * the taint, an {@link Operator} defining how the value comparison works
 * ({@code Equal} or {@code Exists}), and an optional {@link Taint.Effect} that
 * limits which taint effects this toleration covers.</p>
 *
 * @param key      the taint key the toleration applies to (empty matches every key
 *                 only when {@code operator == Exists})
 * @param operator the match operator
 * @param value    the expected taint value (ignored for {@code Exists})
 * @param effect   the taint effect this toleration covers; {@code null} matches every effect
 *
 * @since CloudSim Plus 9.0.0
 */
public record Toleration(String key, Operator operator, String value, Taint.Effect effect) {

    /** Match operators supported by {@link Toleration}. */
    public enum Operator { EQUAL, EXISTS }

    public Toleration {
        if (operator == null) {
            throw new IllegalArgumentException("Toleration operator is required");
        }
        key = key == null ? "" : key;
        value = value == null ? "" : value;
        if (operator == Operator.EXISTS && !value.isEmpty()) {
            throw new IllegalArgumentException("Operator=Exists must not specify a value");
        }
    }

    /** Convenience: matches any taint with the given key, regardless of value. */
    public static Toleration exists(final String key) {
        return new Toleration(key, Operator.EXISTS, "", null);
    }

    /** Convenience: matches a {@code key=value} taint with any effect. */
    public static Toleration equal(final String key, final String value) {
        return new Toleration(key, Operator.EQUAL, value, null);
    }

    /** Convenience: matches a {@code key=value} taint with the given effect. */
    public static Toleration equal(final String key, final String value, final Taint.Effect effect) {
        return new Toleration(key, Operator.EQUAL, value, effect);
    }

    /**
     * @return {@code true} if this toleration covers the given taint.
     */
    public boolean tolerates(final Taint taint) {
        if (taint == null) {
            return false;
        }
        if (effect != null && effect != taint.effect()) {
            return false;
        }
        if (!key.isEmpty() && !key.equals(taint.key())) {
            return false;
        }
        return switch (operator) {
            case EXISTS -> true;
            case EQUAL  -> value.equals(taint.value());
        };
    }

    /**
     * Helper: evaluates whether {@code tolerations} cover every taint on a node
     * whose effect is in scope (i.e. {@code NoSchedule} or {@code NoExecute}).
     * {@code PreferNoSchedule} taints do not block scheduling, so they are
     * deliberately ignored here and surfaced via the score function instead.
     */
    public static boolean coversAll(final Collection<Toleration> tolerations, final Collection<Taint> taints) {
        for (final var taint : taints) {
            if (taint.effect() == Taint.Effect.PREFER_NO_SCHEDULE) {
                continue;
            }
            if (tolerations.stream().noneMatch(t -> t.tolerates(taint))) {
                return false;
            }
        }
        return true;
    }
}

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

/**
 * A taint applied to a {@link KubernetesNode}: a marker that <i>repels</i>
 * pods unless they declare a matching {@link Toleration}.
 *
 * <p>Mirrors Kubernetes' {@code v1.Taint}: a {@code key}, optional {@code value},
 * and an {@link Effect} that determines what happens when a pod cannot tolerate
 * the taint.</p>
 *
 * @param key    taint key (required)
 * @param value  taint value (may be empty when only the key matters)
 * @param effect the effect applied to non-tolerating pods
 *
 * @since CloudSim Plus 9.0.0
 */
public record Taint(String key, String value, Effect effect) {

    public Taint {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Taint key must be non-blank");
        }
        if (effect == null) {
            throw new IllegalArgumentException("Taint effect is required");
        }
        value = value == null ? "" : value;
    }

    /** Convenience: create a {@code NoSchedule} taint with an empty value. */
    public static Taint noSchedule(final String key) {
        return new Taint(key, "", Effect.NO_SCHEDULE);
    }

    /** Convenience: create a {@code NoSchedule} taint with the given value. */
    public static Taint noSchedule(final String key, final String value) {
        return new Taint(key, value, Effect.NO_SCHEDULE);
    }

    /**
     * The effect applied by a {@link Taint} to a pod that does not tolerate it.
     * Mirrors Kubernetes' {@code TaintEffect} values.
     */
    public enum Effect {
        /** Hard-block scheduling: the scheduler will not place the pod on the node. */
        NO_SCHEDULE,
        /** Soft-block: the scheduler prefers other nodes but may still place the pod here. */
        PREFER_NO_SCHEDULE,
        /** Hard-block plus eviction: also prevents new pods <i>and</i> evicts existing ones. */
        NO_EXECUTE
    }
}

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
package org.cloudsimplus.kubernetes.lifecycle;

import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Resources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link PodCondition} / {@link PodPhase} are wired into
 * {@link KubernetesPod} correctly: defaults, mutability, and the {@code Ready}
 * helper used by {@link org.cloudsimplus.kubernetes.KubernetesService}.
 */
class PodEnumsTest {

    private KubernetesPod pod() {
        return new KubernetesPod("p",
            List.of(new KubernetesContainer("c", 100, Resources.of("100m", "32Mi"))));
    }

    @Test
    void podStartsInPendingPhaseAndNotReady() {
        final var p = pod();
        assertEquals(PodPhase.PENDING, p.getPhase());
        assertFalse(p.isReady());
        assertTrue(p.getConditions().isEmpty());
    }

    @Test
    void setConditionFlipsReadyHelper() {
        final var p = pod();
        p.setCondition(PodCondition.READY, true);
        assertTrue(p.isReady());
        p.setCondition(PodCondition.READY, false);
        assertFalse(p.isReady());
    }

    @Test
    void setConditionsArePersisted() {
        final var p = pod();
        p.setCondition(PodCondition.POD_SCHEDULED, true)
         .setCondition(PodCondition.INITIALIZED, true)
         .setCondition(PodCondition.CONTAINERS_READY, false);
        assertEquals(Boolean.TRUE,  p.getConditions().get(PodCondition.POD_SCHEDULED));
        assertEquals(Boolean.TRUE,  p.getConditions().get(PodCondition.INITIALIZED));
        assertEquals(Boolean.FALSE, p.getConditions().get(PodCondition.CONTAINERS_READY));
    }

    @Test
    void allPhaseValuesAreReachable() {
        final var p = pod();
        for (final var phase : PodPhase.values()) {
            p.setPhase(phase);
            assertEquals(phase, p.getPhase());
        }
    }
}

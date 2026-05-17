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
import org.cloudsimplus.kubernetes.Resources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeTest {

    private static KubernetesContainer container() {
        return new KubernetesContainer("c", 100, Resources.of("100m", "32Mi"));
    }

    @Test
    void livenessProbeRunsSuppliedPredicate() {
        final var c = container();
        final var probe = new LivenessProbe(__ -> false);
        assertFalse(probe.check(c));

        final var alwaysOk = new LivenessProbe(__ -> true);
        assertTrue(alwaysOk.check(c));
    }

    @Test
    void readinessProbeRunsSuppliedPredicate() {
        final var c = container();
        final var ok = new ReadinessProbe(__ -> true);
        final var fail = new ReadinessProbe(__ -> false);
        assertTrue(ok.check(c));
        assertFalse(fail.check(c));
    }

    @Test
    void timingKnobsHaveSensibleDefaults() {
        final var p = new LivenessProbe(__ -> true);
        assertEquals(0.0, p.getInitialDelaySeconds());
        assertEquals(10.0, p.getPeriodSeconds());
        assertEquals(3, p.getFailureThreshold());
        assertEquals(1, p.getSuccessThreshold());
    }

    @Test
    void timingKnobsAreFluentlyChainable() {
        final var p = new ReadinessProbe(__ -> true)
            .setInitialDelaySeconds(5.0)
            .setPeriodSeconds(2.0)
            .setFailureThreshold(2)
            .setSuccessThreshold(3);
        assertEquals(5.0, p.getInitialDelaySeconds());
        assertEquals(2.0, p.getPeriodSeconds());
        assertEquals(2, p.getFailureThreshold());
        assertEquals(3, p.getSuccessThreshold());
    }

    @Test
    void checkPredicateIsCarried() {
        final var p = new LivenessProbe(__ -> true);
        assertNotNull(p.getCheck());
    }
}

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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link KubernetesPod#isUnschedulable() unschedulable}
 * tracking added by the May 2026 review fix. The flag is the source of truth
 * the {@link org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler} uses
 * to decide when to provision a new node — if it ever drifts from "scheduler
 * tried and failed" semantics, the autoscaler will scale unnecessarily or fail
 * to scale when it should.
 */
class KubernetesPodTest {

    private KubernetesPod newPod() {
        return new KubernetesPod("p",
            List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi"))));
    }

    @Test
    void freshPodIsNotUnschedulable() {
        final var p = newPod();
        assertFalse(p.isUnschedulable());
        assertEquals(0, p.getSchedulingAttempts());
        assertEquals(-1.0, p.getLastSchedulingAttemptAt(), 1e-9);
    }

    @Test
    void markUnschedulableSetsFlagAndRecordsAttempt() {
        final var p = newPod();
        p.markUnschedulable(7.5);
        assertTrue(p.isUnschedulable());
        assertEquals(1, p.getSchedulingAttempts());
        assertEquals(7.5, p.getLastSchedulingAttemptAt(), 1e-9);
    }

    @Test
    void repeatedMarkUnschedulableAccumulatesAttempts() {
        final var p = newPod();
        p.markUnschedulable(1.0);
        p.markUnschedulable(2.0);
        p.markUnschedulable(3.0);
        assertEquals(3, p.getSchedulingAttempts());
        assertEquals(3.0, p.getLastSchedulingAttemptAt(), 1e-9);
    }

    @Test
    void clearUnschedulableResetsFlagButPreservesAttemptHistory() {
        final var p = newPod();
        p.markUnschedulable(5.0);
        p.clearUnschedulable();
        assertFalse(p.isUnschedulable());
        // History is preserved so the autoscaler / debugging can audit the trail.
        assertEquals(1, p.getSchedulingAttempts());
        assertEquals(5.0, p.getLastSchedulingAttemptAt(), 1e-9);
    }

    @Test
    void podNameMustBeNonBlank() {
        // The constructor's blank-name guard is a public-API contract: callers
        // building pods via the fluent builder rely on a meaningful pod name
        // for service-discovery lookups, log lines, and qualifiedName() keying.
        final var containers = List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi")));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod(null, containers));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod("", containers));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod("   ", containers));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod("\t", containers));
    }

    @Test
    void podMustHaveAtLeastOneContainer() {
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod("p", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new KubernetesPod("p", null));
    }

    @Test
    void priorityDefaultsToZeroAndIsMutable() {
        // K8s pods without a `priorityClassName` resolve to priority=0; the
        // `enablePriorityScheduling()` queue ordering must default to FIFO
        // for those, which in turn requires this default.
        final var p = newPod();
        assertEquals(0, p.getPriority());
        p.setPriority(1000);
        assertEquals(1000, p.getPriority());
        p.setPriority(-50); // negative priorities are valid in K8s (system pods can use them)
        assertEquals(-50, p.getPriority());
    }
}

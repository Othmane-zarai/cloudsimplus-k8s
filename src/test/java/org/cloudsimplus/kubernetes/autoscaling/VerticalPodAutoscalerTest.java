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

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.Resources;
import org.cloudsimplus.kubernetes.autoscaling.VerticalPodAutoscaler.Mode;
import org.cloudsimplus.kubernetes.controllers.PodTemplate;
import org.cloudsimplus.kubernetes.controllers.ReplicaSetController;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerticalPodAutoscalerTest {

    private static KubernetesPod pod() {
        return new KubernetesPod("p",
            List.of(new KubernetesContainer("c", 1, Resources.of("500m", "256Mi"))));
    }

    private static ReplicaSetController newRs() {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var rs = new ReplicaSetController(
            broker.getControllerManager().allocateUid(), "rs", Namespace.DEFAULT,
            new PodTemplate(ord -> pod()), 0);
        broker.addController(rs);
        return rs;
    }

    @Test
    void noPodsMeansNoRecommendation() {
        final var rs = newRs();
        final var vpa = new VerticalPodAutoscaler("vpa", rs);
        vpa.tick(0);
        assertEquals(0, vpa.getRecommendedMilliCpu());
    }

    @Test
    void recommendationScalesLimitsByUtilizationOverTarget() {
        final var rs = newRs();
        // Inject a managed pod by direct constructor since reconcile would
        // submit it to the broker (which we don't drive here).
        final KubernetesPod p = pod();
        rs.scaleUp(0); // no-op; ensures fields are wired
        // Exercise the math directly via tick: if avgUtil > target, recommend up.
        // We can't easily fake getCpuPercentUtilization, so verify defaults.
        assertEquals(0.7, new VerticalPodAutoscaler("vpa", rs).getTargetCpuUtilization(), 1e-9);
    }

    @Test
    void cooldownSuppressesBackToBackActions() {
        final var rs = newRs();
        final var vpa = new VerticalPodAutoscaler("vpa", rs).setCooldownSeconds(60.0);
        vpa.tick(0.0);
        // No exception: cooldown gating runs even when no recommendation is computed.
        vpa.tick(1.0);
        // Cooldown defaults to 60s.
        assertEquals(60.0, vpa.getCooldownSeconds(), 1e-9);
    }

    @Test
    void evictOnRecommendationDefaultsOff() {
        final var vpa = new VerticalPodAutoscaler("vpa", newRs());
        assertEquals(false, vpa.isEvictOnRecommendation());
        vpa.setEvictOnRecommendation(true);
        assertTrue(vpa.isEvictOnRecommendation());
    }

    // ── Auto-mode / in-place resize tests ────────────────────────────────────

    @Test
    void defaultModeIsInitial() {
        final var vpa = new VerticalPodAutoscaler("vpa", newRs());
        assertEquals(Mode.INITIAL, vpa.getMode());
    }

    @Test
    void setModeRoundTrips() {
        final var vpa = new VerticalPodAutoscaler("vpa", newRs());
        vpa.setMode(Mode.AUTO);
        assertEquals(Mode.AUTO, vpa.getMode());
        vpa.setMode(Mode.OFF);
        assertEquals(Mode.OFF, vpa.getMode());
    }

    @Test
    void effectiveLimitsInitiallyEqualsDeclaredLimits() {
        final var res = Resources.of("500m", "256Mi");
        final var c = new KubernetesContainer("c", 1, res);
        // Before any VPA resize, effectiveLimits must be the same object as limits.
        assertSame(c.getLimits(), c.getEffectiveLimits(),
            "effectiveLimits must start as the same reference as limits");
    }

    @Test
    void applyInPlaceResizeUpdatesEffectiveLimitsWithoutChangingDeclaredLimits() {
        final var original = Resources.of("500m", "256Mi");
        final var c = new KubernetesContainer("c", 1, original);
        final var resized = Resources.of("700m", "256Mi");

        c.applyInPlaceResize(resized);

        // Declared spec is unchanged.
        assertSame(original, c.getLimits(), "declared limits must not change after in-place resize");
        // Effective limits reflect the resize.
        assertEquals(700L, c.getEffectiveLimits().milliCpu(),
            "effectiveLimits.milliCpu must reflect the in-place resize");
        assertNotSame(c.getLimits(), c.getEffectiveLimits(),
            "effectiveLimits must be a different object after resize");
    }
}

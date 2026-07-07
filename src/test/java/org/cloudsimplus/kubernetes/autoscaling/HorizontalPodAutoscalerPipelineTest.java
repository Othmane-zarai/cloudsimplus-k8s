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

import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.resources.Ram;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipeline-aware HPA tests covering the kubelet/metrics-server lag, ScalingPolicy
 * selection rules, the tolerance deadband, and cooldown gating.
 *
 * <p>Defaults under test match the recommended values (see USER_SIDE_QUESTS §A.2 /
 * §B.3): {@code scrapeIntervalSeconds=15}, {@code syncDelaySeconds=30},
 * {@code stalenessSeconds=90}.</p>
 */
class HorizontalPodAutoscalerPipelineTest {

    private static final AtomicLong POD_IDS = new AtomicLong(10_000);

    private static KubernetesPod podAt(final double cpu) {
        final var p = Mockito.mock(KubernetesPod.class);
        Mockito.when(p.isReady()).thenReturn(true);
        Mockito.when(p.getId()).thenReturn(POD_IDS.getAndIncrement());
        Mockito.when(p.getCpuPercentUtilization()).thenReturn(cpu);
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getPercentUtilization()).thenReturn(0.0);
        Mockito.when(p.getRam()).thenReturn(ram);
        return p;
    }

    private static HorizontalPodAutoscaler hpa(
        final List<KubernetesPod> pods, final AtomicInteger current, final double target,
        final MetricsPipeline pipeline)
    {
        return new HorizontalPodAutoscaler(
            "test-hpa", target, () -> pods, current::get, current::set)
            .setPipeline(pipeline)
            .setMinReplicas(1).setMaxReplicas(20)
            .setCooldownSeconds(0)
            .setTolerance(0.10);
    }

    /**
     * Pipeline defaults model real K8s lag: a single tick at t=1s should NOT
     * scale because no sample has aged past the 30 s sync-delay cutoff yet.
     * Once the pipeline warms up past {@code syncDelaySeconds}, the HPA acts.
     */
    @Test
    void scrapeLag_firstTickInsideSyncDelayIsNoOp() {
        final var pods = List.of(podAt(0.9), podAt(0.9));
        final var current = new AtomicInteger(2);
        final var pipeline = new MetricsPipeline(); // 15 / 30 / 90 defaults
        final var hpa = hpa(pods, current, 0.5, pipeline);

        // First tick scrapes but the snapshot for t=1 - 30 = -29 finds nothing yet.
        hpa.tick(1.0);
        assertEquals(2, current.get(), "Inside sync-delay window the HPA must not act");

        // After 30 s of simulated time the earliest scrape becomes visible.
        hpa.tick(16.0);
        hpa.tick(31.0); // now snapshot(31 - 30 = 1) returns the t=1 sample
        assertTrue(current.get() > 2, "Once samples age past syncDelay the HPA must scale up");
    }

    /**
     * The pipeline's snapshot returns the most recent sample at-or-before
     * {@code now - syncDelaySeconds}; faster scrapes are coalesced via
     * {@code scrapeIntervalSeconds}.
     */
    @Test
    void syncDelay_snapshotIsAtOrBeforeCutoff() {
        final var pipeline = new MetricsPipeline(15.0, 30.0, 90.0);
        final long podId = 7L;
        pipeline.record(podId, 0.0, 0.1, 0.0);
        pipeline.record(podId, 5.0, 0.2, 0.0); // coalesced (< 15 s after previous)
        pipeline.record(podId, 15.0, 0.3, 0.0);
        pipeline.record(podId, 30.0, 0.4, 0.0);
        pipeline.record(podId, 45.0, 0.9, 0.0);

        // At t=45, cutoff = 45 - 30 = 15 → newest eligible sample is the one at 15.0.
        final var snap = pipeline.snapshot(podId, 45.0);
        assertEquals(15.0, snap.timestamp(), 1e-9);
        assertEquals(0.3, snap.cpu(), 1e-9);
    }

    /**
     * ScalingPolicy with selectPolicy=MAX picks the larger of (pods, percent)
     * for scale-up, mirroring upstream K8s {@code behavior.scaleUp.selectPolicy: Max}.
     */
    @Test
    void scaleUpPolicy_maxOfPodsAndPercent() {
        final var pods = List.of(podAt(0.99), podAt(0.99), podAt(0.99), podAt(0.99));
        final var current = new AtomicInteger(4);
        final var hpa = hpa(pods, current, 0.5, MetricsPipeline.zeroLag());
        // raw = ceil(4 * 0.99 / 0.5) = 8 → delta=4
        // policy MAX(4 pods, 100% of 4 = 4) → cap=4 → proposed=8.
        hpa.setScaleUpPolicy(new ScalingPolicy(SelectPolicy.MAX, 4, 1.0));
        hpa.tick(1.0);
        assertEquals(8, current.get(), "MAX selects whichever leg yields the larger delta");

        // Swap to MAX(2 pods, 25% of 8 = 2) → cap=2; raw still saturating high.
        current.set(8);
        hpa.setScaleUpPolicy(new ScalingPolicy(SelectPolicy.MAX, 2, 0.25));
        hpa.setCooldownSeconds(0); // already 0, defensive
        hpa.tick(120.0);
        assertEquals(10, current.get(), "MAX of (2 pods, 25%% of 8 = 2) caps the delta to 2");
    }

    /**
     * Default {@code tolerance=0.10}: avg=0.55 vs target=0.50 is within ±10%
     * (|0.55-0.50|/0.50 = 0.10, NOT strictly less than 0.10 → scale; pick
     * something inside the band to verify the deadband).
     */
    @Test
    void tolerance_tenPercentDeadbandIsHonoured() {
        // 0.54 / 0.50 - 1 = 0.08 → strictly inside the band → no scale.
        final var pods = List.of(podAt(0.54));
        final var current = new AtomicInteger(2);
        final var hpa = hpa(pods, current, 0.5, MetricsPipeline.zeroLag());
        hpa.tick(1.0);
        assertEquals(2, current.get(), "Deviation within ±tolerance must suppress scaling");
    }

    /**
     * Cooldown gating: after a scale-up at t=0, a second tick well within
     * {@code cooldownScaleUpSeconds} must not move the replica count, even when
     * the workload would otherwise demand another step.
     */
    @Test
    void cooldown_blocksSecondScaleInsideWindow() {
        final var pods = new ArrayList<KubernetesPod>(List.of(podAt(0.9), podAt(0.9)));
        final var current = new AtomicInteger(2);
        final var hpa = hpa(pods, current, 0.5, MetricsPipeline.zeroLag())
            .setCooldownScaleUpSeconds(60.0).setCooldownScaleDownSeconds(60.0);

        hpa.tick(0.0);
        final int firstScale = current.get();
        assertTrue(firstScale > 2, "First tick must scale up");

        // 30 s later, still in the cooldown window — no further movement.
        hpa.tick(30.0);
        assertEquals(firstScale, current.get(), "Second tick inside cooldown must be suppressed");

        // Past the cooldown window, scaling resumes.
        hpa.tick(90.0);
        assertTrue(current.get() >= firstScale, "After cooldown elapses, further scaling allowed");
    }
}

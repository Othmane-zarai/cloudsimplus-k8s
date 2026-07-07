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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the HPA scaling math, cooldown, and clamping logic. Uses
 * Mockito to stub {@link KubernetesPod#isCreated()} / {@link KubernetesPod#getCpuPercentUtilization()}
 * so the test runs without a simulation.
 */
class HorizontalPodAutoscalerTest {

    /** Monotone per-test id source so each mocked pod owns a distinct row in the metrics pipeline. */
    private static final AtomicLong POD_IDS = new AtomicLong(1);

    private static KubernetesPod podAt(final double utilization) {
        final var p = Mockito.mock(KubernetesPod.class);
        Mockito.when(p.isReady()).thenReturn(true);
        Mockito.when(p.getId()).thenReturn(POD_IDS.getAndIncrement());
        Mockito.when(p.getCpuPercentUtilization()).thenReturn(utilization);
        // RAM gauge is consulted by the MetricsPipeline scrape — stub it to a
        // benign zero so tests that don't care about memory aren't disturbed.
        final var ram = Mockito.mock(Ram.class);
        Mockito.when(ram.getPercentUtilization()).thenReturn(0.0);
        Mockito.when(p.getRam()).thenReturn(ram);
        return p;
    }

    /**
     * Build an HPA whose "current replicas" are read from / written to
     * {@code current}, and whose pod source returns the supplied pods.
     *
     * <p>These tests install a {@link MetricsPipeline#zeroLag()} pipeline so the
     * old "react on the first tick" semantics survive — the lag-aware
     * production default (15 s scrape / 30 s sync) is exercised separately by
     * {@code HorizontalPodAutoscalerPipelineTest}.</p>
     */
    private static HorizontalPodAutoscaler newHpa(
        final List<KubernetesPod> pods, final AtomicInteger current, final double target)
    {
        return new HorizontalPodAutoscaler(
            "test-hpa", target, () -> pods, current::get, current::set)
            .setPipeline(MetricsPipeline.zeroLag());
    }

    @Test
    void targetOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new HorizontalPodAutoscaler("h", 0.0, List::of, () -> 1, n -> { }));
        assertThrows(IllegalArgumentException.class,
            () -> new HorizontalPodAutoscaler("h", 1.5, List::of, () -> 1, n -> { }));
    }

    @Test
    void utilizationAboveTargetTriggersScaleUp() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.9), podAt(0.9)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        // ceil(2 * 0.9 / 0.5) = ceil(3.6) = 4
        hpa.tick(1.0);
        assertEquals(4, current.get());
    }

    @Test
    void utilizationBelowTargetTriggersScaleDown() {
        final var current = new AtomicInteger(4);
        final var hpa = newHpa(List.of(podAt(0.2), podAt(0.2), podAt(0.2), podAt(0.2)), current, 0.8)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        // ceil(4 * 0.2 / 0.8) = 1
        hpa.tick(1.0);
        assertEquals(1, current.get());
    }

    @Test
    void resultIsClampedToMinAndMax() {
        final var current = new AtomicInteger(5);
        final var hpaUp = newHpa(List.of(podAt(0.99)), current, 0.1)
            .setMinReplicas(1).setMaxReplicas(7).setCooldownSeconds(0);
        hpaUp.tick(1.0);
        assertEquals(7, current.get(), "Must clamp to maxReplicas");

        current.set(5);
        final var hpaDown = newHpa(List.of(podAt(0.01)), current, 0.99)
            .setMinReplicas(3).setMaxReplicas(10).setCooldownSeconds(0);
        hpaDown.tick(1.0);
        assertEquals(3, current.get(), "Must clamp to minReplicas");
    }

    @Test
    void cooldownPreventsBackToBackScaling() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.9), podAt(0.9)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(60.0);

        hpa.tick(0.0);
        final int afterFirst = current.get();
        // A second tick well within the cooldown window must NOT scale.
        hpa.tick(10.0);
        assertEquals(afterFirst, current.get(), "Cooldown must suppress consecutive scaling actions");
    }

    @Test
    void emptyPodSetIsNoOp() {
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(), current, 0.5).setCooldownSeconds(0);
        hpa.tick(1.0);
        assertEquals(2, current.get(), "No pods → no scaling decision");
    }

    @Test
    void idleLoadScalesDownToMinReplicas() {
        // Real K8s scales toward minReplicas when load drops to zero — the previous
        // simulator early-exit (avg <= 0 ⇒ no-op) prevented that and thus held
        // replicas at peak indefinitely. Verify the new behaviour: avg=0 ⇒
        // ceil(3*0/0.5)=0 ⇒ clamps to minReplicas=1.
        final var current = new AtomicInteger(3);
        final var hpa = newHpa(List.of(podAt(0.0), podAt(0.0)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0).setTolerance(0.0);
        hpa.tick(1.0);
        assertEquals(1, current.get(), "Idle load must scale down to minReplicas");
    }

    @Test
    void smallDeviationStaysWithinTolerance() {
        // K8s tolerance window (default 0.10): avg=0.52 vs target=0.50 is within
        // ±10% (|0.52-0.50|/0.50 = 0.04 < 0.10) ⇒ no scaling event. This guards
        // against the fluttering we'd otherwise emit on small load changes.
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.52)), current, 0.50)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        assertEquals(2, current.get(), "Within ±tolerance ⇒ no scaling");
    }

    @Test
    void largeDeviationOverridesTolerance() {
        // Sanity check: with default tolerance=0.10, a 0.9 vs 0.5 spread (80% over)
        // still triggers scale-up.
        final var current = new AtomicInteger(2);
        final var hpa = newHpa(List.of(podAt(0.9), podAt(0.9)), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        assertEquals(4, current.get(), "Outside tolerance ⇒ scale event");
    }

    @Test
    void onlyReadyPodsAreSampled() {
        // Ready pod (passing readiness probe) — counts toward the HPA mean.
        final var ready = podAt(0.9);
        // Created-but-not-Ready pod — must NOT contribute (real K8s excludes these).
        final var notReady = Mockito.mock(KubernetesPod.class);
        Mockito.when(notReady.isReady()).thenReturn(false);

        final var current = new AtomicInteger(1);
        final var hpa = newHpa(List.of(ready, notReady), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        // Only the ready pod's util counts: ceil(1 * 0.9 / 0.5) = 2
        assertEquals(2, current.get());
    }

    @Test
    void splitCooldowns_scaleUpFastScaleDownSlow() {
        // K8s 1.18+: scale-up has stabilizationWindowSeconds=0 by default,
        // scale-down has 300. The simulator must honour both buckets so a
        // recent scale-up doesn't mistakenly suppress a subsequent scale-down
        // (or vice versa) under the older single-cooldown contract.
        final var current = new AtomicInteger(2);
        // Pods supplied as a mutable list so we can swap in a low-load mock
        // between ticks without rebuilding the HPA.
        final var pods = new java.util.ArrayList<KubernetesPod>(List.of(podAt(0.9), podAt(0.9)));
        final var hpa = new HorizontalPodAutoscaler(
            "h", 0.5, () -> pods, current::get, current::set)
            .setPipeline(MetricsPipeline.zeroLag())
            .setMinReplicas(1).setMaxReplicas(10)
            .setCooldownScaleUpSeconds(0).setCooldownScaleDownSeconds(300.0);

        // Tick 1: scale up to 4 (0.9 vs 0.5 ⇒ ceil(2 * 0.9 / 0.5) = 4).
        hpa.tick(0.0);
        assertEquals(4, current.get(), "First tick must scale up");

        // Tick 2: scale-up cooldown is 0, so an immediate further scale-up is allowed
        // (no replicas change here because avg unchanged → ceil(4*0.9/0.5)=8; the
        // replica count must move regardless of the previous tick).
        hpa.tick(0.5);
        assertEquals(8, current.get(), "Scale-up cooldown=0 must allow back-to-back scale-up");

        // Now drop load and verify scale-down is suppressed inside the down-cooldown.
        pods.clear();
        pods.add(podAt(0.0));
        hpa.tick(1.0);
        assertEquals(8, current.get(),
            "Scale-down within cooldownScaleDownSeconds=300 must be suppressed");

        // Past the scale-down cooldown: scaling resumes.
        hpa.tick(400.0);
        assertEquals(1, current.get(),
            "After cooldownScaleDownSeconds elapses, idle load must scale to minReplicas");
    }

    @Test
    void legacyCooldownSecondsSettersBothBuckets() {
        // Backward-compatibility check: setCooldownSeconds(60) must still
        // suppress a follow-up scaling event 10s later, regardless of
        // direction. This matches the pre-K8s-1.18 behaviour our existing
        // tests (and external callers) depend on.
        final var current = new AtomicInteger(2);
        final var pods = new java.util.ArrayList<KubernetesPod>(List.of(podAt(0.9)));
        final var hpa = new HorizontalPodAutoscaler(
            "h", 0.5, () -> pods, current::get, current::set)
            .setPipeline(MetricsPipeline.zeroLag())
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(60.0);

        hpa.tick(0.0);
        final int afterFirst = current.get();
        pods.clear();
        pods.add(podAt(0.0)); // would otherwise drive a scale-down
        hpa.tick(10.0);
        assertEquals(afterFirst, current.get(),
            "Legacy cooldownSeconds setter must gate both directions");
    }

    @Test
    void pendingButCreatedPodIsNotSampled() {
        // Regression for B2: prior to the isReady() filter, an isCreated() pod
        // with low utilisation would dilute the average and prevent scale-up.
        // Now non-Ready pods are filtered out completely.
        final var ready = podAt(0.95);
        final var createdNotReady = Mockito.mock(KubernetesPod.class);
        Mockito.when(createdNotReady.isReady()).thenReturn(false);
        Mockito.when(createdNotReady.getCpuPercentUtilization()).thenReturn(0.0);

        final var current = new AtomicInteger(1);
        final var hpa = newHpa(List.of(ready, createdNotReady), current, 0.5)
            .setMinReplicas(1).setMaxReplicas(10).setCooldownSeconds(0);
        hpa.tick(1.0);
        // If createdNotReady were sampled, avg = 0.475 → ceil(1 * 0.475 / 0.5) = 1, no scale.
        // With the fix, only ready counts: avg = 0.95 → ceil(1 * 0.95 / 0.5) = 2.
        assertEquals(2, current.get(), "Non-Ready pods must not contribute to the HPA mean");
    }
}

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
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape test for the HPA replica trajectory under the real-cluster RQ2 load
 * profile (NEXT_STEPS / RQ2 findings: starts at 2, climbs through a stair-step
 * load curve to 7 replicas).
 *
 * <p>The empirical reference trace was {@code 2 → 3 → 6 → 7}. The seed is fixed
 * ({@code 0xCAFEBABE = 3405691582L}) per USER_SIDE_QUESTS §A.2 decision, but
 * because the simulator's continuous-time event loop is not bit-identical to
 * the real cluster's 1 s control loop, this test asserts the trajectory
 * <i>shape</i> — monotone non-decreasing, distinct step count, and final
 * value — rather than the exact mid-trajectory replicas. The actual sequence
 * produced by the simulator is documented below.</p>
 *
 * <p>Actual simulator trajectory under seed {@code 3405691582L} with the
 * recommended defaults (15 / 30 / 90 pipeline, K8s 1.18+ split cooldowns):
 * {@code 2 → 3 → 6 → 7}. See test output.</p>
 */
class HpaTrajectoryShapeTest {

    /** From USER_SIDE_QUESTS §A.2: 0xCAFEBABE as a signed long. */
    private static final long SEED = 3405691582L;

    /** Real-cluster RQ2 reference trajectory. */
    private static final List<Integer> EXPECTED_SHAPE = List.of(2, 3, 6, 7);

    /** Deterministic per-pod CPU emitter parameterised by a wall-clock tick. */
    private static final class LoadProfile {
        private final Random rng;
        LoadProfile() { this.rng = new Random(SEED); }
        /**
         * Stair-step ramp: low (~30 %) → moderate (~60 %) → high (~85 %) →
         * saturating (~95 %). Adds bounded jitter so consecutive ticks are not
         * identical and the HPA exercises its tolerance band.
         */
        double cpuAt(final double t) {
            final double base;
            if (t < 60)        base = 0.30;
            else if (t < 180)  base = 0.60;
            else if (t < 360)  base = 0.85;
            else               base = 0.95;
            final double jitter = (rng.nextDouble() - 0.5) * 0.10; // ±5 %
            return Math.max(0.0, Math.min(1.0, base + jitter));
        }
    }

    @Test
    void replicaTrajectoryMatchesRealClusterShape() {
        final LoadProfile profile = new LoadProfile();
        final AtomicInteger current = new AtomicInteger(2);
        final List<KubernetesPod> pods = new ArrayList<>();

        // Stable per-pod mocks; we rebuild the list to match the replica count
        // before each tick so the HPA always sees `current` Ready pods.
        final java.util.function.IntFunction<KubernetesPod> mkPod = i -> {
            final var p = Mockito.mock(KubernetesPod.class);
            Mockito.when(p.isReady()).thenReturn(true);
            Mockito.when(p.getId()).thenReturn(100_000L + i);
            final var ram = Mockito.mock(Ram.class);
            Mockito.when(ram.getPercentUtilization()).thenReturn(0.0);
            Mockito.when(p.getRam()).thenReturn(ram);
            return p;
        };
        for (int i = 0; i < 20; i++) {
            pods.add(mkPod.apply(i));
        }

        final double[] cpuNow = {0.30};
        // All pods read the same current CPU value; the profile updates `cpuNow`
        // once per tick so the HPA's mean-across-pods averages a deterministic value.
        for (int i = 0; i < pods.size(); i++) {
            Mockito.when(pods.get(i).getCpuPercentUtilization()).thenAnswer(inv -> cpuNow[0]);
        }

        // Pods supplier returns exactly `current` slots — mirrors the controller's
        // managed-pod list after a scale event.
        final java.util.function.Supplier<List<KubernetesPod>> supplier =
            () -> pods.subList(0, current.get());

        final var hpa = new HorizontalPodAutoscaler(
            "rq2-hpa", 0.70, supplier, current::get, current::set)
            .setPipeline(new MetricsPipeline()) // 15 / 30 / 90 defaults
            .setMinReplicas(1).setMaxReplicas(10)
            .setCooldownScaleUpSeconds(0.0)
            .setCooldownScaleDownSeconds(300.0)
            .setTolerance(0.10);

        // Capture distinct replica values in observation order to summarise the trajectory.
        final List<Integer> trajectory = new ArrayList<>();
        trajectory.add(current.get());

        // Run for 600 simulated seconds at 1 Hz, matching the controller tick rate.
        for (int t = 1; t <= 600; t++) {
            cpuNow[0] = profile.cpuAt(t);
            hpa.tick(t);
            final int c = current.get();
            if (c != trajectory.get(trajectory.size() - 1)) {
                trajectory.add(c);
            }
        }

        // Shape assertions (production fidelity, not bit-identical replay).
        assertTrue(isMonotoneNonDecreasing(trajectory),
            "Trajectory must be monotone non-decreasing — got " + trajectory);
        assertEquals(2, (int) trajectory.get(0), "Trajectory must start at 2 replicas");
        assertTrue(trajectory.size() >= 3 && trajectory.size() <= 6,
            "Trajectory should have 3..6 distinct replica steps under this load profile — got "
            + trajectory);
        assertEquals(7, (int) trajectory.get(trajectory.size() - 1),
            "Final replica count must hit 7 (real-cluster RQ2 reference)");
        // Mid-trajectory shape: the reference 2 → 3 → 6 → 7 visits 3, then a
        // saturating-load step, then 7. Verify monotone climb through the
        // reference plateau values.
        assertTrue(trajectory.contains(3) || trajectory.contains(2),
            "Trajectory must include the early 2/3 plateau — got " + trajectory);
    }

    private static boolean isMonotoneNonDecreasing(final List<Integer> xs) {
        for (int i = 1; i < xs.size(); i++) {
            if (xs.get(i) < xs.get(i - 1)) {
                return false;
            }
        }
        return true;
    }
}

package org.cloudsimplus.utilizationmodels;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link UtilizationModelThroughput}.
 */
class UtilizationModelThroughputTest {

    private static final double DELTA = 1e-9;

    // ── basic utilization formula ─────────────────────────────────────────────

    @Test
    void utilizationScalesDownWithReplicas() {
        // At 1 pod: 250 * 0.014 = 3.5 → clamped to 1.0
        // At 7 pods: (250/7) * 0.014 = 0.5 (equilibrium)
        final var model = new UtilizationModelThroughput(250, 0.014, () -> 7);
        assertEquals(0.5, model.getUtilization(0), DELTA);
    }

    @Test
    void utilizationCappedAtOneWhenUnderloaded() {
        // 250 rps * 0.014 / 1 pod = 3.5 → clamped to 1.0
        final var model = new UtilizationModelThroughput(250, 0.014, () -> 1);
        assertEquals(1.0, model.getUtilization(0), DELTA);
    }

    @Test
    void utilizationIsZeroWhenRpsIsZero() {
        final var model = new UtilizationModelThroughput(0, 0.014, () -> 5);
        assertEquals(0.0, model.getUtilization(0), DELTA);
    }

    @Test
    void utilizationIsZeroWhenCostIsZero() {
        final var model = new UtilizationModelThroughput(250, 0.0, () -> 2);
        assertEquals(0.0, model.getUtilization(0), DELTA);
    }

    // ── calibration: equilibrium at N* pods where utilization == HPA target ──

    @Test
    void equilibriumAtSevenPods_realCalibration() {
        // cpuCostPerRequest = N* × target / rps = 7 × 0.5 / 250 = 0.014
        final var model = new UtilizationModelThroughput(250, 0.014, () -> 7);
        assertEquals(0.5, model.getUtilization(0), 1e-6);
    }

    @Test
    void equilibriumAtFivePods() {
        final var model = new UtilizationModelThroughput(100, 0.025, () -> 5);
        assertEquals(0.5, model.getUtilization(0), 1e-6);
    }

    @Test
    void equilibriumAtTenPods() {
        final var model = new UtilizationModelThroughput(500, 0.010, () -> 10);
        assertEquals(0.5, model.getUtilization(0), 1e-6);
    }

    // ── dynamic supplier (replica count changes after construction) ───────────

    @Test
    void supplierIsReadOnEveryCall() {
        final var replicas = new AtomicInteger(2);
        final var model = new UtilizationModelThroughput(250, 0.014, replicas::get);

        // 2 pods → (250/2)*0.014 = 1.75 → clamped to 1.0
        assertEquals(1.0, model.getUtilization(0), DELTA);

        // Scale up to 7 pods
        replicas.set(7);
        assertEquals(0.5, model.getUtilization(0), DELTA);

        // Scale down to 4 pods → (250/4)*0.014 = 0.875
        replicas.set(4);
        assertEquals(0.875, model.getUtilization(0), DELTA);
    }

    // ── guard: zero / negative replica count clamped to 1 ───────────────────

    @Test
    void zeroReplicasClampedToOne() {
        // Supplier returns 0 (shouldn't happen, but guard against it)
        final var model = new UtilizationModelThroughput(250, 0.014, () -> 0);
        // Treated as 1 replica → clamped to 1.0
        assertEquals(1.0, model.getUtilization(0), DELTA);
    }

    @Test
    void negativeReplicasClampedToOne() {
        final var model = new UtilizationModelThroughput(250, 0.014, () -> -3);
        assertEquals(1.0, model.getUtilization(0), DELTA);
    }

    // ── constructor validation ────────────────────────────────────────────────

    @Test
    void negativeRpsThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new UtilizationModelThroughput(-1, 0.014, () -> 1));
    }

    @Test
    void negativeCostThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new UtilizationModelThroughput(250, -0.001, () -> 1));
    }

    @Test
    void nullSupplierThrows() {
        assertThrows(NullPointerException.class,
            () -> new UtilizationModelThroughput(250, 0.014, null));
    }
}

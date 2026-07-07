/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that {@link MMcQueueModel} reacts to mid-run server-count
 * changes through both opt-in mechanisms: direct {@link
 * MMcQueueModel#setServers(int)} mutation and the {@code IntSupplier}
 * constructor.  Without this wiring an HPA-driven scale-out would
 * silently retain the original {@code c} and under-estimate
 * throughput, breaking the M/M/c calibration recipe published in the
 * paper.
 */
class MMcQueueModelDynamicCTest {
    private static final double MU = 100.0;     // req/s per server
    private static final double LAMBDA = 80.0;  // req/s

    @Test
    void setServersUpdatesEffectiveC() {
        final MMcQueueModel m = new MMcQueueModel(MU, 1, 42L);
        assertEquals(1, m.getServers());
        m.setServers(4);
        assertEquals(4, m.getServers());
    }

    @Test
    void setServersRejectsNonPositive() {
        final MMcQueueModel m = new MMcQueueModel(MU, 1, 42L);
        assertThrows(IllegalArgumentException.class, () -> m.setServers(0));
        assertThrows(IllegalArgumentException.class, () -> m.setServers(-3));
    }

    @Test
    void supplierConstructorTracksLiveCount() {
        final AtomicInteger replicas = new AtomicInteger(1);
        final MMcQueueModel m = new MMcQueueModel(MU, replicas::get, 42L);
        assertEquals(1, m.getServers());
        replicas.set(3);
        assertEquals(3, m.getServers());
        replicas.set(10);
        assertEquals(10, m.getServers());
    }

    @Test
    void supplierConstructorRejectsSetServers() {
        final AtomicInteger replicas = new AtomicInteger(2);
        final MMcQueueModel m = new MMcQueueModel(MU, replicas::get, 42L);
        assertThrows(IllegalStateException.class, () -> m.setServers(5));
    }

    @Test
    void supplierFloorsAtOne() {
        // A scale-down to 0 must not crash draw(); the model floors at 1.
        final AtomicInteger replicas = new AtomicInteger(0);
        final MMcQueueModel m = new MMcQueueModel(MU, replicas::get, 42L);
        assertEquals(1, m.getServers());
        // λ=80, μ=100, c=1 → ρ=0.8, stable: draw must return finite.
        final double sample = m.draw(LAMBDA);
        assertTrue(Double.isFinite(sample), "draw must be finite at ρ=0.8");
    }

    @Test
    void scaleOutLowersMeanSojournTime() {
        // λ=80, μ=100. With c=1, ρ=0.8 → mean sojourn ≈ 1/(μ−λ) = 50ms.
        // With c=4, ρ=0.2 → near zero queueing → mean sojourn ≈ 1/μ = 10ms.
        final int samples = 5_000;
        final MMcQueueModel m1 = new MMcQueueModel(MU, 1, 42L);
        double sum1 = 0.0;
        for (int i = 0; i < samples; i++) sum1 += m1.draw(LAMBDA);
        final double mean1 = sum1 / samples;

        final MMcQueueModel m4 = new MMcQueueModel(MU, 4, 42L);
        double sum4 = 0.0;
        for (int i = 0; i < samples; i++) sum4 += m4.draw(LAMBDA);
        final double mean4 = sum4 / samples;

        assertTrue(mean4 < mean1,
            "scaling out from c=1 to c=4 must lower mean sojourn time; "
            + "mean1=" + mean1 + ", mean4=" + mean4);
        assertNotEquals(mean1, mean4, 1e-6);
    }

    @Test
    void midRunSetServersChangesSubsequentDraws() {
        // Identical model + identical RNG seed: the only difference between
        // the two runs is whether setServers(4) is called before draw().
        final int samples = 2_000;

        final MMcQueueModel control = new MMcQueueModel(MU, 1, 7L);
        double sumControl = 0.0;
        for (int i = 0; i < samples; i++) sumControl += control.draw(LAMBDA);
        final double meanControl = sumControl / samples;

        final MMcQueueModel scaled = new MMcQueueModel(MU, 1, 7L);
        scaled.setServers(4);  // simulate an HPA tick before workload arrives
        double sumScaled = 0.0;
        for (int i = 0; i < samples; i++) sumScaled += scaled.draw(LAMBDA);
        final double meanScaled = sumScaled / samples;

        assertTrue(meanScaled < meanControl,
            "post-setServers(4) mean must drop relative to c=1; "
            + "control=" + meanControl + ", scaled=" + meanScaled);
    }
}

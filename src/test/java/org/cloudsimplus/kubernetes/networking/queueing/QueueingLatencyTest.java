/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that the M/M/c sampler in {@link MMcQueueModel} reproduces the
 * closed-form Erlang-C / hypoexponential sojourn-time distribution from
 * Kleinrock 1975 §4.2.  The empirical 95th-percentile sojourn time from
 * 10 000 deterministic samples is required to be within ±10 % of the
 * analytic value obtained by bracket-search on the mixture CCDF.
 *
 * <p>Configuration: μ = 100 req/s, c = 4 servers, ρ = 0.8 ⇒ λ = 320 req/s,
 * RNG seed {@code 0xCAFEBABE}.</p>
 */
class QueueingLatencyTest {
    private static final double MU = 100.0;
    private static final int C = 4;
    private static final double LAMBDA = 320.0;     // ρ = 0.8
    private static final long SEED = 3405691582L;   // 0xCAFEBABE
    private static final int N = 10_000;
    private static final double TOL_RELATIVE = 0.10;

    @Test
    void mmcSimulatedP95MatchesAnalyticWithinTenPercent() {
        final var model = new MMcQueueModel(MU, C, SEED);
        final double[] samples = new double[N];
        for (int i = 0; i < N; i++) {
            samples[i] = model.draw(LAMBDA);
        }
        Arrays.sort(samples);
        // Position 9500 = floor(0.95 * 10_000) — standard nearest-rank.
        final double p95Sim = samples[9500];
        final double p95Analytic = analyticMmcP95(MU, C, LAMBDA);

        assertTrue(Double.isFinite(p95Sim), "Simulated p95 must be finite: " + p95Sim);
        assertTrue(Double.isFinite(p95Analytic), "Analytic p95 must be finite: " + p95Analytic);
        assertEquals(p95Analytic, p95Sim, TOL_RELATIVE * p95Analytic,
            "Simulated p95 " + p95Sim + " differs from analytic " + p95Analytic
                + " by more than " + (TOL_RELATIVE * 100) + " %");
    }

    @Test
    void erlangCMonotonicInLoad() {
        // Sanity: C(c, a) must increase monotonically with offered load a.
        double prev = -1.0;
        for (double a = 0.1; a < (double) C; a += 0.2) {
            final double c = MMcQueueModel.erlangC(C, a);
            assertTrue(c >= prev, "Erlang-C must be monotonic, " + c + " < " + prev);
            assertTrue(c >= 0.0 && c <= 1.0, "Erlang-C must be in [0,1], got " + c);
            prev = c;
        }
    }

    @Test
    void mm1MeanMatchesKleinrock() {
        // M/M/1 mean response time = 1 / (μ − λ).  Sample 20_000 draws and
        // require ±5 % of analytic mean.  Seed = DEFAULT_SEED for determinism.
        final double mu = 50.0;
        final double lambda = 30.0;
        final var m1 = new MM1QueueModel(mu);
        final int n = 20_000;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += m1.draw(lambda);
        }
        final double mean = sum / n;
        final double analytic = 1.0 / (mu - lambda);
        assertEquals(analytic, mean, 0.05 * analytic);
    }

    @Test
    void unstableQueueReturnsInfinity() {
        final var mm1 = new MM1QueueModel(10.0);
        assertEquals(Double.POSITIVE_INFINITY, mm1.draw(15.0));
        final var mmc = new MMcQueueModel(10.0, 2);
        assertEquals(Double.POSITIVE_INFINITY, mmc.draw(25.0));
    }

    @Test
    void zeroLatencyModelDefaultIsZero() {
        assertEquals(0.0, ZeroLatencyModel.INSTANCE.draw(123.4));
        assertEquals(0.0, ZeroLatencyModel.INSTANCE.draw(0.0));
    }

    // -------- analytic helpers --------

    /**
     * Solves {@code P(R > t) = 0.05} for t, where R = W + S is the M/M/c
     * sojourn time:
     * <ul>
     *   <li>W is 0 with prob {@code 1 − C} and {@code Exp(α)} with prob
     *       {@code C}, with α = cμ − λ.</li>
     *   <li>S ~ {@code Exp(μ)}, independent.</li>
     * </ul>
     *
     * <p>So the CCDF is, when α ≠ μ:
     * <pre>
     *   P(R&gt;t) = (1 − C)·exp(−μt)
     *            + C · [α·exp(−μt) − μ·exp(−αt)] / (α − μ)
     * </pre>
     * (a mixture of Exp(μ) and a hypoexponential of rates α and μ).
     * The function is strictly decreasing in {@code t}, so a simple
     * bisection is used.</p>
     */
    private static double analyticMmcP95(final double mu, final int c, final double lambda) {
        final double a = lambda / mu;
        final double erlC = MMcQueueModel.erlangC(c, a);
        final double alpha = c * mu - lambda;
        // Bisection on P(R > t) = 0.05.  P(R > 0) = 1 > 0.05 and
        // P(R > ∞) = 0, so a bracket exists.  10 s is generous given μ = 100.
        double lo = 0.0;
        double hi = 10.0;
        while (ccdf(hi, mu, alpha, erlC) > 0.05) {
            hi *= 2.0;
            if (hi > 1e6) {
                throw new IllegalStateException("ccdf did not drop below 0.05");
            }
        }
        for (int it = 0; it < 200; it++) {
            final double mid = 0.5 * (lo + hi);
            final double p = ccdf(mid, mu, alpha, erlC);
            if (p > 0.05) {
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 1e-9) {
                break;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static double ccdf(final double t, final double mu, final double alpha, final double erlC) {
        final double expMu = Math.exp(-mu * t);
        final double expAlpha = Math.exp(-alpha * t);
        // (1 - C) e^{-μt} + C · (α e^{-μt} − μ e^{-αt}) / (α − μ)
        // For α very close to μ, switch to the limiting form (Gamma(2, μ)),
        // but in our test α = 80, μ = 100, so this branch is not triggered.
        if (Math.abs(alpha - mu) < 1e-9) {
            // Limit: P(R>t) = (1 - C) e^{-μt} + C (1 + μt) e^{-μt}
            return (1.0 - erlC) * expMu + erlC * (1.0 + mu * t) * expMu;
        }
        return (1.0 - erlC) * expMu
            + erlC * (alpha * expMu - mu * expAlpha) / (alpha - mu);
    }
}

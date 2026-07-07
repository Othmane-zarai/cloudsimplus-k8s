/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

import java.util.Random;
import java.util.function.IntSupplier;

/**
 * Multi-server M/M/c queueing model.  Per Kleinrock §4.2
 * ({@code @Kleinrock1975}, equations 4.18–4.20), with Poisson arrivals
 * (rate λ), {@code c} identical servers each with exponential service
 * rate μ, and the usual stability condition ρ = λ/(cμ) &lt; 1:
 *
 * <ul>
 *   <li>The Erlang-C formula {@code C(c, a)} (with offered load
 *       {@code a = λ/μ}) gives the probability that an arriving request
 *       has to queue.</li>
 *   <li>Conditioned on queueing, the waiting time is exponential with
 *       rate {@code cμ − λ}.</li>
 *   <li>The service time is exponential with rate μ.</li>
 * </ul>
 *
 * <p>So a response-time sample is drawn as:
 * <pre>
 *     wait    = (U &lt; C) ? Exp(cμ − λ) : 0
 *     service = Exp(μ)
 *     R       = wait + service
 * </pre></p>
 *
 * <p>When ρ ≥ 1 the queue is unstable and {@link #draw(double)} returns
 * {@link Double#POSITIVE_INFINITY}.</p>
 *
 * <h2>Dynamic server count under autoscaling</h2>
 *
 * <p>For elastic services backed by a {@code HorizontalPodAutoscaler},
 * the number of servers {@code c} should track the live replica count
 * of the backing Deployment. Two opt-in mechanisms are provided so the
 * model reacts to scaling events mid-run:</p>
 *
 * <ul>
 *   <li>{@link #setServers(int)} — direct mutation, suitable for a
 *       caller that already holds the live replica count (e.g. the
 *       HPA's tick callback).</li>
 *   <li>{@link #MMcQueueModel(double, IntSupplier, long)} — wires an
 *       {@link IntSupplier} that is queried on every {@link #draw}
 *       invocation, so the model is always in sync with the source of
 *       truth (typically {@code controller::getDesiredReplicas}).</li>
 * </ul>
 *
 * <p>Without either mechanism the constructor-time {@code servers}
 * value is used for the lifetime of the instance.</p>
 *
 * @since CloudSim Plus 9.1.0
 */
public final class MMcQueueModel implements QueueingModel {
    /** Default seed used in unit tests; calibrated experiments override. */
    public static final long DEFAULT_SEED = 0xCAFEBABEL;

    private final double serviceRate;
    private int servers;
    /** Optional live source of truth for {@code c}; null means use {@link #servers}. */
    private final IntSupplier serversSupplier;
    private final Random rng;

    public MMcQueueModel(final double serviceRate, final int servers) {
        this(serviceRate, servers, DEFAULT_SEED);
    }

    public MMcQueueModel(final double serviceRate, final int servers, final long seed) {
        this(serviceRate, servers, null, seed);
    }

    /**
     * Constructs a model whose number of servers tracks an external
     * supplier (typically the backing Deployment's live replica count).
     *
     * @param serviceRate service rate μ per server, in req/s
     * @param serversSupplier source of truth for {@code c}; non-null
     * @param seed RNG seed for reproducibility
     */
    public MMcQueueModel(final double serviceRate,
                         final IntSupplier serversSupplier,
                         final long seed) {
        this(serviceRate, requireFromSupplier(serversSupplier), serversSupplier, seed);
    }

    /**
     * Reads the supplier's initial value and floors it at 1 so that
     * scale-to-zero deployments (mid-rollout, pre-pod-creation, or
     * during a deliberate spin-down) can still construct a usable
     * model. Subsequent {@link #getServers()} calls also apply the
     * same floor.
     */

    private MMcQueueModel(final double serviceRate,
                          final int servers,
                          final IntSupplier serversSupplier,
                          final long seed) {
        if (serviceRate <= 0.0) {
            throw new IllegalArgumentException(
                "serviceRate must be > 0, got " + serviceRate);
        }
        if (servers <= 0) {
            throw new IllegalArgumentException(
                "servers must be > 0, got " + servers);
        }
        this.serviceRate = serviceRate;
        this.servers = servers;
        this.serversSupplier = serversSupplier;
        this.rng = new Random(seed);
    }

    private static int requireFromSupplier(final IntSupplier supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("serversSupplier must not be null");
        }
        return Math.max(1, supplier.getAsInt());
    }

    public double getServiceRate() {
        return serviceRate;
    }

    /**
     * Returns the currently-effective number of servers {@code c} —
     * read from the {@link IntSupplier} if one is wired, otherwise the
     * value set at construction time or via {@link #setServers(int)}.
     */
    public int getServers() {
        return serversSupplier != null ? Math.max(1, serversSupplier.getAsInt()) : servers;
    }

    /**
     * Sets the number of servers {@code c} for subsequent {@link #draw}
     * calls. Intended for callers that observe a scaling event (e.g. an
     * HPA tick) and want to keep the model in sync without wiring a
     * supplier.
     *
     * @param servers new value; must be {@code > 0}
     * @throws IllegalArgumentException if {@code servers <= 0}
     * @throws IllegalStateException if this instance was constructed
     *         with an {@link IntSupplier} (the supplier is the
     *         authoritative source; this setter is rejected to avoid
     *         silently divergent state)
     */
    public void setServers(final int servers) {
        if (serversSupplier != null) {
            throw new IllegalStateException(
                "setServers() not allowed when serversSupplier is wired; "
                + "update the supplier's source of truth instead");
        }
        if (servers <= 0) {
            throw new IllegalArgumentException(
                "servers must be > 0, got " + servers);
        }
        this.servers = servers;
    }

    /**
     * Computes the Erlang-C blocking probability {@code C(c, a)} — the
     * probability that an arriving Poisson request finds all {@code c}
     * servers busy and therefore queues.  Uses a linear recurrence on the
     * series terms {@code a^k / k!} so the computation never materialises
     * a factorial, avoiding overflow at large {@code c}.
     *
     * <p>Numerically:
     * <pre>
     *     a   = λ/μ                 (offered load, in Erlangs)
     *     ρ   = a/c                 (per-server utilisation, must be &lt; 1)
     *     P₀⁻¹ = Σₖ₌₀…ᶜ⁻¹ aᵏ/k!
     *     last = aᶜ/c!
     *     C    = last/(1−ρ) ÷ (P₀⁻¹ + last/(1−ρ))
     * </pre></p>
     *
     * @param c number of servers (≥ 1)
     * @param a offered load in Erlangs (= λ/μ)
     * @return {@code C(c, a)} ∈ [0, 1], or {@code 1.0} when ρ ≥ 1
     */
    public static double erlangC(final int c, final double a) {
        if (c <= 0) {
            throw new IllegalArgumentException("c must be > 0, got " + c);
        }
        if (a < 0.0) {
            throw new IllegalArgumentException("a must be ≥ 0, got " + a);
        }
        final double rho = a / c;
        if (rho >= 1.0) {
            return 1.0;
        }
        // Build Σ_{k=0..c-1} a^k / k!  and  a^c / c!  by linear recurrence.
        double p0Inv = 1.0;          // k = 0 term
        double term = 1.0;            // a^0 / 0!
        for (int k = 1; k < c; k++) {
            term *= a / k;
            p0Inv += term;            // adds a^k / k!
        }
        final double lastTerm = term * a / c;   // a^c / c!
        final double numer = lastTerm / (1.0 - rho);
        return numer / (p0Inv + numer);
    }

    @Override
    public double draw(final double arrivalRate) {
        final int effectiveServers = getServers();
        final double a = arrivalRate / serviceRate;
        if (a >= effectiveServers) {
            return Double.POSITIVE_INFINITY;
        }
        final double c = erlangC(effectiveServers, a);
        double wait = 0.0;
        if (rng.nextDouble() < c) {
            // Queue-delay rate (cμ − λ).
            final double waitRate = effectiveServers * serviceRate - arrivalRate;
            wait = -Math.log(1.0 - rng.nextDouble()) / waitRate;
        }
        // Service time ~ Exp(μ).
        final double service = -Math.log(1.0 - rng.nextDouble()) / serviceRate;
        return wait + service;
    }
}

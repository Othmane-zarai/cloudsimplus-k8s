/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

import java.util.Random;

/**
 * Classical single-server M/M/1 queueing model.  Per Kleinrock §3.2
 * ({@code @Kleinrock1975}), with Poisson arrivals (rate λ) and exponential
 * service times (rate μ), the steady-state response time W is itself
 * exponentially distributed with mean {@code 1 / (μ − λ)}.  This class
 * therefore samples W via inverse-CDF transform on a per-call basis.
 *
 * <p>When ρ = λ/μ ≥ 1 the queue is unstable and {@link #draw(double)}
 * returns {@link Double#POSITIVE_INFINITY}.</p>
 *
 * @since CloudSim Plus 9.1.0
 */
public final class MM1QueueModel implements QueueingModel {
    /** Default seed used when no explicit seed is supplied — chosen for
     *  determinism in unit tests and audited experiments. */
    public static final long DEFAULT_SEED = 0xC0FFEEL;

    private final double serviceRate;
    private final Random rng;

    /**
     * @param serviceRate the per-server service rate μ in req/s (must be > 0)
     */
    public MM1QueueModel(final double serviceRate) {
        this(serviceRate, DEFAULT_SEED);
    }

    public MM1QueueModel(final double serviceRate, final long seed) {
        if (serviceRate <= 0.0) {
            throw new IllegalArgumentException(
                "serviceRate must be > 0, got " + serviceRate);
        }
        this.serviceRate = serviceRate;
        this.rng = new Random(seed);
    }

    public double getServiceRate() {
        return serviceRate;
    }

    @Override
    public double draw(final double arrivalRate) {
        if (arrivalRate >= serviceRate) {
            return Double.POSITIVE_INFINITY;
        }
        final double rate = serviceRate - arrivalRate;
        // Inverse-CDF of Exp(rate): -ln(1 - U) / rate
        final double u = rng.nextDouble();
        return -Math.log(1.0 - u) / rate;
    }
}

/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

/**
 * A queueing-theoretic latency model for a {@link
 * org.cloudsimplus.kubernetes.KubernetesService}.  Given the current request
 * arrival rate (in req/s), {@link #draw(double)} returns a single random
 * sample of the service's end-to-end response time (in seconds).
 *
 * <p>Implementations encode the classical results of
 * <a href="https://archive.org/details/queueingsystems01klei">Kleinrock,
 * <i>Queueing Systems, Volume 1: Theory</i> (Wiley, 1975)</a>: closed-form
 * waiting-time distributions for M/M/1, M/M/c, etc.  See
 * {@code @Kleinrock1975} in the project bibliography.</p>
 *
 * <p>The default model attached to every {@code KubernetesService} is
 * {@link ZeroLatencyModel}, so existing simulations continue to observe
 * zero network/service latency unless an explicit model is wired in.</p>
 *
 * @since CloudSim Plus 9.1.0
 */
@FunctionalInterface
public interface QueueingModel {
    /**
     * Draws one sample of the service's response time at the given arrival rate.
     *
     * @param arrivalRate aggregate request arrival rate λ in req/s
     * @return a single response-time sample in seconds; may be
     *         {@link Double#POSITIVE_INFINITY} when the system is saturated
     *         (ρ ≥ 1)
     */
    double draw(double arrivalRate);
}

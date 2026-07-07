/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.networking.queueing;

/**
 * Trivial {@link QueueingModel} that always returns {@code 0.0} — the
 * back-compatible default for {@link
 * org.cloudsimplus.kubernetes.KubernetesService}, where request routing
 * was historically treated as instantaneous.
 *
 * <p>Use this when latency is irrelevant to the experiment (e.g. pure
 * placement / scheduling studies).  For closed-form M/M/1 or M/M/c
 * latency, see {@link MM1QueueModel} and {@link MMcQueueModel}.</p>
 *
 * @since CloudSim Plus 9.1.0
 */
public final class ZeroLatencyModel implements QueueingModel {
    /** Singleton instance — this model is stateless. */
    public static final ZeroLatencyModel INSTANCE = new ZeroLatencyModel();

    private ZeroLatencyModel() {
    }

    @Override
    public double draw(final double arrivalRate) {
        return 0.0;
    }
}

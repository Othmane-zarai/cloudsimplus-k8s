/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.scheduler;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware.Policy;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Pins the determinism contract documented on {@link KubernetesScheduler}:
 * for fixed pod inputs, fixed node list, and a fixed tie-break strategy,
 * two independent runs produce identical pod→host assignments.
 *
 * <p>Tests three regimes:</p>
 * <ul>
 *   <li>{@link TieBreakStrategy#lexical()} — bit-identical placement across runs.</li>
 *   <li>{@link TieBreakStrategy#random(long)} — bit-identical with same seed,
 *       differs across seeds (on a workload designed to have ties).</li>
 *   <li>{@link TieBreakStrategy#firstFit()} — bit-identical across runs.</li>
 * </ul>
 *
 * @since CloudSim Plus 9.1.0
 */
class KubernetesSchedulerDeterminismTest {

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    /** Builds a fresh simulation with three identical-cost worker nodes. */
    private static List<KubernetesPod> runOnce(final TieBreakStrategy strategy) {
        final var sim = new CloudSimPlus();
        final var n1 = NodeBuilder.of("alpha").pes(4, 1000).ram(8192).costPerHour(0.10).build();
        final var n2 = NodeBuilder.of("bravo").pes(4, 1000).ram(8192).costPerHour(0.10).build();
        final var n3 = NodeBuilder.of("charlie").pes(4, 1000).ram(8192).costPerHour(0.10).build();
        final var scheduler = new KubernetesScheduler(Policy.COST_OPTIMIZED)
            .setTieBreakStrategy(strategy);
        new DatacenterSimple(sim, List.of(n1, n2, n3), scheduler);

        final var broker = new KubernetesClusterBroker(sim);
        final List<KubernetesPod> pods = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final var pod = PodBuilder.of("pod-" + i)
                .container(ContainerBuilder.of("c")
                    .cpu("500m").mem("256Mi").length(1).build())
                .build();
            broker.submitPod(pod);
            pods.add(pod);
        }
        sim.terminateAt(60.0);
        sim.start();
        return pods;
    }

    private static List<String> placement(final List<KubernetesPod> pods) {
        final List<String> out = new ArrayList<>(pods.size());
        for (final var p : pods) {
            final var host = p.getHost();
            out.add(host == null ? "<none>" : String.valueOf(host.getId()));
        }
        return out;
    }

    @Test
    void lexicalProducesBitIdenticalPlacementAcrossRuns() {
        final var a = placement(runOnce(TieBreakStrategy.lexical()));
        final var b = placement(runOnce(TieBreakStrategy.lexical()));
        assertEquals(a, b, "lexical() must be deterministic across independent runs");
    }

    @Test
    void firstFitProducesBitIdenticalPlacementAcrossRuns() {
        final var a = placement(runOnce(TieBreakStrategy.firstFit()));
        final var b = placement(runOnce(TieBreakStrategy.firstFit()));
        assertEquals(a, b, "firstFit() must be deterministic across independent runs");
    }

    @Test
    void randomWithSameSeedReproducesPlacement() {
        final var a = placement(runOnce(TieBreakStrategy.random(20260523L)));
        final var b = placement(runOnce(TieBreakStrategy.random(20260523L)));
        assertEquals(a, b,
            "random(seed) must be deterministic when the seed is fixed; "
            + "this is the cross-run reproducibility contract for the paper's seed-sensitivity sweep");
    }

    @Test
    void randomWithDifferentSeedsDivergesOnAtLeastOnePod() {
        final var a = placement(runOnce(TieBreakStrategy.random(1L)));
        final var b = placement(runOnce(TieBreakStrategy.random(99999L)));
        assertNotEquals(a, b,
            "different seeds on a tied-score workload must produce at least one different assignment; "
            + "if this assertion ever fails, the workload no longer exercises the tie-break path");
    }
}

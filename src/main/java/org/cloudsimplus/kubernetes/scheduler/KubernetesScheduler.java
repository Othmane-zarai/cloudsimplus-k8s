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
package org.cloudsimplus.kubernetes.scheduler;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSuitability;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.PodAffinity;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.kubernetes.Toleration;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A Kubernetes-flavored {@link VmAllocationPolicyTopologyAware}: layers the
 * core kube-scheduler filters and scoring on top of the existing topology
 * policies (cost / latency / spread / rack-anti-affinity / geographic spread).
 *
 * <p><b>Strict filters added on top of the parent's checks:</b></p>
 * <ul>
 *   <li>{@link KubernetesNode#isSchedulable() schedulable} flag (analog of
 *       {@code kubectl cordon})</li>
 *   <li>The pod's {@link KubernetesPod#getNodeSelector() nodeSelector} must
 *       match the node's labels</li>
 *   <li>The pod's {@link KubernetesPod#getNodeAffinity() required nodeAffinity}
 *       rules must be satisfied</li>
 *   <li>The pod must {@link Toleration#tolerates(Taint) tolerate} every
 *       {@code NoSchedule}/{@code NoExecute} taint on the node</li>
 * </ul>
 *
 * <p><b>Score adjustments (lower is better, mirroring the parent):</b></p>
 * <ul>
 *   <li>Subtract NodeAffinity {@code preferred} weight when matched</li>
 *   <li>Add a small penalty for each {@code PreferNoSchedule} taint not
 *       tolerated by the pod</li>
 * </ul>
 *
 * <p>Non-K8s VMs / non-K8s hosts fall straight through to the parent's
 * behavior, so this policy is safe to use in mixed scenarios.</p>
 *
 * <h2>Determinism contract</h2>
 *
 * <p>For a fixed workload (the same pods submitted in the same order, the
 * same node list, the same parent topology policy parameters) the
 * placement timeline produced by this scheduler is deterministic, with
 * exactly one source of nondeterminism:</p>
 *
 * <ul>
 *   <li>The {@link #tieBreakStrategy} object. {@link TieBreakStrategy#lexical()}
 *       (the default), {@link TieBreakStrategy#firstFit()},
 *       {@link TieBreakStrategy#lowestUid()}, and
 *       {@link TieBreakStrategy#roundRobin()} are deterministic functions of
 *       the input host list and produce identical placements across JVMs.
 *       {@link TieBreakStrategy#random(long)} is deterministic only for a
 *       fixed seed — two runs with the same seed produce identical
 *       placement timelines; two runs with different seeds will diverge on
 *       tied-score decisions.</li>
 * </ul>
 *
 * <p>The scheduler does not introduce any other source of randomness:
 * the filter phase is purely set-membership over labels and toleration
 * predicates; the score phase is a deterministic real-valued function
 * of pod and node state; the eviction-style preemption hook
 * ({@link #attemptPreemption}) iterates in priority order with stable
 * sub-orderings. Callers that need bit-identical event timelines across
 * machines should select {@link TieBreakStrategy#lexical()} (the
 * default) or {@link TieBreakStrategy#random(long)} with an explicitly
 * pinned seed.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesScheduler extends VmAllocationPolicyTopologyAware {

    /** Score penalty added for each PreferNoSchedule taint not tolerated by the pod. */
    public static final double PREFER_NO_SCHEDULE_PENALTY = 10.0;

    /**
     * Default scale factor applied to K8s score contributions (NodeAffinity
     * preferred bonus, PreferNoSchedule penalty, PodAffinity preferred score)
     * before they are combined with the parent topology policy's score. Without
     * this factor, K8s weights (1–100) would dominate parent cost scores
     * (often $0.05–$2 per hour) by orders of magnitude. The default of
     * {@value} translates a NodeAffinity weight of 100 into a score
     * contribution of 1.0, which is comparable to a dollar of hourly cost.
     */
    public static final double DEFAULT_K8S_SCORE_SCALE = 0.01;

    /**
     * Source of the placed pods used to evaluate {@link PodAffinity}. The
     * broker installs this on construction so the scheduler can read peer-pod
     * placement state. Defaults to an empty list (PodAffinity rules then no-op).
     */
    private Supplier<List<KubernetesPod>> placedPodsSource = Collections::emptyList;

    /**
     * Scale factor applied to K8s-specific score contributions before they
     * are summed with the parent {@link VmAllocationPolicyTopologyAware} score.
     * Tune up to make K8s preferences dominate parent topology choices (cost,
     * latency, spread); tune down to make parent topology dominate.
     *
     * @see #DEFAULT_K8S_SCORE_SCALE
     */
    private double k8sScoreScale = DEFAULT_K8S_SCORE_SCALE;

    public KubernetesScheduler() {
        super();
    }

    public KubernetesScheduler(final Policy policy) {
        super(policy);
    }

    /**
     * Magnitude of the deterministic lexical tie-break contribution to the
     * score. Small enough that any genuine score difference (cost, latency,
     * spread, K8s preferences scaled by {@link #k8sScoreScale}) overrides it,
     * but non-zero so two hosts that would otherwise tie always produce the
     * same winner across JVMs and runs (E5 fix). Rank is bounded by the host
     * count, so {@code maxRank * TIE_BREAK_EPSILON} stays well below 1µ even
     * for a 1000-node cluster.
     */
    public static final double TIE_BREAK_EPSILON = 1.0e-9;

    /**
     * Cached snapshot of placed pods reused across {@link #defaultFindHostForVm(Vm)}
     * calls until invalidated by a placement or deallocation event (A1 P0b fix).
     * Without this cache the scheduler is O(P · H · |vms|) per pod, dominating
     * RQ3 scalability — see {@code docs/profiling/before-A1-findings.md}.
     * Mutated by {@link #invalidatePlacedCache()}.
     */
    private List<KubernetesPod> placedCache;

    /**
     * Cached host-id → rank map produced by {@link #tieBreakStrategy}. Refreshed
     * lazily; the host list rarely changes (only on ClusterAutoscaler events),
     * so we keep this until {@link #invalidateHostRankCache()} fires.
     */
    private Map<Long, Integer> hostRankCache;

    /**
     * Pluggable strategy for breaking score ties (A4). Defaults to lexicographic
     * order for backwards compatibility with the pre-A4 deterministic
     * behaviour; production-fidelity comparisons should switch to
     * {@link TieBreakStrategy#random(long)} with a fixed seed.
     */
    private TieBreakStrategy tieBreakStrategy = TieBreakStrategy.lexical();

    /**
     * Wraps the parent's filter+score search to record an
     * {@link KubernetesPod#isUnschedulable() unschedulable} signal when no host
     * passes the strict filters. This is the source of truth used by
     * {@link org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler} to
     * decide when to provision a new node — without it, the autoscaler can't
     * distinguish between a pod that just hasn't been tried yet and a pod that
     * the scheduler has rejected from every node.
     */
    @Override
    protected Optional<Host> defaultFindHostForVm(final Vm vm) {
        // A1 P0b: caches persist across calls; rebuilt only when invalidated
        // by allocateHostForVm / deallocateHostForVm. Lazy population keeps the
        // first call's cost the same as before.
        if (placedCache == null) {
            placedCache = buildPlacedSnapshot();
        }
        if (hostRankCache == null) {
            hostRankCache = tieBreakStrategy.rank(new ArrayList<>(this.<Host>getHostList()));
        }
        Optional<Host> result = super.defaultFindHostForVm(vm);

        if (vm instanceof KubernetesPod pod) {
            final double now = vm.getSimulation() == null ? 0.0 : vm.getSimulation().clock();

            // If standard placement fails, attempt eviction-style preemption.
            if (result.isEmpty() && pod.getPriority() > 0) {
                result = attemptPreemption(pod);
            }

            if (result.isEmpty()) {
                pod.markUnschedulable(now);
            } else {
                pod.clearUnschedulable();
            }
        }
        return result;
    }

    /**
     * Hook for the parent policy's successful-placement path. Invalidates the
     * placed-pod cache so the next scheduling pass sees the new state.
     */
    @Override
    public HostSuitability allocateHostForVm(final Vm vm, final Host host) {
        final HostSuitability result = super.allocateHostForVm(vm, host);
        if (result != null && result.fully()) {
            invalidatePlacedCache();
        }
        return result;
    }

    /** Hook for deallocation events — invalidates the placed-pod cache. */
    @Override
    public void deallocateHostForVm(final Vm vm) {
        super.deallocateHostForVm(vm);
        invalidatePlacedCache();
    }

    /** Drops the placed-pods snapshot so the next placement attempt rebuilds it. */
    public void invalidatePlacedCache() {
        this.placedCache = null;
    }

    /** Drops the host-rank cache (called when nodes are added/removed). */
    public void invalidateHostRankCache() {
        this.hostRankCache = null;
    }

    /**
     * Eviction-style preemption: when no host passes the strict filters for a
     * high-priority pod, look for hosts where evicting strictly lower-priority
     * pods would free enough capacity. Hosts are visited in ascending base
     * score order (cheapest / closest first) and victims are picked
     * lowest-priority-first within each host.
     *
     * <p>Evicted victims are re-submitted to the broker, mirroring real K8s
     * preemption — losing the workload would silently delete user pods, which
     * would be a fidelity bug. Returns the host on which the high-priority pod
     * can be placed after eviction, or empty if no host can be made to fit.</p>
     */
    private Optional<Host> attemptPreemption(final KubernetesPod highPriPod) {
        final List<Host> sortedHosts = new java.util.ArrayList<>(this.<Host>getHostList());
        sortedHosts.sort(Comparator.comparingDouble((Host h) -> score(highPriPod, h)));

        for (final Host host : sortedHosts) {
            if (!(host instanceof KubernetesNode node) || !node.isSchedulable()) {
                continue;
            }
            // Strict, non-resource filters (taints, nodeSelector, affinity)
            // must already pass — eviction can free PEs/RAM but cannot change
            // node labels or taints.
            if (!nonResourceConstraintsPass(highPriPod, node)) {
                continue;
            }

            final java.util.List<KubernetesPod> lowerPriPods = new java.util.ArrayList<>();
            for (final Vm v : host.getVmList()) {
                if (v instanceof KubernetesPod p && p.getPriority() < highPriPod.getPriority()) {
                    lowerPriPods.add(p);
                }
            }
            lowerPriPods.sort(Comparator.comparingInt(KubernetesPod::getPriority));

            final long needPes = highPriPod.getPesNumber();
            final long needRam = highPriPod.getRam().getCapacity();
            long recoverablePes = host.getFreePesNumber();
            long recoverableRam = host.getRam().getAvailableResource();

            final java.util.List<KubernetesPod> victims = new java.util.ArrayList<>();
            for (final KubernetesPod victim : lowerPriPods) {
                if (recoverablePes >= needPes && recoverableRam >= needRam) {
                    break;
                }
                recoverablePes += victim.getPesNumber();
                recoverableRam += victim.getRam().getCapacity();
                victims.add(victim);
            }
            if (recoverablePes < needPes || recoverableRam < needRam) {
                continue; // even evicting all lower-pri pods isn't enough
            }
            if (victims.isEmpty()) {
                // The host already fits without eviction — let the parent
                // placement loop reach the same conclusion next pass; we never
                // get here because defaultFindHostForVm would have returned
                // this host. Defensive: skip rather than silently swallow.
                continue;
            }

            for (final KubernetesPod victim : victims) {
                // Immediate per-host destruction frees PEs/RAM in time for the
                // post-eviction capacity re-check below; the broker's
                // requestIdleVmDestruction is async and would let
                // host.getSuitabilityFor still see the victim's reservation.
                if (host instanceof org.cloudsimplus.hosts.HostAbstract h) {
                    h.destroyVm(victim);
                }
                // Re-queue so the workload isn't silently lost (real K8s
                // preemption sends the victim back through the scheduler).
                final var broker = victim.getBroker();
                if (broker != null) {
                    broker.submitVm(victim);
                }
            }
            // After the host frees up, the strict + capacity filter must pass.
            if (passesStrictConstraints(highPriPod, host)
                && host.getSuitabilityFor(highPriPod).fully()) {
                return Optional.of(host);
            }
        }
        return Optional.empty();
    }

    /** Strict filters that eviction cannot influence (labels, taints, affinity). */
    private boolean nonResourceConstraintsPass(final KubernetesPod pod, final KubernetesNode node) {
        if (!pod.getNodeSelector().matches(node.getLabels())) {
            return false;
        }
        if (!pod.getNodeAffinity().requiredMatches(node.getLabels())) {
            return false;
        }
        if (!Toleration.coversAll(pod.getTolerations(), node.getTaints())) {
            return false;
        }
        return passesRequiredPodAffinity(pod, node);
    }

    private List<KubernetesPod> buildPlacedSnapshot() {
        final var out = new java.util.LinkedHashSet<KubernetesPod>(placedPodsSource.get());
        for (final var h : getHostList()) {
            for (final var v : h.getVmList()) {
                if (v instanceof KubernetesPod kp) {
                    out.add(kp);
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    @Override
    protected boolean passesStrictConstraints(final Vm vm, final Host host) {
        if (!super.passesStrictConstraints(vm, host)) {
            return false;
        }
        if (!(vm instanceof KubernetesPod pod) || !(host instanceof KubernetesNode node)) {
            return true;
        }
        if (!node.isSchedulable()) {
            return false;
        }
        if (!pod.getNodeSelector().matches(node.getLabels())) {
            return false;
        }
        if (!pod.getNodeAffinity().requiredMatches(node.getLabels())) {
            return false;
        }
        if (!Toleration.coversAll(pod.getTolerations(), node.getTaints())) {
            return false;
        }
        final boolean affinityOk = passesRequiredPodAffinity(pod, node);
        return affinityOk;
    }

    private boolean passesRequiredPodAffinity(final KubernetesPod pod, final KubernetesNode candidate) {
        if (pod.getPodAffinity().isEmpty()) {
            return true;
        }
        final var placed = collectPlacedPods();
        for (final var rule : pod.getPodAffinity().getRules()) {
            if (!rule.isRequired()) {
                continue;
            }
            final boolean hasMatchingPeerInBucket = anyMatchInBucket(pod, placed, rule, candidate);
            if (rule.antiAffinity() && hasMatchingPeerInBucket) {
                return false;
            }
            if (!rule.antiAffinity() && !hasMatchingPeerInBucket) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the placed-pods snapshot for the current scheduling pass. Uses
     * the {@link #placedCache} when one is available (built once per
     * {@link #defaultFindHostForVm(Vm)} call); falls back to a fresh scan when
     * called outside a placement pass (e.g. from the parent policy directly).
     */
    private List<KubernetesPod> collectPlacedPods() {
        return placedCache != null ? placedCache : buildPlacedSnapshot();
    }

    private static boolean anyMatchInBucket(
        final KubernetesPod self,
        final List<KubernetesPod> placed,
        final PodAffinity.Rule rule,
        final KubernetesNode candidate)
    {
        for (final var peer : placed) {
            if (peer == self) {
                continue;
            }
            // A peer pod that's already attached to a host counts as "placed"
            // for affinity purposes — even if the broker hasn't yet processed
            // the VM_CREATE_ACK and flipped peer.isCreated() to true. This matters
            // for back-to-back submissions at the same simulated time, where pod
            // N+1's filter pass runs before pod N's ACK round-trip completes.
            if (!(peer.getHost() instanceof KubernetesNode peerNode)) {
                continue;
            }
            if (rule.selector().matches(peer.getLabels()) && rule.sameBucket(candidate, peerNode)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected double score(final Vm vm, final Host host) {
        final double base = super.score(vm, host);
        if (!(vm instanceof KubernetesPod pod) || !(host instanceof KubernetesNode node)) {
            return base;
        }
        // NodeAffinity preferred: higher weight ↔ better fit, so subtract from the score.
        final double affinityBonus = pod.getNodeAffinity().preferredScore(node.getLabels());
        // PreferNoSchedule taints: each non-tolerated one nudges the score up.
        final double taintPenalty = preferNoSchedulePenalty(pod, node);
        // NoSchedule/NoExecute taints that the pod merely tolerates contribute
        // nothing to the score: kube-scheduler's TaintToleration Score plugin
        // ranks only by untolerated PreferNoSchedule taints and awards no
        // preference for tolerating a hard taint. Awarding such a bonus made
        // the simulator over-prefer tainted-but-tolerated nodes relative to
        // kube-scheduler — a score-phase fidelity divergence (RQ1) — so it is
        // not modelled.
        // Preferred PodAffinity / PodAntiAffinity contributions.
        final double podAffinityScore = preferredPodAffinityScore(pod, node);
        // All K8s-specific contributions are normalized so that they remain
        // comparable to the parent's score (cost / latency / spread) rather
        // than dominating it by orders of magnitude.
        final double k8sContrib =
            (-affinityBonus + taintPenalty + podAffinityScore) * k8sScoreScale;
        // Deterministic tie-break (E5): when every other component is equal,
        // the lexicographically smaller host name wins. The epsilon scaling
        // ensures this never overrides genuine score differences.
        return base + k8sContrib + lexicalTieBreak(host);
    }

    private double lexicalTieBreak(final Host host) {
        if (hostRankCache == null) {
            return 0.0;
        }
        final Integer rank = hostRankCache.get(host.getId());
        return rank == null ? 0.0 : rank * TIE_BREAK_EPSILON;
    }

    /**
     * A4: Returns the set of nodes tied at the minimum score for {@code pod},
     * excluding the {@link #TIE_BREAK_EPSILON} tie-break contribution. The
     * returned set is what the upstream kube-scheduler's tie-break extension
     * sees as its input — useful for cross-tool comparisons (B2 score-set
     * agreement metric).
     *
     * <p>The returned list is in arbitrary order; callers that need the
     * winner should apply {@link #getTieBreakStrategy()} explicitly.</p>
     *
     * @param pod the pod to score against the current host list
     * @return the tied-minimum-score nodes, or empty if no node is feasible
     */
    public List<KubernetesNode> getCandidateNodes(final KubernetesPod pod) {
        final var feasible = new ArrayList<KubernetesNode>();
        final var scoresByHostId = new HashMap<Long, Double>();
        // Build the placed cache once for this query so passesStrictConstraints
        // and score() see a consistent view of the cluster.
        final List<KubernetesPod> savedCache = placedCache;
        try {
            if (placedCache == null) {
                placedCache = buildPlacedSnapshot();
            }
            for (final Host h : this.<Host>getHostList()) {
                if (!(h instanceof KubernetesNode node)) {
                    continue;
                }
                if (!passesStrictConstraints(pod, h)) {
                    continue;
                }
                if (!h.getSuitabilityFor(pod).fully()) {
                    continue;
                }
                feasible.add(node);
                // Score without the tie-break epsilon to surface genuine ties.
                scoresByHostId.put(h.getId(), scoreWithoutTieBreak(pod, h));
            }
        } finally {
            placedCache = savedCache;
        }
        if (feasible.isEmpty()) {
            return List.of();
        }
        final double minScore = feasible.stream()
            .mapToDouble(n -> scoresByHostId.get(n.getId()))
            .min().orElseThrow();
        final var tied = new ArrayList<KubernetesNode>();
        for (final var node : feasible) {
            final double s = scoresByHostId.get(node.getId());
            if (Math.abs(s - minScore) < TIE_BREAK_EPSILON) {
                tied.add(node);
            }
        }
        return tied;
    }

    /** Score components excluding the tie-break epsilon — used by {@link #getCandidateNodes}. */
    private double scoreWithoutTieBreak(final KubernetesPod pod, final Host host) {
        return score(pod, host) - lexicalTieBreak(host);
    }

    private double preferredPodAffinityScore(final KubernetesPod pod, final KubernetesNode node) {
        if (pod.getPodAffinity().isEmpty()) {
            return 0.0;
        }
        final var placed = collectPlacedPods();
        double score = 0.0;
        for (final var rule : pod.getPodAffinity().getRules()) {
            if (rule.isRequired()) {
                continue;
            }
            final boolean hasMatch = anyMatchInBucket(pod, placed, rule, node);
            // For affinity: matching peers in the bucket reduce the score (better fit).
            // For anti-affinity: matching peers increase the score (worse fit).
            if (hasMatch) {
                score += rule.antiAffinity() ? rule.weight() : -rule.weight();
            }
        }
        return score;
    }

    private static double preferNoSchedulePenalty(final KubernetesPod pod, final KubernetesNode node) {
        double penalty = 0.0;
        for (final var taint : node.getTaints()) {
            if (taint.effect() != Taint.Effect.PREFER_NO_SCHEDULE) {
                continue;
            }
            if (pod.getTolerations().stream().noneMatch(t -> t.tolerates(taint))) {
                penalty += PREFER_NO_SCHEDULE_PENALTY;
            }
        }
        return penalty;
    }
}

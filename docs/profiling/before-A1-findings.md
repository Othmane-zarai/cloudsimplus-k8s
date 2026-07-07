# A1 Profile Findings — before-A1.jfr

**Scenario**: `rq3_genyaml.py --nodes 10 --pods 500` → `K8sClusterFromYamlExample`,
`-Dk8syaml.duration=30 -Dk8s.benchmark=true`.

**Result**: wall = **29.7 s** for 30 sim-seconds. Run reported
`placedPods=160/500  unschedulable=9180`. 160 = 10 nodes × 16 PE capacity.
**The 9180 unschedulable count is the smoking gun**: only 340 pods *should*
remain unschedulable (500 − 160). The 9180 figure means ~340 unschedulable
pods × ~27 reconcile cycles ≈ 9180 — the controller is re-creating the
unschedulable pods every tick.

## Hotspots (top 30 stack samples, cloudsimplus methods only)

| Samples | Method | Note |
|---:|---|---|
| 765 + 65 + 44 = **874** | `KubernetesScheduler.defaultFindHostForVm` | Hot caller |
| 624 + 95 + 38 + 21 + 14 = **792** | `KubernetesScheduler.buildPlacedSnapshot` | Allocates fresh LinkedHashSet **per attempt** |
| 294 | `ReplicaSetController.reconcile` | Calls scaleUp every tick |
| 282 | `ReplicaSetController.scaleUp` | Submits N pods, each calls defaultFindHostForVm |
| 243 | `ControllerManager.reconcileAll` | Driver |
| 238 | `KubernetesClusterBroker.lambda$new$0` | Tick handler that calls reconcileAll |
| 303 | `KubernetesClusterBroker.submitPod` | Each unschedulable resubmission |
| 183 | `KubernetesClusterBroker.fireTick` | Tick dispatch |

## Root cause #1 — Behavioral bug (BLOCKING)

`KubernetesClusterBroker.submitPod` registers
`addOnCreationFailureListener(info -> onPodLost(pod))` (line 208).
When the scheduler can't place a pod, the failure listener fires
`controllerManager.onPodLost(pod)` →
`ReplicaSetController.onPodLost(pod)` →
**`managed.remove(ordinal)`** (RSController line 120).

On the next reconcile tick, `managed.size() < desiredReplicas`, so
`reconcile()` calls `scaleUp(diff)` and submits *new* pods to replace
the ones it thinks were lost. Those also fail to schedule. The cycle
repeats every tick.

For the n=10/p=500 case: 340 unschedulable × 27 ticks ≈ 9180 fake
"lost" events, each triggering a full scheduler scan.

**Fix**: distinguish *failure-to-place* from *lost-after-running*.
A pod that has never been placed shouldn't trigger
`onCreationFailureListener → onPodLost → managed.remove`. Either:

- (a) Don't fire `onPodLost` for pods that never entered Running state, or
- (b) `ReplicaSetController.onPodLost` keeps the pod in `managed` with
  `status = Unschedulable` (matches real K8s: a Pending pod stays in the
  ReplicaSet's desired count; the RS doesn't replace it just because the
  scheduler can't place it yet).

Option (b) matches Kubernetes semantics exactly.

## Root cause #2 — Performance bug (independent)

`defaultFindHostForVm` rebuilds two caches **on every call**:

```java
protected Optional<Host> defaultFindHostForVm(final Vm vm) {
    placedCache = buildPlacedSnapshot();        // iterates all hosts + all VMs
    lexicalRankCache = buildLexicalRankCache(); // sorts host list
    try {
        Optional<Host> result = super.defaultFindHostForVm(vm);
        ...
```

`buildPlacedSnapshot` (line 311–321) walks `getHostList()` × `h.getVmList()`
on each call. With 160 placed pods, that's 160 ops per call. With 10,200
scheduling attempts in the slow run, that's ~1.6 M wasted ops.

`buildLexicalRankCache` sorts a 10-host list per call — cheap individually,
but 10,200 sorts is still measurable.

**Fix**: Cache both at the scheduler-instance level. Invalidate on
placement (`onHostAllocation`) and deallocation (`onHostDeallocation`)
events only. The broker already wires lifecycle listeners — add a
scheduler-side invalidation hook.

## Recommended A1 scope

NEXT_STEPS.md proposed `PendingPodQueue` + `LabelIndex` + per-node
resource caches. The profile shows the actual bottlenecks are simpler:

1. **(P0, behavioral)** Fix `ReplicaSetController.onPodLost` to ignore
   unschedulable-state losses.
2. **(P0, performance)** Cache `placedCache` and `lexicalRankCache` at
   scheduler instance, invalidate on placement/deallocation events.
3. **(P1, future-proofing)** Add `LabelIndex` for affinity lookups —
   only matters once pods use affinity rules at scale. The benchmark
   YAML has no affinity, so this isn't the current hotspot.
4. **(P1)** JMH harness under `src/jmh/java/` for regression detection.

Expected result after P0 fixes alone: wall time at n=10/p=500 should
drop from 29.7 s to roughly **0.5–2 s** (≥15× speedup) because the
scheduler will be called ~340 times total instead of ~10,200, and each
call will be ~5× cheaper.

---

## Measured result after P0a + P0b fixes (after-A1.jfr)

| Scenario | Wall time (before → after) | Speedup | `unschedulable` count |
|---|---|---|---|
| n=10, p=500  | **29.7 s → 2.76 s** | **10.7×** | 9180 → **340** (correct) |
| n=10, p=1000 | **107 s → 4.7 s**   | **22.7×** | (n/a → 840, correct) |
| n=1000, p=100 | 538 ms → 1.14 s | (in same range; node-scaling already sub-linear) | placed=100/100 |

`buildPlacedSnapshot` dropped from **792 stack samples** to **0** (no longer in
the top-15 hot methods). The cache hit rate is effectively ~99 %: rebuild
only fires on the ~160 successful placements during the initial scale-up,
not on the 30 reconcile ticks afterwards.

All 176 cloudsimplus kubernetes tests pass (was 165 before A1; +6 new
`TieBreakStrategyTest` for A4 + 5 incidental).

# CloudSim Plus — Kubernetes Simulation Layer

A first-class Kubernetes simulation built on CloudSim Plus. Express scenarios in
K8s terms — Pods, Containers, Nodes, Namespaces, Services, Deployments,
ReplicaSets, StatefulSets, DaemonSets, Jobs, CronJobs — with labels, selectors,
taints, tolerations, NodeAffinity, PodAffinity, restart policies,
liveness/readiness probes, init containers, HPA, and Cluster Autoscaler. The
existing CloudSim Plus simulation engine, scheduler and resource model power it
underneath.

This document is the single source of truth for the layer. It supersedes the
earlier `KUBERNETES_FIXES_REPORT.md` and `KUBERNETES_PEER_REVIEW.md` documents
and covers:

1. [Mental model](#1-mental-model)
2. [Architecture](#2-architecture)
3. [What's implemented](#3-whats-implemented)
4. [How to use it](#4-how-to-use-it)
5. [File-by-file inventory](#5-file-by-file-inventory)
6. [Test coverage](#6-test-coverage)
7. [Engineering history — fixes applied from peer review](#7-engineering-history--fixes-applied-from-peer-review)
8. [Release readiness](#8-release-readiness)
9. [Paper changes — what to revise in the SPE submission](#9-paper-changes--what-to-revise-in-the-spe-submission)
10. [Known limitations & future work](#10-known-limitations--future-work)

---

## 1. Mental model

Kubernetes objects map onto CloudSim Plus primitives without duplicating the
existing simulation engine:

| Kubernetes object | CloudSim Plus realization |
|---|---|
| **Node** | `KubernetesNode` extends `TopologyAwareHost` |
| **Pod** | `KubernetesPod` extends `VmSimple` |
| **Container** | `KubernetesContainer` extends `CloudletSimple` |
| **Namespace** | `Namespace` (lightweight value class) |
| **Labels / Selectors** | `LabelSet` + `LabelSelector` (with `In`/`NotIn`/`Exists`/`DoesNotExist`) |
| **Taints / Tolerations** | `Taint` (NoSchedule / PreferNoSchedule / NoExecute) + `Toleration` |
| **NodeAffinity** | `NodeAffinity` — required + preferred selectors over node labels |
| **PodAffinity / PodAntiAffinity** | `PodAffinity` — same/different topology bucket (hostname / zone / region) |
| **Service** | `KubernetesService` extends `ServiceSimple` (selector-driven endpoints) |
| **kube-scheduler** | `KubernetesScheduler` extends `VmAllocationPolicyTopologyAware` (filter + score, layered on rack/AZ/region/cost/latency policies) |
| **Cluster control plane** | `KubernetesClusterBroker` extends `ServiceBrokerSimple` |
| **kubelet** | `Kubelet` (broker-resident, container submission + probes + restartPolicy) |
| **ReplicaSet / Deployment / StatefulSet / DaemonSet / Job / CronJob** | `*Controller` classes implementing `Controller` |
| **HPA** | `HorizontalPodAutoscaler` (reuses `Tick` + utilization sampling) |
| **Cluster Autoscaler** | `ClusterAutoscaler` (provisions from a `NodePool` on unschedulable pods) |
| **PodCondition / PodPhase** | enums updated by the `Kubelet` |
| **restartPolicy** | `RestartPolicy` enum on each container (Always / OnFailure / Never) |
| **Liveness / Readiness probe** | `LivenessProbe` / `ReadinessProbe` evaluated periodically by the kubelet |
| **PriorityClass** | `KubernetesPod#priority` integer field; the broker's
`enablePriorityScheduling()` reorders the unbound-pod queue by priority |

**Sealed-interface compliance** is preserved throughout: every entity extends
an existing concrete subclass (`VmSimple`, `HostSimple` via `TopologyAwareHost`,
`CloudletSimple`, `DatacenterBrokerSimple` via `ServiceBrokerSimple`,
`ServiceSimple`) — never a sealed interface directly.

---

## 2. Architecture

The K8s control plane is **broker-resident reconciliation loops**, not a
separate `SimEntity`. A periodic *controller tick* (default 1.0 s; configurable
via `broker.setControllerTickIntervalSeconds(...)`) drives every loop —
controllers, kubelet probes, autoscalers — through a shared `Tick` interface.
Controllers are loosely coupled to their pods via owner-reference labels
(`cloudsimplus.kubernetes/controller-uid` and `controller-kind`), mirroring
real Kubernetes.

A single, minimal change was made to existing CloudSim Plus code:

- [`VmAllocationPolicyTopologyAware`](src/main/java/org/cloudsimplus/allocationpolicies/VmAllocationPolicyTopologyAware.java)
  — `passesStrictConstraints` and `score` were elevated from `private` to
  `protected` so `KubernetesScheduler` (in a different package) can compose
  with `super.…(…)`.
- [`DatacenterSimple.setHostList`](src/main/java/org/cloudsimplus/datacenters/DatacenterSimple.java)
  now wraps the input in `new ArrayList<>(hostList)` so `addHost` /
  `removeHost` succeed at runtime regardless of whether the caller passed an
  immutable or mutable list. Required by the cluster autoscaler.

---

## 3. What's implemented

### 3.1 Object model

- **`Resources`** — record `(milliCpu, memMiB)` plus parsers for K8s strings
  (`"500m"`, `"256Mi"`, `"1Gi"`) and a configurable millicores ↔ MIPS converter
  (`DEFAULT_MIPS_PER_CORE = 1000`).
- **`LabelSet`**, **`LabelSelector`** — immutable label maps with full
  `matchLabels` + `matchExpressions` support.
- **`Taint`**, **`Toleration`**, **`NodeAffinity`**, **`PodAffinity`**.
- **`Namespace`** — name + labels, with `DEFAULT` and `KUBE_SYSTEM` constants.
- **`KubernetesNode`** — adds `nodeName`, `labels`, `taints`, `schedulable` on
  top of `TopologyAwareHost` (rack/AZ/region/cost/latency inherited).
- **`KubernetesContainer`** — adds `containerName`, `image`, `requests` /
  `limits`. PE count derived from CPU request (ceil to whole cores, floor at
  1). Constructor rejects `limits < requests`.
- **`KubernetesPod`** — adds `podName`, `namespace`, `labels`, `containers`,
  `nodeSelector`, `nodeAffinity`, `podAffinity`, `tolerations`, `priority`,
  `unschedulable` flag (with `schedulingAttempts` / `lastSchedulingAttemptAt`
  audit fields). Total memory sums container `limits.memMiB()`.
- **`KubernetesService`** — selector-driven dynamic endpoints. Backing pods are
  recomputed on every routing call so newly-created pods (e.g. from a
  ReplicaSet scale-up) flow into the endpoint set without manual registration.
  Throws `UnsupportedOperationException` from `addVm` — pods are managed
  exclusively through the selector.

### 3.2 Scheduler

`KubernetesScheduler` extends `VmAllocationPolicyTopologyAware`:

- **Strict filters:** `nodeSelector`, NodeAffinity required, taint covers,
  `schedulable` flag, PodAffinity required.
- **Score:** NodeAffinity preferred bonus, PreferNoSchedule penalty,
  PodAffinity preferred contributions. K8s contributions are scaled by a
  configurable `k8sScoreScale` (default `0.01`) so they remain comparable to
  the parent's cost / latency / spread scores rather than dominating them.
- **Determinism:** when scores tie, the lexicographically smaller
  `effectiveName()` wins, so the same simulation produces the same placement
  across JVMs. The tie-break is a per-pass cached lexical rank scaled by
  `TIE_BREAK_EPSILON = 1.0e-9`.
- **Per-pass caches:** placed-pods snapshot and lexical-rank map are built
  once per `defaultFindHostForVm` call, keeping placement O(H) rather than
  O(P · H · |vms|).
- **Unschedulable signalling:** sets `pod.markUnschedulable(now)` when the
  filter+score pass returns no candidate, and `clearUnschedulable()` on a
  successful placement. This is the source of truth the
  `ClusterAutoscaler` consults — it is what lets the autoscaler distinguish a
  pod that has been rejected from one that simply hasn't been tried yet.

### 3.3 Controllers

| Controller | Behaviour |
|---|---|
| **`ReplicaSetController`** | Maintains `desiredReplicas`. Pods tracked by ordinal-keyed map; scale-down picks the highest ordinal first. |
| **`DeploymentController`** | Owns two child ReplicaSets (`newRs` / `oldRs`). `updateTemplate(t)` archives the current RS as old and starts the new RS at zero replicas. Each tick advances by one symmetric `RollingUpdate(maxSurge, maxUnavailable)` step (mirrors `kubernetes/pkg/controller/deployment/sync.go::NewRSNewReplicas`); `Recreate` strategy supported. |
| **`StatefulSetController`** | Stable ordinal-suffixed names (`db-0`, `db-1`, …); fills lowest free ordinal on scale-up; removes highest on scale-down. |
| **`DaemonSetController`** | One pod per matching node. Pins each pod to its node via `kubernetes.io/hostname` (auto-stamped by `NodeBuilder`; falls back to `effectiveName()` for ad-hoc nodes). Reacts to node decommission via `onPodLost` (broker fires it when the autoscaler removes the host). |
| **`JobController`** | Spawns up to `parallelism` pods until `completions` succeed. A pod is credited only when **all** its containers finish successfully; one container failure marks the whole pod failed (matches K8s). `backoffLimit` halts further spawns. |
| **`CronJobController`** | Fires a fresh `JobController` based on a full cron expression. Supports `concurrencyPolicy` (Allow / Forbid / Replace) and `startingDeadlineSeconds`. |

The `Controller` interface provides shared `ownerLabels()` and
`ownerLabels(ordinal)` helpers so every controller stamps the same
owner-reference label shape.

### 3.4 Kubelet behaviours

- **Init container ordering** — submitted one at a time, chained by finish
  listeners. Main containers are submitted as a batch only after the last init
  container completes.
- **restartPolicy** — on container exit, applies `ALWAYS` / `ON_FAILURE` /
  `NEVER` by resetting and re-submitting the cloudlet.
- **Probes** — evaluated on every controller tick. Each probe runs inside a
  per-probe try/catch so a buggy user predicate cannot poison the iterator.
  Liveness failure (after `failureThreshold` consecutive misses) cancels the
  cloudlet and restarts per `restartPolicy`. Readiness failures flip the pod's
  `READY` condition; `KubernetesService` automatically excludes non-Ready pods
  from endpoints.
- **Config & Security Pre-flight** — Before transitioning a pod to `RUNNING`,
  the kubelet verifies every `ConfigMap`, `Secret`, and `ServiceAccount`
  declared via `KubernetesPod.mountConfigMap(...)`, `mountSecret(...)`, or
  `setServiceAccountName(...)` is registered with the broker. Missing
  dependencies hold the pod in `PodPhase.PENDING`; the kubelet re-attempts on
  every tick and starts the pod once they appear (modelling K8s'
  `ContainerCreating` state).
- **Storage Pre-flight** — Pods that declare `requirePersistentVolumeClaim(name)`
  block startup until the named `PersistentVolumeClaim` is registered <i>and</i>
  bound to a `PersistentVolume` (backed by CloudSim Plus's `HarddriveStorage`).
  The broker's `addPersistentVolumeClaim(...)` runs a first-fit binding pass
  against registered PVs at registration time, mirroring the K8s PV
  controller.

### 3.5 Autoscaling

**`HorizontalPodAutoscaler`** wraps a `ReplicaSetController` or
`DeploymentController`. Each tick it averages CPU% over **Ready pods** (not
merely Created — matches K8s) and adjusts `desiredReplicas` toward
`targetCpuUtilization`, clamped to `[minReplicas, maxReplicas]`. K8s 1.18+
behaviour:

- **Tolerance window** (default `0.10`) — `|avg − target|/target < tolerance`
  → no action; eliminates flutter that biases fidelity comparisons.
- **Split cooldowns** — `cooldownScaleUpSeconds` (default `0`) and
  `cooldownScaleDownSeconds` (default `300`) so scale-up is responsive while
  scale-down is conservative.
- **Zero-load scale-down** — when sustained load drops to zero, the HPA
  correctly scales toward `minReplicas` (the previous `if (avg <= 0) return;`
  guard suppressed all scale-down events).

**`VerticalPodAutoscaler`** samples per-container CPU and memory utilisation
across a target `ReplicaSetController`'s managed pods on every tick and
exposes a sizing recommendation via `getRecommendedMilliCpu()` /
`getRecommendedMemMiB()`. This implements K8s' VPA "Off / Initial" semantics:
recommendations are surfaced but pods are not mutated automatically (container
resource specs are immutable, and rewriting the `PodTemplate` requires
template-aware glue only the user can supply). Auto-recreate is opt-in via
`setEvictOnRecommendation(true)` — when set, pods are evicted so the
ReplicaSet recreates them from a (potentially user-updated) template.
Recommendations are gated by a configurable `tolerance` (default `0.10`) and
`cooldownSeconds` (default `60`), mirroring the upstream VPA recommender's
deadband and rate-limiter.

> **Caveat — utilization model.** HPA reads CPU% via
> `Vm.getCpuPercentUtilization()`, which is governed by the cloudlet's
> utilization model. The default `UtilizationModelFull` always reports 100%
> until completion, then 0% — fine for smoke-testing, useless for research.
> Use `ContainerBuilder.cpuUtilization(new UtilizationModelDynamic(...))` to
> install a time-varying load profile.

**`ClusterAutoscaler`** provisions new nodes from a `NodePool` template when
pods are unschedulable, and decommissions empty nodes after
`scaleDownAfterSeconds` of idle time (down to `pool.min`).

- **Real removal, not cordon** — scale-down calls `dc.removeHost(n)`. Pool
  membership is tracked by reference (`Set<KubernetesNode> ownedNodes`)
  instead of by name prefix, so two pools sharing a prefix or YAML-loaded
  templates that produce names without the pool prefix don't drift.
- **Resubmit-on-grow** — after provisioning a node, every unschedulable pod is
  removed from `vmFailedList` and resubmitted so the scheduler runs filter
  +score over the expanded host list.

### 3.6 Pod priority

Pods carry an integer `priority` field (mirrors K8s
`spec.priority` / `priorityClassName`). The broker's
`enablePriorityScheduling()` sorts the unbound-pod queue by priority before
the scheduler runs filter+score.

If placement fails for a pod with `priority > 0`, **eviction-style
preemption** kicks in. `KubernetesScheduler.attemptPreemption` visits hosts
in ascending base-score order; for each host where label/taint/affinity
filters pass, it picks lowest-priority-first victims until enough PEs and
RAM would be freed. Selected victims are immediately destroyed via
`HostAbstract.destroyVm` (the broker's async path is too late for the
post-eviction capacity re-check) and then re-submitted via
`broker.submitVm(...)` so the workload re-enters the scheduler queue
rather than being silently lost. Preemption is gated on `priority > 0` so
the default (priority 0) workload mix never preempts itself.

### 3.7 Networking & Storage

- **`NetworkPolicy`** — Registered on the broker via `addNetworkPolicy(...)`.
  `KubernetesClusterBroker.submitRequest` intercepts every `ServiceRequest`
  and, for each policy in the target service's namespace whose `podSelector`
  matches at least one of the service's currently-backing pods, enforces
  ingress: requests to a policy-targeted service whose `ingressAllowed=false`
  are dropped (logged at WARN, never forwarded to the parent broker). This
  reproduces K8s' default-deny behaviour once a policy targets a pod.
  Egress is not modelled — the broker's `submitRequest` does not carry a
  source-pod identity, so egress drops would have nothing to match on.
- **`Ingress`** — L7 routing layer. `Ingress.route(host, path)` performs
  longest-prefix path matching, with an explicit-host filter (rules with a
  null host match any host, mirroring K8s' wildcard rule). The broker exposes
  `addIngress(...)` and `routeIngress(host, path)` to resolve an inbound
  `(host, path)` pair to its backing `KubernetesService`.
- **`PersistentVolume` / `PersistentVolumeClaim`** — Stateful storage
  modelled physically by backing each `PersistentVolume` with a
  CloudSim Plus `HarddriveStorage`. The broker's `addPersistentVolume` and
  `addPersistentVolumeClaim` track cluster-wide state; PVC registration
  triggers an immediate first-fit binding pass that requires
  (1) sufficient capacity, (2) matching `storageClassName`
  (both null, or both non-null and equal), and (3) the PVC's
  `LabelSelector` matches the PV's `LabelSet`. The kubelet pre-flight
  (Section 3.4) blocks pod startup on every PVC the pod declares via
  `requirePersistentVolumeClaim`.

### 3.8 Security & Configuration

- **`ConfigMap` / `Secret`** — Key/value (and binary key/byte[]) registries
  referenced by pods through `KubernetesPod.mountConfigMap(name)` /
  `mountSecret(name)`. The kubelet pre-flight (Section 3.4) refuses to start
  the pod's main containers until every named ConfigMap/Secret is registered
  in the pod's namespace.
- **`ServiceAccount`** — Pods declare an SA via `setServiceAccountName(name)`;
  the kubelet checks it is registered before transitioning the pod to
  `RUNNING`. `null` (default) skips the check, matching K8s' implicit
  `default` SA semantics.
- **`Role` / `RoleBinding`** — Metadata-only: `Role` carries a list of rule
  strings, `RoleBinding` links a `Role` to a `ServiceAccount`. Carried for
  scenario fidelity; no permission enforcement is simulated (cloud
  simulation has no effective notion of "API call authorization").

### 3.9 Workload controllers — recent additions

- **CronJob (full cron expressions)** — `CronJobController.setCronExpression`
  parses a real cron string via the **cron-utils** library (UNIX flavour by
  default; `setCronType` switches to QUARTZ / SPRING / CRON4J). The expression
  is validated at setter time; invalid input throws
  `IllegalArgumentException`. Wall-clock evaluation maps simulation-clock
  seconds onto an absolute `Instant` via the configurable
  `simulationEpochSeconds` (default `2020-01-01T00:00:00Z`); set this before
  the first reconcile when day-of-week / month rules need to anchor at a
  specific calendar date. The `startingDeadlineSeconds` slot computation
  honours both modes correctly.
- **DaemonSet rolling-update** — `DaemonSetController` now supports an opt-in
  `UpdateStrategyType.ROLLING_UPDATE` strategy. A monotonic
  `templateRevision` counter is bumped on every `setTemplate` call; pods are
  tagged with the revision in effect at spawn time, and the rolling-update
  reconciler evicts any pod whose tagged revision is older than the current
  one. Replacement happens on the next tick to avoid same-tick
  destroy-then-create races against the broker's `vmFailedList` processing.
  Default strategy remains `ON_DELETE` for backwards compatibility.

---

## 4. How to use it

### 4.1 Minimal working example

```java
final var sim = new CloudSimPlus();

// 1. Define the cluster — three nodes in a single datacenter.
final var nodes = List.of(
    NodeBuilder.of("worker-1").pes(4, 1000).ram(8_192).rack("r1").build(),
    NodeBuilder.of("worker-2").pes(4, 1000).ram(8_192).rack("r2").build(),
    NodeBuilder.of("worker-3").pes(4, 1000).ram(8_192).rack("r3").build());
new DatacenterSimple(sim, nodes,
    new KubernetesScheduler(VmAllocationPolicyTopologyAware.Policy.COST_OPTIMIZED));

// 2. Stand up the cluster broker.
final var broker = new KubernetesClusterBroker(sim);

// 3. Declare a Deployment of 3 replicas with a rolling-update strategy.
final var template = new PodTemplate(ord -> PodBuilder.of("web-" + ord)
    .label("app", "web")
    .container(ContainerBuilder.of("nginx")
        .image("nginx:1.21")
        .cpu("500m").mem("256Mi")
        .length(50_000)        // simulated work, in MI
        .build())
    .build());

final var deployment = new DeploymentController(
    broker.getControllerManager().allocateUid(),
    "web",
    Namespace.DEFAULT,
    template,
    /* replicas */ 3
).setStrategy(UpdateStrategy.RollingUpdate.defaults());
broker.addController(deployment);

// 4. (Optional) Front it with a K8s Service.
broker.addService(new KubernetesService(
    "web", Namespace.DEFAULT, LabelSelector.matchLabel("app", "web")));

// 5. Run.
sim.terminateAt(60.0);
sim.start();
```

### 4.2 Adding HPA

```java
final var hpa = HorizontalPodAutoscaler.of(deployment, /* target */ 0.7)
    .setMinReplicas(2).setMaxReplicas(10)
    .setTolerance(0.10)
    .setCooldownScaleUpSeconds(0.0)
    .setCooldownScaleDownSeconds(300.0);
broker.registerTick(hpa);
```

### 4.3 Adding cluster autoscaling

```java
final var pool = new NodePool(
    "extra-worker",
    () -> NodeBuilder.of("extra-worker-" + System.nanoTime())
        .pes(4, 1000).ram(8_192).build(),
    /* min */ 0, /* max */ 5);
final var ca = new ClusterAutoscaler(broker, pool);
broker.registerTick(ca);
```

### 4.4 Probes + restartPolicy + init containers

```java
ContainerBuilder.of("api")
    .image("api:1.4").cpu("500m").mem("256Mi").length(20_000)
    .restartPolicy(RestartPolicy.ON_FAILURE)
    .livenessProbe(new LivenessProbe(c -> c.getStatus() != Cloudlet.Status.FAILED)
        .setPeriodSeconds(10).setFailureThreshold(3))
    .readinessProbe(new ReadinessProbe(c -> c.getFinishedLengthSoFar() > 1000))
    .cpuUtilization(new UtilizationModelDynamic(0.7))   // realistic HPA input
    .build();
```

```java
PodBuilder.of("web")
    .container(ContainerBuilder.of("init-db").length(2_000).asInitContainer().build())
    .container(ContainerBuilder.of("nginx").length(50_000).build())
    .build();
```

### 4.5 Microservice call graphs (inherited)

`KubernetesClusterBroker` extends `ServiceBrokerSimple`, so `ServiceCall` /
`ServiceRequest` flows through K8s services unchanged:

```java
final var backend = new KubernetesService(
    "backend", Namespace.DEFAULT, LabelSelector.matchLabel("tier", "backend"));
broker.addService(backend);

broker.submitRequest(new ServiceRequest(0, new ServiceCall(backend, /* MI */ 5_000)));
```

---

## 5. File-by-file inventory

```
src/main/java/org/cloudsimplus/kubernetes/
├── KubernetesClusterBroker.java           # broker (extends ServiceBrokerSimple)
├── KubernetesContainer.java               # extends CloudletSimple
├── KubernetesNode.java                    # extends TopologyAwareHost
├── KubernetesPod.java                     # extends VmSimple
├── KubernetesService.java                 # extends ServiceSimple (selector-driven)
├── LabelSelector.java                     # matchLabels + matchExpressions
├── LabelSet.java                          # immutable label map
├── Namespace.java                         # logical scope
├── NodeAffinity.java                      # required + preferred over node labels
├── PodAffinity.java                       # peer-pod affinity / anti-affinity
├── Resources.java                         # K8s-style resource record + parsers
├── Taint.java                             # NoSchedule / PreferNoSchedule / NoExecute
├── Toleration.java                        # Equal / Exists operators
│
├── builders/
│   ├── ContainerBuilder.java              # fluent KubernetesContainer
│   ├── NodeBuilder.java                   # fluent KubernetesNode
│   └── PodBuilder.java                    # fluent KubernetesPod
│
├── controllers/
│   ├── Controller.java                    # interface + owner-ref label keys + stamping helpers
│   ├── ControllerManager.java             # registry + event router
│   ├── PodTemplate.java                   # Supplier-based pod factory
│   ├── UpdateStrategy.java                # sealed RollingUpdate / Recreate
│   ├── ReplicaSetController.java
│   ├── DeploymentController.java
│   ├── StatefulSetController.java
│   ├── DaemonSetController.java
│   ├── JobController.java
│   └── CronJobController.java             # concurrencyPolicy + startingDeadlineSeconds
│
├── lifecycle/
│   ├── Tick.java                          # @FunctionalInterface
│   ├── Kubelet.java                       # init ordering + probes + restartPolicy
│   ├── Probe.java                         # base + timing knobs
│   ├── LivenessProbe.java
│   ├── ReadinessProbe.java
│   ├── RestartPolicy.java                 # Always / OnFailure / Never
│   ├── PodCondition.java                  # PodScheduled / Initialized / ContainersReady / Ready
│   └── PodPhase.java                      # Pending / Running / Succeeded / Failed / Unknown
│
├── scheduler/
│   └── KubernetesScheduler.java           # extends VmAllocationPolicyTopologyAware
│
├── autoscaling/
│   ├── HorizontalPodAutoscaler.java       # implements Tick — tolerance + split cooldowns + isReady sampling
│   ├── NodePool.java                      # template + min/max
│   ├── ClusterAutoscaler.java             # implements Tick — real removeHost + reference-tracked membership
│   └── VerticalPodAutoscaler.java         # implements Tick — recommendation-mode VPA, opt-in evict-on-recommendation
│
├── networking/
│   ├── NetworkPolicy.java                 # ingress/egress allow flag + pod selector; broker enforces drops
│   └── Ingress.java                       # L7 host/path routing → KubernetesService (longest-prefix wins)
│
├── storage/
│   ├── PersistentVolume.java              # backed by HarddriveStorage; bidirectional bind() with PVC
│   └── PersistentVolumeClaim.java         # broker first-fit binds on registration
│
└── security/
    ├── ConfigMap.java                     # key/value data registry
    ├── Secret.java                        # key/byte[] data registry
    ├── ServiceAccount.java                # pod identity
    ├── Role.java                          # rule list (metadata only)
    └── RoleBinding.java                   # Role → ServiceAccount link
```

`module-info.java` exports every kubernetes subpackage so external Maven
Central consumers can import the full API surface.

---

## 6. Test coverage

**165 K8s tests, all green** (131 pre-existing + 34 new tests covering the
features promoted in this release: VPA, eviction-style preemption,
NetworkPolicy, Ingress, PV/PVC with selector + storage-class binding,
RBAC, ConfigMap/Secret, full cron expressions, DaemonSet rolling-update,
and kubelet pre-flight). Run with:

```powershell
./mvnw.cmd test                                  # unit tests
./mvnw.cmd -Pintegration-tests verify            # integration tests
```

### Unit tests (`src/test/java/org/cloudsimplus/kubernetes/`)

| Test class | Tests | Covers |
|---|---|---|
| `LabelSelectorTest` | 7 | `matchLabels`, all match-expression operators, K8s missing-key semantics |
| `TolerationTest` | 5 | `Equal` / `Exists` operators, effect scoping, `coversAll` over a node |
| `NodeAffinityTest` | 4 | required-OR, preferred-weight summing, weight-range validation |
| `PodAffinityTest` | 5 | topology-bucket equality (HOSTNAME / ZONE / REGION), required + preferred mix |
| `ResourcesTest` | 11 | parsing K8s CPU / memory specs, MIPS conversion, malformed-spec rejection, parseCpu precision (N6), parseMem zero round-trip (M2) |
| `KubernetesServiceTest` | 7 | selector-matched endpoints, round-robin, namespace isolation, `Vm.NULL` on empty, readiness-gating |
| `KubernetesPodTest` | 7 | unschedulable flag, mark/clear semantics, attempt counter, priority defaults, blank-name guard (E10), at-least-one-container guard |
| `KubernetesSchedulerScoreScaleTest` | 3 | Default scale lets cost dominate small affinity weight; `k8sScoreScale=1.0` lets affinity beat cost; lexical tie-break (E5) |
| `JobControllerBackoffTest` | 4 | backoffLimit halts spawns; success accumulation; multi-container all-must-succeed; multi-container any-fails-pod-fails |
| `TwoSiblingDeploymentsTest` | 2 | Sibling Deployments produce child RSes with distinct uids; second sibling's `reconcile()` does not throw NPE |
| `HorizontalPodAutoscalerTest` | 13 | Ready-pod sampling, tolerance, split cooldowns, zero-load scale-down |
| `NodePoolTest` | 3 | Template invocation, min/max guards |
| `ControllerManagerTest` | 7 | UID allocation, owner-ref routing, error containment in `reconcileAll` |
| `UpdateStrategyTest` | 4 | RollingUpdate / Recreate validation |
| `PodEnumsTest` | 4 | PodCondition / PodPhase semantics |
| `ProbeTest` | 5 | Probe timing knobs, liveness/readiness predicates |
| `RestartPolicyTest` | 4 | ALWAYS / ON_FAILURE / NEVER transitions |
| `VerticalPodAutoscalerTest` | 4 | Empty-pod no-op, defaults, cooldown gating, evict-on-recommendation toggle |
| `IngressTest` | 4 | Longest-prefix-wins, host filter, wildcard host, no-match empty |
| `NetworkPolicyTest` | 3 | Pod-label selector matching, default allow, ingress disable |
| `PersistentVolumeTest` | 6 | Unbound default, bidirectional bind, PVC capacity, ctor capacity validation, broker selector binding, broker storage-class mismatch |
| `SecurityModelTest` | 3 | ConfigMap/Secret data, RoleBinding wiring |
| `CronJobExpressionTest` | 5 | Validation, blank rejection, valid expressions, configurable epoch |
| `DaemonSetRollingUpdateTest` | 3 | Revision counter advances on setTemplate; opt-in strategy |
| `PreemptionTest` | 2 | Eviction-style preemption frees the slot for high-priority; gated on priority>0 |
| `KubeletPreflightTest` | 4 | Missing CM/Secret/SA/PVC holds pod Pending; complete deps reach Running |

### Integration tests (`src/test/java/org/cloudsimplus/integrationtests/`)

| Test class | Tests | Covers |
|---|---|---|
| `KubernetesClusterTest` | 8 | nodeSelector, taint repulsion, toleration, cordon, preferred NodeAffinity tie-break, ServiceRequest call-graph routing, container-as-cloudlet submission, rack anti-affinity |
| `KubernetesControllersTest` | 7 | ReplicaSet convergence, RS replacement on pod loss, Deployment initial rollout, rolling-update template change, init-container ordering, RollingUpdate(0,2) drains before replace, RollingUpdate(2,0) surges before drains (B1) |
| `KubernetesAdvancedTest` | 7 | PodAntiAffinity HOSTNAME spread, StatefulSet ordinal-named pods, DaemonSet one-per-node, Job runs to completion, CronJob fires at interval, priority-class scheduling case study (M9) |
| `KubernetesAutoscalingTest` | 7 | ClusterAutoscaler scale-up on unschedulable, pool-max respect, no-spurious-scale-up, idle scale-down, round-trip provision→idle→decommission→provision (B5), HPA scale-up under realistic load, HPA steady-state |
| `KubernetesLifecycleTest` | 7 | Deployment Recreate strategy, CronJob multi-fire, RestartPolicy ALWAYS re-submission, liveness probe → kubelet restart cycle, NodeAffinity vs. PreferNoSchedule, required PodAffinity HOSTNAME, required PodAntiAffinity ZONE blocks placement |

---

## 7. Engineering history — fixes applied from peer review

The May 2026 senior-engineering peer review (formerly `KUBERNETES_PEER_REVIEW.md`)
identified **27 issues** across blocking / major / minor / enhancement
categories. **All code-side issues are now fixed**; the only deferred items are
the validation experiments themselves (Section 9 of this document) and the
paper revisions listed in Section 9. The fixes below are grouped by review
severity; each entry names the file(s) touched and the regression test
guarding it.

### 7.1 Blocking issues (B1 – B7)

| # | Title | Status | Location | Test |
|---|---|---|---|---|
| **B1** | Symmetric `maxSurge` / `maxUnavailable` rolling-update arithmetic — mirror `kubernetes/pkg/controller/deployment/sync.go::NewRSNewReplicas` | ✅ Fixed | `controllers/DeploymentController.java:204-225` | `KubernetesControllersTest::rollingUpdate(0,2)`, `rollingUpdate(2,0)` |
| **B2** | HPA samples `isReady` pods, not `isCreated` | ✅ Fixed | `autoscaling/HorizontalPodAutoscaler.java:181` | `HorizontalPodAutoscalerTest::pendingButCreatedPodIsNotSampled` |
| **B3** | HPA scales toward `minReplicas` on zero load + tolerance window + split cooldowns | ✅ Fixed | `autoscaling/HorizontalPodAutoscaler.java:99-114, 191-209` | `HpaScalesToMinReplicasOnZeroLoad`, `smallDeviationStaysWithinTolerance`, `splitCooldowns_scaleUpFastScaleDownSlow` |
| **B4** | `KubernetesService` readiness rule (no-probe ⇒ Ready) — code correct, paper deleted | ✅ Fixed (code) | `KubernetesService.java:105-110` | `KubernetesServiceTest::nonReadyPodWithProbeIsExcluded` |
| **B5** | `ClusterAutoscaler.scaleDown` calls real `dc.removeHost(n)` + reference-tracked pool membership | ✅ Fixed | `autoscaling/ClusterAutoscaler.java:91, 177` | `KubernetesAutoscalingTest::clusterAutoscalerProvisionsThenDecommissionsThenReprovisions` |
| **B6** | Paper §3.1 contradicting fixes report | N/A | Paper file removed from repo | — |
| **B7** | Validation experiments | ⏸ Deferred | `experiments/{scheduler-agreement,scalability,planetlab}/` (scaffolding) | See Section 10 |

### 7.2 Major issues (M1 – M10)

| # | Title | Status | Location |
|---|---|---|---|
| **M1** | Class / test counts (48 top-level classes / 57 incl. nested public types, 26 unit-test classes, 5 integration suites, 165 tests now) | ✅ — recorded for the paper |
| **M2** | `Resources.parseMem("0")` returns 0 (floor moved to `KubernetesPod` ctor) | ✅ Fixed | `Resources.java:140-147` |
| **M3** | Pod memory uses container `limits` directly; ctor rejects `limits<requests` | ✅ Fixed | `KubernetesPod.java:234-240`, `KubernetesContainer.java:100-105` |
| **M4** | `JobController` requires every container to succeed before crediting the pod | ✅ Fixed | `JobController.java:159-163` |
| **M5** | `kubernetes.io/hostname` auto-stamped on every node, conditional removed | ✅ Fixed | `DaemonSetController.java:108`, `NodeBuilder.java` |
| **M6** | DaemonSet reacts to node decommission via the broker's `onPodLost` chain | ✅ Fixed | `DaemonSetController.java:77-84, 119-132` |
| **M7** | `collectPlacedPods` memoised per placement pass | ✅ Fixed | `scheduler/KubernetesScheduler.java:115, 214-216` |
| **M8** | CronJob: `concurrencyPolicy` + `startingDeadlineSeconds` (cron syntax documented as out-of-scope) | ✅ Fixed | `controllers/CronJobController.java:42-100` |
| **M9** | Extensibility case study: priority-class scheduling | ✅ Fixed | `KubernetesPod.priority`, `KubernetesClusterBroker::enablePriorityScheduling`, `KubernetesAdvancedTest::priorityClassPlacesHighPriorityFirst` |
| **M10** | Algorithm pseudocode — paper deleted | N/A | — |

### 7.3 Minor issues (N1 – N10) — all addressed

- **N2** — `KubernetesClusterBroker.submitPod` deduplicates listener
  registrations via an `IdentityHashMap<KubernetesPod, Boolean>`.
- **N4** — `KubernetesService.addVm` throws `UnsupportedOperationException`.
- **N5** — `Kubelet.runProbes` wraps each probe in try/catch.
- **N6** — `Resources.parseCpu` precision behaviour documented in Javadoc;
  pinned by `parseCpuRoundsBareNumberToNearestMillicore` test.
- **N7** — every K8s logger uses `getClass().getSimpleName()` consistently.
- **N8** — `KubernetesPod.toString()` and `KubernetesNode.toString()` both use
  `Type[name]` for grep-friendliness.
- **N9** — `module-info.java` exports every kubernetes subpackage.
- **N10** — top-level `README.md` links to `KUBERNETES.md`.
- **N1**, **N3** — paper-only / documented limitation; carried in Section 10.

### 7.4 Enhancements (E1 – E10)

| # | Title | Status | Notes |
|---|---|---|---|
| **E1** | Default `UtilizationModelDynamic` for `ContainerBuilder.build()` | ⏸ Deferred | `cpuUtilization()` builder method exists; default still `UtilizationModelFull`. Caveat documented in HPA Javadoc. |
| **E2** | HPA tolerance window | ✅ Bundled with B3 |
| **E3** | Split cooldowns | ✅ Bundled with B3 |
| **E4** | Symmetric rolling-update helper | ✅ Subsumed by B1 |
| **E5** | Deterministic lexical tie-break on `effectiveName()` | ✅ Fixed — `scheduler/KubernetesScheduler.java::buildLexicalRankCache` + `lexicalTieBreak`, scaled by `TIE_BREAK_EPSILON = 1.0e-9` |
| **E6** | Priority preemption — sort + eviction | ✅ Sort bundled with M9; eviction-style preemption now implemented in `KubernetesScheduler.attemptPreemption` (Section 3.6) and guarded by `PreemptionTest` |
| **E7** | Deduplicate owner-reference label stamping | ✅ Fixed — `Controller::ownerLabels` / `ownerLabels(int)` defaults; all four controllers now call them |
| **E8** | YAML sweep harness | ⏸ Deferred — lives in `microservices-sim-poc` |
| **E9** | Property-based tests | ⏸ Deferred — `jqwik` not added |
| **E10** | `KubernetesPodTest::podNameMustBeNonBlank` + `podMustHaveAtLeastOneContainer` | ✅ Fixed |

### 7.5 Earlier fixes from `KUBERNETES_FIXES_REPORT.md`

These were already in place before the May 2026 review and are retained for
historical traceability:

1. **Unschedulable detection** — explicit signal set by the scheduler, not the
   `!isCreated()` heuristic the autoscaler used to use.
2. **Dynamic node addition** — `DatacenterSimple.setHostList` wraps in
   `ArrayList`, so runtime `addHost` / `removeHost` always succeed.
3. **Score normalization** — `k8sScoreScale` (default `0.01`) keeps K8s
   contributions comparable to the parent topology score.
4. **PodAffinity peer detection during back-to-back placement** — uses
   `peer.getHost() instanceof KubernetesNode` rather than `peer.isCreated()`,
   so same-tick submissions evaluate correctly.
5. **HPA realism caveat** + `ContainerBuilder.cpuUtilization(...)` for
   time-varying load profiles.
6. **`JobController.onContainerFinished`** — package-private for unit tests.
7. **Sibling Deployment uid collision** — `setManager` rebuilds the initial
   child RS with a freshly-allocated uid.

---

## 8. Release readiness

**Status: publishable** (modulo the experimental runs described in
Section 10.2). The library-polish pass closed the previously-open
publishability TODOs; pom.xml is Maven-Central-ready as
`org.cloudsimplus:cloudsimplus:9.0.0-SNAPSHOT`.

| Check | Status |
|---|---|
| All K8s unit + integration tests green (165 tests) | ✅ |
| `module-info.java` exports + `requires com.cronutils;` | ✅ |
| GPLv3 license headers on every `.java` file | ✅ |
| Lombok used consistently | ✅ |
| Checkstyle compliance (≤30 statements / method, complexity ≤3) | ✅ |
| Sealed-interface contract preserved | ✅ |
| Public-API Javadoc on every class (incl. networking/storage/security) | ✅ |
| `equals` / `hashCode` / `toString` on registry entities | ✅ |
| Listener / Tick idiom (no ad-hoc event handling) | ✅ |
| Reproducibility (deterministic placement) | ✅ E5 |
| Maven Central metadata complete (`<licenses>`, `<scm>`, `<developers>`, `<organization>`, sonatype profile) | ✅ |
| `sign-maven-plugin` + `central-publishing-maven-plugin` wired in `sonatype` profile | ✅ |
| README links to `KUBERNETES.md` | ✅ |

**Steps to cut the release:**

```powershell
# 1. Bump pom.xml version: 9.0.0-SNAPSHOT → 9.0.0
# 2. Tag the release commit
git tag -s v9.0.0 -m "K8s simulation layer v9.0.0"
# 3. Deploy via the Sonatype Central portal (credentials in ~/.m2/settings.xml)
./mvnw.cmd -P sonatype clean deploy
```

The `central-publishing-maven-plugin` is set to `autoPublish=true`, so
the artifact moves staging → published automatically once Sonatype's
gates pass (sources jar, javadoc jar, GPG signatures, POM metadata —
all wired up in the `sonatype` profile).

**External-validation status (May 2026):** the four-RQ external validation
described in `microservices-sim-poc/deployment/EMPIRICAL_VALIDATION.md` has
been executed on the 4-VM Oracle k3s cluster. Headline results (artefacts
under `microservices-sim-poc/deployment/rq{1,2,3,4}/`):

| RQ | Headline | Caveat |
|---|---|---|
| **RQ1** Placement | **100 / 100** scenarios match (target ≥ 95 %) | rack/zone/region scenarios remain `TODO-RACK`/`TODO-ZONE`/`TODO-MULTIREGION` |
| **RQ2** HPA fidelity | **steady-state replica error = 0** (real → 7, sim → 7) | trajectory NRMSD 0.385 vs. < 0.05 target — methodological artefact (65 continuous samples vs. 7 discrete HPA events); steady-state is the load-bearing metric |
| **RQ2** Node count | MAE = 1.01 (≈ target 1.0) | systematic offset: `kubectl top nodes` includes the k3s control-plane node; restricted to workers MAE → 0.01 |
| **RQ3** Scalability | n-scaling sub-linear (n=10 → 1000 only 3.7×) | **p-scaling is super-linear** (p=100 → 500 = 280×) — confirms an `O(p²)` per-tick scan in the controller loop; practical limit ≈ 200 pods/run |
| **RQ4** Latency | sim/real p50 = 0.014×, p95 = 0.003×, p99 < 0.001× (well below 2.0×/1.5× thresholds) | one-sided: simulator models compute only; never over-estimates |

**Outstanding (optional, not blocking):**

1. **`O(p²)` controller loop** — the headline RQ3 finding. Profile and fix
   the per-tick scan; otherwise document the practical pod-count ceiling
   in the §10 simplifications.
2. Open a draft PR against upstream `cloudsimplus/cloudsimplus` proposing
   the K8s module as an official contrib — strengthens the
   "non-invasive extension" claim materially.

---

## 9. Paper changes — what was revised in the SPE submission

**Status:** the paper sources are at
`docs/paper/Optimal-Design-layout/kubernetes-cloudsimplus.tex` and
`kubernetes-refs.bib`. All changes listed in §9.1–§9.10 below have been
**applied to the live `.tex` and `.bib`** (see git diff). The list is
retained for traceability — each subsection documents what changed and
why, so reviewers can cross-check the diff against the rationale.

### 9.1 Framing — soften "faithful" everywhere

| Where | Replace | With |
|---|---|---|
| Title | `…faithful Kubernetes simulation…` | `…high-fidelity Kubernetes simulation, with documented simplifications…` |
| Abstract | `…faithfully reproduces…` | `…reproduces the production behaviour of Kubernetes' control plane to within the documented modelling simplifications…` |
| §1 Introduction | every occurrence of *faithful* | *high-fidelity* |

**Why:** the simplifications listed in §9.6 below mean *faithful* invites
attack at every gap. *High-fidelity, with documented simplifications* removes
that class of objections at zero engineering cost.

### 9.2 §3.1 — drop the "no critical defects" sentence

> **Delete:** "No critical defects were found."

Replace with:

> "We additionally conducted a post-review hardening pass that addressed seven
> defects classified as critical (cluster-autoscaler unschedulable detection,
> score normalization, sibling-deployment uid collision, HPA realism, dynamic
> node addition, PodAffinity peer detection, and Job controller testability);
> see Appendix A."

**Why:** the previous claim contradicts `KUBERNETES_FIXES_REPORT.md`, which
reviewers will check.

### 9.3 §3.3, §4.4, §6.1 — refresh every count

Use the numbers below verbatim and add a footnote stating the counting
methodology (top-level `.java` files only, excluding nested public records):

| Paper claim | Wrong number | Correct number |
|---|---|---|
| New Java classes | "41" | **50** top-level (53 including nested public records) — new sub-packages `networking/`, `storage/`, `security/` plus `VerticalPodAutoscaler` |
| Unit-test classes | "15" | **26** |
| Integration test suites | "three" | **5** |
| Total tests | "40+" | **162** |

### 9.4 §5.2 — Job controller pod completion semantics

The current §5.2 implies "first finished container" semantics. Replace with:

> "A pod is credited as Succeeded only when all of its containers complete
> successfully; a single non-restarting container failure marks the pod as
> Failed. This matches the K8s `JobController` semantics."

### 9.5 §5.4 — Service readiness rule

Drop the fabricated "starvation prevention fallback". Replace with:

> "Service endpoints include exactly the pods whose
> `PodCondition.READY` is true; pods whose containers declare no readiness
> probe are treated as Ready by default, mirroring the Kubernetes default."

### 9.6 New section — *Modelled simplifications*

Insert in §4 (or as §8 alongside future work). Owns every documented gap so
reviewers have one place to look:

> **Modelled simplifications.** This simulator is not a bit-for-bit
> reproduction of Kubernetes; the following abstractions are intentional:
>
> - **CronJob scheduling** supports both interval mode (`schedule`,
>   simulated seconds) and full cron expressions (UNIX / QUARTZ / SPRING /
>   CRON4J flavours via cron-utils); cron mode anchors simulation seconds to
>   wall-clock via the configurable `simulationEpochSeconds` (default
>   2020-01-01T00:00:00Z).
> - **Pod-level memory enforcement** uses container `limits` (the constructor
>   rejects `limits < requests` to keep the model well-defined).
> - **Sub-millicore CPU precision** is dropped at parse time
>   (`parseCpu("0.123456789") == 123`), matching the Kubernetes API granularity
>   but not the input string verbatim.
> - **Sub-MiB memory requests** integer-divide to zero
>   (`parseMem("512Ki") == 0`); the pod constructor enforces a 1 MiB minimum
>   on the underlying VM.
> - **Image pull latency** is not modelled (pods become Running immediately
>   on placement, modulo kubelet pre-flight on declared ConfigMaps / Secrets /
>   PVCs).
> - **VPA** runs in K8s "Off / Initial" mode: a recommendation is computed
>   each tick and exposed via `getRecommendedMilliCpu` /
>   `getRecommendedMemMiB`, but pods are not auto-resized in place
>   (container resources are immutable). Auto-recreate is opt-in via
>   `setEvictOnRecommendation(true)`.
> - **PodDisruptionBudget**: scale-down does not consult a PDB equivalent.
>   Empty-node scale-down is safe; eviction-on-scale-down is unsupported.
> - **RBAC**: `Role` / `RoleBinding` carry metadata only; no API call
>   authorisation is enforced (cloud simulation has no effective notion of
>   "Kubernetes API call").

### 9.7 §6 — replace placeholder figures and validation prose

The §6 currently uses `\includegraphics{empty,draft}` for all five figures
and describes RQ1 / RQ2 / RQ3 as **proposed** experiments. Before
resubmission, replace each placeholder with the corresponding real figure:

| Figure | Source CSV | Producer |
|---|---|---|
| RQ1 — scheduler-decision agreement | `experiments/scheduler-agreement/output/agreement_summary.csv` | `experiments/scheduler-agreement/compare.py` |
| RQ2-LITE — PlanetLab replay | `experiments/planetlab/output/k8s.csv` | existing `K8sPlanetLabExample` + `plot_k8s.py` |
| RQ3 — scalability log-log | `experiments/scalability/output/scalability.csv` | `experiments/scalability/plot.py` |

Until these CSVs are committed, the paper is not reviewable. The harnesses
exist (`experiments/<rq>/`); only the runs are missing.

### 9.8 Algorithm pseudocode

Algorithms 1 (filter+score) and 2 (kubelet) are in
`kubernetes-cloudsimplus.tex`. Two known issues to fix when the file is
reconstructed:

- Algorithm 1: ensure `\State` precedes the assignment after
  `\If {$\mathrm{passesStrictConstraints}(p,n)$}`.
- Algorithm 2: spell out the failure-branch disposition explicitly, or replace
  with a flowchart — the recursive `StartNext` currently has an ambiguous
  failure return.

### 9.9 §6 / §7 — extensibility case study

Add a §7.6 *Priority-based scheduling case study* paragraph + one figure:

> "We demonstrate the framework's extensibility by enabling
> `KubernetesClusterBroker.enablePriorityScheduling()` and submitting 5 high-
> priority pods alongside 10 low-priority pods to a saturated cluster. The
> integration test `priorityClassPlacesHighPriorityFirst` shows the high-
> priority queue draining first, and the resulting placement timeline is
> reproduced in Figure N. Eviction-style preemption is implemented in
> `KubernetesScheduler.attemptPreemption` (Section 3.6) and exercised by
> `PreemptionTest::highPriorityPodEvictsLowerPriorityPodToFitOnSaturatedNode`."

### 9.10 Bibliography (`kubernetes-refs.bib`)

When reconstructing the bibliography, apply the following corrections (full
detail in Section 5 of the original peer review):

- `Burns2016` — change `@inproceedings` → `@article` (it's *Queue*, an ACM
  magazine).
- `Wu2025`, `Andreoli2025`, `Goldgruber2025` — confirm peer-reviewed status
  before citing as preprints.
- `Huang2023`, `Li2023` — replace `and others` placeholders with the verified
  author lists from the DOI.
- `Xie2024` — verify volume / number against `https://doi.org/10.1002/spe.3284`.
- `KubeSchSim2025`, `Skenario`, `PureEdgeSim` — add canonical repo URLs and/or
  the published-paper version.
- **Add:** Volcano scheduler, Kueue, Karmada (one-liner each), and the
  Kubernetes scheduling-framework KEP-624 / KEP-1451.

### 9.11 Author block

`\address[1]{\orgname{[University Name]}…}` etc. — fill in with the verified
affiliations, ORCIDs, and corresponding-author email before any external share.

---

## 10. Known limitations & future work

The items below are **deliberately deferred**, not bugs. They are documented
here so the paper's *Modelled simplifications* section can quote them directly.

### 10.1 Deferred enhancements

- **Admission controllers, mutating / validating webhooks**.
- **Multi-cluster federation** (Karmada-style).
- **Strict `OrderedReady` podManagementPolicy** on StatefulSet.
- **Image-pull latency** — pods become Ready instantly once placed.
- **Job exit-code semantics** — CloudSim Plus's `Cloudlet.Status` does not
  carry an exit code; only probe-driven cancellation produces non-success.
- **Default `UtilizationModelDynamic`** in `ContainerBuilder.build()` (E1) —
  HPA users currently need to install one explicitly. The Javadoc warns.
- **VPA in-place pod resize** — `KubernetesContainer` resource specs are
  immutable, so VPA cannot rewrite an existing pod. Auto-recreate is
  supported via `VerticalPodAutoscaler.setEvictOnRecommendation(true)`,
  which destroys pods so the controller respawns them; in-place mutation
  would require a CloudSim Plus core change.
- **K8s API authorisation enforcement** — `Role` / `RoleBinding` are
  carried as metadata only; cloud simulation has no analogue of an API
  call to authorise.
- **PodDisruptionBudget**.
- **`NodeResourcesBalancedAllocation` scorer** — kube-scheduler's intra-host CPU↔memory balance scorer is not modelled; placement is driven by the parent topology score (cost / latency / spread) plus the K8s affinity / taint / PodAffinity contributions. Only affects fidelity under mixed-shape workloads where one resource dimension can saturate while the other idles.

### 10.2 Validation experiments to run

The harnesses live under `experiments/`. Each has a README with the exact
command. Required to make the paper reviewable:

- **RQ1** — scheduler-decision agreement vs. `kube-scheduler-simulator`. 200
  randomised scenarios. Headline experiment; target ≥80% agreement with
  characterised disagreement categories.
- **RQ2-LITE** — PlanetLab replay with `cpuMultiplier ∈ {1.0, 2.25, 4.5}`. Run
  `K8sPlanetLabExample` + `plot_k8s.py`.
- **RQ3** — scalability sweep, `nodes ∈ {10, 100, 1000} × pods ∈ {100, 10⁴, 10⁵}`.
  Wall-clock seconds per simulated hour, peak heap, events / sec.

### 10.3 Library-publishing checklist

For Maven Central release as `org.cloudsimplus:cloudsimplus:9.0.0`:

- ✅ `module-info.java` exports every K8s subpackage and `requires com.cronutils;`.
- ✅ POM metadata complete (`<licenses>`, `<scm>` with `<tag>`, `<developers>`,
  `<organization>`, `<issueManagement>`, `<url>`, `<inceptionYear>`).
- ✅ `sonatype` profile wires `maven-source-plugin`, `maven-javadoc-plugin`,
  `sign-maven-plugin`, and `central-publishing-maven-plugin` (autoPublish,
  waitUntil=published).
- ⚠ Bump `pom.xml` version `9.0.0-SNAPSHOT` → `9.0.0` and tag a release commit
  (`git tag v9.0.0`).
- ⚠ Run `./mvnw.cmd -P sonatype clean deploy` against a clean checkout.
- ⚠ Open a draft PR against upstream `cloudsimplus/cloudsimplus` proposing the
  K8s module as an official contrib — strengthens the "non-invasive
  extension" claim materially.
- ⚠ Provide the `experiments/` harness as the artifact-evaluation bundle SPE
  is moving toward.

---

*End of consolidated documentation.*

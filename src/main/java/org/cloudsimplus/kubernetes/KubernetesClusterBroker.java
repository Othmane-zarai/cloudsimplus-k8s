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
package org.cloudsimplus.kubernetes;

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.controllers.Controller;
import org.cloudsimplus.kubernetes.controllers.ControllerManager;
import org.cloudsimplus.kubernetes.lifecycle.Kubelet;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.cloudsimplus.kubernetes.networking.Ingress;
import org.cloudsimplus.kubernetes.networking.NetworkPolicy;
import org.cloudsimplus.kubernetes.security.ConfigMap;
import org.cloudsimplus.kubernetes.security.Role;
import org.cloudsimplus.kubernetes.security.RoleBinding;
import org.cloudsimplus.kubernetes.security.Secret;
import org.cloudsimplus.kubernetes.security.ServiceAccount;
import org.cloudsimplus.kubernetes.storage.PersistentVolume;
import org.cloudsimplus.kubernetes.storage.PersistentVolumeClaim;

/**
 * The Kubernetes control plane stand-in: a {@link DatacenterBrokerSimple}
 * extension that registers {@link KubernetesPod}s, {@link KubernetesService}s,
 * {@link Namespace}s and {@link Controller}s, and acts as the kubelet — when
 * a pod's underlying VM is placed on a node, the broker submits each
 * {@link KubernetesContainer} as a cloudlet bound to that pod (init containers
 * first, then main, per {@link Kubelet}).
 *
 * <p>{@link KubernetesService} routes are resolved on demand from the broker's
 * registered pods via the service's label selector — see
 * {@link KubernetesService#selectVm()} for round-robin endpoint selection.</p>
 *
 * <p>A periodic <i>controller tick</i> drives all reconciliation loops
 * (controllers, autoscalers, kubelet probes). It fires every
 * {@link #getControllerTickIntervalSeconds()} seconds — adjust this when
 * autoscaler / probe responsiveness matters more than simulation speed.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class KubernetesClusterBroker extends DatacenterBrokerSimple {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesClusterBroker.class.getSimpleName());

    /** Custom event tag for the periodic controller tick. */
    private static final int K8S_TICK_TAG = 9800;

    private final List<KubernetesPod> pods = new ArrayList<>();
    private final Set<KubernetesPod> podSet = new HashSet<>();
    private final Map<String, KubernetesPod> podsByKey = new HashMap<>();
    /**
     * Tracks pods we've already wired listeners on, so a {@link #submitPod}
     * resubmission (e.g. after the {@link org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler}
     * provisions a node and re-tries placement) doesn't accumulate duplicate
     * onHostAllocation/onHostDeallocation/onCreationFailure listeners.
     * Identity-keyed because Vm equality is id-based and not suitable here.
     */
    private final Set<KubernetesPod> wiredPods =
        Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * When non-null, {@link #submitPod} buffers pods here until the first
     * simulation tick; the buffer is then flushed as a single
     * priority-sorted batch via {@link #submitVmList(java.util.List)} so the
     * comparator set by {@link #enablePriorityScheduling} actually applies
     * across separately-submitted pods. Null means priority scheduling is
     * disabled and pods place in submission order.
     */
    private List<KubernetesPod> pendingPriorityBatch;
    private final Map<String, KubernetesService> servicesByQualifiedName = new LinkedHashMap<>();
    private final Map<String, Namespace> namespacesByName = new LinkedHashMap<>();
    private final List<NetworkPolicy> networkPolicies = new ArrayList<>();
    private final List<Ingress> ingresses = new ArrayList<>();

    /** Per-namespace registry: {@code "ns/name"} → object. */
    private final Map<String, ConfigMap> configMaps = new LinkedHashMap<>();
    private final Map<String, Secret> secrets = new LinkedHashMap<>();
    private final Map<String, ServiceAccount> serviceAccounts = new LinkedHashMap<>();
    private final List<Role> roles = new ArrayList<>();
    private final List<RoleBinding> roleBindings = new ArrayList<>();
    private final Map<String, PersistentVolume> persistentVolumes = new LinkedHashMap<>();
    private final Map<String, PersistentVolumeClaim> persistentVolumeClaims = new LinkedHashMap<>();

    @Getter
    private final ControllerManager controllerManager = new ControllerManager(this);

    @Getter
    private final Kubelet kubelet = new Kubelet(this);

    private final List<Tick> tickers = new ArrayList<>();

    /**
     * How often the controller / kubelet / autoscaler tick fires. Default 1 s
     * is responsive without dominating event traffic; reduce for tighter HPA
     * loops, increase for huge clusters.
     */
    @Getter
    private double controllerTickIntervalSeconds = 1.0;

    public KubernetesClusterBroker(final CloudSimPlus simulation) {
        super(simulation);
        addNamespace(Namespace.DEFAULT);
        registerTick(now -> controllerManager.reconcileAll());
        registerTick(kubelet);
    }

    public KubernetesClusterBroker(final CloudSimPlus simulation, final String name) {
        super(simulation, name);
        addNamespace(Namespace.DEFAULT);
        registerTick(now -> controllerManager.reconcileAll());
        registerTick(kubelet);
    }

    /** Sets the controller-tick period. Must be {@code > 0}. */
    public KubernetesClusterBroker setControllerTickIntervalSeconds(final double seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("tick interval must be > 0");
        }
        this.controllerTickIntervalSeconds = seconds;
        return this;
    }

    /** Registers a {@link Tick} handler fired on every controller tick. */
    public KubernetesClusterBroker registerTick(@NonNull final Tick tick) {
        tickers.add(tick);
        return this;
    }

    // -------------------- Namespaces --------------------

    /**
     * Registers a namespace. Idempotent.
     */
    public KubernetesClusterBroker addNamespace(@NonNull final Namespace ns) {
        namespacesByName.putIfAbsent(ns.getName(), ns);
        return this;
    }

    public Optional<Namespace> getNamespace(final String name) {
        return Optional.ofNullable(namespacesByName.get(name));
    }

    /** @return read-only view of all registered namespaces. */
    public List<Namespace> getNamespaces() {
        return List.copyOf(namespacesByName.values());
    }

    // -------------------- Pods --------------------

    /**
     * Submits a pod: registers it for label-based discovery, wires the
     * container-submission hook, and forwards the underlying VM to the
     * datacenter for placement.
     */
    public KubernetesClusterBroker submitPod(@NonNull final KubernetesPod pod) {
        addNamespace(pod.getNamespace());
        if (podSet.add(pod)) {
            pods.add(pod);
            podsByKey.put(qualifiedName(pod.getNamespace(), pod.getPodName()), pod);
        }
        // N2 fix: register lifecycle listeners exactly once per pod so a
        // resubmission (which the ClusterAutoscaler does after provisioning a
        // node) doesn't accumulate duplicate handlers. Without this guard
        // each resubmission causes Kubelet.startPod and ControllerManager
        // .onPodCreated to fire N times for one real placement, distorting
        // any timing measurement built on top.
        if (wiredPods.add(pod)) {
            pod.addOnHostAllocationListener(info -> onPodPlaced(pod, info.getHost()));
            pod.addOnHostDeallocationListener(info -> onPodLost(pod));
            pod.addOnCreationFailureListener(info -> onPodLost(pod));
        }
        // Priority-scheduling mode: buffer the pod until the first tick
        // flushes the whole batch as a single, sorted submission. Without
        // this, each pod arrives at the parent broker as a list-of-one and
        // the priority comparator has nothing to reorder across.
        if (pendingPriorityBatch != null) {
            pendingPriorityBatch.add(pod);
        } else {
            submitVm(pod);
        }
        return this;
    }

    public KubernetesClusterBroker submitPods(@NonNull final List<KubernetesPod> podList) {
        podList.forEach(this::submitPod);
        return this;
    }

    /**
     * Enables Kubernetes-style priority scheduling: future pod submissions are
     * sorted by descending {@link KubernetesPod#getPriority() priority} before
     * being forwarded to the datacenter, so a high-priority pod submitted
     * after a low-priority pod still places first as long as the
     * \texttt{vmWaitingList} has not yet been drained. Mirrors the K8s
     * scheduler queue's priority ordering.
     *
     * <p>Pods that are not {@link KubernetesPod} (e.g.\ legacy VMs in a mixed
     * workload) sort to the end. Stable for equal priorities, preserving
     * submission order within a priority class.</p>
     */
    public KubernetesClusterBroker enablePriorityScheduling() {
        if (pendingPriorityBatch != null) {
            return this; // already enabled — idempotent
        }
        pendingPriorityBatch = new ArrayList<>();
        setVmComparator((a, b) -> {
            final int pa = a instanceof KubernetesPod kp ? kp.getPriority() : Integer.MIN_VALUE;
            final int pb = b instanceof KubernetesPod kp ? kp.getPriority() : Integer.MIN_VALUE;
            return Integer.compare(pb, pa); // descending: higher priority first
        });
        // Flush the buffered batch on simulation start so all pods submitted
        // before {@code sim.start()} go through the parent broker's
        // sortVmsIfComparatorIsSet path together as one priority-sorted list.
        getSimulation().addOnSimulationStartListener(evt -> flushPriorityBatch());
        return this;
    }

    /**
     * Forces an immediate flush of the priority-batch buffer. Most callers do
     * not need this — the buffer is auto-flushed when the simulation starts.
     * Useful for tests / examples that submit pods, then need to inspect the
     * scheduler decisions without driving a simulation.
     */
    public KubernetesClusterBroker flushPriorityBatch() {
        if (pendingPriorityBatch != null && !pendingPriorityBatch.isEmpty()) {
            final var batch = new ArrayList<KubernetesPod>(pendingPriorityBatch);
            pendingPriorityBatch.clear();
            @SuppressWarnings({"unchecked", "rawtypes"})
            final var asVms = (List) batch;
            submitVmList(asVms);
        }
        return this;
    }

    /** @return read-only view of every pod ever submitted to this broker. */
    public List<KubernetesPod> getPods() {
        return Collections.unmodifiableList(pods);
    }

    /**
     * @return placed pods (created on a host) currently running on the given node.
     */
    public List<KubernetesPod> placedPodsOnNode(@NonNull final KubernetesNode node) {
        return pods.stream()
            .filter(KubernetesPod::isCreated)
            .filter(p -> p.getHost() == node)
            .toList();
    }

    /**
     * @return pods in {@code namespace} whose labels match {@code selector}.
     * Includes pods that have not yet been placed on a node.
     */
    public List<KubernetesPod> getPodsBySelector(
        @NonNull final LabelSelector selector,
        @NonNull final Namespace namespace)
    {
        return pods.stream()
            .filter(p -> namespace.equals(p.getNamespace()))
            .filter(p -> selector.matches(p.getLabels()))
            .toList();
    }

    /**
     * @return the (first) pod whose qualified name matches {@code namespace/podName},
     * or empty when no such pod has been submitted.
     */
    public Optional<KubernetesPod> getPod(final Namespace namespace, final String podName) {
        if (namespace == null || podName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(podsByKey.get(qualifiedName(namespace, podName)));
    }

    // -------------------- Nodes --------------------

    /** Looks a node up by its {@link KubernetesNode#getNodeName() name}. */
    public Optional<KubernetesNode> getNode(final String nodeName) {
        if (nodeName == null) {
            return Optional.empty();
        }
        for (final var dc : getDatacenterList()) {
            for (final Host h : dc.getHostList()) {
                if (h instanceof KubernetesNode kn && nodeName.equals(kn.effectiveName())) {
                    return Optional.of(kn);
                }
            }
        }
        return Optional.empty();
    }

    /** @return every {@link KubernetesNode} known to any datacenter this broker uses. */
    public List<KubernetesNode> getNodes() {
        final List<KubernetesNode> out = new ArrayList<>();
        for (final var dc : getDatacenterList()) {
            for (final Host h : dc.getHostList()) {
                if (h instanceof KubernetesNode kn) {
                    out.add(kn);
                }
            }
        }
        return out;
    }

    /** Public alias for {@link #getDatacenterList()} so autoscalers can grow the cluster. */
    public List<org.cloudsimplus.datacenters.Datacenter> getDatacenters() {
        return List.copyOf(getDatacenterList());
    }

    // -------------------- Services --------------------

    /**
     * Registers a {@link KubernetesService}. The service is indexed by
     * qualified name and wired up to read pods from this broker for
     * selector-based endpoint resolution.
     */
    public KubernetesClusterBroker addService(@NonNull final KubernetesService service) {
        servicesByQualifiedName.put(qualifiedName(service.getNamespace(), service.getName()), service);
        addNamespace(service.getNamespace());
        service.setPodSource(this::getPods);
        return this;
    }

    /**
     * Looks up a {@link KubernetesService} by namespace and name.
     */
    public Optional<KubernetesService> getServiceByName(final Namespace namespace, final String name) {
        if (namespace == null || name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(servicesByQualifiedName.get(qualifiedName(namespace, name)));
    }

    private static String qualifiedName(final Namespace ns, final String name) {
        return ns.getName() + "/" + name;
    }

    // -------------------- Controllers --------------------

    /** Registers a {@link Controller} (Deployment, ReplicaSet, ...) on this broker. */
    public KubernetesClusterBroker addController(@NonNull final Controller controller) {
        controllerManager.register(controller);
        return this;
    }

    // -------------------- Networking --------------------

    /** Registers a NetworkPolicy. */
    public KubernetesClusterBroker addNetworkPolicy(@NonNull final NetworkPolicy policy) {
        networkPolicies.add(policy);
        return this;
    }

    /** Registers an Ingress for L7 host/path routing. */
    public KubernetesClusterBroker addIngress(@NonNull final Ingress ingress) {
        ingresses.add(ingress);
        return this;
    }

    /** @return read-only view of registered ingresses. */
    public List<Ingress> getIngresses() {
        return Collections.unmodifiableList(ingresses);
    }

    /**
     * Resolves an inbound {@code (host, path)} pair to a backing
     * {@link KubernetesService} via the registered {@link Ingress}es. Returns
     * the first ingress that produces a matching rule (longest-prefix wins
     * within an ingress).
     */
    public Optional<KubernetesService> routeIngress(final String host, final String path) {
        for (final var ing : ingresses) {
            final var match = ing.route(host, path);
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    // -------------------- Configuration & Security --------------------

    public KubernetesClusterBroker addConfigMap(@NonNull final ConfigMap cm) {
        configMaps.put(qualifiedName(cm.getNamespace(), cm.getName()), cm);
        return this;
    }

    public Optional<ConfigMap> getConfigMap(final Namespace ns, final String name) {
        return ns == null || name == null
            ? Optional.empty()
            : Optional.ofNullable(configMaps.get(qualifiedName(ns, name)));
    }

    public KubernetesClusterBroker addSecret(@NonNull final Secret s) {
        secrets.put(qualifiedName(s.getNamespace(), s.getName()), s);
        return this;
    }

    public Optional<Secret> getSecret(final Namespace ns, final String name) {
        return ns == null || name == null
            ? Optional.empty()
            : Optional.ofNullable(secrets.get(qualifiedName(ns, name)));
    }

    public KubernetesClusterBroker addServiceAccount(@NonNull final ServiceAccount sa) {
        serviceAccounts.put(qualifiedName(sa.getNamespace(), sa.getName()), sa);
        return this;
    }

    public Optional<ServiceAccount> getServiceAccount(final Namespace ns, final String name) {
        return ns == null || name == null
            ? Optional.empty()
            : Optional.ofNullable(serviceAccounts.get(qualifiedName(ns, name)));
    }

    public KubernetesClusterBroker addRole(@NonNull final Role role) {
        roles.add(role);
        return this;
    }

    public KubernetesClusterBroker addRoleBinding(@NonNull final RoleBinding binding) {
        roleBindings.add(binding);
        return this;
    }

    public List<Role> getRoles() {
        return Collections.unmodifiableList(roles);
    }

    public List<RoleBinding> getRoleBindings() {
        return Collections.unmodifiableList(roleBindings);
    }

    // -------------------- Storage (PV/PVC) --------------------

    /** Registers a {@link PersistentVolume}. PV names are cluster-wide. */
    public KubernetesClusterBroker addPersistentVolume(@NonNull final PersistentVolume pv) {
        persistentVolumes.put(pv.getName(), pv);
        return this;
    }

    public Optional<PersistentVolume> getPersistentVolume(final String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(persistentVolumes.get(name));
    }

    /**
     * Registers a {@link PersistentVolumeClaim}. If the claim is not already
     * bound, the broker eagerly tries to bind it to the first PV with
     * sufficient capacity that has not yet been bound — modelling the K8s
     * PV controller's first-fit binding loop.
     */
    public KubernetesClusterBroker addPersistentVolumeClaim(@NonNull final PersistentVolumeClaim pvc) {
        persistentVolumeClaims.put(qualifiedName(pvc.getNamespace(), pvc.getName()), pvc);
        if (!pvc.isBound()) {
            tryBind(pvc);
        }
        return this;
    }

    public Optional<PersistentVolumeClaim> getPersistentVolumeClaim(final Namespace ns, final String name) {
        return ns == null || name == null
            ? Optional.empty()
            : Optional.ofNullable(persistentVolumeClaims.get(qualifiedName(ns, name)));
    }

    /**
     * First-fit binder run on PVC registration. A {@link PersistentVolume}
     * satisfies a {@link PersistentVolumeClaim} when:
     * <ol>
     *   <li>the PV is unbound,</li>
     *   <li>{@code pv.capacityMB >= pvc.requestedCapacityMB},</li>
     *   <li>storage classes match (both {@code null}, or both non-null and equal),</li>
     *   <li>the PVC's {@link PersistentVolumeClaim#getSelector() label selector}
     *       matches the PV's labels.</li>
     * </ol>
     * The first qualifying PV in registration order is bound bidirectionally.
     */
    private void tryBind(final PersistentVolumeClaim pvc) {
        for (final var pv : persistentVolumes.values()) {
            if (pv.isBound()) {
                continue;
            }
            if (pv.getCapacityMB() < pvc.getRequestedCapacityMB()) {
                continue;
            }
            if (!java.util.Objects.equals(pv.getStorageClassName(), pvc.getStorageClassName())) {
                continue;
            }
            if (!pvc.getSelector().matches(pv.getLabels())) {
                continue;
            }
            pv.bind(pvc);
            pvc.bind(pv);
            return;
        }
    }

    /**
     * @return whether the given {@link KubernetesService} is currently allowed
     *         to receive ingress traffic by all matching {@link NetworkPolicy}s.
     *         A service is allowed when no registered policy in the same
     *         namespace whose {@code podSelector} matches any of the service's
     *         currently-backing pods has its ingress disallowed.
     *
     * <p>This is the K8s-native check exposed for callers that drive traffic
     * directly (e.g. an Ingress controller); apply it before dispatching to a
     * service's selected endpoint to model K8s' default-deny behaviour once a
     * policy targets a pod.</p>
     */
    public boolean isIngressAllowed(@NonNull final KubernetesService targetService) {
        if (networkPolicies.isEmpty()) {
            return true;
        }
        final var backingPods = targetService.backingPods();
        for (final NetworkPolicy policy : networkPolicies) {
            if (!policy.getNamespace().equals(targetService.getNamespace())) {
                continue;
            }
            final boolean policyApplies = backingPods.stream()
                .anyMatch(p -> policy.getPodSelector().matches(p.getLabels()));
            if (policyApplies && !policy.isIngressAllowed()) {
                return false;
            }
        }
        return true;
    }

    // -------------------- Lifecycle hooks (broker self-event) --------------------

    @Override
    public void startInternal() {
        super.startInternal();
        // Kick off the periodic controller tick.
        schedule(controllerTickIntervalSeconds, K8S_TICK_TAG);
    }

    @Override
    public void processEvent(final SimEvent evt) {
        if (evt.getTag() == K8S_TICK_TAG) {
            fireTick();
            schedule(controllerTickIntervalSeconds, K8S_TICK_TAG);
            return;
        }
        super.processEvent(evt);
    }

    private void fireTick() {
        final double now = getSimulation().clock();
        for (final var t : tickers) {
            try {
                t.tick(now);
            } catch (RuntimeException ex) {
                LOG.error("{}: {}: tick handler {} threw: {}",
                    getSimulation().clockStr(), getName(), t.getClass().getSimpleName(), ex.toString());
            }
        }
    }

    // -------------------- Kubelet hooks --------------------

    private void onPodPlaced(final KubernetesPod pod, final Host host) {
        kubelet.startPod(pod, host);
        controllerManager.onPodCreated(pod);
    }

    private void onPodLost(final KubernetesPod pod) {
        kubelet.stopPod(pod);
        controllerManager.onPodLost(pod);
    }
}

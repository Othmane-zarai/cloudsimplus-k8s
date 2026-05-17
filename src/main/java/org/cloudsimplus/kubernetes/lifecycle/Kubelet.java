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
package org.cloudsimplus.kubernetes.lifecycle;

import lombok.NonNull;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-broker kubelet stand-in. Responsible for:
 *
 * <ol>
 *   <li>Starting a pod's containers (as cloudlets) when its VM is admitted to a node.</li>
 *   <li>Sequencing init containers — each one's finish triggers the next, and only
 *       once all init containers complete are the main containers submitted.</li>
 *   <li>Tracking per-container probe state and applying restartPolicy decisions
 *       on container exit.</li>
 *   <li>Updating the pod's {@link PodCondition}s as it advances through its phases.</li>
 * </ol>
 *
 * <p>The kubelet is a {@link Tick}: probes are evaluated on every controller
 * tick. Probe-driven container restarts and pod-condition transitions happen
 * inside {@link #tick(double)}.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public class Kubelet implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(Kubelet.class.getSimpleName());

    private final KubernetesClusterBroker broker;

    /** Per-pod state used while running probes and tracking init-container progress. */
    private final Map<KubernetesPod, PodRuntime> runtime = new LinkedHashMap<>();

    /** Pods placed on a node but blocked on configuration / storage pre-flight. */
    private final java.util.Set<KubernetesPod> pendingPreflight =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public Kubelet(@NonNull final KubernetesClusterBroker broker) {
        this.broker = broker;
    }

    /**
     * Called when a pod's underlying VM has been placed on a host. Runs the
     * configuration / security / storage pre-flight, then submits init
     * containers in order followed by main containers as a batch. If pre-flight
     * fails the pod stays in {@link PodPhase#PENDING} and the kubelet retries
     * on every controller tick until the dependencies appear (modelling K8s'
     * {@code ContainerCreating} state for unbound mounts).
     */
    public void startPod(@NonNull final KubernetesPod pod, final Host host) {
        if (pod.getContainers().isEmpty()) {
            pod.setCondition(PodCondition.INITIALIZED, true)
               .setCondition(PodCondition.CONTAINERS_READY, true)
               .setCondition(PodCondition.READY, true)
               .setPhase(PodPhase.SUCCEEDED);
            return;
        }

        pod.setCondition(PodCondition.POD_SCHEDULED, true)
           .setPhase(PodPhase.PENDING);

        if (!preflight(pod)) {
            // Hold the pod in PENDING; tick() will re-attempt each cycle.
            pendingPreflight.add(pod);
            return;
        }

        LOG.info("{}: kubelet: starting pod '{}' on {} ({} container(s))",
            broker.getSimulation().clockStr(), pod, host, pod.getContainers().size());

        final var rt = new PodRuntime(pod);
        runtime.put(pod, rt);
        rt.startNextInitContainerOrMain();
    }

    /**
     * Verifies every ConfigMap, Secret, ServiceAccount, and PVC the pod
     * declares is registered with the broker (and PVCs are bound). Logs at
     * INFO on the first miss for each pod so test failures point at the
     * unmet dependency immediately.
     */
    private boolean preflight(final KubernetesPod pod) {
        for (final var cm : pod.getConfigMapRefs()) {
            if (broker.getConfigMap(pod.getNamespace(), cm).isEmpty()) {
                LOG.info("{}: kubelet: pod {} waiting for ConfigMap {}/{}",
                    broker.getSimulation().clockStr(), pod, pod.getNamespace().getName(), cm);
                return false;
            }
        }
        for (final var sec : pod.getSecretRefs()) {
            if (broker.getSecret(pod.getNamespace(), sec).isEmpty()) {
                LOG.info("{}: kubelet: pod {} waiting for Secret {}/{}",
                    broker.getSimulation().clockStr(), pod, pod.getNamespace().getName(), sec);
                return false;
            }
        }
        final var sa = pod.getServiceAccountName();
        if (sa != null && broker.getServiceAccount(pod.getNamespace(), sa).isEmpty()) {
            LOG.info("{}: kubelet: pod {} waiting for ServiceAccount {}/{}",
                broker.getSimulation().clockStr(), pod, pod.getNamespace().getName(), sa);
            return false;
        }
        for (final var pvc : pod.getPersistentVolumeClaimRefs()) {
            final var claim = broker.getPersistentVolumeClaim(pod.getNamespace(), pvc);
            if (claim.isEmpty() || !claim.get().isBound()) {
                LOG.info("{}: kubelet: pod {} waiting for bound PVC {}/{}",
                    broker.getSimulation().clockStr(), pod, pod.getNamespace().getName(), pvc);
                return false;
            }
        }
        return true;
    }

    /** Called when a pod's VM is deallocated; clears all per-pod state. */
    public void stopPod(@NonNull final KubernetesPod pod) {
        pendingPreflight.remove(pod);
        final var rt = runtime.remove(pod);
        if (rt != null) {
            pod.setCondition(PodCondition.READY, false)
               .setCondition(PodCondition.CONTAINERS_READY, false);
        }
    }

    @Override
    public void tick(final double clockTime) {
        // Re-attempt pre-flight for pods that were blocked at startPod time.
        if (!pendingPreflight.isEmpty()) {
            for (final var pod : new ArrayList<>(pendingPreflight)) {
                if (preflight(pod)) {
                    pendingPreflight.remove(pod);
                    LOG.info("{}: kubelet: pod {} pre-flight satisfied; starting containers",
                        broker.getSimulation().clockStr(), pod);
                    final var rt = new PodRuntime(pod);
                    runtime.put(pod, rt);
                    rt.startNextInitContainerOrMain();
                }
            }
        }
        for (final var rt : new ArrayList<>(runtime.values())) {
            rt.runProbes(clockTime);
        }
    }

    /** Runtime state attached to a pod managed by this kubelet. */
    private final class PodRuntime {
        private final KubernetesPod pod;
        private final List<KubernetesContainer> initContainers;
        private final List<KubernetesContainer> mainContainers;
        private final Map<KubernetesContainer, ProbeState> liveness = new HashMap<>();
        private final Map<KubernetesContainer, ProbeState> readiness = new HashMap<>();
        private int initIndex;

        PodRuntime(final KubernetesPod pod) {
            this.pod = pod;
            this.initContainers = pod.getContainers().stream()
                .filter(KubernetesContainer::isInitContainer).toList();
            this.mainContainers = pod.getContainers().stream()
                .filter(c -> !c.isInitContainer()).toList();
        }

        void startNextInitContainerOrMain() {
            if (initIndex < initContainers.size()) {
                final var next = initContainers.get(initIndex++);
                next.setVm(pod);
                next.addOnFinishListener(info -> onInitContainerFinished(info.getCloudlet()));
                broker.submitCloudlet(next);
                return;
            }
            // All init containers done.
            pod.setCondition(PodCondition.INITIALIZED, true);
            startMainContainers();
        }

        void startMainContainers() {
            for (final var c : mainContainers) {
                c.setVm(pod);
                if (c.getRestartPolicy() != null) {
                    c.addOnFinishListener(info -> onMainContainerFinished(c, info.getCloudlet()));
                }
                if (c.getLivenessProbe() != null) {
                    liveness.put(c, new ProbeState(c.getLivenessProbe()));
                }
                if (c.getReadinessProbe() != null) {
                    readiness.put(c, new ProbeState(c.getReadinessProbe()));
                }
            }
            broker.submitCloudletList(mainContainers);
            pod.setPhase(PodPhase.RUNNING);
            // No probes configured ⇒ ready immediately.
            if (readiness.isEmpty()) {
                pod.setCondition(PodCondition.CONTAINERS_READY, true)
                   .setCondition(PodCondition.READY, true);
            }
        }

        void onInitContainerFinished(final Cloudlet cloudlet) {
            // The finish listener fires while status is still INEXEC; only
            // explicit failure states should block init progression.
            if (isFailedStatus(cloudlet)) {
                LOG.warn("{}: kubelet: init container {} on {} failed; pod stays Pending",
                    broker.getSimulation().clockStr(), cloudlet, pod);
                pod.setPhase(PodPhase.FAILED);
                return;
            }
            startNextInitContainerOrMain();
        }

        private boolean isFailedStatus(final Cloudlet cloudlet) {
            final var s = cloudlet.getStatus();
            return s == Cloudlet.Status.FAILED || s == Cloudlet.Status.FAILED_RESOURCE_UNAVAILABLE;
        }

        void onMainContainerFinished(final KubernetesContainer container, final Cloudlet cloudlet) {
            if (RestartPolicy.shouldRestart(container.getRestartPolicy(), isFailedStatus(cloudlet))) {
                restartContainer(container);
            }
        }

        void restartContainer(final KubernetesContainer container) {
            LOG.info("{}: kubelet: restarting container '{}' on pod {}",
                broker.getSimulation().clockStr(), container.getContainerName(), pod);
            // Re-add the same cloudlet to the VM's scheduler. CloudSim's
            // CloudletScheduler accepts a re-submission of a finished cloudlet
            // by clearing its status.
            container.reset();
            container.setVm(pod);
            broker.submitCloudlet(container);
        }

        void runProbes(final double now) {
            // N5 fix: a single user-supplied predicate that throws must not
            // poison the whole tick or stop other containers' probes from
            // running. Mirrors ControllerManager.reconcileAll's defensive
            // try-catch around each controller.
            for (final var entry : liveness.entrySet()) {
                final var c = entry.getKey();
                final var st = entry.getValue();
                try {
                    final boolean ok = st.evaluate(c, now);
                    if (!ok && st.consecutiveFailures >= st.probe.getFailureThreshold()) {
                        onLivenessProbeFailed(c);
                        st.reset();
                    }
                } catch (RuntimeException ex) {
                    LOG.error("{}: kubelet: liveness probe for container '{}' on pod {} threw: {}",
                        broker.getSimulation().clockStr(), c.getContainerName(), pod, ex.toString());
                }
            }
            // Pod-level Ready aggregates per-container readiness probes.
            if (!readiness.isEmpty()) {
                boolean allReady = true;
                for (final var entry : readiness.entrySet()) {
                    final var c = entry.getKey();
                    final var st = entry.getValue();
                    boolean ready = false;
                    try {
                        final boolean ok = st.evaluate(c, now);
                        ready = ok && st.consecutiveSuccesses >= st.probe.getSuccessThreshold();
                    } catch (RuntimeException ex) {
                        LOG.error("{}: kubelet: readiness probe for container '{}' on pod {} threw: {}",
                            broker.getSimulation().clockStr(), c.getContainerName(), pod, ex.toString());
                    }
                    allReady = allReady && ready;
                }
                final boolean wasReady = pod.isReady();
                pod.setCondition(PodCondition.READY, allReady)
                   .setCondition(PodCondition.CONTAINERS_READY, allReady);
                if (allReady && !wasReady) {
                    LOG.info("{}: kubelet: pod {} became Ready",
                        broker.getSimulation().clockStr(), pod);
                }
            }
        }

        void onLivenessProbeFailed(final KubernetesContainer container) {
            LOG.info("{}: kubelet: liveness probe FAILED for container '{}' on pod {} — restarting",
                broker.getSimulation().clockStr(), container.getContainerName(), pod);
            // Cancel the running cloudlet on its VM scheduler, then restart per policy.
            try {
                pod.getCloudletScheduler().cloudletCancel(container);
            } catch (RuntimeException ignored) {
                // some schedulers throw if the cloudlet isn't currently in the exec list
            }
            if (container.getRestartPolicy() != RestartPolicy.NEVER) {
                restartContainer(container);
            }
        }
    }

    /** Per-container probe accounting (consecutive pass / fail counts). */
    private static final class ProbeState {
        final Probe probe;
        double lastEvalAt = -1;
        int consecutiveFailures;
        int consecutiveSuccesses;

        ProbeState(final Probe probe) {
            this.probe = probe;
        }

        /** @return the probe's current pass/fail result, or {@code true} if it's not yet time to re-evaluate. */
        boolean evaluate(final KubernetesContainer c, final double now) {
            if (now - lastEvalAt < probe.getPeriodSeconds() && lastEvalAt >= 0) {
                return consecutiveFailures == 0;
            }
            if (now < probe.getInitialDelaySeconds()) {
                return true;
            }
            lastEvalAt = now;
            final boolean ok = probe.check(c);
            if (ok) {
                consecutiveSuccesses++;
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                consecutiveSuccesses = 0;
            }
            return ok;
        }

        void reset() {
            consecutiveFailures = 0;
            consecutiveSuccesses = 0;
        }
    }
}

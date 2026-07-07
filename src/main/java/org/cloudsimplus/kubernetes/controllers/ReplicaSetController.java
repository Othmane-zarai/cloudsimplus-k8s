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
package org.cloudsimplus.kubernetes.controllers;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ReplicaSet controller: maintains a stable set of replica pods built from a
 * {@link PodTemplate}. {@link #reconcile()} compares the current managed-pod
 * count to {@link #getDesiredReplicas()} and submits or destroys pods to
 * converge.
 *
 * <p>Pods are owned via the {@link #LABEL_CONTROLLER_UID} /
 * {@link #LABEL_CONTROLLER_KIND} labels stamped at creation; lifecycle events
 * route back to {@link #onPodLost(KubernetesPod)} via the
 * {@link ControllerManager}, which immediately requests a replacement on the
 * next reconcile.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class ReplicaSetController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicaSetController.class.getSimpleName());

    private final long uid;

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    @NonNull
    private final PodTemplate template;

    private int desiredReplicas;

    /** Monotonic counter used to derive ordinals for newly-spawned pods. */
    private int nextOrdinal;

    /** Pods currently owned by this RS, keyed by ordinal so scale-down picks the highest. */
    private final Map<Integer, KubernetesPod> managed = new LinkedHashMap<>();

    private ControllerManager manager;

    public ReplicaSetController(
        final long uid,
        final String name,
        final Namespace namespace,
        final PodTemplate template,
        final int desiredReplicas)
    {
        if (desiredReplicas < 0) {
            throw new IllegalArgumentException("desiredReplicas must be >= 0");
        }
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        this.template = template;
        this.desiredReplicas = desiredReplicas;
    }

    @Override
    public String getKind() {
        return "ReplicaSet";
    }

    /** @return read-only view of currently-owned pods. */
    public List<KubernetesPod> getManagedPods() {
        return List.copyOf(managed.values());
    }

    /** @return number of pods currently owned by this RS (placed or pending). */
    public int currentReplicas() {
        return managed.size();
    }

    @Override
    public void onPodLost(final KubernetesPod pod) {
        // K8s semantics (A1 P0a fix): a pod that never reached a node
        // (POD_SCHEDULED=false) stays in the ReplicaSet's desired count — the
        // scheduler keeps retrying until the cluster grows or the pod is
        // explicitly deleted. Only treat the pod as lost if it actually got
        // bound to a node at some point. Without this guard, every
        // failed-creation event triggers a fresh scaleUp() call next tick,
        // producing an O(unschedulable × ticks) thrash that dominated RQ3.
        if (!pod.isScheduled()) {
            return;
        }
        final var ordinalLabel = pod.getLabels().get(LABEL_POD_ORDINAL);
        if (ordinalLabel == null) {
            return;
        }
        try {
            managed.remove(Integer.parseInt(ordinalLabel));
        } catch (NumberFormatException ignored) {
            // legacy/unknown pod — best-effort scan
            managed.values().removeIf(p -> p == pod);
        }
    }

    @Override
    public void reconcile() {
        final int diff = desiredReplicas - managed.size();
        if (diff > 0) {
            scaleUp(diff);
        } else if (diff < 0) {
            scaleDown(-diff);
        }
    }

    /**
     * Force the next reconcile to spawn {@code count} new pods. Used by the
     * {@link DeploymentController} during rolling updates.
     */
    public void scaleUp(final int count) {
        for (int i = 0; i < count; i++) {
            final int ordinal = nextOrdinal++;
            final var pod = stamp(template.create(ordinal), ordinal);
            managed.put(ordinal, pod);
            log("submitting", pod);
            manager.broker().submitPod(pod);
        }
    }

    /**
     * Force the next reconcile to remove {@code count} pods (highest ordinal
     * first, like Kubernetes).
     */
    public void scaleDown(final int count) {
        final var ordinals = new ArrayList<>(managed.keySet());
        Collections.sort(ordinals);
        for (int i = 0; i < count && !ordinals.isEmpty(); i++) {
            final int ordinal = ordinals.remove(ordinals.size() - 1);
            final var pod = managed.remove(ordinal);
            if (pod != null) {
                log("destroying", pod);
                manager.broker().requestIdleVmDestruction(pod);
            }
        }
    }

    private KubernetesPod stamp(final KubernetesPod pod, final int ordinal) {
        pod.setLabels(pod.getLabels().merge(ownerLabels(ordinal)));
        pod.setNamespace(namespace);
        return pod;
    }

    private void log(final String verb, final KubernetesPod pod) {
        if (manager == null) {
            return;
        }
        LOG.info("{}: ReplicaSet '{}' (uid={}) {} pod '{}' (current={}, desired={})",
            manager.broker().getSimulation().clockStr(), name, uid, verb,
            pod.getPodName(), managed.size(), desiredReplicas);
    }
}

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

/**
 * A Deployment controller: owns two child {@link ReplicaSetController}s — one
 * for the current pod template ({@code newRs}) and one for the previous
 * template during a rolling update ({@code oldRs}). On each
 * {@link #reconcile()} tick it advances the rollout one step toward the goal
 * described by {@link UpdateStrategy}.
 *
 * <p>{@link #updateTemplate(PodTemplate)} replaces the current template,
 * archives the existing newRs as oldRs, and creates a fresh newRs at zero
 * replicas to roll up to {@link #getDesiredReplicas() desired}.</p>
 *
 * <p>Pod lifecycle events are routed by their owner labels to one of the two
 * child ReplicaSets, never to the Deployment itself.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class DeploymentController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentController.class.getSimpleName());

    private final long uid;

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    private int desiredReplicas;

    @NonNull
    private UpdateStrategy strategy = UpdateStrategy.RollingUpdate.defaults();

    private ReplicaSetController newRs;
    private ReplicaSetController oldRs;

    private ControllerManager manager;
    private long childCounter;

    public DeploymentController(
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
        this.desiredReplicas = desiredReplicas;
        this.newRs = newChildRs("v1", template, desiredReplicas);
    }

    @Override
    public String getKind() {
        return "Deployment";
    }

    @Override
    public DeploymentController setManager(final ControllerManager manager) {
        this.manager = manager;
        if (newRs != null && newRs.getUid() == -1L) {
            // The child RS was constructed in this Deployment's ctor with the
            // sentinel uid -1 (no manager yet). Now that we have a manager,
            // rebuild it with a proper unique uid — otherwise two sibling
            // Deployments would both submit a child RS with uid=-1 to the
            // registry and the second one would be silently dropped, leaving
            // its setManager() call un-invoked and reconcile() throwing NPE.
            newRs = new ReplicaSetController(
                manager.allocateUid(),
                newRs.getName(),
                newRs.getNamespace(),
                newRs.getTemplate(),
                newRs.getDesiredReplicas());
        }
        if (newRs != null) {
            manager.register(newRs);
        }
        return this;
    }

    /**
     * Replace the current pod template, kicking off a rolling update from the
     * current pods to the new template. The pre-update {@code newRs} becomes
     * the {@code oldRs}.
     */
    public DeploymentController updateTemplate(@NonNull final PodTemplate template) {
        if (oldRs != null && oldRs.currentReplicas() > 0) {
            // an update is already in flight: bail out for now (M1 simplification)
            LOG.warn("{}: Deployment '{}' got a new template while a rollout is in flight; ignoring",
                clockStr(), name);
            return this;
        }
        oldRs = newRs;
        oldRs.setDesiredReplicas(oldRs.currentReplicas()); // freeze; rollout drives down
        final var version = "v" + (++childCounter + 1);
        newRs = newChildRs(version, template, 0);
        if (manager != null) {
            manager.register(newRs);
        }
        LOG.info("{}: Deployment '{}': starting rollout (new RS '{}', old RS '{}')",
            clockStr(), name, newRs.getName(), oldRs.getName());
        return this;
    }

    public DeploymentController setDesiredReplicas(final int n) {
        if (n < 0) {
            throw new IllegalArgumentException("desiredReplicas must be >= 0");
        }
        this.desiredReplicas = n;
        // If no rollout is in flight, the new RS owns the full count.
        if (oldRs == null || oldRs.currentReplicas() == 0) {
            newRs.setDesiredReplicas(n);
        }
        return this;
    }

    /** @return the active replica set (post-update). */
    public ReplicaSetController getActiveReplicaSet() {
        return newRs;
    }

    /** @return the legacy replica set during a rolling update, or {@code null} when not rolling. */
    public ReplicaSetController getLegacyReplicaSet() {
        return oldRs;
    }

    @Override
    public void reconcile() {
        if (oldRs == null) {
            return; // no rollout in progress; child RS handles its own count
        }
        switch (strategy) {
            case UpdateStrategy.Recreate r -> reconcileRecreate();
            case UpdateStrategy.RollingUpdate ru -> reconcileRolling(ru);
        }
    }

    private void reconcileRecreate() {
        if (oldRs.currentReplicas() > 0) {
            oldRs.setDesiredReplicas(0);
            return;
        }
        // old gone: bring new up to full
        newRs.setDesiredReplicas(desiredReplicas);
        if (newRs.currentReplicas() == desiredReplicas) {
            finishRollout();
        }
    }

    /**
     * Symmetric rolling-update step modelled after Kubernetes'
     * {@code pkg/controller/deployment/util.go} arithmetic:
     *
     * <ul>
     *   <li><b>Surge</b>: new RS may grow while {@code newCur + oldCur < desired + maxSurge}.</li>
     *   <li><b>Unavailability</b>: old RS may shrink while
     *       {@code newCur + (oldCur - 1) >= desired - maxUnavailable}.</li>
     * </ul>
     *
     * <p>Both actions are evaluated each tick, not serialised; this matches K8s
     * and lets {@code RollingUpdate(0, 2)} (drain-before-replace) make progress.
     * Within one reconciliation pass the child RSes pick up the new
     * {@code desiredReplicas} and converge in the same cycle.</p>
     */
    private void reconcileRolling(final UpdateStrategy.RollingUpdate ru) {
        final int newCur = newRs.currentReplicas();
        final int oldCur = oldRs.currentReplicas();
        final int total = newCur + oldCur;
        final int maxTotal = desiredReplicas + ru.maxSurge();
        final int minAvailable = Math.max(0, desiredReplicas - ru.maxUnavailable());

        // Scale up new: still need more, and surge cap is not yet reached.
        if (newCur < desiredReplicas && total < maxTotal) {
            newRs.setDesiredReplicas(newCur + 1);
        }
        // Scale down old: anything to drain, and unavailability invariant is honoured
        // after the proposed scale-down. The check uses post-scale arithmetic
        // (newCur + (oldCur - 1)), so a drain only happens when at least
        // minAvailable replicas remain after.
        if (oldCur > 0 && newCur + (oldCur - 1) >= minAvailable) {
            oldRs.setDesiredReplicas(oldCur - 1);
        }
        if (oldRs.currentReplicas() == 0 && newRs.currentReplicas() == desiredReplicas) {
            finishRollout();
        }
    }

    private void finishRollout() {
        LOG.info("{}: Deployment '{}': rollout complete ({} replicas of '{}')",
            clockStr(), name, desiredReplicas, newRs.getName());
        oldRs = null;
    }

    private ReplicaSetController newChildRs(final String suffix, final PodTemplate template, final int replicas) {
        final long childUid = manager == null ? -1 : manager.allocateUid();
        return new ReplicaSetController(childUid, name + "-" + suffix, namespace, template, replicas);
    }

    private String clockStr() {
        return manager == null ? "?" : manager.broker().getSimulation().clockStr();
    }
}

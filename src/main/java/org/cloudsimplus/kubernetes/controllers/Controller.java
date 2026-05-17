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

import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;

/**
 * A Kubernetes controller — a reconciliation loop that drives a set of
 * {@link KubernetesPod}s toward a desired state. Implementations include
 * {@link ReplicaSetController}, {@link DeploymentController},
 * {@link StatefulSetController}, {@link DaemonSetController},
 * {@link JobController}, {@link CronJobController}.
 *
 * <p>Controllers are loosely coupled to their pods through the owner-reference
 * labels {@link #LABEL_CONTROLLER_UID} and {@link #LABEL_CONTROLLER_KIND}; the
 * {@link ControllerManager} reads them off pod-lifecycle events and dispatches
 * to the right controller.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public interface Controller {

    /** Pod label carrying the owning controller's UID. */
    String LABEL_CONTROLLER_UID  = "cloudsimplus.kubernetes/controller-uid";

    /** Pod label carrying the owning controller's kind ({@code ReplicaSet}, {@code Deployment}, ...). */
    String LABEL_CONTROLLER_KIND = "cloudsimplus.kubernetes/controller-kind";

    /** Pod label carrying the controller-relative ordinal (used by stable-identity controllers). */
    String LABEL_POD_ORDINAL = "cloudsimplus.kubernetes/ordinal";

    /** @return a unique-within-the-broker controller id, used by owner-reference labels. */
    long getUid();

    String getName();

    Namespace getNamespace();

    /** A short identifier ({@code "ReplicaSet"}, {@code "Deployment"}, …). */
    String getKind();

    /** Wires the controller to its broker-side context. Called once by {@link ControllerManager#register}. */
    Controller setManager(ControllerManager manager);

    /**
     * Notification that a pod owned by this controller has just been placed on
     * a node (its underlying VM was created on a host).
     */
    default void onPodCreated(KubernetesPod pod) {
    }

    /**
     * Notification that a pod owned by this controller is no longer running:
     * either the placement attempt failed, or its VM was deallocated. Should
     * not be called for graceful shutdowns the controller itself initiated
     * (controllers are expected to update their own internal state directly
     * when scaling down).
     */
    default void onPodLost(KubernetesPod pod) {
    }

    /**
     * Periodic reconciliation hook fired on every broker tick (interval set by
     * {@link org.cloudsimplus.kubernetes.KubernetesClusterBroker#setControllerTickIntervalSeconds(double)}).
     * Implementations should be idempotent — they may be called repeatedly
     * even when there's nothing to do.
     */
    void reconcile();

    /**
     * Owner-reference labels stamped on every pod a controller spawns, so the
     * {@link ControllerManager} can route pod-lifecycle events back to the
     * right controller instance. Centralised here so all controllers stamp the
     * same shape (previously each controller hand-rolled the same three lines).
     *
     * @return labels carrying {@link #LABEL_CONTROLLER_UID} and {@link #LABEL_CONTROLLER_KIND}
     */
    default LabelSet ownerLabels() {
        return LabelSet.of()
            .with(LABEL_CONTROLLER_UID, Long.toString(getUid()))
            .with(LABEL_CONTROLLER_KIND, getKind())
            .build();
    }

    /**
     * Owner-reference labels including a controller-relative ordinal — used by
     * stable-identity controllers (ReplicaSet, StatefulSet) where each pod
     * needs to be addressable by ordinal for scale-down and replacement.
     *
     * @param ordinal the pod's controller-relative ordinal
     * @return labels carrying {@link #LABEL_CONTROLLER_UID}, {@link #LABEL_CONTROLLER_KIND}
     *         and {@link #LABEL_POD_ORDINAL}
     */
    default LabelSet ownerLabels(final int ordinal) {
        return LabelSet.of()
            .with(LABEL_CONTROLLER_UID, Long.toString(getUid()))
            .with(LABEL_CONTROLLER_KIND, getKind())
            .with(LABEL_POD_ORDINAL, Integer.toString(ordinal))
            .build();
    }
}

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

import lombok.NonNull;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Broker-side registry of {@link Controller}s. The manager:
 *
 * <ul>
 *   <li>routes pod lifecycle events from the broker to the controller that
 *       owns the pod (matched by the {@link Controller#LABEL_CONTROLLER_UID}
 *       label set on the pod when it was created)</li>
 *   <li>provides controllers with the broker handle they need to submit and
 *       destroy pods</li>
 *   <li>fans out periodic reconcile ticks driven by the broker's tick event</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
public class ControllerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerManager.class.getSimpleName());

    private final KubernetesClusterBroker broker;
    private final Map<Long, Controller> byUid = new LinkedHashMap<>();
    private long nextUid = 1;

    public ControllerManager(@NonNull final KubernetesClusterBroker broker) {
        this.broker = broker;
    }

    /**
     * Allocates a fresh controller UID. Controllers should request one in their
     * constructor and use it as the value of the {@link Controller#LABEL_CONTROLLER_UID}
     * label they stamp on every pod they create.
     */
    public long allocateUid() {
        return nextUid++;
    }

    /** Registers a controller and gives it a back-reference to this manager. */
    public ControllerManager register(@NonNull final Controller controller) {
        if (byUid.put(controller.getUid(), controller) == null) {
            controller.setManager(this);
            LOG.info("{}: ControllerManager: registered {} '{}' (uid={})",
                broker.getSimulation().clockStr(), controller.getKind(),
                controller.getName(), controller.getUid());
        }
        return this;
    }

    public Optional<Controller> getController(final long uid) {
        return Optional.ofNullable(byUid.get(uid));
    }

    public Map<Long, Controller> getControllers() {
        return Collections.unmodifiableMap(byUid);
    }

    /** @return the broker handle. Used by controllers to submit / destroy pods. */
    public KubernetesClusterBroker broker() {
        return broker;
    }

    // --------------- event dispatch ---------------

    /** Routes a pod-created event to the owning controller, if any. */
    public void onPodCreated(final KubernetesPod pod) {
        ownerOf(pod).ifPresent(c -> c.onPodCreated(pod));
    }

    /** Routes a pod-lost event to the owning controller, if any. */
    public void onPodLost(final KubernetesPod pod) {
        ownerOf(pod).ifPresent(c -> c.onPodLost(pod));
    }

    /** Calls {@link Controller#reconcile()} on every registered controller. */
    public void reconcileAll() {
        for (final var c : byUid.values()) {
            try {
                c.reconcile();
            } catch (RuntimeException ex) {
                LOG.error("{}: Controller '{}' threw during reconcile: {}",
                    broker.getSimulation().clockStr(), c.getName(), ex.toString());
            }
        }
    }

    private Optional<Controller> ownerOf(final KubernetesPod pod) {
        if (pod == null) {
            return Optional.empty();
        }
        final var uidStr = pod.getLabels().get(Controller.LABEL_CONTROLLER_UID);
        if (uidStr == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(byUid.get(Long.parseLong(uidStr)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}

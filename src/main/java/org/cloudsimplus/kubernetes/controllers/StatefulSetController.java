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
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StatefulSet controller — like {@link ReplicaSetController} but every pod gets
 * a stable, ordinal-suffixed name ({@code name-0}, {@code name-1}, ...) and a
 * matching {@code ordinal} label. Scale-up creates pods in ordinal order
 * starting at the lowest gap; scale-down removes the highest ordinals.
 *
 * <p>For simplicity v2 does not enforce strict serial ordering during
 * Ready transitions (Kubernetes' {@code OrderedReady} podManagementPolicy);
 * pods may launch in parallel. Stable identity is preserved either way.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class StatefulSetController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(StatefulSetController.class.getSimpleName());

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private final PodTemplate template;
    private int desiredReplicas;
    private final Map<Integer, KubernetesPod> managed = new LinkedHashMap<>();
    private ControllerManager manager;

    public StatefulSetController(final long uid, final String name, final Namespace namespace,
                                 final PodTemplate template, final int desiredReplicas) {
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
        return "StatefulSet";
    }

    public List<KubernetesPod> getManagedPods() {
        return List.copyOf(managed.values());
    }

    @Override
    public void onPodLost(final KubernetesPod pod) {
        final var ord = pod.getLabels().get(LABEL_POD_ORDINAL);
        if (ord != null) {
            try {
                managed.remove(Integer.parseInt(ord));
            } catch (NumberFormatException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void reconcile() {
        // Scale up: fill the lowest empty ordinal first.
        while (managed.size() < desiredReplicas) {
            final int ordinal = nextFreeOrdinal();
            final var pod = template.create(ordinal);
            pod.setPodName(name + "-" + ordinal);
            pod.setNamespace(namespace);
            pod.setLabels(pod.getLabels().merge(ownerLabels(ordinal)));
            managed.put(ordinal, pod);
            LOG.info("{}: StatefulSet '{}': submitting {} (ordinal {})",
                manager.broker().getSimulation().clockStr(), name, pod.getPodName(), ordinal);
            manager.broker().submitPod(pod);
        }
        // Scale down: remove highest ordinals first.
        while (managed.size() > desiredReplicas) {
            final var maxOrdinal = managed.keySet().stream().max(Integer::compareTo).orElseThrow();
            final var pod = managed.remove(maxOrdinal);
            LOG.info("{}: StatefulSet '{}': decommissioning {} (ordinal {})",
                manager.broker().getSimulation().clockStr(), name, pod.getPodName(), maxOrdinal);
            manager.broker().requestIdleVmDestruction(pod);
        }
    }

    private int nextFreeOrdinal() {
        for (int i = 0; i < desiredReplicas + managed.size() + 1; i++) {
            if (!managed.containsKey(i)) {
                return i;
            }
        }
        return managed.size();
    }
}

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
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DaemonSet controller — ensures exactly one pod per matching node. The
 * {@link #getNodeSelector() nodeSelector} filters which nodes are eligible;
 * {@link #reconcile()} adds a pod to any matching node that doesn't already
 * have one, and tolerates node additions over time.
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class DaemonSetController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(DaemonSetController.class.getSimpleName());

    public enum UpdateStrategyType {
        ON_DELETE,
        ROLLING_UPDATE
    }

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private PodTemplate template;
    private LabelSelector nodeSelector = LabelSelector.MATCH_ALL;
    private UpdateStrategyType updateStrategy = UpdateStrategyType.ON_DELETE;
    /**
     * Monotonic revision counter — bumped on every {@link #setTemplate} call.
     * Pods are tagged with the revision in effect when they were spawned, and
     * the rolling-update strategy evicts any pod whose tagged revision is
     * older than the current one. This is content-agnostic by design: changing
     * the {@link PodTemplate} object is the explicit signal for a new
     * revision, mirroring K8s' behaviour where rolling updates are driven by
     * the controller spec changing rather than container hash diffs.
     */
    private int templateRevision;
    private final Map<Long, KubernetesPod> podByNodeId = new LinkedHashMap<>();
    private final Map<KubernetesPod, Integer> podRevision = new LinkedHashMap<>();
    private int nextOrdinal;
    private ControllerManager manager;

    public DaemonSetController(final long uid, final String name, final Namespace namespace,
                               final PodTemplate template) {
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        setTemplate(template);
    }

    /**
     * Replaces the pod template and bumps the internal revision counter so
     * the {@link UpdateStrategyType#ROLLING_UPDATE} strategy can detect that
     * existing pods are stale.
     */
    public DaemonSetController setTemplate(final PodTemplate template) {
        this.template = template;
        this.templateRevision++;
        return this;
    }

    @Override
    public String getKind() {
        return "DaemonSet";
    }

    @Override
    public void reconcile() {
        // Pod lifecycle (host deallocation, creation failure) is handled by
        // onPodLost, which removes the entry. M6: when a node is physically
        // removed by the ClusterAutoscaler, the daemon pod that was on it
        // has its host deallocated, which fires onHostDeallocationListener
        // → KubernetesClusterBroker.onPodLost → ControllerManager.onPodLost
        // → DaemonSetController.onPodLost → entry cleared. So no explicit
        // node-prune step is needed; reconcile() simply checks every live
        // node and spawns where missing.
        for (final var node : manager.broker().getNodes()) {
            if (!nodeSelector.matches(node.getLabels())) {
                continue;
            }
            if (podByNodeId.containsKey(node.getId())) {
                final KubernetesPod existingPod = podByNodeId.get(node.getId());
                final Integer rev = podRevision.get(existingPod);
                if (updateStrategy == UpdateStrategyType.ROLLING_UPDATE
                    && rev != null && rev < templateRevision)
                {
                    LOG.info("{}: DaemonSet '{}': evicting stale pod {} (rev={}, current={}) on node {}",
                        manager.broker().getSimulation().clockStr(), name,
                        existingPod.getPodName(), rev, templateRevision, node.effectiveName());
                    if (existingPod.getBroker() != null) {
                        existingPod.getBroker().requestIdleVmDestruction(existingPod);
                    }
                    podByNodeId.remove(node.getId());
                    podRevision.remove(existingPod);
                    // Replacement happens on the next reconcile tick once the
                    // map entry is gone — keeping the eviction and the spawn
                    // on separate ticks avoids a same-tick destroy-then-create
                    // race against the broker's vmFailedList processing.
                }
                continue;
            }
            spawnPodOnNode(node);
        }
    }

    private void spawnPodOnNode(final KubernetesNode node) {
        final int ordinal = nextOrdinal++;
        final var pod = template.create(ordinal);
        pod.setPodName(name + "-" + node.effectiveName());
        pod.setNamespace(namespace);
        pod.setLabels(pod.getLabels().merge(ownerLabels()));
        // Pin to this specific node via the well-known hostname label.
        // NodeBuilder always stamps "kubernetes.io/hostname"; we fall back to
        // effectiveName() for nodes constructed without it (test fixtures, etc.).
        final var hostname = node.getLabels().has("kubernetes.io/hostname")
            ? node.getLabels().get("kubernetes.io/hostname")
            : node.effectiveName();
        pod.setNodeSelector(LabelSelector.matchLabel("kubernetes.io/hostname", hostname));
        podByNodeId.put(node.getId(), pod);
        podRevision.put(pod, templateRevision);
        LOG.info("{}: DaemonSet '{}': scheduling {} on node {}",
            manager.broker().getSimulation().clockStr(), name, pod.getPodName(), node.effectiveName());
        manager.broker().submitPod(pod);
    }

    @Override
    public void onPodLost(final KubernetesPod pod) {
        // Remove the lost pod from the per-node map so reconcile() can
        // re-create it on the next tick. Identity-keyed because the underlying
        // VmSimple equals/compareTo is based on id and not suitable for
        // value-based set membership.
        podByNodeId.entrySet().stream()
            .filter(e -> e.getValue() == pod)
            .map(Map.Entry::getKey)
            .findFirst().ifPresent(podByNodeId::remove);

        podRevision.remove(pod);
    }
}

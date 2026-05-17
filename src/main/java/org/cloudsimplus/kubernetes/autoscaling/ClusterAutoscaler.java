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
package org.cloudsimplus.kubernetes.autoscaling;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.lifecycle.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cluster Autoscaler — adds nodes from a {@link NodePool} when pods cannot be
 * scheduled, and decommissions empty nodes after a configurable idle window.
 *
 * <p><b>Unschedulable detection.</b> A pod counts as unschedulable when the
 * scheduler has actually tried to place it and rejected every node — i.e. its
 * {@link KubernetesPod#isUnschedulable()} flag is set. This is set by
 * {@link org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler}'s strict
 * filter pass and cleared the moment the pod successfully lands on a node.
 * Using the flag (rather than the previous {@code !isCreated()} heuristic)
 * eliminates the race where a freshly-submitted pod that hasn't yet been
 * tried by the scheduler would incorrectly trigger a node provision.</p>
 *
 * <p><b>Scale-up retry semantics.</b> After provisioning a new host, the
 * autoscaler resubmits every unschedulable pod (clearing their failed-VM
 * state on the broker) so the scheduler attempts placement again on the
 * expanded host list.</p>
 *
 * <p>This scaler supports a single {@link NodePool} per instance; register
 * multiple instances on a broker for heterogeneous fleets.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class ClusterAutoscaler implements Tick {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterAutoscaler.class.getSimpleName());

    @NonNull
    private final KubernetesClusterBroker broker;

    @NonNull
    private final NodePool pool;

    /** Idle seconds before a node becomes a candidate for decommission. */
    private double scaleDownAfterSeconds = 600.0;

    /** Minimum simulated seconds between two cluster-scale actions. */
    private double cooldownSeconds = 30.0;

    private double lastActionAt = -1.0;
    /**
     * Tracks pool-owned nodes by reference. Replaces the previous string-prefix
     * heuristic on node names, which was fragile when two pools shared a prefix
     * or when YAML-defined templates produced names without the pool prefix.
     */
    private final Set<KubernetesNode> ownedNodes = new LinkedHashSet<>();
    private final Map<KubernetesNode, Double> idleSince = new LinkedHashMap<>();

    public ClusterAutoscaler(final KubernetesClusterBroker broker, final NodePool pool) {
        this.broker = broker;
        this.pool = pool;
    }

    @Override
    public void tick(final double clockTime) {
        if (clockTime - lastActionAt < cooldownSeconds && lastActionAt >= 0) {
            return;
        }
        if (scaleUpIfPodsPending(clockTime)) {
            return;
        }
        scaleDownIfNodesIdle(clockTime);
    }

    /** Number of pool-owned nodes currently provisioned (live in the datacenter). */
    public int getProvisioned() {
        return ownedNodes.size();
    }

    private boolean scaleUpIfPodsPending(final double clockTime) {
        final List<KubernetesPod> pending = broker.getPods().stream()
            .filter(KubernetesPod::isUnschedulable)
            .filter(p -> !p.isCreated())
            .toList();
        if (pending.isEmpty()) {
            return false;
        }
        if (ownedNodes.size() >= pool.getMax()) {
            LOG.warn("ClusterAutoscaler({}): {} pending pod(s) but pool is at max capacity ({}/{}); cannot scale up",
                pool.getName(), pending.size(), ownedNodes.size(), pool.getMax());
            return false;
        }
        final var dc = primaryDatacenter();
        if (dc == null) {
            return false;
        }
        final var node = pool.getTemplate().get();
        dc.addHost(node);
        ownedNodes.add(node);
        lastActionAt = clockTime;
        LOG.info("ClusterAutoscaler({}): provisioned new node '{}' to relieve {} pending pod(s) (pool size now {})",
            pool.getName(), node.effectiveName(), pending.size(), ownedNodes.size());
        // Re-trigger placement for every unschedulable pod. Without this the
        // broker leaves them in vmFailedList and they never get tried on the
        // new host. Resubmitting moves them back into vmWaitingList so the
        // scheduler runs filter+score over the expanded host list.
        broker.getVmFailedList().removeIf(pending::contains);
        for (final var p : pending) {
            p.clearUnschedulable();
            broker.submitVm(p);
        }
        return true;
    }

    private void scaleDownIfNodesIdle(final double clockTime) {
        // Iterate over a snapshot — the loop may mutate ownedNodes via decommission.
        final var snapshot = new ArrayList<>(ownedNodes);
        final List<KubernetesNode> toDecommission = new ArrayList<>();
        for (final var node : snapshot) {
            final boolean empty = broker.placedPodsOnNode(node).isEmpty();
            if (!empty) {
                idleSince.remove(node);
                continue;
            }
            final double since = idleSince.computeIfAbsent(node, n -> clockTime);
            if (clockTime - since >= scaleDownAfterSeconds
                && snapshot.size() - toDecommission.size() > pool.getMin()) {
                toDecommission.add(node);
            }
        }
        if (toDecommission.isEmpty()) {
            return;
        }
        final var dc = primaryDatacenter();
        for (final var n : toDecommission) {
            // Real removal — not just a cordon. The datacenter's hostList is
            // mutable (DatacenterSimple wraps in ArrayList on construction) so
            // removeHost is safe; it simply unlinks the host without firing
            // events. Cordon-only would leak hosts every cycle and make
            // pool accounting drift from physical state.
            if (dc != null) {
                dc.removeHost(n);
            }
            ownedNodes.remove(n);
            idleSince.remove(n);
            lastActionAt = clockTime;
            LOG.info("ClusterAutoscaler({}): decommissioned idle node '{}' (pool size now {})",
                pool.getName(), n.effectiveName(), ownedNodes.size());
        }
    }

    private Datacenter primaryDatacenter() {
        return broker.getDatacenters().isEmpty() ? null : broker.getDatacenters().get(0);
    }
}

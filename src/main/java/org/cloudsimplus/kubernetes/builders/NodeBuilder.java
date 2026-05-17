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
package org.cloudsimplus.kubernetes.builders;

import org.cloudsimplus.kubernetes.KubernetesNode;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Taint;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for {@link KubernetesNode}: assembles PEs, RAM, BW and
 * storage along with topology metadata (rack / AZ / region / cost) and
 * Kubernetes-specific labels and taints.
 *
 * <pre>
 * var node = NodeBuilder.of("worker-1")
 *     .pes(8, 1000).ram(32_768).bw(10_000).storage(500_000)
 *     .rack("rack-A").zone("us-east-1a").region("us-east").costPerHour(0.20)
 *     .label("workload", "general")
 *     .taint(Taint.noSchedule("dedicated", "gpu"))
 *     .build();
 * </pre>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class NodeBuilder {

    private final String name;
    private long pesCount = 1;
    private double mipsPerPe = 1_000;
    private long ramMiB = 16_384;
    private long bwMbps = 10_000;
    private long storageMB = 100_000;
    private String rack = "";
    private String zone = "";
    private String region = "";
    private double costPerHour;
    private boolean schedulable = true;
    private final Map<String, String> labels = new HashMap<>();
    private final List<Taint> taints = new ArrayList<>();

    private NodeBuilder(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Node name is required");
        }
        this.name = name;
    }

    public static NodeBuilder of(final String name) {
        return new NodeBuilder(name);
    }

    public NodeBuilder pes(final long count, final double mipsEach) {
        if (count <= 0) {
            throw new IllegalArgumentException("PE count must be > 0");
        }
        this.pesCount = count;
        this.mipsPerPe = mipsEach;
        return this;
    }

    public NodeBuilder ram(final long miB) {
        this.ramMiB = miB;
        return this;
    }

    public NodeBuilder bw(final long mbps) {
        this.bwMbps = mbps;
        return this;
    }

    public NodeBuilder storage(final long mb) {
        this.storageMB = mb;
        return this;
    }

    public NodeBuilder rack(final String rackId) {
        this.rack = rackId == null ? "" : rackId;
        return this;
    }

    public NodeBuilder zone(final String az) {
        this.zone = az == null ? "" : az;
        return this;
    }

    public NodeBuilder region(final String reg) {
        this.region = reg == null ? "" : reg;
        return this;
    }

    public NodeBuilder costPerHour(final double cost) {
        this.costPerHour = cost;
        return this;
    }

    public NodeBuilder schedulable(final boolean s) {
        this.schedulable = s;
        return this;
    }

    public NodeBuilder label(final String key, final String value) {
        labels.put(key, value);
        return this;
    }

    public NodeBuilder taint(final Taint t) {
        if (t != null) {
            taints.add(t);
        }
        return this;
    }

    public KubernetesNode build() {
        final List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < pesCount; i++) {
            peList.add(new PeSimple(mipsPerPe));
        }
        final var node = new KubernetesNode(ramMiB, bwMbps, storageMB, peList);
        // Always stamp the well-known hostname label so DaemonSet pinning,
        // PodAffinity HOSTNAME topology, and other K8s identity-by-hostname
        // logic works without relying on the user remembering to add it.
        // M5 fix: previously a conditional branch in DaemonSetController
        // silently let DaemonSet pods scatter across nodes when this label
        // was missing.
        labels.putIfAbsent("kubernetes.io/hostname", name);
        if (!zone.isEmpty()) {
            labels.putIfAbsent("topology.kubernetes.io/zone", zone);
        }
        if (!region.isEmpty()) {
            labels.putIfAbsent("topology.kubernetes.io/region", region);
        }
        node.setNodeName(name)
            .setLabels(LabelSet.from(labels))
            .setSchedulable(schedulable)
            .setRackId(rack)
            .setAvailabilityZone(zone)
            .setRegion(region)
            .setCostPerHour(costPerHour);
        node.addTaints(taints);
        return node;
    }
}

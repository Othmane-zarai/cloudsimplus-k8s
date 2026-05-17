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
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.hosts.TopologyAwareHost;
import org.cloudsimplus.resources.Pe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A Kubernetes Node, modelled as a {@link TopologyAwareHost} extended with
 * Kubernetes-specific metadata: a {@code nodeName}, a {@link LabelSet} matched
 * by pod {@code nodeSelector}/{@link NodeAffinity} rules, a list of
 * {@link Taint}s repelling pods that don't tolerate them, and a
 * {@code schedulable} flag analogous to a kubelet's {@code cordon}/{@code drain}
 * state.
 *
 * <p>Topology fields ({@code rackId}, {@code availabilityZone}, {@code region},
 * {@code costPerHour}, latency table) are inherited from {@link TopologyAwareHost}
 * so the existing
 * {@link org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware}
 * scoring policies (cost / latency / spread / rack-anti-affinity) keep working
 * unchanged on K8s nodes.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesNode extends TopologyAwareHost {

    /** Human-readable node name (mirrors {@code metadata.name}). */
    private String nodeName = "";

    @NonNull
    private LabelSet labels = LabelSet.EMPTY;

    private final List<Taint> taints = new ArrayList<>();

    /**
     * Whether the kubelet is currently accepting pods. When {@code false} the
     * scheduler must not place any new pod on the node — analogous to
     * {@code kubectl cordon}.
     */
    private boolean schedulable = true;

    public KubernetesNode(final List<Pe> peList) {
        super(peList);
    }

    public KubernetesNode(final long ram, final long bw, final long storage, final List<Pe> peList) {
        super(ram, bw, storage, peList);
    }

    /**
     * Adds a taint to this node. Pods that lack a matching {@link Toleration}
     * will be rejected by the {@link org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler}.
     */
    public KubernetesNode addTaint(@NonNull final Taint taint) {
        taints.add(taint);
        return this;
    }

    public KubernetesNode addTaints(final List<Taint> ts) {
        if (ts != null) {
            ts.forEach(this::addTaint);
        }
        return this;
    }

    public List<Taint> getTaints() {
        return Collections.unmodifiableList(taints);
    }

    /**
     * @return the effective node name — falls back to {@code "node-<id>"} when
     * no explicit name was set, so the broker-side name index always has a key.
     */
    public String effectiveName() {
        return nodeName == null || nodeName.isBlank() ? "node-" + getId() : nodeName;
    }

    @Override
    public String toString() {
        return "KubernetesNode[%s]".formatted(effectiveName());
    }
}

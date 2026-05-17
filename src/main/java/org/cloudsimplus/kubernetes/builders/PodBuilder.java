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

import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.NodeAffinity;
import org.cloudsimplus.kubernetes.Resources;
import org.cloudsimplus.kubernetes.Toleration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for {@link KubernetesPod} — accumulates containers, labels,
 * a node selector, optional node affinity, and tolerations.
 *
 * <pre>
 * var pod = PodBuilder.of("web-1")
 *     .namespace(myNs)
 *     .label("app", "web").label("tier", "frontend")
 *     .container(ContainerBuilder.of("nginx").image("nginx:1.21").cpu("250m").mem("128Mi").length(50_000).build())
 *     .build();
 * </pre>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class PodBuilder {

    private final String name;
    private Namespace namespace = Namespace.DEFAULT;
    private final Map<String, String> labels = new HashMap<>();
    private LabelSelector nodeSelector = LabelSelector.MATCH_ALL;
    private NodeAffinity nodeAffinity = NodeAffinity.NONE;
    private final List<Toleration> tolerations = new ArrayList<>();
    private final List<KubernetesContainer> containers = new ArrayList<>();
    private int mipsPerCore = Resources.DEFAULT_MIPS_PER_CORE;

    private PodBuilder(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pod name is required");
        }
        this.name = name;
    }

    public static PodBuilder of(final String name) {
        return new PodBuilder(name);
    }

    public PodBuilder namespace(final Namespace ns) {
        this.namespace = ns == null ? Namespace.DEFAULT : ns;
        return this;
    }

    public PodBuilder label(final String key, final String value) {
        labels.put(key, value);
        return this;
    }

    public PodBuilder labels(final Map<String, String> all) {
        if (all != null) {
            labels.putAll(all);
        }
        return this;
    }

    public PodBuilder nodeSelector(final LabelSelector selector) {
        this.nodeSelector = selector == null ? LabelSelector.MATCH_ALL : selector;
        return this;
    }

    public PodBuilder nodeAffinity(final NodeAffinity affinity) {
        this.nodeAffinity = affinity == null ? NodeAffinity.NONE : affinity;
        return this;
    }

    public PodBuilder tolerate(final Toleration t) {
        if (t != null) {
            tolerations.add(t);
        }
        return this;
    }

    public PodBuilder container(final KubernetesContainer c) {
        if (c == null) {
            throw new IllegalArgumentException("Container must not be null");
        }
        containers.add(c);
        return this;
    }

    public PodBuilder mipsPerCore(final int mips) {
        this.mipsPerCore = mips;
        return this;
    }

    public KubernetesPod build() {
        if (containers.isEmpty()) {
            throw new IllegalStateException("A Pod must have at least one container");
        }
        final var pod = new KubernetesPod(name, containers, mipsPerCore)
            .setNamespace(namespace)
            .setLabels(LabelSet.from(labels))
            .setNodeSelector(nodeSelector)
            .setNodeAffinity(nodeAffinity);
        pod.addTolerations(tolerations);
        return pod;
    }
}

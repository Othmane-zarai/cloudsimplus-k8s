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
import org.cloudsimplus.kubernetes.lifecycle.PodCondition;
import org.cloudsimplus.kubernetes.lifecycle.PodPhase;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A Kubernetes Pod, modelled as a {@link VmSimple} with K8s-specific metadata.
 *
 * <p>The Pod is the schedulable unit on a {@link KubernetesNode}; its
 * {@link KubernetesContainer}s share the underlying VM's
 * {@link org.cloudsimplus.schedulers.cloudlet.CloudletScheduler}, mirroring how
 * containers in a real pod share the kubelet's runtime. The pod's compute /
 * memory capacity is sized at construction time as the sum of its containers'
 * resource limits (or requests when limits are unset).</p>
 *
 * <p>K8s scheduling inputs carried by this class:</p>
 * <ul>
 *   <li>{@link #getNodeSelector() nodeSelector} — a {@link LabelSelector} the
 *       chosen node's labels must match</li>
 *   <li>{@link #getNodeAffinity() nodeAffinity} — required + preferred rules</li>
 *   <li>{@link #getTolerations() tolerations} — taint covers</li>
 * </ul>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class KubernetesPod extends VmSimple {

    @NonNull
    private String podName;

    @NonNull
    private Namespace namespace = Namespace.DEFAULT;

    @NonNull
    private LabelSet labels = LabelSet.EMPTY;

    @NonNull
    private LabelSelector nodeSelector = LabelSelector.MATCH_ALL;

    @NonNull
    private NodeAffinity nodeAffinity = NodeAffinity.NONE;

    @NonNull
    private PodAffinity podAffinity = PodAffinity.NONE;

    private final List<KubernetesContainer> containers;
    private final List<Toleration> tolerations = new ArrayList<>();
    private final int mipsPerCore;

    /** Names of ConfigMaps this pod mounts; the kubelet refuses to start
     *  main containers until every named ConfigMap has been registered with
     *  the broker. */
    private final List<String> configMapRefs = new ArrayList<>();

    /** Names of Secrets this pod mounts; checked by the kubelet pre-flight. */
    private final List<String> secretRefs = new ArrayList<>();

    /** Names of PersistentVolumeClaims this pod requires; the kubelet
     *  blocks startup until each PVC is bound to a {@code PersistentVolume}. */
    private final List<String> persistentVolumeClaimRefs = new ArrayList<>();

    /** ServiceAccount this pod runs as; null defers to the namespace default
     *  and skips the SA pre-flight check. */
    private String serviceAccountName;

    /**
     * Kubernetes-style PriorityClass value. Higher numbers schedule first, and
     * (when {@code preemptionEnabled=true} on the scheduler) a higher-priority
     * pod may evict a lower-priority pod to free capacity. Defaults to
     * {@code 0}, matching the K8s behaviour for pods that do not declare a
     * \texttt{priorityClassName}. Mirrors {@code spec.priority} on the K8s API.
     */
    private int priority;

    private final Map<PodCondition, Boolean> conditions = new EnumMap<>(PodCondition.class);

    @NonNull
    private PodPhase phase = PodPhase.PENDING;

    /**
     * Whether the scheduler has tried and failed to place this pod on any node
     * given the current cluster state. Set by {@link org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler}
     * after a strict-constraint pass returns no candidates; cleared on the next
     * placement attempt that succeeds. The {@link org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler}
     * uses this signal to decide when to provision a new node.
     */
    private boolean unschedulable;

    /** How many times the scheduler has attempted to place this pod. */
    private int schedulingAttempts;

    /** Wall-clock simulation time of the most recent scheduling attempt. */
    private double lastSchedulingAttemptAt = -1.0;

    /**
     * Creates a Pod with the default {@link Resources#DEFAULT_MIPS_PER_CORE} calibration.
     *
     * @param podName    the pod name (must be non-blank)
     * @param containers the containers running inside this pod (at least one)
     */
    public KubernetesPod(final String podName, final List<KubernetesContainer> containers) {
        this(podName, containers, Resources.DEFAULT_MIPS_PER_CORE);
    }

    /**
     * Creates a Pod with an explicit MIPS-per-core calibration.
     *
     * @param podName     the pod name (must be non-blank)
     * @param containers  the containers running inside this pod (at least one)
     * @param mipsPerCore the simulator's CPU calibration
     */
    public KubernetesPod(
        final String podName,
        final List<KubernetesContainer> containers,
        final int mipsPerCore)
    {
        super(mipsPerCore, totalPes(containers));
        if (podName == null || podName.isBlank()) {
            throw new IllegalArgumentException("Pod name must be non-blank");
        }
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("A Pod must declare at least one container");
        }
        this.podName = podName;
        this.containers = List.copyOf(containers);
        this.mipsPerCore = mipsPerCore;
        setRam(Math.max(1, totalMemMiB(containers)));
    }

    /** @return read-only view of this pod's containers. */
    public List<KubernetesContainer> getContainers() {
        return containers;
    }

    /** @return read-only view of this pod's tolerations. */
    public List<Toleration> getTolerations() {
        return Collections.unmodifiableList(tolerations);
    }

    /** Adds a toleration declaration to this pod. */
    public KubernetesPod addToleration(@NonNull final Toleration toleration) {
        tolerations.add(toleration);
        return this;
    }

    /** Convenience: bulk-add. */
    public KubernetesPod addTolerations(final List<Toleration> ts) {
        if (ts != null) {
            ts.forEach(this::addToleration);
        }
        return this;
    }

    /** Declares a ConfigMap dependency by name; checked at pod startup. */
    public KubernetesPod mountConfigMap(@NonNull final String configMapName) {
        configMapRefs.add(configMapName);
        return this;
    }

    /** Declares a Secret dependency by name; checked at pod startup. */
    public KubernetesPod mountSecret(@NonNull final String secretName) {
        secretRefs.add(secretName);
        return this;
    }

    /** Declares a PVC dependency by name; the kubelet blocks startup until
     *  the PVC is bound. */
    public KubernetesPod requirePersistentVolumeClaim(@NonNull final String pvcName) {
        persistentVolumeClaimRefs.add(pvcName);
        return this;
    }

    /** @return read-only view of declared ConfigMap dependencies. */
    public List<String> getConfigMapRefs() {
        return Collections.unmodifiableList(configMapRefs);
    }

    /** @return read-only view of declared Secret dependencies. */
    public List<String> getSecretRefs() {
        return Collections.unmodifiableList(secretRefs);
    }

    /** @return read-only view of declared PVC dependencies. */
    public List<String> getPersistentVolumeClaimRefs() {
        return Collections.unmodifiableList(persistentVolumeClaimRefs);
    }

    /**
     * @return the fully-qualified pod name as {@code "namespace/podName"}, used
     * by the broker as a stable lookup key.
     */
    public String qualifiedName() {
        return namespace.getName() + "/" + podName;
    }

    /** @return whether this pod's {@link PodCondition#READY} condition is currently true. */
    public boolean isReady() {
        return Boolean.TRUE.equals(conditions.get(PodCondition.READY));
    }

    /**
     * @return whether this pod's {@link PodCondition#POD_SCHEDULED} condition
     *         is currently true. The kubelet sets this on successful placement,
     *         distinguishing a never-placed Pending pod (POD_SCHEDULED=false)
     *         from a placed pod that may transition into other states.
     *         Used by {@link org.cloudsimplus.kubernetes.controllers.ReplicaSetController}
     *         to skip "lost" events for pods that never bound to a node.
     */
    public boolean isScheduled() {
        return Boolean.TRUE.equals(conditions.get(PodCondition.POD_SCHEDULED));
    }

    /** Sets a single pod condition; chainable. */
    public KubernetesPod setCondition(@NonNull final PodCondition condition, final boolean value) {
        conditions.put(condition, value);
        return this;
    }

    /** @return read-only view of this pod's conditions. */
    public Map<PodCondition, Boolean> getConditions() {
        return Collections.unmodifiableMap(conditions);
    }

    /**
     * Mark this pod as unschedulable: the scheduler has run its strict filters
     * over every node in the cluster and none passed. Called by the scheduler;
     * read by the {@link org.cloudsimplus.kubernetes.autoscaling.ClusterAutoscaler}
     * to decide when to provision a new node.
     *
     * @param at the simulation clock time of the failed attempt
     */
    public KubernetesPod markUnschedulable(final double at) {
        this.unschedulable = true;
        this.schedulingAttempts++;
        this.lastSchedulingAttemptAt = at;
        return this;
    }

    /**
     * Mark this pod as successfully scheduled (or eligible for re-scheduling
     * after the cluster grew). Clears the {@link #isUnschedulable() unschedulable}
     * flag so the autoscaler stops treating it as pending.
     */
    public KubernetesPod clearUnschedulable() {
        this.unschedulable = false;
        return this;
    }

    @Override
    public String toString() {
        return "Pod[%s]".formatted(qualifiedName());
    }

    private static long totalPes(final List<KubernetesContainer> containers) {
        if (containers == null || containers.isEmpty()) {
            return 1;
        }
        return containers.stream().mapToLong(KubernetesContainer::getPesNumber).sum();
    }

    private static long totalMemMiB(final List<KubernetesContainer> containers) {
        // K8s sizes pod-level memory by limits (or requests when limits are unset,
        // which the container ctor flattens to limits=requests). Container
        // construction guarantees limits >= requests, so this is well-defined.
        return containers.stream()
            .mapToLong(c -> c.getLimits().memMiB())
            .sum();
    }
}

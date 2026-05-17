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
package org.cloudsimplus.kubernetes.lifecycle;

import ch.qos.logback.classic.Level;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyTopologyAware.Policy;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.scheduler.KubernetesScheduler;
import org.cloudsimplus.kubernetes.security.ConfigMap;
import org.cloudsimplus.kubernetes.security.Secret;
import org.cloudsimplus.kubernetes.security.ServiceAccount;
import org.cloudsimplus.kubernetes.storage.PersistentVolume;
import org.cloudsimplus.kubernetes.storage.PersistentVolumeClaim;
import org.cloudsimplus.util.Log;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link Kubelet} pre-flight check that holds a pod in PENDING
 * until every required ConfigMap, Secret, ServiceAccount, and bound PVC is
 * present on the broker. Once the dependencies appear, the pod must transition
 * to RUNNING on the next tick.
 */
class KubeletPreflightTest {

    @BeforeAll
    static void quiet() {
        Log.setLevel(Level.OFF);
    }

    @Test
    void podWithMissingConfigMapStaysPending() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(2, 1000).ram(1024).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);

        final var pod = PodBuilder.of("needs-config")
            .container(ContainerBuilder.of("c").length(2_000).build())
            .build();
        pod.mountConfigMap("missing-cm");
        broker.submitPod(pod);

        sim.terminateAt(10.0);
        sim.start();

        // Pre-flight failed → pod should remain PENDING (no main containers started).
        assertEquals(PodPhase.PENDING, pod.getPhase(),
            "Pod with missing ConfigMap must stay Pending");
    }

    @Test
    void podWithBoundPvcReachesRunning() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(2, 1000).ram(1024).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);

        broker.addPersistentVolume(new PersistentVolume("pv-1", 10_000));
        broker.addPersistentVolumeClaim(
            new PersistentVolumeClaim("data", Namespace.DEFAULT, 5_000));

        final var pod = PodBuilder.of("with-pvc")
            .container(ContainerBuilder.of("c").length(1_000).build())
            .build();
        pod.requirePersistentVolumeClaim("data");
        broker.submitPod(pod);

        sim.terminateAt(60.0);
        sim.start();

        assertTrue(pod.getPhase() == PodPhase.RUNNING || pod.getPhase() == PodPhase.SUCCEEDED,
            "Pod with bound PVC should run; got phase=" + pod.getPhase());
    }

    @Test
    void podWithUnboundPvcStaysPending() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(2, 1000).ram(1024).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);

        // PVC registered but no PV — claim stays unbound.
        broker.addPersistentVolumeClaim(
            new PersistentVolumeClaim("data", Namespace.DEFAULT, 5_000));

        final var pod = PodBuilder.of("waits-for-pv")
            .container(ContainerBuilder.of("c").length(1_000).build())
            .build();
        pod.requirePersistentVolumeClaim("data");
        broker.submitPod(pod);

        sim.terminateAt(10.0);
        sim.start();

        assertEquals(PodPhase.PENDING, pod.getPhase(),
            "Pod with unbound PVC must stay Pending");
    }

    @Test
    void allDependenciesPresentAllowsPodToRun() {
        final var sim = new CloudSimPlus();
        new DatacenterSimple(sim,
            List.of(NodeBuilder.of("n").pes(2, 1000).ram(1024).build()),
            new KubernetesScheduler(Policy.COST_OPTIMIZED));
        final var broker = new KubernetesClusterBroker(sim);
        broker.addConfigMap(new ConfigMap("app-config", Namespace.DEFAULT));
        broker.addSecret(new Secret("creds", Namespace.DEFAULT));
        broker.addServiceAccount(new ServiceAccount("default-sa", Namespace.DEFAULT));

        final var pod = PodBuilder.of("p")
            .container(ContainerBuilder.of("c").length(1_000).build())
            .build()
            .mountConfigMap("app-config")
            .mountSecret("creds")
            .setServiceAccountName("default-sa");
        broker.submitPod(pod);

        sim.terminateAt(60.0);
        sim.start();

        assertTrue(pod.getPhase() == PodPhase.RUNNING || pod.getPhase() == PodPhase.SUCCEEDED,
            "Pod with all dependencies present must reach Running");
    }
}

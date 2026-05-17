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

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for the May 2026 fix to {@link DeploymentController#setManager(ControllerManager)}.
 *
 * <p>Before the fix, every {@link DeploymentController} constructed its
 * initial child {@link ReplicaSetController} with the sentinel uid {@code -1}
 * (no manager available yet). When two sibling Deployments were registered on
 * the same broker, both child RSes hit the registry with uid=-1; the second
 * was silently dropped, its {@code setManager} never invoked, and its first
 * {@code reconcile()} threw NPE on {@code manager.broker()}.</p>
 *
 * <p>This test reproduces that scenario via the public broker API and
 * verifies that both child RSes get distinct, non-sentinel uids and that
 * {@code reconcile()} on the second one does not throw.</p>
 */
class TwoSiblingDeploymentsTest {

    @Test
    void siblingDeploymentsBothGetUniqueChildReplicaSetUids() {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());

        final var depA = newDeployment(broker, "alpha");
        final var depB = newDeployment(broker, "beta");
        broker.addController(depA);
        broker.addController(depB);

        final var rsA = depA.getActiveReplicaSet();
        final var rsB = depB.getActiveReplicaSet();
        assertNotNull(rsA, "alpha must own a child RS");
        assertNotNull(rsB, "beta must own a child RS");
        assertNotEquals(-1L, rsA.getUid(), "alpha's child RS must get a real uid, not the -1 sentinel");
        assertNotEquals(-1L, rsB.getUid(), "beta's child RS must get a real uid, not the -1 sentinel");
        assertNotEquals(rsA.getUid(), rsB.getUid(),
            "Sibling Deployments must produce child RSes with distinct uids");
    }

    @Test
    void secondSiblingsReconcileDoesNotThrow() {
        // Pre-fix: this throws NPE because the second RS was dropped from the
        // registry and never had its manager wired up.
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var depA = newDeployment(broker, "alpha");
        final var depB = newDeployment(broker, "beta");
        broker.addController(depA);
        broker.addController(depB);

        // Driving reconcile manually rather than running the sim — we just want
        // to assert no exception escapes.
        broker.getControllerManager().reconcileAll();
    }

    private static DeploymentController newDeployment(
        final KubernetesClusterBroker broker, final String name)
    {
        final var template = new PodTemplate(ord -> PodBuilder.of(name + "-" + ord)
            .container(ContainerBuilder.of("c").cpu("100m").mem("32Mi").length(1).build())
            .build());
        return new DeploymentController(
            broker.getControllerManager().allocateUid(),
            name, Namespace.DEFAULT, template, /* desiredReplicas */ 0);
    }
}

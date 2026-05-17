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
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.Resources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DaemonSetRollingUpdateTest {

    private static KubernetesPod pod() {
        return new KubernetesPod("ds-pod",
            List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi"))));
    }

    private static DaemonSetController newDs() {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var ds = new DaemonSetController(
            broker.getControllerManager().allocateUid(), "ds", Namespace.DEFAULT,
            new PodTemplate(ord -> pod()));
        broker.addController(ds);
        return ds;
    }

    @Test
    void revisionAdvancesOnEverySetTemplateCall() {
        final var ds = newDs();
        final int initial = ds.getTemplateRevision();
        ds.setTemplate(new PodTemplate(ord -> pod()));
        ds.setTemplate(new PodTemplate(ord -> pod()));
        assertEquals(initial + 2, ds.getTemplateRevision(),
            "Each setTemplate must bump the revision so RU can detect staleness");
    }

    @Test
    void differentTemplateInstancesProduceDifferentRevisions() {
        final var ds = newDs();
        final int rev1 = ds.getTemplateRevision();
        ds.setTemplate(new PodTemplate(ord -> pod()));
        final int rev2 = ds.getTemplateRevision();
        assertNotEquals(rev1, rev2);
    }

    @Test
    void rollingUpdateStrategyIsOptIn() {
        final var ds = newDs();
        assertEquals(DaemonSetController.UpdateStrategyType.ON_DELETE, ds.getUpdateStrategy());
        ds.setUpdateStrategy(DaemonSetController.UpdateStrategyType.ROLLING_UPDATE);
        assertEquals(DaemonSetController.UpdateStrategyType.ROLLING_UPDATE, ds.getUpdateStrategy());
    }
}

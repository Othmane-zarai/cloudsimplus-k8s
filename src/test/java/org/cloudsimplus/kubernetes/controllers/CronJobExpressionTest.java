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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CronJobExpressionTest {

    private static KubernetesPod pod() {
        return new KubernetesPod("p",
            List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi"))));
    }

    private static CronJobController newCron(final String name) {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var cj = new CronJobController(
            broker.getControllerManager().allocateUid(), name, Namespace.DEFAULT,
            (uid, idx) -> new JobController(uid, name + "-" + idx, Namespace.DEFAULT,
                new PodTemplate(ord -> pod())));
        broker.addController(cj);
        return cj;
    }

    @Test
    void invalidCronExpressionFailsAtSetup() {
        final var cj = newCron("c");
        assertThrows(IllegalArgumentException.class,
            () -> cj.setCronExpression("not a cron"));
    }

    @Test
    void blankCronExpressionRejected() {
        final var cj = newCron("c");
        assertThrows(IllegalArgumentException.class,
            () -> cj.setCronExpression(""));
        assertThrows(IllegalArgumentException.class,
            () -> cj.setCronExpression(null));
    }

    @Test
    void validCronExpressionAcceptedAndStored() {
        final var cj = newCron("c");
        cj.setCronExpression("*/5 * * * *");  // every 5 minutes
        assertEquals("*/5 * * * *", cj.getCronExpression());
        assertNotNull(cj.getExecutionTime());
    }

    @Test
    void everyFifteenMinutesExpressionCompiles() {
        // Validates a slightly more interesting pattern: the 0,15,30,45 minute marks.
        final var cj = newCron("c");
        cj.setCronExpression("0,15,30,45 * * * *");
        assertNotNull(cj.getExecutionTime());
    }

    @Test
    void simulationEpochIsConfigurable() {
        final var cj = newCron("c");
        cj.setSimulationEpochSeconds(0L);
        assertEquals(0L, cj.getSimulationEpochSeconds());
    }
}

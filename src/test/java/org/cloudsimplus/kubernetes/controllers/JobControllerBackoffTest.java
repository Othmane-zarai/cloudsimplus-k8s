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

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.KubernetesContainer;
import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.Resources;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link JobController} backoff / completion logic that the
 * end-to-end Job test (which runs only the success path) does not cover.
 *
 * <p>We invoke the package-private {@code onContainerFinished} hook directly
 * with a stubbed cloudlet whose status is {@link Cloudlet.Status#FAILED} so
 * the failure-counting path executes without needing a full simulation that
 * forces a cloudlet to fail.</p>
 */
class JobControllerBackoffTest {

    private static KubernetesPod pod(final String name) {
        return new KubernetesPod(name,
            List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi"))));
    }

    private static Cloudlet failingCloudlet() {
        final var c = Mockito.mock(CloudletSimple.class);
        Mockito.when(c.getStatus()).thenReturn(Cloudlet.Status.FAILED);
        return c;
    }

    private static Cloudlet succeededCloudlet() {
        final var c = Mockito.mock(CloudletSimple.class);
        Mockito.when(c.getStatus()).thenReturn(Cloudlet.Status.SUCCESS);
        return c;
    }

    /** Build a JobController already wired to a real broker / manager. */
    private static JobController newRegisteredJob(final String name) {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var jc = new JobController(broker.getControllerManager().allocateUid(),
            name, Namespace.DEFAULT,
            new PodTemplate(ord -> pod(name + "-" + ord)));
        broker.addController(jc);
        return jc;
    }

    @Test
    void backoffLimitExceededHaltsFurtherSpawns() {
        final var jc = newRegisteredJob("batch");
        jc.setCompletions(5).setParallelism(1).setBackoffLimit(2);

        // Simulate 3 failures in a row. Track them via the public success/failure
        // counters: spinning up an active pod manually and then telling the
        // controller it failed.
        for (int i = 0; i < 3; i++) {
            final var p = pod("batch-" + i);
            // Stamp owner-uid label so onPodCreated/onPodLost would recognize it.
            p.setLabels(LabelSet.of()
                .with(Controller.LABEL_CONTROLLER_UID, Long.toString(jc.getUid()))
                .with(Controller.LABEL_CONTROLLER_KIND, jc.getKind())
                .build());
            jc.getActive().add(p);
            jc.onContainerFinished(p, failingCloudlet());
        }

        assertTrue(jc.getFailures() >= 3,
            "Expected >=3 recorded failures (got " + jc.getFailures() + ")");
        assertTrue(jc.isComplete(),
            "Job should be 'complete' (terminal) once failures exceed backoffLimit (failures="
                + jc.getFailures() + ", backoffLimit=" + jc.getBackoffLimit() + ")");
    }

    @Test
    void successfulRunsAccrueTowardCompletions() {
        final var jc = newRegisteredJob("batch2");
        jc.setCompletions(2).setParallelism(1).setBackoffLimit(0);

        final var p1 = pod("batch-0");
        jc.getActive().add(p1);
        jc.onContainerFinished(p1, succeededCloudlet());

        assertFalse(jc.isComplete(), "Single success below completions=2 should not finish the job");

        final var p2 = pod("batch-1");
        jc.getActive().add(p2);
        jc.onContainerFinished(p2, succeededCloudlet());

        assertTrue(jc.isComplete(), "Two successes should reach completions=2");
    }

    @Test
    void multiContainerPodSucceedsOnlyAfterAllContainersFinish() {
        // M3 fix: the previous "first finish ⇒ pod done" simplification credited
        // a pod's success after just one container completed, mis-counting
        // multi-container pod outcomes. The new semantics defer the success
        // verdict until every container has finished.
        final var c1 = new KubernetesContainer("a", 1, Resources.of("100m", "32Mi"));
        final var c2 = new KubernetesContainer("b", 1, Resources.of("100m", "32Mi"));
        final var p = new KubernetesPod("multi", List.of(c1, c2));

        final var jc = newRegisteredJob("multi-job");
        jc.setCompletions(1).setParallelism(1).setBackoffLimit(0);
        jc.getActive().add(p);

        // First container finishes: pod is NOT yet credited as a completion.
        c1.setStatus(org.cloudsimplus.cloudlets.Cloudlet.Status.SUCCESS);
        jc.onContainerFinished(p, c1);
        assertFalse(jc.isComplete(),
            "Pod must not be credited until all containers finish");

        // Second container finishes: pod is credited and the Job completes.
        c2.setStatus(org.cloudsimplus.cloudlets.Cloudlet.Status.SUCCESS);
        jc.onContainerFinished(p, c2);
        assertTrue(jc.isComplete(),
            "Pod credited as success once both containers finish");
        assertTrue(jc.getSucceeded() == 1, "Should record exactly one pod success");
    }

    @Test
    void multiContainerPodFailsOnAnyContainerFailure() {
        // Symmetric to the above: failure of any non-restarting container fails
        // the whole pod (matching K8s Job semantics where backoffLimit counts
        // pod-level failures).
        final var c1 = new KubernetesContainer("a", 1, Resources.of("100m", "32Mi"));
        final var c2 = new KubernetesContainer("b", 1, Resources.of("100m", "32Mi"));
        final var p = new KubernetesPod("multi-fail", List.of(c1, c2));

        final var jc = newRegisteredJob("multi-fail-job");
        jc.setCompletions(2).setParallelism(1).setBackoffLimit(0);
        jc.getActive().add(p);

        c1.setStatus(org.cloudsimplus.cloudlets.Cloudlet.Status.FAILED);
        jc.onContainerFinished(p, c1);
        assertTrue(jc.isComplete(),
            "Pod fails as soon as any container fails ⇒ Job hits backoffLimit=0 immediately");
        assertTrue(jc.getFailures() == 1);
    }
}

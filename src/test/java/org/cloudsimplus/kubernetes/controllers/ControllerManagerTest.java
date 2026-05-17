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
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.Resources;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level tests for {@link ControllerManager}: UID allocation, registration,
 * and event routing by owner-reference labels (no simulation run required).
 */
class ControllerManagerTest {

    @Test
    void allocateUidProducesUniqueIds() {
        final var mgr = manager();
        final var a = mgr.allocateUid();
        final var b = mgr.allocateUid();
        final var c = mgr.allocateUid();
        assertEquals(3, java.util.Set.of(a, b, c).size());
    }

    @Test
    void registerWiresManagerBackReference() {
        final var mgr = manager();
        final var ctl = new RecordingController(mgr.allocateUid());
        mgr.register(ctl);
        assertSame(mgr, ctl.injectedManager);
        assertTrue(mgr.getController(ctl.getUid()).isPresent());
    }

    @Test
    void onPodCreatedRoutesToOwningController() {
        final var mgr = manager();
        final var ctl = new RecordingController(mgr.allocateUid());
        mgr.register(ctl);

        mgr.onPodCreated(podWithOwner(ctl.getUid()));
        assertEquals(1, ctl.created.size());
        mgr.onPodLost(podWithOwner(ctl.getUid()));
        assertEquals(1, ctl.lost.size());
    }

    @Test
    void unownedPodIsNotRouted() {
        final var mgr = manager();
        final var ctl = new RecordingController(mgr.allocateUid());
        mgr.register(ctl);

        // Pod has no controller-uid label.
        mgr.onPodCreated(buildPod(LabelSet.EMPTY));
        assertEquals(0, ctl.created.size());
    }

    @Test
    void mismatchedOwnerLabelDoesNotRouteToOtherController() {
        final var mgr = manager();
        final var a = new RecordingController(mgr.allocateUid());
        final var b = new RecordingController(mgr.allocateUid());
        mgr.register(a);
        mgr.register(b);

        mgr.onPodCreated(podWithOwner(b.getUid()));
        assertEquals(0, a.created.size());
        assertEquals(1, b.created.size());
    }

    @Test
    void reconcileAllInvokesEveryRegisteredController() {
        final var mgr = manager();
        final var a = new RecordingController(mgr.allocateUid());
        final var b = new RecordingController(mgr.allocateUid());
        mgr.register(a);
        mgr.register(b);

        mgr.reconcileAll();
        mgr.reconcileAll();
        assertEquals(2, a.reconciles);
        assertEquals(2, b.reconciles);
    }

    @Test
    void reconcileAllSwallowsControllerExceptions() {
        final var mgr = manager();
        final var bad = new RecordingController(mgr.allocateUid()) {
            @Override
            public void reconcile() {
                throw new IllegalStateException("boom");
            }
        };
        final var good = new RecordingController(mgr.allocateUid());
        mgr.register(bad);
        mgr.register(good);

        mgr.reconcileAll(); // must not propagate
        assertEquals(1, good.reconciles, "Sibling controller still reconciles after a peer fails");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ControllerManager manager() {
        return new KubernetesClusterBroker(new CloudSimPlus()).getControllerManager();
    }

    private static KubernetesPod podWithOwner(final long uid) {
        final var labels = LabelSet.of()
            .with(Controller.LABEL_CONTROLLER_UID, Long.toString(uid))
            .with(Controller.LABEL_CONTROLLER_KIND, "Test")
            .build();
        return buildPod(labels);
    }

    private static KubernetesPod buildPod(final LabelSet labels) {
        final var pod = new KubernetesPod("p",
            List.of(new KubernetesContainer("c", 1, Resources.of("100m", "32Mi"))));
        pod.setLabels(labels);
        return pod;
    }

    /** Test double that records lifecycle calls. */
    private static class RecordingController implements Controller {
        final long uid;
        ControllerManager injectedManager;
        final List<KubernetesPod> created = new ArrayList<>();
        final List<KubernetesPod> lost = new ArrayList<>();
        int reconciles;

        RecordingController(final long uid) {
            this.uid = uid;
        }

        @Override public long getUid() { return uid; }
        @Override public String getName() { return "rec-" + uid; }
        @Override public Namespace getNamespace() { return Namespace.DEFAULT; }
        @Override public String getKind() { return "Test"; }

        @Override
        public Controller setManager(final ControllerManager manager) {
            this.injectedManager = manager;
            return this;
        }

        @Override public void onPodCreated(final KubernetesPod pod) { created.add(pod); }
        @Override public void onPodLost(final KubernetesPod pod) { lost.add(pod); }
        @Override public void reconcile() { reconciles++; }
    }
}

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
package org.cloudsimplus.kubernetes.storage;

import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.kubernetes.KubernetesClusterBroker;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.LabelSet;
import org.cloudsimplus.kubernetes.Namespace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentVolumeTest {

    @Test
    void newPvIsUnboundAndCarriesUnderlyingStorage() {
        final var pv = new PersistentVolume("pv-1", 10_000);
        assertFalse(pv.isBound());
        assertEquals(10_000, pv.getStorage().getCapacity());
    }

    @Test
    void bindingPvAndPvcIsBidirectional() {
        final var pv = new PersistentVolume("pv-1", 10_000);
        final var pvc = new PersistentVolumeClaim("data", Namespace.DEFAULT, 5_000);

        pv.bind(pvc);
        pvc.bind(pv);

        assertTrue(pv.isBound());
        assertTrue(pvc.isBound());
        assertSame(pvc, pv.getClaimRef());
        assertSame(pv, pvc.getVolumeRef());
    }

    @Test
    void newPvcIsUnbound() {
        final var pvc = new PersistentVolumeClaim("data", Namespace.DEFAULT, 1_000);
        assertFalse(pvc.isBound());
        assertEquals(1_000, pvc.getRequestedCapacityMB());
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new PersistentVolume("pv", 0));
        assertThrows(IllegalArgumentException.class,
            () -> new PersistentVolumeClaim("c", Namespace.DEFAULT, 0));
    }

    @Test
    void brokerBindsBySelectorWhenLabelsMatch() {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var ssd = new PersistentVolume("pv-ssd", 10_000)
            .setLabels(LabelSet.of("disk", "ssd"));
        final var hdd = new PersistentVolume("pv-hdd", 10_000)
            .setLabels(LabelSet.of("disk", "hdd"));
        broker.addPersistentVolume(ssd);
        broker.addPersistentVolume(hdd);

        final var pvc = new PersistentVolumeClaim("fast", Namespace.DEFAULT, 1_000)
            .setSelector(LabelSelector.matchLabel("disk", "ssd"));
        broker.addPersistentVolumeClaim(pvc);

        assertTrue(pvc.isBound(), "Selector-matching PVC must bind to the SSD volume");
        assertSame(ssd, pvc.getVolumeRef());
        assertFalse(hdd.isBound(), "Non-matching HDD volume must remain unbound");
    }

    @Test
    void brokerHonoursStorageClassMismatch() {
        final var broker = new KubernetesClusterBroker(new CloudSimPlus());
        final var fast = new PersistentVolume("pv-fast", 10_000)
            .setStorageClassName("fast");
        broker.addPersistentVolume(fast);

        // Claim asks for the standard class — must not bind to the fast PV.
        final var pvc = new PersistentVolumeClaim("data", Namespace.DEFAULT, 1_000)
            .setStorageClassName("standard");
        broker.addPersistentVolumeClaim(pvc);

        assertFalse(pvc.isBound(),
            "PVC with storageClass='standard' must not bind to PV with storageClass='fast'");
        assertFalse(fast.isBound());
    }
}

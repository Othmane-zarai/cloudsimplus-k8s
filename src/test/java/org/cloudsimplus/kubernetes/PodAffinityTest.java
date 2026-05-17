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

import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PodAffinityTest {

    @Test
    void emptyAffinityIsNoop() {
        assertTrue(PodAffinity.NONE.isEmpty());
    }

    @Test
    void hostnameTopologyMatchesSameNode() {
        final var n1 = NodeBuilder.of("a").pes(2, 1000).zone("z1").build();
        final var n2 = NodeBuilder.of("b").pes(2, 1000).zone("z1").build();
        final var rule = new PodAffinity.Rule(
            LabelSelector.matchLabel("app", "x"),
            PodAffinity.TopologyKey.HOSTNAME,
            false, PodAffinity.Rule.REQUIRED);
        assertTrue(rule.sameBucket(n1, n1));
        assertFalse(rule.sameBucket(n1, n2));
    }

    @Test
    void zoneTopologyMatchesByAvailabilityZone() {
        final var east1 = NodeBuilder.of("a").pes(2, 1000).zone("us-east-1a").build();
        final var east2 = NodeBuilder.of("b").pes(2, 1000).zone("us-east-1a").build();
        final var west = NodeBuilder.of("c").pes(2, 1000).zone("us-west-1a").build();
        final var rule = new PodAffinity.Rule(
            LabelSelector.matchLabel("app", "x"),
            PodAffinity.TopologyKey.ZONE,
            false, PodAffinity.Rule.REQUIRED);
        assertTrue(rule.sameBucket(east1, east2));
        assertFalse(rule.sameBucket(east1, west));
    }

    @Test
    void requiredAndPreferredCoexist() {
        final var aff = PodAffinity.builder()
            .requireAffinity(LabelSelector.matchLabel("app", "x"), PodAffinity.TopologyKey.HOSTNAME)
            .preferAntiAffinity(LabelSelector.matchLabel("tier", "noisy"), PodAffinity.TopologyKey.HOSTNAME, 30)
            .build();
        assertFalse(aff.isEmpty());
        assertTrue(aff.getRules().get(0).isRequired());
        assertFalse(aff.getRules().get(1).isRequired());
        assertTrue(aff.getRules().get(1).antiAffinity());
    }

    @Test
    void invalidWeightRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new PodAffinity.Rule(LabelSelector.MATCH_ALL, PodAffinity.TopologyKey.HOSTNAME, false, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new PodAffinity.Rule(LabelSelector.MATCH_ALL, PodAffinity.TopologyKey.HOSTNAME, false, 200));
    }
}

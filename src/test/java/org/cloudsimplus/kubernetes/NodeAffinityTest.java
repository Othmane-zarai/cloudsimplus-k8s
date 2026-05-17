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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeAffinityTest {

    private static final LabelSet GPU_NODE = LabelSet.of()
        .with("hardware", "gpu").with("zone", "us-east-1a").build();

    private static final LabelSet GENERAL_NODE = LabelSet.of()
        .with("hardware", "cpu").with("zone", "us-east-1b").build();

    @Test
    void emptyAffinityNeverBlocks() {
        assertTrue(NodeAffinity.NONE.requiredMatches(GPU_NODE));
        assertTrue(NodeAffinity.NONE.requiredMatches(LabelSet.EMPTY));
        assertEquals(0, NodeAffinity.NONE.preferredScore(GPU_NODE));
    }

    @Test
    void requiredRulesAreOredAcrossSelectors() {
        final var aff = NodeAffinity.builder()
            .require(LabelSelector.matchLabel("hardware", "gpu"))
            .require(LabelSelector.matchLabel("hardware", "tpu"))
            .build();
        assertTrue(aff.requiredMatches(GPU_NODE));
        assertFalse(aff.requiredMatches(GENERAL_NODE));
    }

    @Test
    void preferredWeightsSumWhenMatched() {
        final var aff = NodeAffinity.builder()
            .prefer(LabelSelector.matchLabel("zone", "us-east-1a"), 30)
            .prefer(LabelSelector.matchLabel("hardware", "gpu"), 50)
            .build();
        assertEquals(80, aff.preferredScore(GPU_NODE));
        assertEquals(0, aff.preferredScore(GENERAL_NODE));
    }

    @Test
    void weightOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new NodeAffinity.Preference(LabelSelector.MATCH_ALL, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new NodeAffinity.Preference(LabelSelector.MATCH_ALL, NodeAffinity.MAX_WEIGHT + 1));
    }
}

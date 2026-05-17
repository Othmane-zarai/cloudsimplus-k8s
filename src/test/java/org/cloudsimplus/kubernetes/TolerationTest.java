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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TolerationTest {

    private final Taint dedicatedGpu = new Taint("dedicated", "gpu", Taint.Effect.NO_SCHEDULE);
    private final Taint dedicatedDb = new Taint("dedicated", "db", Taint.Effect.NO_SCHEDULE);
    private final Taint maintenance = Taint.noSchedule("maintenance");

    @Test
    void equalOperatorMatchesKeyAndValue() {
        final var t = Toleration.equal("dedicated", "gpu");
        assertTrue(t.tolerates(dedicatedGpu));
        assertFalse(t.tolerates(dedicatedDb));
    }

    @Test
    void existsOperatorMatchesKeyOnly() {
        final var t = Toleration.exists("dedicated");
        assertTrue(t.tolerates(dedicatedGpu));
        assertTrue(t.tolerates(dedicatedDb));
        assertFalse(t.tolerates(maintenance));
    }

    @Test
    void effectScopeNarrowsMatch() {
        final var only = Toleration.equal("dedicated", "gpu", Taint.Effect.NO_EXECUTE);
        // taint has NO_SCHEDULE effect → not covered
        assertFalse(only.tolerates(dedicatedGpu));
    }

    @Test
    void existsRejectsValuePresence() {
        assertThrows(IllegalArgumentException.class,
            () -> new Toleration("k", Toleration.Operator.EXISTS, "v", null));
    }

    @Test
    void coversAllOnlyConsidersBlockingEffects() {
        final var taints = List.of(
            new Taint("dedicated", "gpu", Taint.Effect.NO_SCHEDULE),
            new Taint("hint", "yes", Taint.Effect.PREFER_NO_SCHEDULE));
        // Only the NoSchedule taint must be covered.
        assertTrue(Toleration.coversAll(List.of(Toleration.equal("dedicated", "gpu")), taints));
        assertFalse(Toleration.coversAll(List.of(), taints));
    }
}

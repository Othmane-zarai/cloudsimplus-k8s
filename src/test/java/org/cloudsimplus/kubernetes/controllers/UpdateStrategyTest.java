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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateStrategyTest {

    @Test
    void rollingUpdateDefaultsAreSensible() {
        final var def = UpdateStrategy.RollingUpdate.defaults();
        assertEquals(1, def.maxSurge());
        assertEquals(0, def.maxUnavailable());
    }

    @Test
    void rollingUpdateRejectsNegativeBudgets() {
        assertThrows(IllegalArgumentException.class,
            () -> new UpdateStrategy.RollingUpdate(-1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new UpdateStrategy.RollingUpdate(0, -1));
    }

    @Test
    void rollingUpdateRejectsZeroBudget() {
        // Both maxSurge and maxUnavailable being 0 would block any progress.
        assertThrows(IllegalArgumentException.class,
            () -> new UpdateStrategy.RollingUpdate(0, 0));
    }

    @Test
    void recreateIsValid() {
        final var r = new UpdateStrategy.Recreate();
        assertTrue(r instanceof UpdateStrategy);
    }
}

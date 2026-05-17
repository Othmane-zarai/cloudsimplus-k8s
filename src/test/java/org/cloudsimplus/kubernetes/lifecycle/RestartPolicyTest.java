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
package org.cloudsimplus.kubernetes.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestartPolicyTest {

    @Test
    void alwaysRestartsRegardlessOfFailure() {
        assertTrue(RestartPolicy.shouldRestart(RestartPolicy.ALWAYS, false));
        assertTrue(RestartPolicy.shouldRestart(RestartPolicy.ALWAYS, true));
    }

    @Test
    void onFailureOnlyRestartsAfterFailure() {
        assertFalse(RestartPolicy.shouldRestart(RestartPolicy.ON_FAILURE, false));
        assertTrue(RestartPolicy.shouldRestart(RestartPolicy.ON_FAILURE, true));
    }

    @Test
    void neverNeverRestarts() {
        assertFalse(RestartPolicy.shouldRestart(RestartPolicy.NEVER, false));
        assertFalse(RestartPolicy.shouldRestart(RestartPolicy.NEVER, true));
    }

    @Test
    void nullPolicyTreatedAsNever() {
        assertFalse(RestartPolicy.shouldRestart(null, true));
        assertFalse(RestartPolicy.shouldRestart(null, false));
    }
}

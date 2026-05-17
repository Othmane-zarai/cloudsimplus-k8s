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
package org.cloudsimplus.kubernetes.autoscaling;

import org.cloudsimplus.kubernetes.builders.NodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodePoolTest {

    @Test
    void validBoundsAccepted() {
        final var pool = new NodePool("workers",
            () -> NodeBuilder.of("worker-x").pes(2, 1000).build(), 0, 5);
        assertEquals("workers", pool.getName());
        assertEquals(0, pool.getMin());
        assertEquals(5, pool.getMax());
    }

    @Test
    void invalidBoundsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new NodePool("p", () -> null, -1, 5));
        assertThrows(IllegalArgumentException.class,
            () -> new NodePool("p", () -> null, 5, 1));
    }

    @Test
    void templateProducesFreshInstancesPerCall() {
        final var pool = new NodePool("workers",
            () -> NodeBuilder.of("worker-x").pes(2, 1000).build(), 0, 5);
        final var a = pool.getTemplate().get();
        final var b = pool.getTemplate().get();
        assertNotSame(a, b, "NodePool template must produce a new node each invocation");
    }
}

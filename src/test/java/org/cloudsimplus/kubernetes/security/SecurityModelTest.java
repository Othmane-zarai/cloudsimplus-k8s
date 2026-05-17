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
package org.cloudsimplus.kubernetes.security;

import org.cloudsimplus.kubernetes.Namespace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SecurityModelTest {

    @Test
    void configMapStoresKeyValueData() {
        final var cm = new ConfigMap("app-config", Namespace.DEFAULT)
            .putData("LOG_LEVEL", "INFO")
            .putData("MAX_CONNECTIONS", "100");
        assertEquals("INFO", cm.getData().get("LOG_LEVEL"));
        assertEquals("100", cm.getData().get("MAX_CONNECTIONS"));
    }

    @Test
    void secretStoresBinaryData() {
        final var secret = new Secret("db-creds", Namespace.DEFAULT)
            .putData("password", new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, secret.getData().get("password"));
    }

    @Test
    void roleBindingLinksServiceAccountToRole() {
        final var sa = new ServiceAccount("ci", Namespace.DEFAULT);
        final var role = new Role("readonly", Namespace.DEFAULT).addRule("get pods");
        final var binding = new RoleBinding("ci-readonly", Namespace.DEFAULT, role, sa);

        assertSame(role, binding.getRoleRef());
        assertSame(sa, binding.getSubject());
        assertEquals(1, role.getRules().size());
    }
}

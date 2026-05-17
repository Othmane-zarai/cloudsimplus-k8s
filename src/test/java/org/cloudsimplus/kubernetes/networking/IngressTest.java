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
package org.cloudsimplus.kubernetes.networking;

import org.cloudsimplus.kubernetes.KubernetesService;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngressTest {

    private static KubernetesService svc(final String name) {
        return new KubernetesService(name, Namespace.DEFAULT, LabelSelector.matchLabel("app", name));
    }

    @Test
    void longestPathPrefixWinsWithinAnIngress() {
        final var apiSvc = svc("api");
        final var apiV2Svc = svc("api-v2");
        final var ing = new Ingress("web", Namespace.DEFAULT)
            .addRule(new Ingress.IngressRule("example.com", "/api", apiSvc))
            .addRule(new Ingress.IngressRule("example.com", "/api/v2", apiV2Svc));

        assertEquals(apiV2Svc, ing.route("example.com", "/api/v2/users").orElseThrow());
        assertEquals(apiSvc,  ing.route("example.com", "/api/v1/users").orElseThrow());
    }

    @Test
    void hostMustMatchWhenSetExplicitly() {
        final var web = svc("web");
        final var ing = new Ingress("ing", Namespace.DEFAULT)
            .addRule(new Ingress.IngressRule("example.com", "/", web));
        assertTrue(ing.route("other.com", "/").isEmpty());
        assertEquals(web, ing.route("example.com", "/").orElseThrow());
    }

    @Test
    void wildcardHostRuleMatchesAnyHost() {
        final var fallback = svc("fallback");
        final var ing = new Ingress("ing", Namespace.DEFAULT)
            .addRule(new Ingress.IngressRule(null, "/", fallback));
        assertEquals(fallback, ing.route("anything.example", "/").orElseThrow());
    }

    @Test
    void noMatchYieldsEmpty() {
        final var ing = new Ingress("ing", Namespace.DEFAULT)
            .addRule(new Ingress.IngressRule("a.com", "/foo", svc("a")));
        assertTrue(ing.route("a.com", "/bar").isEmpty());
    }
}

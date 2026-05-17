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

import org.cloudsimplus.kubernetes.KubernetesPod;
import org.cloudsimplus.kubernetes.LabelSelector;
import org.cloudsimplus.kubernetes.Namespace;
import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkPolicyTest {

    private KubernetesPod pod(final String key, final String value) {
        return PodBuilder.of("p-" + value)
            .label(key, value)
            .container(ContainerBuilder.of("c").length(1).build())
            .build();
    }

    @Test
    void selectorMatchesPodWithCorrespondingLabel() {
        final var policy = new NetworkPolicy(
            "deny-frontend", Namespace.DEFAULT, LabelSelector.matchLabel("tier", "frontend"));
        assertTrue(policy.getPodSelector().matches(pod("tier", "frontend").getLabels()));
        assertFalse(policy.getPodSelector().matches(pod("tier", "backend").getLabels()));
    }

    @Test
    void defaultsAllowIngress() {
        final var policy = new NetworkPolicy(
            "open", Namespace.DEFAULT, LabelSelector.MATCH_ALL);
        assertTrue(policy.isIngressAllowed());
    }

    @Test
    void ingressCanBeDisabled() {
        final var policy = new NetworkPolicy(
            "no-ingress", Namespace.DEFAULT, LabelSelector.MATCH_ALL)
            .setIngressAllowed(false);
        assertFalse(policy.isIngressAllowed());
    }
}

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

import org.cloudsimplus.kubernetes.builders.ContainerBuilder;
import org.cloudsimplus.kubernetes.builders.PodBuilder;
import org.cloudsimplus.kubernetes.lifecycle.PodCondition;
import org.cloudsimplus.kubernetes.lifecycle.ReadinessProbe;
import org.cloudsimplus.vms.Vm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesServiceTest {

    private final Namespace ns = Namespace.DEFAULT;

    @Test
    void selectorOnlyMatchesPodsWithMatchingLabels() {
        final var backend1 = pod("backend-1", "tier", "backend");
        final var backend2 = pod("backend-2", "tier", "backend");
        final var frontend = pod("frontend-1", "tier", "frontend");

        final var svc = backendService();
        svc.setPodSource(() -> List.of(backend1, backend2, frontend));

        final var endpoints = svc.backingPods();
        assertEquals(2, endpoints.size());
        assertTrue(endpoints.contains(backend1));
        assertTrue(endpoints.contains(backend2));
    }

    @Test
    void selectVmRoundRobinsAcrossBackingPods() {
        final var backend1 = pod("backend-1", "tier", "backend");
        final var backend2 = pod("backend-2", "tier", "backend");
        final var backend3 = pod("backend-3", "tier", "backend");

        final var svc = backendService();
        svc.setPodSource(() -> List.of(backend1, backend2, backend3));

        final var picks = new ArrayList<Vm>();
        for (int i = 0; i < 6; i++) {
            picks.add(svc.selectVm());
        }
        assertSame(backend1, picks.get(0));
        assertSame(backend2, picks.get(1));
        assertSame(backend3, picks.get(2));
        assertSame(backend1, picks.get(3));
        assertSame(backend2, picks.get(4));
        assertSame(backend3, picks.get(5));
    }

    @Test
    void uncreatedPodsAreExcludedFromEndpoints() {
        final var pendingPod = pod("backend-1", "tier", "backend");
        pendingPod.setCreated(false);
        final var runningPod = pod("backend-2", "tier", "backend");
        runningPod.setCreated(true);

        final var svc = backendService();
        svc.setPodSource(() -> List.of(pendingPod, runningPod));

        final var endpoints = svc.backingPods();
        assertEquals(1, endpoints.size());
        assertSame(runningPod, endpoints.get(0));
    }

    @Test
    void emptyEndpointSetReturnsVmNull() {
        final var svc = backendService();
        svc.setPodSource(List::of);
        assertSame(Vm.NULL, svc.selectVm());
    }

    @Test
    void podWithReadinessProbeIsExcludedUntilReady() {
        // Real K8s gates Service endpoints on ReadinessProbe success; a pod with
        // a probe declared but not yet passing must not receive traffic.
        final var withProbe = PodBuilder.of("backend-probe")
            .namespace(ns).label("tier", "backend")
            .container(ContainerBuilder.of("c").length(1)
                .readinessProbe(new ReadinessProbe(c -> false))
                .build())
            .build();
        withProbe.setCreated(true);
        // READY condition not set ⇒ pod.isReady() = false ⇒ excluded.

        final var svc = backendService();
        svc.setPodSource(() -> List.of(withProbe));
        assertEquals(0, svc.backingPods().size(),
            "Pod with readiness probe but not Ready must not be a service endpoint");

        // Once the kubelet flips READY=true, the pod becomes a valid endpoint.
        withProbe.setCondition(PodCondition.READY, true);
        assertEquals(1, svc.backingPods().size(),
            "Pod with readiness probe and READY=true must be a service endpoint");
    }

    @Test
    void podWithoutReadinessProbeIsImmediatelyAnEndpoint() {
        // Mirrors K8s default: no readiness probe ⇒ pod is considered Ready as
        // soon as it is created. This is the behaviour the existing tests already
        // depend on; codified here so the rule isn't accidentally regressed.
        final var noProbe = pod("backend-noprobe", "tier", "backend");
        // setCreated(true) is set by the helper; READY condition is NOT set.

        final var svc = backendService();
        svc.setPodSource(() -> List.of(noProbe));
        assertEquals(1, svc.backingPods().size(),
            "Pod without readiness probe must be a service endpoint by default");
    }

    @Test
    void namespaceIsolatesEndpoints() {
        final var ns1 = new Namespace("team-a");
        final var ns2 = new Namespace("team-b");
        final var p1 = PodBuilder.of("p1").namespace(ns1).label("tier", "backend")
            .container(ContainerBuilder.of("c").length(1).build())
            .build();
        p1.setCreated(true);
        final var p2 = PodBuilder.of("p2").namespace(ns2).label("tier", "backend")
            .container(ContainerBuilder.of("c").length(1).build())
            .build();
        p2.setCreated(true);

        final var svcNs1 = new KubernetesService(
            "backend", ns1, LabelSelector.matchLabel("tier", "backend"));
        svcNs1.setPodSource(() -> List.of(p1, p2));

        assertEquals(1, svcNs1.backingPods().size());
        assertSame(p1, svcNs1.backingPods().get(0));
    }

    private KubernetesPod pod(final String name, final String labelKey, final String labelValue) {
        final var p = PodBuilder.of(name)
            .namespace(ns)
            .label(labelKey, labelValue)
            .container(ContainerBuilder.of("app").length(1).build())
            .build();
        p.setCreated(true);
        return p;
    }

    private KubernetesService backendService() {
        return new KubernetesService(
            "backend", ns, LabelSelector.matchLabel("tier", "backend"));
    }
}

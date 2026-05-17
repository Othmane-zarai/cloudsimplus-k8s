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

import lombok.Getter;
import lombok.NonNull;
import org.cloudsimplus.kubernetes.KubernetesService;
import org.cloudsimplus.kubernetes.Namespace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A Kubernetes Ingress: an L7 routing rule set that maps an external
 * {@code (host, path)} pair to a backing {@link KubernetesService}.
 *
 * <p><b>Routing semantics.</b> {@link #route(String, String)} performs
 * longest-prefix path matching, with an explicit-host filter on the inbound
 * {@code Host} header: rules whose {@code host} is non-null are skipped when
 * the input host differs; rules whose {@code host} is {@code null} act as a
 * wildcard fallback that matches any host. Among remaining rules, the one
 * whose {@code path} is the longest prefix of the input path wins.</p>
 *
 * <p>Registered on the broker via
 * {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker#addIngress(Ingress)};
 * resolved via
 * {@link
 * org.cloudsimplus.kubernetes.KubernetesClusterBroker#routeIngress(String,
 * String)}, which iterates registered ingresses in registration order and
 * returns the first matching route.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter
public final class Ingress {

    @NonNull
    private final String name;

    @NonNull
    private final Namespace namespace;

    private final List<IngressRule> rules = new ArrayList<>();

    /**
     * Creates an Ingress.
     *
     * @param name      the ingress name (must be non-blank, namespace-unique)
     * @param namespace the owning namespace
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public Ingress(final String name, final Namespace namespace) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ingress name must be non-blank");
        }
        this.name = name;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * Appends a routing rule. Rules are evaluated in the order added when
     * computing host filtering, but the longest-prefix-wins tie-break makes
     * the result independent of insertion order across paths.
     *
     * @param rule the rule to append (non-null)
     * @return this instance, for chaining
     */
    public Ingress addRule(@NonNull final IngressRule rule) {
        this.rules.add(rule);
        return this;
    }

    /** @return read-only view of this Ingress's rules in insertion order. */
    public List<IngressRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * Resolves the {@code (host, path)} pair to a {@link KubernetesService}
     * using longest-prefix path matching.
     *
     * @param host the inbound Host header (may be null when routing pure-path)
     * @param path the inbound request path (may be null, in which case no rule matches)
     * @return the chosen service, or empty if no rule matches
     */
    public Optional<KubernetesService> route(final String host, final String path) {
        if (path == null) {
            return Optional.empty();
        }
        IngressRule best = null;
        int bestLen = -1;
        for (final var r : rules) {
            if (r.getHost() != null && !r.getHost().equals(host)) {
                continue;
            }
            final String rp = r.getPath() == null ? "/" : r.getPath();
            if (path.startsWith(rp) && rp.length() > bestLen) {
                best = r;
                bestLen = rp.length();
            }
        }
        return Optional.ofNullable(best).map(IngressRule::getTargetService);
    }

    /** @return the namespace-qualified name as {@code "namespace/name"}. */
    public String qualifiedName() {
        return namespace.getName() + "/" + name;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Ingress that
            && name.equals(that.name)
            && namespace.equals(that.namespace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, namespace);
    }

    @Override
    public String toString() {
        return "Ingress[%s, rules=%d]".formatted(qualifiedName(), rules.size());
    }

    /**
     * One {@code (host, path) → service} routing rule attached to an
     * {@link Ingress}. Mutable through chained setters so rules can be
     * built incrementally; the {@code targetService} is required at
     * construction time and cannot be set to {@code null} via
     * {@link #setTargetService(KubernetesService)}.
     *
     * @since CloudSim Plus 9.0.0
     */
    @lombok.Getter
    @lombok.experimental.Accessors(chain = true)
    public static final class IngressRule {

        /** Virtual-host filter; {@code null} ⇒ wildcard (matches any host). */
        private String host;

        /** Path prefix to match; {@code null} ⇒ {@code "/"}. */
        private String path;

        /** Backing service to route matching requests to. */
        @NonNull
        private KubernetesService targetService;

        /**
         * @param host          the virtual-host filter; {@code null} ⇒ wildcard
         * @param path          the path prefix to match; {@code null} ⇒ {@code "/"}
         * @param targetService the backing service (required, non-null)
         */
        public IngressRule(final String host, final String path, final KubernetesService targetService) {
            this.host = host;
            this.path = path;
            this.targetService = Objects.requireNonNull(targetService, "targetService");
        }

        public IngressRule setHost(final String host) {
            this.host = host;
            return this;
        }

        public IngressRule setPath(final String path) {
            this.path = path;
            return this;
        }

        public IngressRule setTargetService(@NonNull final KubernetesService targetService) {
            this.targetService = targetService;
            return this;
        }

        @Override
        public String toString() {
            return "IngressRule[host=%s, path=%s, target=%s]"
                .formatted(host, path, targetService.getName());
        }
    }
}

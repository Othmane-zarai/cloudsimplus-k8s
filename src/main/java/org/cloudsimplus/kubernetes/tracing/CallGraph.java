/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Declarative call graph that describes how a request fans out across
 * microservices.
 *
 * <p>A {@code CallGraph} is a collection of named {@link EntryPath}s. Each
 * entry path starts at one root operation (e.g. {@code GET /} on the
 * {@code frontend} service) and lists the directed
 * {@link Edge caller->callee} hops the request makes downstream. Probabilities
 * on edges allow modelling optional fan-out (e.g. the recommendation engine
 * is queried 60% of the time). A {@link #pickEntryPath(Random)} draw is
 * weighted by the {@code weight} field on each entry path, so the relative
 * frequencies of, say, "browse" vs "checkout" requests are also controllable.</p>
 *
 * <p>The graph is engine-agnostic: it carries no notion of queueing or
 * latency. {@link RequestTraceGenerator} is the bridge that combines a
 * {@code CallGraph} with a service-time draw to produce
 * {@link RequestTrace}s.</p>
 *
 * @since CloudSim Plus 9.2.0
 */
public final class CallGraph {

    /**
     * A directed edge from a {@code caller} service's currently-executing span
     * to a {@code callee} service. The {@code operation} appears as the
     * callee span's operation name. {@code probability} is the chance the
     * call is actually made (in {@code [0, 1]}); useful for branchy services
     * that only fan out on certain code paths.
     */
    public record Edge(
        String caller,
        String callee,
        String operation,
        double probability) {
        public Edge {
            if (caller == null || caller.isBlank()) {
                throw new IllegalArgumentException("caller must be non-blank");
            }
            if (callee == null || callee.isBlank()) {
                throw new IllegalArgumentException("callee must be non-blank");
            }
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation must be non-blank");
            }
            if (probability < 0.0 || probability > 1.0) {
                throw new IllegalArgumentException(
                    "probability must be in [0,1], got " + probability);
            }
        }

        /** @return an edge that is always taken (probability 1.0). */
        public static Edge always(final String caller, final String callee,
                                  final String operation) {
            return new Edge(caller, callee, operation, 1.0);
        }
    }

    /**
     * One observable entry point (typically an HTTP route on the ingress
     * service). The {@code edges} list is in declaration order: the generator
     * iterates them top-down, lazily drawing on {@code probability}, and
     * routes each call's parent to the most-recent ancestor span on the
     * caller service. (This mirrors how real OpenTelemetry instrumentation
     * propagates context.)
     */
    public record EntryPath(
        String name,
        String entryService,
        String entryOperation,
        double weight,
        List<Edge> edges) {
        public EntryPath {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (entryService == null || entryService.isBlank()) {
                throw new IllegalArgumentException("entryService must be non-blank");
            }
            if (entryOperation == null || entryOperation.isBlank()) {
                throw new IllegalArgumentException("entryOperation must be non-blank");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be > 0, got " + weight);
            }
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    /** Builds an empty graph. Use {@link #addEntry(EntryPath)} to populate. */
    public static CallGraph builder() {
        return new CallGraph();
    }

    private final Map<String, EntryPath> entries = new LinkedHashMap<>();
    private double totalWeight = 0.0;

    public CallGraph addEntry(final EntryPath path) {
        if (entries.containsKey(path.name())) {
            throw new IllegalArgumentException("duplicate entry name: " + path.name());
        }
        entries.put(path.name(), path);
        totalWeight += path.weight();
        return this;
    }

    public List<EntryPath> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Picks an {@link EntryPath} at random, weighted by each path's
     * {@code weight} field. Reproducible when callers pass a seeded
     * {@link Random}.
     *
     * @throws IllegalStateException if the graph has no entries
     */
    public EntryPath pickEntryPath(final Random rng) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("call graph has no entry paths");
        }
        final double pick = rng.nextDouble() * totalWeight;
        double cum = 0.0;
        for (final var e : entries.values()) {
            cum += e.weight();
            if (pick < cum) {
                return e;
            }
        }
        // Floating-point edge case at the upper boundary.
        return entries.values().iterator().next();
    }

    /** @return all distinct service names that appear anywhere in this graph. */
    public List<String> distinctServices() {
        final var set = new java.util.LinkedHashSet<String>();
        for (final var e : entries.values()) {
            set.add(e.entryService());
            for (final var edge : e.edges()) {
                set.add(edge.caller());
                set.add(edge.callee());
            }
        }
        return List.copyOf(set);
    }
}

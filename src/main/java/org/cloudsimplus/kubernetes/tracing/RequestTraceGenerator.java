/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.tracing;

import org.cloudsimplus.kubernetes.networking.queueing.QueueingModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Produces {@link RequestTrace}s from a {@link CallGraph} by drawing each
 * span's duration from the callee service's queueing model.
 *
 * <p>For every call to {@link #generate(double)} the generator:</p>
 * <ol>
 *   <li>Picks an {@link CallGraph.EntryPath} weighted by entry weight.</li>
 *   <li>Creates the root span with a duration drawn from the entry service's
 *       queueing model at the configured per-service arrival rate.</li>
 *   <li>Walks the entry's edges in declaration order; for each edge that
 *       passes its probability check, attaches a child span (parent = the
 *       most-recently-created span on the caller service) with a duration
 *       drawn from the callee's queueing model.</li>
 *   <li>Sets the root span's duration to cover all child spans
 *       (max-of-completion timestamp), modelling synchronous gRPC fan-out
 *       where the caller waits for the slowest callee — this matches what a
 *       real Jaeger trace exposes for fan-out services.</li>
 * </ol>
 *
 * <p>Trace and span IDs are 16-hex-character random strings, matching the
 * 64-bit ID format Jaeger v1 JSON accepts.</p>
 *
 * @since CloudSim Plus 9.2.0
 */
public final class RequestTraceGenerator {

    /** Per-service Poisson arrival rate (req/s). */
    public interface ArrivalRateLookup extends Function<String, Double> {}

    private final CallGraph graph;
    private final Map<String, QueueingModel> queues;
    private final ArrivalRateLookup arrivalRates;
    private final Random rng;
    private long traceCounter;

    public RequestTraceGenerator(
        final CallGraph graph,
        final Map<String, QueueingModel> queues,
        final ArrivalRateLookup arrivalRates,
        final long seed)
    {
        this.graph = graph;
        this.queues = Map.copyOf(queues);
        this.arrivalRates = arrivalRates;
        this.rng = new Random(seed);
    }

    /**
     * Produces one trace whose root span is timestamped at {@code clockSec}
     * (converted to microseconds for the Jaeger payload).
     */
    public RequestTrace generate(final double clockSec) {
        final long startMicros = (long) (clockSec * 1_000_000.0);
        final var entry = graph.pickEntryPath(rng);
        final String traceId = hex16();

        // Track, per service, the most recently created span; that's the parent
        // for any subsequent calls FROM that service.
        final Map<String, Span> lastSpanByService = new HashMap<>();
        final List<Span> spans = new ArrayList<>();

        // Root span — duration filled in later (max of children).
        final var rootId = hex16();
        // Placeholder root span; we will replace it after computing children.
        final Span rootPlaceholder = new Span(
            traceId, rootId, "",
            entry.entryService(), entry.entryOperation(),
            startMicros, 0L, Map.of());
        spans.add(rootPlaceholder);
        lastSpanByService.put(entry.entryService(), rootPlaceholder);

        long maxChildEnd = startMicros;

        for (final var edge : entry.edges()) {
            if (rng.nextDouble() >= edge.probability()) {
                continue;
            }
            final var parent = lastSpanByService.get(edge.caller());
            if (parent == null) {
                // The graph names a caller that no edge has visited yet. Skip
                // gracefully so a malformed graph doesn't crash trace gen.
                continue;
            }
            final var queue = queues.get(edge.callee());
            if (queue == null) {
                continue;
            }
            final double rt = queue.draw(arrivalRates.apply(edge.callee()));
            if (!Double.isFinite(rt) || rt < 0) {
                // Skip the call entirely on saturation — Jaeger traces simply
                // wouldn't contain a span for a request that was rejected.
                continue;
            }
            final long childStart = parent.startMicros();
            final long childDuration = (long) (rt * 1_000_000.0);
            final Span child = new Span(
                traceId, hex16(), parent.spanId(),
                edge.callee(), edge.operation(),
                childStart, childDuration, Map.of());
            spans.add(child);
            lastSpanByService.put(edge.callee(), child);
            maxChildEnd = Math.max(maxChildEnd, childStart + childDuration);
        }

        // Fill the root duration so it covers the latest child completion plus
        // the root service's own self-time draw (gRPC: parent waits on
        // children + does its own work).
        final var rootQueue = queues.get(entry.entryService());
        final long ownTime;
        if (rootQueue != null) {
            final double rt = rootQueue.draw(arrivalRates.apply(entry.entryService()));
            ownTime = Double.isFinite(rt) && rt >= 0 ? (long) (rt * 1_000_000.0) : 0L;
        } else {
            ownTime = 0L;
        }
        final long rootDuration = Math.max(maxChildEnd - startMicros, 0L) + ownTime;
        final Span root = new Span(
            traceId, rootId, "",
            entry.entryService(), entry.entryOperation(),
            startMicros, rootDuration,
            Map.of("entry.path", entry.name()));
        spans.set(0, root);

        traceCounter++;
        return new RequestTrace(traceId, spans);
    }

    /** Reset the internal RNG counter so reproducible runs start clean. */
    public long generatedCount() {
        return traceCounter;
    }

    private String hex16() {
        // 64-bit random as a 16-char lowercase hex string — Jaeger v1 ID format.
        final long n = rng.nextLong();
        return String.format("%016x", n);
    }
}

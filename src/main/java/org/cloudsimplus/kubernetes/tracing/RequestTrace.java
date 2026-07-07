/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single distributed request, modelled as a list of {@link Span}s that
 * collectively form a tree rooted at the ingress service. The list is
 * insertion-ordered: the first element is always the root span.
 *
 * <p>Use {@link RequestTraceGenerator} to produce these from a
 * {@link CallGraph} + per-service queueing models; use
 * {@link JaegerJsonExporter} to dump them in a format Jaeger's HTTP API
 * accepts (and any analysis script that reads Jaeger traces will accept).</p>
 *
 * @since CloudSim Plus 9.2.0
 */
public final class RequestTrace {
    private final String traceId;
    private final List<Span> spans;

    public RequestTrace(final String traceId, final List<Span> spans) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must be non-blank");
        }
        if (spans == null || spans.isEmpty()) {
            throw new IllegalArgumentException("a trace must have at least one span");
        }
        this.traceId = traceId;
        this.spans = List.copyOf(new ArrayList<>(spans));
        if (!this.spans.get(0).isRoot()) {
            throw new IllegalArgumentException(
                "first span must be the root (parentSpanId empty) for trace " + traceId);
        }
        for (final var s : this.spans) {
            if (!s.traceId().equals(traceId)) {
                throw new IllegalArgumentException(
                    "span " + s.spanId() + " has wrong traceId");
            }
        }
    }

    public String traceId() {
        return traceId;
    }

    public List<Span> spans() {
        return Collections.unmodifiableList(spans);
    }

    public Span root() {
        return spans.get(0);
    }

    public long totalDurationMicros() {
        return spans.stream().mapToLong(Span::durationMicros).max().orElse(0L);
    }

    public int spanCount() {
        return spans.size();
    }
}

/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.tracing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single OpenTelemetry / Jaeger-compatible span. Spans form trees rooted at
 * the ingress service and fanning out through whichever backend services
 * handled a request. The simulator emits spans whose
 * {@link #durationMicros()} is a draw from the callee's
 * {@link org.cloudsimplus.kubernetes.networking.queueing.QueueingModel} so the
 * shape of the simulated trace distribution can be compared against real
 * Jaeger traces collected from a cluster running the same call graph.
 *
 * <p>The data layout maps 1:1 to Jaeger v1 JSON: {@code traceID}, {@code spanID},
 * {@code parentSpanID} (empty string for roots), {@code operationName},
 * {@code startTime} (microseconds since epoch), {@code duration}
 * (microseconds), plus the service name carried out-of-band by the exporter.</p>
 *
 * @since CloudSim Plus 9.2.0
 */
public record Span(
    String traceId,
    String spanId,
    String parentSpanId,
    String service,
    String operation,
    long startMicros,
    long durationMicros,
    Map<String, String> tags
) {
    public Span {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must be non-blank");
        }
        if (spanId == null || spanId.isBlank()) {
            throw new IllegalArgumentException("spanId must be non-blank");
        }
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service must be non-blank");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must be non-blank");
        }
        if (durationMicros < 0) {
            throw new IllegalArgumentException("durationMicros must be >= 0");
        }
        tags = tags == null ? Collections.emptyMap() : Map.copyOf(new LinkedHashMap<>(tags));
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
    }

    /** @return true when this span has no parent (root of its trace tree). */
    public boolean isRoot() {
        return parentSpanId.isEmpty();
    }
}

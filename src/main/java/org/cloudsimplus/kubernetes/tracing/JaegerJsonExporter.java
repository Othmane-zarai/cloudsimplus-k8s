/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 */
package org.cloudsimplus.kubernetes.tracing;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a list of {@link RequestTrace}s to a JSON file in the same layout
 * Jaeger's HTTP API returns from
 * {@code GET /api/traces?service=...}. The schema is:
 *
 * <pre>{@code
 * {
 *   "data": [
 *     {
 *       "traceID": "abcd1234...",
 *       "spans": [
 *         {
 *           "traceID": "abcd1234...",
 *           "spanID":  "1111...",
 *           "operationName": "...",
 *           "references": [{"refType":"CHILD_OF","traceID":"...","spanID":"..."}],
 *           "startTime":  <microseconds since epoch>,
 *           "duration":   <microseconds>,
 *           "processID":  "p1",
 *           "tags": [{"key":"...","type":"string","value":"..."}]
 *         },
 *         ...
 *       ],
 *       "processes": {
 *         "p1": {"serviceName":"frontend","tags":[]},
 *         ...
 *       }
 *     },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>Any Python script that loads Jaeger traces will load these unmodified —
 * the only abstraction leak is that we synthesise one
 * {@code process} per distinct service name across the entire dump, rather
 * than per-trace, which Jaeger also does and which downstream consumers
 * tolerate.</p>
 *
 * <p>Implemented with a manual {@link Writer} (no Jackson dependency) so the
 * library stays free of optional-runtime transitive deps.</p>
 *
 * @since CloudSim Plus 9.2.0
 */
public final class JaegerJsonExporter {

    private JaegerJsonExporter() {}

    public static void writeTo(final Path file, final List<RequestTrace> traces)
        throws IOException
    {
        // 1) Gather distinct services for the global processes table.
        final Set<String> services = new HashSet<>();
        for (final var t : traces) {
            for (final var s : t.spans()) {
                services.add(s.service());
            }
        }
        final var serviceList = List.copyOf(services);
        // Map service name -> "p<index>" (Jaeger's processID convention).
        final java.util.Map<String, String> processId = new java.util.HashMap<>();
        for (int i = 0; i < serviceList.size(); i++) {
            processId.put(serviceList.get(i), "p" + (i + 1));
        }

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("{\"data\":[");
            boolean firstTrace = true;
            for (final var trace : traces) {
                if (!firstTrace) w.write(",");
                firstTrace = false;
                writeTrace(w, trace, processId, serviceList);
            }
            w.write("]}");
        }
    }

    private static void writeTrace(
        final Writer w, final RequestTrace t,
        final Map<String, String> processId, final List<String> serviceList) throws IOException
    {
        w.write("{\"traceID\":\"");
        w.write(t.traceId());
        w.write("\",\"spans\":[");
        boolean firstSpan = true;
        for (final var s : t.spans()) {
            if (!firstSpan) w.write(",");
            firstSpan = false;
            writeSpan(w, s, processId.get(s.service()));
        }
        w.write("],\"processes\":{");
        // Per-trace processes block, only including services that appear in this trace.
        final Set<String> svcInTrace = new HashSet<>();
        for (final var s : t.spans()) svcInTrace.add(s.service());
        boolean firstProc = true;
        for (final String svc : serviceList) {
            if (!svcInTrace.contains(svc)) continue;
            if (!firstProc) w.write(",");
            firstProc = false;
            w.write("\"");
            w.write(processId.get(svc));
            w.write("\":{\"serviceName\":\"");
            w.write(esc(svc));
            w.write("\",\"tags\":[]}");
        }
        w.write("}}");
    }

    private static void writeSpan(final Writer w, final Span s, final String procId)
        throws IOException
    {
        w.write("{\"traceID\":\"");
        w.write(s.traceId());
        w.write("\",\"spanID\":\"");
        w.write(s.spanId());
        w.write("\",\"operationName\":\"");
        w.write(esc(s.operation()));
        w.write("\",\"references\":[");
        if (!s.isRoot()) {
            w.write("{\"refType\":\"CHILD_OF\",\"traceID\":\"");
            w.write(s.traceId());
            w.write("\",\"spanID\":\"");
            w.write(s.parentSpanId());
            w.write("\"}");
        }
        w.write("],\"startTime\":");
        w.write(Long.toString(s.startMicros()));
        w.write(",\"duration\":");
        w.write(Long.toString(s.durationMicros()));
        w.write(",\"processID\":\"");
        w.write(procId);
        w.write("\",\"tags\":[");
        boolean firstTag = true;
        for (final var e : s.tags().entrySet()) {
            if (!firstTag) w.write(",");
            firstTag = false;
            w.write("{\"key\":\"");
            w.write(esc(e.getKey()));
            w.write("\",\"type\":\"string\",\"value\":\"");
            w.write(esc(e.getValue()));
            w.write("\"}");
        }
        w.write("]}");
    }

    private static String esc(final String s) {
        // Minimal JSON string escape: ", \, and control chars.
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}

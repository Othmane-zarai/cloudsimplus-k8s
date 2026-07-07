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
package org.cloudsimplus.kubernetes.autoscaling;

import java.util.HashMap;
import java.util.Map;

/**
 * Two-stage metrics pipeline mirroring the real Kubernetes monitoring path:
 * <em>kubelet/metrics-server scrape → HPA controller sync</em>.
 *
 * <p>Real clusters do not let the HPA read a pod's live CPU gauge. The kubelet
 * exports cAdvisor samples every {@code metric-resolution} seconds (default 15 s
 * in metrics-server 0.6+); the HPA controller then polls metrics-server on its
 * own sync loop (default 15 s), so by the time an HPA decision uses a sample
 * the data is typically 15–45 s stale.</p>
 *
 * <p>This pipeline reproduces that lag in three configurable knobs (all in
 * simulated seconds):</p>
 * <ul>
 *   <li>{@code scrapeIntervalSeconds} (default 15) — minimum spacing between
 *       accepted observations per pod; samples that arrive faster than this
 *       are coalesced (the more recent value wins on the next due tick).</li>
 *   <li>{@code syncDelaySeconds} (default 30) — every {@link #snapshot(long, double)}
 *       returns the most recent sample dated <em>at or before</em>
 *       {@code now - syncDelaySeconds}, modelling the controller's stale view.</li>
 *   <li>{@code staleness} (default 90) — samples older than {@code now - staleness}
 *       are evicted on snapshot.</li>
 * </ul>
 *
 * <p>The defaults match Prometheus / metrics-server documented values:</p>
 * <ul>
 *   <li>scrape = Prometheus scrape_interval default</li>
 *   <li>syncDelay = metrics-server {@code --metric-resolution=15s} × 2</li>
 *   <li>staleness = Prometheus {@code --query.lookback-delta} default</li>
 * </ul>
 *
 * <p>Pods are keyed by a stable {@code long} id (recommended:
 * {@link org.cloudsimplus.kubernetes.KubernetesPod#getId()}).</p>
 *
 * <p>Not thread-safe — the simulator drives all ticks from one thread.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public final class MetricsPipeline {

    /** Default 15 s scrape interval — Prometheus default. */
    public static final double DEFAULT_SCRAPE_INTERVAL_SECONDS = 15.0;
    /** Default 30 s sync delay — metrics-server resolution × 2. */
    public static final double DEFAULT_SYNC_DELAY_SECONDS = 30.0;
    /** Default 90 s staleness — Prometheus query lookback-delta. */
    public static final double DEFAULT_STALENESS_SECONDS = 90.0;

    /** Ring-buffer length per pod; large enough to cover staleness ÷ scrape with margin. */
    private static final int PER_POD_BUFFER = 16;

    /**
     * Immutable per-sample record exposed by {@link #snapshot(long, double)}.
     *
     * @param timestamp simulation clock when the sample was recorded
     * @param cpu CPU utilisation as a fraction in {@code [0, 1]}
     * @param mem memory utilisation as a fraction in {@code [0, 1]}
     */
    public record Sample(double timestamp, double cpu, double mem) {
        /** Sentinel returned when no sample is available for a pod yet. */
        public static final Sample EMPTY = new Sample(Double.NaN, Double.NaN, Double.NaN);

        /** {@code true} iff this is the {@link #EMPTY} sentinel. */
        public boolean isEmpty() {
            return Double.isNaN(timestamp);
        }
    }

    private final double scrapeIntervalSeconds;
    private final double syncDelaySeconds;
    private final double stalenessSeconds;

    /** Per-pod ring of samples, newest last. */
    private final Map<Long, CircularBuffer<Sample>> samplesByPod = new HashMap<>();
    /** Per-pod last-accepted timestamp for scrape-rate gating. */
    private final Map<Long, Double> lastScrapeAt = new HashMap<>();

    /** Build a pipeline with documented defaults (15 / 30 / 90 s). */
    public MetricsPipeline() {
        this(DEFAULT_SCRAPE_INTERVAL_SECONDS, DEFAULT_SYNC_DELAY_SECONDS, DEFAULT_STALENESS_SECONDS);
    }

    /**
     * Builds a degenerate "zero-lag" pipeline: every {@link #record} is
     * immediately visible to the next {@link #snapshot}. Intended for unit tests
     * that want to exercise the surrounding decision logic without modelling the
     * scrape/sync lag.
     */
    public static MetricsPipeline zeroLag() {
        return new MetricsPipeline(0.0, 0.0, Double.MAX_VALUE / 4.0);
    }

    /**
     * @param scrapeIntervalSeconds minimum spacing between accepted per-pod samples ({@code >= 0})
     * @param syncDelaySeconds      delay applied on snapshot reads ({@code >= 0})
     * @param stalenessSeconds      eviction horizon ({@code > syncDelaySeconds} recommended)
     * @throws IllegalArgumentException if any value is negative or {@code stalenessSeconds < syncDelaySeconds}
     */
    public MetricsPipeline(final double scrapeIntervalSeconds,
                           final double syncDelaySeconds,
                           final double stalenessSeconds)
    {
        if (scrapeIntervalSeconds < 0 || syncDelaySeconds < 0 || stalenessSeconds < 0) {
            throw new IllegalArgumentException("pipeline knobs must be >= 0");
        }
        if (stalenessSeconds < syncDelaySeconds) {
            throw new IllegalArgumentException(
                "stalenessSeconds (" + stalenessSeconds + ") must be >= syncDelaySeconds ("
                + syncDelaySeconds + ")");
        }
        this.scrapeIntervalSeconds = scrapeIntervalSeconds;
        this.syncDelaySeconds = syncDelaySeconds;
        this.stalenessSeconds = stalenessSeconds;
    }

    public double getScrapeIntervalSeconds() {
        return scrapeIntervalSeconds;
    }

    public double getSyncDelaySeconds() {
        return syncDelaySeconds;
    }

    public double getStalenessSeconds() {
        return stalenessSeconds;
    }

    /**
     * Records an observation for {@code podId} at simulation time {@code timestamp}.
     * If the previous sample for this pod is younger than {@code scrapeIntervalSeconds},
     * the call is silently ignored — mirroring the kubelet's fixed scrape cadence.
     *
     * @param podId     stable per-pod key (use {@code pod.getId()})
     * @param timestamp simulation clock at observation
     * @param cpu       CPU utilisation in {@code [0, 1]}
     * @param mem       memory utilisation in {@code [0, 1]}
     */
    public void record(final long podId, final double timestamp, final double cpu, final double mem) {
        final Double last = lastScrapeAt.get(podId);
        if (last != null && timestamp - last < scrapeIntervalSeconds) {
            return;
        }
        samplesByPod.computeIfAbsent(podId, k -> new CircularBuffer<>(PER_POD_BUFFER))
            .add(new Sample(timestamp, cpu, mem));
        lastScrapeAt.put(podId, timestamp);
    }

    /**
     * Returns the most-recent sample for {@code podId} dated at or before
     * {@code now - syncDelaySeconds}. Samples older than {@code now - stalenessSeconds}
     * are evicted (treated as missing).
     *
     * @return the matching {@link Sample}, or {@link Sample#EMPTY} if no eligible sample exists
     */
    public Sample snapshot(final long podId, final double now) {
        final var buf = samplesByPod.get(podId);
        if (buf == null || buf.isEmpty()) {
            return Sample.EMPTY;
        }
        final double cutoff = now - syncDelaySeconds;
        final double staleCutoff = now - stalenessSeconds;
        // Walk newest-first to find the first sample at-or-before the cutoff
        // that is not stale.
        Sample chosen = Sample.EMPTY;
        for (int i = buf.size() - 1; i >= 0; i--) {
            final Sample s = buf.get(i);
            if (s.timestamp() <= cutoff && s.timestamp() >= staleCutoff) {
                chosen = s;
                break;
            }
        }
        return chosen;
    }

    /** Number of pods currently tracked. */
    public int trackedPodCount() {
        return samplesByPod.size();
    }

    /**
     * Drops all retained samples and scrape-bookkeeping for {@code podId}.
     * Call after a pod is permanently evicted to bound memory.
     */
    public void forget(final long podId) {
        samplesByPod.remove(podId);
        lastScrapeAt.remove(podId);
    }
}

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
package org.cloudsimplus.kubernetes.controllers;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.cloudsimplus.kubernetes.Namespace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.cronutils.model.time.ExecutionTime;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.time.Duration;

/**
 * CronJob controller — fires a {@link JobController} on a fixed-period
 * {@link #getSchedule() schedule} (in simulated seconds). Mirrors a simplified
 * subset of Kubernetes' CronJob: rather than wall-clock cron syntax, the
 * schedule is an interval, since a discrete-event simulator doesn't need
 * calendar-aware semantics.
 *
 * <p>Behaviour parameters mirror the Kubernetes API:</p>
 * <ul>
 *   <li>{@link #getConcurrencyPolicy() concurrencyPolicy} —
 *       {@code Allow} (default), {@code Forbid} (skip if a previous Job is
 *       still running), or {@code Replace} (cancel the previous Job and start
 *       a fresh one).</li>
 *   <li>{@link #getStartingDeadlineSeconds() startingDeadlineSeconds} —
 *       optional ceiling on how late a missed firing can still be honoured.</li>
 * </ul>
 *
 * <p>Each fire instantiates a fresh, independent {@link JobController} via
 * {@link #getJobFactory() jobFactory} and registers it with the broker. Past
 * jobs remain registered for inspection.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
@Getter @Setter @Accessors(chain = true)
public class CronJobController implements Controller {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobController.class.getSimpleName());

    /** Factory of fresh Job controllers; called once per fire. */
    public interface JobFactory {
        JobController create(long uid, int firingIndex);
    }

    /** Mirrors Kubernetes {@code CronJob.spec.concurrencyPolicy}. */
    public enum ConcurrencyPolicy {
        /** Default. New runs start regardless of whether earlier ones are still active. */
        ALLOW,
        /** Skip the firing entirely if any previous Job is still running. */
        FORBID,
        /** Cancel any active previous Job, then start a fresh one. */
        REPLACE
    }

    private final long uid;
    private final String name;
    private final Namespace namespace;
    private final JobFactory jobFactory;

    /**
     * Period between consecutive firings, in simulated seconds. The paper
     * uses {@code schedule} as the parameter name. If a valid cron expression
     * is set, it will be used to compute the next firing time.
     */
    private double schedule = 60.0;

    private String cronExpression;
    private ExecutionTime executionTime;
    private CronType cronType = CronType.UNIX;

    /**
     * UNIX epoch seconds used to map {@code simulation.clock()} to a wall-clock
     * instant when evaluating a cron expression. Defaults to
     * {@code 2020-01-01T00:00:00Z}; override before the first reconcile when
     * day-of-week / month rules need to anchor at a specific calendar date.
     */
    private long simulationEpochSeconds = 1577836800L;

    /** {@link ConcurrencyPolicy}; default {@link ConcurrencyPolicy#ALLOW}. */
    private ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.ALLOW;

    /**
     * Deadline (simulated seconds) by which a missed firing must still be
     * triggered. Kubernetes treats a missed firing past this deadline as a
     * skip. Negative ⇒ no deadline.
     */
    private double startingDeadlineSeconds = -1;

    private int fired;
    private double lastFiredAt = -1;
    /** Most recently fired Job, kept around for {@link ConcurrencyPolicy} checks. */
    private JobController lastJob;
    private ControllerManager manager;

    public CronJobController(final long uid, final String name, final Namespace namespace, final JobFactory jobFactory) {
        this.uid = uid;
        this.name = name;
        this.namespace = namespace;
        this.jobFactory = jobFactory;
    }

    @Override
    public String getKind() {
        return "CronJob";
    }

    /** Legacy alias for {@link #getSchedule()}. */
    public double getIntervalSeconds() {
        return schedule;
    }

    /** Legacy alias for {@link #setSchedule(double)}; chainable. */
    public CronJobController setIntervalSeconds(final double seconds) {
        this.schedule = seconds;
        return this;
    }

    /**
     * Parses and installs a full cron expression (UNIX flavour by default —
     * five fields: minute hour day-of-month month day-of-week). Use
     * {@link #setCronType(CronType)} before this call to switch to QUARTZ /
     * SPRING / CRON4J. Throws {@link IllegalArgumentException} on invalid
     * input so misconfiguration fails loudly at setup time, not at first tick.
     */
    public CronJobController setCronExpression(final String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("cronExpression must be non-blank");
        }
        try {
            final CronParser parser =
                new CronParser(CronDefinitionBuilder.instanceDefinitionFor(cronType));
            final Cron cron = parser.parse(cronExpression).validate();
            this.executionTime = ExecutionTime.forCron(cron);
            this.cronExpression = cronExpression;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Invalid cron expression '" + cronExpression + "': " + ex.getMessage(), ex);
        }
        return this;
    }

    @Override
    public void reconcile() {
        final double now = manager.broker().getSimulation().clock();
        final boolean cronMode = cronExpression != null && executionTime != null;

        // 1) Decide whether the current slot has opened.
        final double slotOpenedAt = nextSlotOpenAt(now);
        if (now + 1.0e-9 < slotOpenedAt) {
            return;
        }

        if (!shouldFireUnderConcurrencyPolicy(now, slotOpenedAt, cronMode)) {
            return;
        }
        fireJob();
        lastFiredAt = now;
    }

    /**
     * Returns the simulated time at which the current scheduled slot opened,
     * or {@code Double.POSITIVE_INFINITY} if no slot has opened yet.
     *
     * <p>In cron mode this delegates to {@link ExecutionTime#nextExecution}
     * relative to the previous fire (or simulation start); in interval mode
     * it returns {@code lastFiredAt + schedule} (or {@code schedule} for the
     * very first firing).</p>
     */
    private double nextSlotOpenAt(final double now) {
        if (cronExpression != null && executionTime != null) {
            final long anchor = simulationEpochSeconds
                + Math.round(lastFiredAt < 0 ? 0.0 : lastFiredAt);
            final ZonedDateTime from = Instant.ofEpochSecond(anchor).atZone(ZoneOffset.UTC);
            final Optional<ZonedDateTime> next = executionTime.nextExecution(from);
            if (next.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            final double slotEpochSec = next.get().toEpochSecond();
            return slotEpochSec - simulationEpochSeconds;
        }
        return lastFiredAt < 0 ? schedule : lastFiredAt + schedule;
    }

    private boolean shouldFireUnderConcurrencyPolicy(
        final double now, final double slotOpenedAt, final boolean cronMode)
    {
        // Honour starting deadline: skip if too much time elapsed since the
        // slot opened. Works identically for cron and interval modes.
        if (startingDeadlineSeconds >= 0
            && Double.isFinite(slotOpenedAt)
            && now - slotOpenedAt > startingDeadlineSeconds)
        {
            LOG.info("{}: CronJob '{}': skipping firing (past startingDeadlineSeconds={})",
                manager.broker().getSimulation().clockStr(), name, startingDeadlineSeconds);
            return false;
        }
        if (lastJob == null || lastJob.isComplete()) {
            return true;
        }
        return switch (concurrencyPolicy) {
            case ALLOW -> true;
            case FORBID -> {
                LOG.info("{}: CronJob '{}': previous Job still running, skipping firing (Forbid)",
                    manager.broker().getSimulation().clockStr(), name);
                yield false;
            }
            case REPLACE -> {
                lastJob.setCompletions(Math.max(1, lastJob.getSucceeded()));
                LOG.info("{}: CronJob '{}': replacing active Job '{}' (Replace)",
                    manager.broker().getSimulation().clockStr(), name, lastJob.getName());
                yield true;
            }
        };
    }

    private void fireJob() {
        final long jobUid = manager.allocateUid();
        final var job = jobFactory.create(jobUid, fired++);
        manager.register(job);
        lastJob = job;
        LOG.info("{}: CronJob '{}': firing job '{}' (#{})",
            manager.broker().getSimulation().clockStr(), name, job.getName(), fired - 1);
    }
}

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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers §B.7 parsing-mode semantics for {@link Resources#parseCpu(String)} and
 * {@link Resources#parseMem(String)}. The canonical edge-case table lives in
 * {@code cloudsimplus/KUBERNETES.md} §3.1.
 */
class ResourcesParsingModeTest {

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void resetModeAndAttachAppender() {
        // Always start each test from the default mode so ordering can't leak state.
        Resources.setParsingMode(ParsingMode.LENIENT_WARN);

        // The production code uses LoggerFactory.getLogger(Resources.class.getSimpleName()),
        // so the logger name is the simple class name ("Resources"), not the FQN.
        logbackLogger = (Logger) LoggerFactory.getLogger(Resources.class.getSimpleName());
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logbackLogger.detachAppender(appender);
        appender.stop();
    }

    @AfterAll
    static void restoreDefault() {
        // Leave the process-wide state clean for subsequent test classes.
        Resources.setParsingMode(ParsingMode.LENIENT_WARN);
    }

    // ------------------------------------------------------------------
    // STRICT — degenerate inputs must throw IllegalArgumentException
    // ------------------------------------------------------------------

    @Test
    void strictRejectsZeroMemory() {
        Resources.setParsingMode(ParsingMode.STRICT);
        assertThrows(IllegalArgumentException.class, () -> Resources.parseMem("0"));
        assertThrows(IllegalArgumentException.class, () -> Resources.parseMem("0Mi"));
    }

    @Test
    void strictRejectsSubMiBMemory() {
        Resources.setParsingMode(ParsingMode.STRICT);
        assertThrows(IllegalArgumentException.class, () -> Resources.parseMem("512Ki"));
    }

    @Test
    void strictRejectsSubMillicoreCpu() {
        Resources.setParsingMode(ParsingMode.STRICT);
        final IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class, () -> Resources.parseCpu("0.5m"));
        // The thrown message should be more useful than a raw NumberFormatException.
        assertTrue(ex.getMessage().toLowerCase().contains("sub-millicore")
                || ex.getMessage().toLowerCase().contains("malformed"),
            "STRICT message should explain sub-millicore rejection: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // LENIENT_WARN — degenerate inputs coerce + emit WARN
    // ------------------------------------------------------------------

    @Test
    void lenientCoercesZeroMemoryAndWarns() {
        // Default mode set in @BeforeEach
        assertEquals(0, Resources.parseMem("0"));
        assertTrue(hasWarning(), "LENIENT_WARN must emit a WARN entry for parseMem(\"0\")");
    }

    @Test
    void lenientCoercesSubMiBMemoryAndWarns() {
        assertEquals(1, Resources.parseMem("512Ki"));
        assertTrue(hasWarning(),
            "LENIENT_WARN must emit a WARN entry for sub-MiB memory inputs");
    }

    @Test
    void lenientCoercesSubMillicoreCpuAndWarns() {
        assertEquals(0, Resources.parseCpu("0.5m"));
        assertTrue(hasWarning(),
            "LENIENT_WARN must emit a WARN entry for sub-millicore CPU inputs");
    }

    // ------------------------------------------------------------------
    // Round-trip: flip mode mid-test
    // ------------------------------------------------------------------

    @Test
    void modeFlipChangesBehaviourForSameInput() {
        // Start in LENIENT_WARN — same input parses successfully.
        assertEquals(0, Resources.parseMem("0"));
        assertTrue(hasWarning(), "LENIENT_WARN must warn on parseMem(\"0\")");

        // Clear and flip to STRICT — same input now throws.
        appender.list.clear();
        Resources.setParsingMode(ParsingMode.STRICT);
        assertThrows(IllegalArgumentException.class, () -> Resources.parseMem("0"));

        // Flip back to LENIENT_WARN — parses again.
        Resources.setParsingMode(ParsingMode.LENIENT_WARN);
        assertEquals(0, Resources.parseMem("0"));
        assertTrue(hasWarning(),
            "LENIENT_WARN must warn again after the mode flips back");
    }

    private boolean hasWarning() {
        return appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN);
    }
}

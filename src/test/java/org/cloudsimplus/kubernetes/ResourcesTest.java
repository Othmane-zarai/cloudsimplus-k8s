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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcesTest {

    @Test
    void parseCpuMillicoreSuffix() {
        assertEquals(500, Resources.parseCpu("500m"));
        assertEquals(1500, Resources.parseCpu("1500m"));
    }

    @Test
    void parseCpuBareNumberMeansCores() {
        assertEquals(1000, Resources.parseCpu("1"));
        assertEquals(500, Resources.parseCpu("0.5"));
        assertEquals(2500, Resources.parseCpu("2.5"));
    }

    @Test
    void parseCpuBlankReturnsZero() {
        assertEquals(0, Resources.parseCpu(null));
        assertEquals(0, Resources.parseCpu(""));
        assertEquals(0, Resources.parseCpu("   "));
    }

    @Test
    void parseMemBinarySuffixes() {
        assertEquals(256, Resources.parseMem("256Mi"));
        assertEquals(1024, Resources.parseMem("1Gi"));
    }

    @Test
    void parseMemDecimalSuffixUsesPowersOfTen() {
        // 500M = 500_000_000 bytes ≈ 476 MiB
        assertEquals(500_000_000L / (1024L * 1024L), Resources.parseMem("500M"));
    }

    @Test
    void milliCpuToMipsScalesByCalibration() {
        assertEquals(500.0, Resources.milliCpuToMips(500, 1000));
        assertEquals(2000.0, Resources.milliCpuToMips(1000, 2000));
    }

    @Test
    void recordRejectsNegativeQuantities() {
        assertThrows(IllegalArgumentException.class, () -> new Resources(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Resources(0, -1));
    }

    @Test
    void plusComposesComponentwise() {
        final var sum = new Resources(500, 256).plus(new Resources(1000, 512));
        assertEquals(1500, sum.milliCpu());
        assertEquals(768, sum.memMiB());
    }

    @Test
    void malformedSpecsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Resources.parseCpu("abc"));
        assertThrows(IllegalArgumentException.class, () -> Resources.parseMem("xyz"));
    }

    @Test
    void parseCpuRoundsBareNumberToNearestMillicore() {
        // N6 documented behaviour: bare-number CPU specs go through
        // double * 1000 + Math.round(), so sub-millicore precision is silently
        // dropped — same granularity as the real Kubernetes API. Callers who
        // need exact integer millicores should use the "m" suffix, which
        // bypasses the floating-point step entirely.
        assertEquals(123, Resources.parseCpu("0.123"));
        assertEquals(124, Resources.parseCpu("0.1235"));
        assertEquals(123, Resources.parseCpu("0.123456789"));
        // Suffix path is integer-exact regardless of the value.
        assertEquals(123_456_789L, Resources.parseCpu("123456789m"));
    }

    @Test
    void parseMemReturnsZeroForExplicitZero() {
        // M2 fix: parseMem("0") used to return 1 because of an unconditional
        // floor. The floor belongs in KubernetesPod (RAM>0 invariant), not in
        // the parser, so explicit zero now round-trips faithfully.
        assertEquals(0, Resources.parseMem("0"));
        assertEquals(0, Resources.parseMem("0Mi"));
        // Sub-MiB inputs (< 1 MiB) integer-divide to zero rather than being
        // bumped up to 1 — also part of the M2 fix.
        assertEquals(0, Resources.parseMem("512Ki"));
    }
}

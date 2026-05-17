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

/**
 * A Kubernetes-style resource quantity pair: CPU expressed in <i>millicores</i>
 * (Kubernetes' {@code m} suffix) and memory expressed in <i>MiB</i>
 * (Kubernetes' {@code Mi} suffix). Used both for {@code requests} and
 * {@code limits} on a {@link KubernetesContainer}.
 *
 * <p>Helpers convert from K8s string notation ({@code "500m"}, {@code "256Mi"})
 * and to CloudSim Plus's native MIPS / MB units used by the underlying
 * {@link org.cloudsimplus.vms.Vm} machinery. The conversion assumes a configurable
 * {@link #DEFAULT_MIPS_PER_CORE} (defaults to 1000 MIPS = 1 CPU core).</p>
 *
 * @param milliCpu CPU quantity in millicores (1000 = one full core); must be {@code >= 0}
 * @param memMiB   memory quantity in mebibytes (1 MiB = 2<sup>20</sup> bytes); must be {@code >= 0}
 *
 * @since CloudSim Plus 9.0.0
 */
public record Resources(long milliCpu, long memMiB) {

    /** Default conversion factor: 1 CPU core ↔ 1000 MIPS. */
    public static final int DEFAULT_MIPS_PER_CORE = 1000;

    /** Zero-resource sentinel ({@code 0m} CPU, {@code 0Mi} memory). */
    public static final Resources ZERO = new Resources(0, 0);

    public Resources {
        if (milliCpu < 0) {
            throw new IllegalArgumentException("milliCpu must be >= 0, got " + milliCpu);
        }
        if (memMiB < 0) {
            throw new IllegalArgumentException("memMiB must be >= 0, got " + memMiB);
        }
    }

    /**
     * Parses K8s-style quantity strings into a {@link Resources} value.
     *
     * @param cpu CPU quantity ({@code "500m"}, {@code "1"}, {@code "1.5"}); see {@link #parseCpu(String)}
     * @param mem memory quantity ({@code "256Mi"}, {@code "1Gi"}); see {@link #parseMem(String)}
     * @return the parsed {@link Resources}
     */
    public static Resources of(final String cpu, final String mem) {
        return new Resources(parseCpu(cpu), parseMem(mem));
    }

    /** @return the component-wise sum of this and {@code other}. */
    public Resources plus(final Resources other) {
        return new Resources(milliCpu + other.milliCpu, memMiB + other.memMiB);
    }

    /**
     * @return CPU expressed in MIPS using {@link #DEFAULT_MIPS_PER_CORE}.
     */
    public double toMips() {
        return milliCpuToMips(milliCpu, DEFAULT_MIPS_PER_CORE);
    }

    /**
     * @param mipsPerCore the simulator's calibration: how many MIPS one full core represents
     * @return CPU expressed in MIPS using the supplied calibration
     */
    public double toMips(final int mipsPerCore) {
        return milliCpuToMips(milliCpu, mipsPerCore);
    }

    /**
     * Translates millicores into MIPS. Performed in {@code double} so fractional
     * cores below the calibration granularity round naturally.
     */
    public static double milliCpuToMips(final long millicores, final int mipsPerCore) {
        if (mipsPerCore <= 0) {
            throw new IllegalArgumentException("mipsPerCore must be > 0, got " + mipsPerCore);
        }
        return millicores * (double) mipsPerCore / 1000.0;
    }

    /**
     * Parses a Kubernetes CPU spec string ({@code "500m"}, {@code "1"}, {@code "1.5"})
     * into millicores. Plain numeric values are interpreted as whole cores.
     *
     * <p><b>Precision.</b> Bare-number inputs are parsed as {@code double}
     * and then multiplied by 1000 with {@link Math#round(double)}. This means
     * sub-millicore precision is silently lost: {@code "0.1234"} → 123,
     * {@code "0.1235"} → 124, {@code "0.123456789"} → 123. Real Kubernetes
     * imposes the same granularity (millicores are the smallest unit the API
     * accepts), so this matches the platform — but callers who need exact
     * millicore values should pass them with the explicit {@code m} suffix
     * ({@code "123m"}) which goes through {@link Long#parseLong(String)} and
     * loses no precision.</p>
     *
     * @param spec the K8s CPU spec; {@code null} or blank yields {@code 0}
     * @return millicores (1 core = 1000)
     * @throws IllegalArgumentException on malformed input
     */
    public static long parseCpu(final String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        final String s = spec.trim();
        try {
            if (s.endsWith("m")) {
                return Long.parseLong(s.substring(0, s.length() - 1).trim());
            }
            // bare number: cores (possibly fractional like "0.5")
            return Math.round(Double.parseDouble(s) * 1000.0);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed CPU spec: '" + spec + "'", ex);
        }
    }

    /**
     * Parses a Kubernetes memory spec string ({@code "256Mi"}, {@code "1Gi"},
     * {@code "500M"}, {@code "1G"}, plain bytes) into MiB. Binary-prefixed
     * values ({@code Ki}, {@code Mi}, {@code Gi}) use 1024-based units; their
     * decimal counterparts ({@code K}, {@code M}, {@code G}) use 1000-based units.
     *
     * <p>This returns the raw mebibyte value: a sub-MiB request such as
     * {@code "512Ki"} yields {@code 0}. The "must be at least 1 MiB" invariant
     * is enforced where it matters — by {@link KubernetesPod}'s constructor,
     * which sizes the underlying VM's RAM to {@code max(1, totalMemMiB)}.</p>
     *
     * @param spec the K8s memory spec; {@code null} or blank yields {@code 0}
     * @return memory size in MiB (may be 0 for sub-MiB inputs)
     * @throws IllegalArgumentException on malformed input
     */
    public static long parseMem(final String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        final String s = spec.trim();
        final long bytes = parseMemBytes(s);
        return bytes / (1024L * 1024L);
    }

    private static long parseMemBytes(final String s) {
        for (final var suffix : MemSuffix.values()) {
            if (s.endsWith(suffix.tag)) {
                final var num = s.substring(0, s.length() - suffix.tag.length()).trim();
                return parseLongLenient(num) * suffix.factor;
            }
        }
        return parseLongLenient(s);
    }

    private static long parseLongLenient(final String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Malformed memory spec: '" + s + "'", ex);
        }
    }

    private enum MemSuffix {
        // Order matters: longer suffixes ("Ki") must be tried before shorter ("K").
        KI("Ki", 1024L),
        MI("Mi", 1024L * 1024L),
        GI("Gi", 1024L * 1024L * 1024L),
        TI("Ti", 1024L * 1024L * 1024L * 1024L),
        K("K", 1_000L),
        M("M", 1_000_000L),
        G("G", 1_000_000_000L),
        T("T", 1_000_000_000_000L);

        final String tag;
        final long factor;

        MemSuffix(final String tag, final long factor) {
            this.tag = tag;
            this.factor = factor;
        }
    }
}

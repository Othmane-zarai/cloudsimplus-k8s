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
 * Controls how {@link Resources#parseCpu(String)} and
 * {@link Resources#parseMem(String)} react to degenerate-but-syntactically-valid
 * inputs that real Kubernetes would reject ({@code "0"} memory,
 * {@code "512Ki"} sub-MiB memory, {@code "0.5m"} sub-millicore CPU).
 *
 * <p>The framework defaults to {@link #LENIENT_WARN} so existing simulations
 * and example YAML manifests keep parsing; switch to {@link #STRICT} when
 * authoring new scenarios where these inputs would mask a configuration bug.</p>
 *
 * <p>See {@code KUBERNETES.md} §3.1 for the canonical edge-case table.</p>
 *
 * @since CloudSim Plus 9.0.0
 */
public enum ParsingMode {

    /** Reject degenerate inputs with {@link IllegalArgumentException}. */
    STRICT,

    /**
     * Accept degenerate inputs, coerce them to the closest legal value
     * (0 millicores, 0 or 1 MiB), and emit a {@code WARN} log entry so the
     * caller can investigate. Default.
     */
    LENIENT_WARN
}

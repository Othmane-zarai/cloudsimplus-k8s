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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelSelectorTest {

    private static final LabelSet WEB = LabelSet.of()
        .with("app", "web").with("tier", "frontend").with("env", "prod").build();

    @Test
    void emptySelectorMatchesAnyLabels() {
        assertTrue(LabelSelector.MATCH_ALL.matches(WEB));
        assertTrue(LabelSelector.MATCH_ALL.matches(LabelSet.EMPTY));
    }

    @Test
    void matchLabelsAreAnded() {
        final var sel = LabelSelector.builder()
            .matchLabel("app", "web")
            .matchLabel("tier", "frontend")
            .build();
        assertTrue(sel.matches(WEB));
        assertFalse(sel.matches(LabelSet.of("app", "web")));
        assertFalse(sel.matches(LabelSet.of()
            .with("app", "web").with("tier", "backend").build()));
    }

    @Test
    void matchExpressionInOnlyMatchesListedValues() {
        final var sel = LabelSelector.builder()
            .matchIn("env", "prod", "staging")
            .build();
        assertTrue(sel.matches(WEB));
        assertFalse(sel.matches(LabelSet.of("env", "dev")));
        assertFalse(sel.matches(LabelSet.EMPTY));
    }

    @Test
    void matchExpressionNotInRejectsListedValues() {
        final var sel = LabelSelector.builder()
            .matchNotIn("env", "dev", "test")
            .build();
        assertTrue(sel.matches(WEB));
        assertFalse(sel.matches(LabelSet.of("env", "dev")));
    }

    @Test
    void matchExpressionExistsAndDoesNotExist() {
        final var existsApp = LabelSelector.builder().matchExists("app").build();
        final var notExistsArchive = LabelSelector.builder().matchDoesNotExist("archive").build();
        assertTrue(existsApp.matches(WEB));
        assertFalse(existsApp.matches(LabelSet.EMPTY));
        assertTrue(notExistsArchive.matches(WEB));
        assertFalse(notExistsArchive.matches(LabelSet.of("archive", "yes")));
    }

    @Test
    void matchLabelsAndExpressionsAreCombined() {
        final var sel = LabelSelector.builder()
            .matchLabel("app", "web")
            .matchIn("env", "prod", "staging")
            .matchExists("tier")
            .build();
        assertTrue(sel.matches(WEB));
        // missing "tier" → matchExists fails
        assertFalse(sel.matches(LabelSet.of()
            .with("app", "web").with("env", "prod").build()));
    }

    @Test
    void inOperatorRequiresNonEmptyValueSet() {
        assertThrows(IllegalArgumentException.class,
            () -> new LabelSelector.Expression("k", LabelSelector.Operator.IN, java.util.Set.of()));
    }
}

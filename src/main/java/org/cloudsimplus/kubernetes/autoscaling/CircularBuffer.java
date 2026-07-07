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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A simple fixed-capacity ring buffer used by the Kubernetes autoscalers.
 *
 * <p>When {@link #add(Object)} is called past capacity the oldest entry is
 * overwritten. {@link #snapshot()} returns an immutable, newest-last view —
 * convenient for percentile calculations and tests that need a stable ordered
 * list of the most recent samples.</p>
 *
 * <p>Not thread-safe. The simulator drives all autoscaler ticks from the same
 * event-loop thread.</p>
 *
 * @param <T> the element type
 * @since CloudSim Plus 9.0.0
 */
public final class CircularBuffer<T> {

    private final Object[] data;
    private final int capacity;
    private int head;   // index where the next add() will land
    private int size;

    /**
     * @param capacity maximum number of retained elements (must be {@code > 0})
     * @throws IllegalArgumentException if {@code capacity <= 0}
     */
    public CircularBuffer(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.data = new Object[capacity];
    }

    /** Append a new element, overwriting the oldest if the buffer is full. */
    public void add(final T element) {
        data[head] = element;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
    }

    /** Number of currently retained elements. */
    public int size() {
        return size;
    }

    /** Maximum number of elements this buffer will retain. */
    public int capacity() {
        return capacity;
    }

    /** {@code true} iff {@link #size()} == 0. */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the element at logical index {@code i}, where {@code 0} is the
     * oldest retained element and {@code size()-1} is the newest.
     *
     * @throws IndexOutOfBoundsException if {@code i} is out of range
     */
    @SuppressWarnings("unchecked")
    public T get(final int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException("index " + i + " out of [0, " + size + ")");
        }
        final int physical = (head - size + i + capacity) % capacity;
        return (T) data[physical];
    }

    /**
     * Returns the most recently added element.
     *
     * @throws NoSuchElementException if the buffer is empty
     */
    public T latest() {
        if (size == 0) {
            throw new NoSuchElementException("buffer is empty");
        }
        return get(size - 1);
    }

    /**
     * Returns an immutable newest-last snapshot of the retained elements.
     * The returned list is a defensive copy; subsequent {@link #add(Object)} calls
     * do not affect it.
     */
    public List<T> snapshot() {
        final List<T> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(get(i));
        }
        return Collections.unmodifiableList(out);
    }

    /** Resets the buffer to empty. */
    public void clear() {
        for (int i = 0; i < capacity; i++) {
            data[i] = null;
        }
        head = 0;
        size = 0;
    }
}

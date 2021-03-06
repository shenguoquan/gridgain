/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.util.offheap.unsafe;

import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 *
 */
public class GridOffheapSnapTreeSelfTest extends GridCommonAbstractTest {
    /**
     * Test for memory leaks.
     *
     * @throws Exception If failed.
     */
    @SuppressWarnings("TypeMayBeWeakened")
    public void testMemoryMultithreaded() throws Exception {
        final TestPointerFactory f = new TestPointerFactory(1000);

        final GridUnsafeMemory mem = new GridUnsafeMemory(25000);

        final GridOffHeapSnapTreeMap<TestPointer, TestPointer> m = new GridOffHeapSnapTreeMap<>(
            f, f, mem);

        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        for (int j = 1; j < 20; j++) {
            final int max = Math.min(1000, j * 20);

            multithreaded(new Runnable() {
                @SuppressWarnings({"LockAcquiredButNotSafelyReleased", "StatementWithEmptyBody",
                    "UnusedDeclaration", "unchecked"})
                @Override public void run()
                {
                    Random rnd = new Random();

                    for (int i = 0; i < 100000; i++) {
                        int x = 1 + rnd.nextInt(max);

                        TestPointer fx = f.createPointer(x);

                        boolean put = rnd.nextInt(2) != 0;

                        lock.readLock().lock();

                        GridUnsafeMemory.Operation op = mem.begin();

                        try {
                            GridOffHeapSmartPointer res = put ? m.put(fx, fx) : m.remove(fx);
                        }
                        finally {
                            lock.readLock().unlock();

                            mem.end(op);
                        }

                        if (i % 100 == 0) {
                            lock.writeLock().lock();

                            GridOffHeapSnapTreeMap<TestPointer, TestPointer> m2;

                            try {
                                m.validate();

                                m2 = m.clone();

                                assert m2.equals(m);
                            }
                            finally {
                                lock.writeLock().unlock();
                            }

                            m2.validate();

                            for (GridOffHeapSmartPointer p : m2.values())
                                assertTrue(((TestPointer)p).refs.get() >= 2);

                            m2.close();
                        }
                    }
                }
            }, 29);

            m.validate();

            assertEquals(m.size(), m.nodes(true));

            assertFalse(mem.allocatedSize() == 0);

            X.println(String.valueOf(mem.allocatedSize()));

            int refs = 0;

            for (TestPointer ptr : f.ptrs)
                refs += ptr.refs.get();

            assertEquals(m.size() * 2 + (m.nodes(false) - m.size()), refs);

            if (j % 2 == 0)
                continue;

            GridUnsafeMemory.Operation op = mem.begin();

            try {
                for (int i = 1; i <= max; i++)
                    m.remove(f.createPointer(i));
            }
            finally {
                mem.end(op);
            }

            assertEquals(0, m.size());

            assertTrue(m.isEmpty());

            assertEquals(0, m.nodes(false));

            for (TestPointer ptr : f.ptrs) {
                refs = ptr.refs.get();

                assertEquals(0, refs);
            }
        }
        m.close();

        assertEquals(0, mem.allocatedSize());
    }

    /**
     * @throws Exception If failed.
     */
    public void testKeyLockMultithreaded() throws Exception {
        final GridOffHeapSnapTreeMap.KeyLock lock = new GridOffHeapSnapTreeMap.KeyLock();

        final int[] ints = new int[2000];

        final int iterations = 1000000;

        final int threads = 37;

        final AtomicInteger sum0 = new AtomicInteger();

        final ConcurrentMap<Integer, Object> locked = new ConcurrentHashMap<>();

        multithreaded(new Runnable() {
            @Override public void run() {
                Random rnd = new Random();

                for (int i = 0; i < iterations; i++)
                    sum0.addAndGet(increment(rnd, 1 + rnd.nextInt(ints.length - 1), new HashSet<Integer>()));
            }

            /**
             * @param rnd Random.
             * @param idx Index.
             * @param locIdxs Locked indexes.
             * @return Count of incremented cells.
             */
            int increment(Random rnd, int idx, HashSet<Integer> locIdxs) {
                if (idx >= ints.length)
                    return 0;

                int res = 1;

                GridOffHeapSnapTreeMap.KeyLock.Lock l = lock.lock(idx);

                try {
                    assertTrue(locIdxs.add(idx));

                    Object check = F.asList(l == null ? Boolean.TRUE : l, Thread.currentThread().getName(), idx);

                    Object check2 = locked.putIfAbsent(idx, check);

                    if (check2 != null)
                        fail(">> " + check + " <><><> " + check2);

                    ints[idx]++;

                    assertNull(lock.lock(idx));

                    if (rnd.nextInt(10) > 2) // Test reentrancy.
                        res += increment(rnd, idx + 1 + rnd.nextInt(3), locIdxs);

                    assertTrue(locIdxs.remove(idx));

                    assertSame(check, locked.remove(idx));
                }
                finally {
                    if (l != null)
                        l.unlock();
                }

                return res;
            }
        }, threads);

        int sum = 0;

        for (int i = 1; i < ints.length; i++) {
            GridOffHeapSnapTreeMap.KeyLock.Lock l = lock.lock(i);

            assertNotNull(l);

            sum += ints[i];

            assertNull(lock.lock(i));

            l.unlock();
        }

        assertEquals(sum0.get(), sum);

        X.println("Sum: ", sum);
    }

    /**
     * Test pointer factory.
     */
    private static final class TestPointerFactory implements GridOffHeapSmartPointerFactory<TestPointer> {
        /** */
        private TestPointer[] ptrs;

        /**
         * @param cnt Pointers count.
         */
        TestPointerFactory(int cnt) {
            ptrs = new TestPointer[cnt];

            for (int i = 0 ; i < ptrs.length; i++)
                ptrs[i] = new TestPointer(i + 1);
        }

        @Override public TestPointer createPointer(long ptr) {
            assert ptr > 0 && ptr <= ptrs.length : ptr + " " + Long.toBinaryString(ptr);

            return ptrs[((int)ptr) - 1];
        }
    }

    /**
     * Test pointer.
     */
    private static final class TestPointer implements GridOffHeapSmartPointer, Comparable<TestPointer> {
        /** */
        private final AtomicInteger refs = new AtomicInteger();

        /** */
        private final int ptr;

        /**
         * @param ptr Pointer.
         */
        private TestPointer(int ptr) {
            this.ptr = ptr;
        }

        @Override public long pointer() {
            return ptr;
        }

        @Override public void incrementRefCount() {
            refs.incrementAndGet();
        }

        @Override public void decrementRefCount() {
            int res = refs.decrementAndGet();

            assert res >= 0 : D.dumpWithStop() + ptr;
        }

        @SuppressWarnings("SubtractionInCompareTo")
        @Override public int compareTo(TestPointer o) {
            assert o.refs.get() > 0 : o.refs.get();

            return (int)(o.pointer() - pointer());
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;

            if (o == null || getClass() != o.getClass()) return false;

            TestPointer that = (TestPointer)o;

            return ptr == that.ptr;
        }

        @Override public int hashCode() {
            return ptr;
        }

        @Override public String toString() {
            return ptr + "(" + refs + ")";
        }
    }
}

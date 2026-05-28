/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ThreadFactory} implementation that creates daemon threads with a common name prefix.
 *
 * <p>All threads produced by this factory are marked as daemon threads, meaning they will
 * not prevent JVM shutdown when only daemon threads remain. Each thread is assigned a
 * sequentially numbered name in the format {@code <poolName>-<number>} (e.g. "handler-1",
 * "handler-2") and normalized to {@link Thread#NORM_PRIORITY}.
 *
 * <p>This factory is used internally by the framework for Netty event loop threads and
 * handler executor thread pools.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class DaemonThreadFactory implements ThreadFactory {

    /** Atomically incremented counter for unique thread naming. */
    private final AtomicInteger threadNo = new AtomicInteger(1);

    /** Common name prefix for all threads created by this factory. */
    private final String nameStart;

    /**
     * Creates a new factory that produces daemon threads with the given pool name prefix.
     *
     * @param poolName the prefix used for naming threads (e.g. "handler" produces "handler-1", "handler-2", etc.)
     */
    public DaemonThreadFactory(String poolName) {
        nameStart = poolName + "-";
    }

    /**
     * Creates a new daemon thread with an auto-incremented name and normal priority.
     *
     * @param r the runnable task to be executed by the new thread
     * @return a new daemon thread ready to run the given task
     */
    @Override
    public Thread newThread(Runnable r) {
        String threadName = nameStart + threadNo.getAndIncrement();
        Thread newThread = new Thread(r, threadName);
        newThread.setDaemon(true);
        // Normalize priority to NORM_PRIORITY in case the parent thread has a different priority
        if (newThread.getPriority() != Thread.NORM_PRIORITY) {
            newThread.setPriority(Thread.NORM_PRIORITY);
        }
        return newThread;
    }

}
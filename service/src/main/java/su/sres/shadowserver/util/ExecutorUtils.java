/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorUtils {

  public static Executor newFixedThreadBoundedQueueExecutor(int threadCount, int queueSize) {
    ThreadPoolExecutor executor = new ThreadPoolExecutor(threadCount, threadCount,
                                                         Long.MAX_VALUE, TimeUnit.NANOSECONDS,
                                                         new ArrayBlockingQueue<>(queueSize),
                                                         new ThreadPoolExecutor.AbortPolicy());

    executor.prestartAllCoreThreads();

    return executor;
  }

}
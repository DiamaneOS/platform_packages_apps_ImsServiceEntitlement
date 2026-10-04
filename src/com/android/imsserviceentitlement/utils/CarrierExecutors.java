// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;
import java.util.concurrent.*;

/** Physical admission and cleanup pools; interruption never creates replacement capacity. */
final class CarrierExecutors {
    // Two active FP6 subscriptions plus one activation UI. Timed-out workers
    // continue occupying these slots until their physical operation exits.
    private static final int CARRIER_WORKERS = 3;
    private static final int PENDING_CARRIER_CHECKS = 3;
    private static final ThreadPoolExecutor CARRIER_EXECUTOR = new ThreadPoolExecutor(
            CARRIER_WORKERS, CARRIER_WORKERS, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(PENDING_CARRIER_CHECKS));
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
            task -> { Thread thread = new Thread(task, "CarrierDeadline"); thread.setDaemon(true); return thread; });
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(CARRIER_WORKERS + PENDING_CARRIER_CHECKS));
    // Scheduling is serialized but never shares the main looper or carrier workers.
    private static final ThreadPoolExecutor SCHEDULING = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(CARRIER_WORKERS + PENDING_CARRIER_CHECKS));
    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private CarrierExecutors() {}
    static Executor carrier() { return CARRIER_EXECUTOR; }
    static ScheduledThreadPoolExecutor deadlines() { return DEADLINES; }
    static Executor scheduling() { return SCHEDULING; }
    static void purge() { CARRIER_EXECUTOR.purge(); }
    static void cleanup(Runnable action) {
        try { CLEANUP.execute(action); }
        catch (RejectedExecutionException saturated) { /* Revocation still holds; physical capacity stays bounded. */ }
    }
}

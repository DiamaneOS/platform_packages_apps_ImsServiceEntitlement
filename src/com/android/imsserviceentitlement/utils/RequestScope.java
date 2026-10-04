// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One lifetime across queueing, HTTP, AKA, redirects and the token retry. */
public final class RequestScope implements AutoCloseable {
    // Four ordinary 30-second HTTP-stage budgets cover token renewal, a full
    // AKA exchange and local processing. This is a downstream containment limit.
    public static final long TIMEOUT_MILLIS = 4 * 30_000L;
    private static final ThreadLocal<RequestScope> CURRENT = new ThreadLocal<>();
    private final LongSupplier clock;
    private final long started;
    private final long budgetNanos;
    private final Set<Runnable> transports = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean revoked;
    private boolean accepted;
    private boolean finished;
    private ScheduledFuture<?> timer;

    public RequestScope() { this(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)); }
    RequestScope(LongSupplier clock, long budgetNanos) {
        if (budgetNanos <= 0) throw new IllegalArgumentException("Nonpositive request budget");
        this.clock = clock;
        this.started = clock.getAsLong();
        this.budgetNanos = budgetNanos;
    }
    public synchronized boolean isCurrent() {
        return !revoked && !finished && clock.getAsLong() - started < budgetNanos;
    }
    public synchronized int remainingMillis() throws IOException {
        long remaining = budgetNanos - (clock.getAsLong() - started);
        if (revoked || finished || remaining <= 0) throw new SocketTimeoutException("Carrier request ended");
        // Zero means unlimited to URLConnection; always round positive budgets up.
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1,
                TimeUnit.NANOSECONDS.toMillis(remaining) + (remaining % 1_000_000 == 0 ? 0 : 1)));
    }
    public void check() throws IOException {
        if (!isCurrent() || Thread.currentThread().isInterrupted()) {
            throw new SocketTimeoutException("Carrier request ended");
        }
    }
    /** Only bounded local cache publication may run under this lock. */
    public synchronized <T> T accept(Supplier<T> publication) {
        if (!isCurrent() || accepted || Thread.currentThread().isInterrupted()) return null;
        T result = publication.get();
        if (result != null) {
            accepted = true;
        }
        return result;
    }
    public synchronized void arm(Runnable timeout) {
        if (timer != null) throw new IllegalStateException("Request deadline already armed");
        long remaining = Math.max(0, budgetNanos - (clock.getAsLong() - started));
        timer = CarrierExecutors.deadlines().schedule(() -> {
            if (abort()) timeout.run();
        }, remaining, TimeUnit.NANOSECONDS);
    }
    public void track(Runnable abortTransport) throws IOException {
        synchronized (this) {
            check();
            transports.add(abortTransport);
        }
    }
    public synchronized void untrack(Runnable abortTransport) { transports.remove(abortTransport); }
    public boolean abort() {
        Runnable[] cleanup;
        synchronized (this) {
            if (revoked || finished) return false;
            revoked = true;
            if (timer != null) timer.cancel(false);
            cleanup = transports.toArray(new Runnable[0]);
            transports.clear();
        }
        // Closing a platform transport can block. Never do it on the timer,
        // main thread or under an ownership/cache monitor.
        for (Runnable action : cleanup) CarrierExecutors.cleanup(action);
        return true;
    }
    public static RequestScope current() { return CURRENT.get(); }
    public <T> T run(Supplier<T> action) {
        RequestScope previous = CURRENT.get();
        CURRENT.set(this);
        try { return action.get(); }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    /** Decide successful completion against revocation/deadline in one critical section. */
    public boolean complete() {
        Runnable[] cleanup;
        boolean current;
        synchronized (this) {
            if (finished) return false;
            current = isCurrent() && !Thread.currentThread().isInterrupted();
            finished = true;
            if (timer != null) timer.cancel(false);
            cleanup = transports.toArray(new Runnable[0]);
            transports.clear();
        }
        for (Runnable action : cleanup) CarrierExecutors.cleanup(action);
        return current;
    }
    @Override public void close() { complete(); }
}

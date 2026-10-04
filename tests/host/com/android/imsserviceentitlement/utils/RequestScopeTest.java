// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production lifetime/backoff/admission logic with injected time; no network. */
public final class RequestScopeTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        AtomicLong time = new AtomicLong();
        RequestScope scope = new RequestScope(time::get, TimeUnit.MILLISECONDS.toNanos(20));
        check(scope.remainingMillis() == 20);
        time.set(TimeUnit.MILLISECONDS.toNanos(19) + 1);
        check(scope.remainingMillis() == 1); // Never turn a positive budget into unlimited zero.
        time.set(TimeUnit.MILLISECONDS.toNanos(20));
        try { scope.check(); throw new AssertionError(); } catch (SocketTimeoutException expected) { }
        check(scope.accept(() -> "late") == null);
        scope.close();

        RequestScope parent = new RequestScope();
        RequestScope child = new RequestScope();
        parent.run(() -> {
            check(RequestScope.current() == parent);
            child.run(() -> { check(RequestScope.current() == child); return null; });
            check(RequestScope.current() == parent);
            return null;
        });
        check(RequestScope.current() == null);
        parent.close(); child.close();

        time.set(0);
        RequestScope accepted = new RequestScope(time::get, TimeUnit.MILLISECONDS.toNanos(20));
        check("accepted".equals(accepted.accept(() -> "accepted")));
        check(accepted.accept(() -> "duplicate") == null);
        time.set(TimeUnit.MILLISECONDS.toNanos(21));
        check(!accepted.isCurrent()); // A blocking post-acceptance worker cannot extend the deadline.
        check(!accepted.complete());
        RequestScope successful = new RequestScope();
        check(successful.complete());
        check(!successful.abort());
        check(!successful.complete());
        try { successful.remainingMillis(); throw new AssertionError(); }
        catch (SocketTimeoutException expected) { }

        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        Runnable stuck = () -> {
            active.incrementAndGet(); entered.countDown();
            for (;;) {
                try { release.await(); break; } catch (InterruptedException ignored) { }
            }
            active.decrementAndGet();
        };
        Future<?>[] workers = new Future<?>[3];
        for (int i = 0; i < 3; i++) workers[i] = ((ExecutorService) CarrierExecutors.carrier()).submit(stuck);
        check(entered.await(3, TimeUnit.SECONDS));
        for (Future<?> worker : workers) worker.cancel(true);
        for (int i = 0; i < 3; i++) CarrierExecutors.carrier().execute(() -> {});
        try { CarrierExecutors.carrier().execute(() -> {}); throw new AssertionError(); }
        catch (RejectedExecutionException expected) { }
        check(active.get() == 3); // No caller-runs or replacement capacity for stuck workers.
        release.countDown();

        CountDownLatch timeout = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        RequestScope expiring = new RequestScope(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(20));
        expiring.track(() -> {
            check(!Thread.currentThread().getName().equals("CarrierDeadline"));
            cleanup.countDown();
        });
        expiring.arm(timeout::countDown);
        check(timeout.await(3, TimeUnit.SECONDS));
        check(cleanup.await(3, TimeUnit.SECONDS));
        check(!expiring.isCurrent());
        try { expiring.track(() -> {}); throw new AssertionError(); }
        catch (SocketTimeoutException expected) { }
        expiring.close();
        System.out.println("Request lifetime, deadline and physical admission: PASS");
        System.exit(0); // Only this standalone host runner; production pools live with the Android process.
    }
}

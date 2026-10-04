// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.entitlement;

import static com.google.common.truth.Truth.assertThat;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real private test preferences, injected clocks; no phone or carrier operations. */
@RunWith(AndroidJUnit4.class)
public final class RetryDeadlineTest {
    private final AtomicLong wall = new AtomicLong(1_000_000);
    private final AtomicLong elapsed = new AtomicLong(100_000);
    private SharedPreferences preferences;
    private EntitlementConfigurationsDataStore store;

    @Before public void setup() {
        Context context = ApplicationProvider.getApplicationContext();
        preferences = context.getSharedPreferences("retry-deadline-fixture", Context.MODE_PRIVATE);
        assertThat(preferences.edit().clear().commit()).isTrue();
        store = new EntitlementConfigurationsDataStore(preferences, 5, wall::get, elapsed::get);
    }

    @Test public void clockCorrectionDoesNotShortenRelativeDelayWithinBoot() {
        assertThat(store.deferRequests(store.generation(), () -> true, 60_000)).isTrue();
        wall.addAndGet(3_600_000);
        elapsed.addAndGet(10_000);
        assertThat(store.retryDelayMillis()).isEqualTo(50_000);
        wall.addAndGet(-7_200_000);
        assertThat(store.retryDelayMillis()).isEqualTo(50_000);
    }

    @Test public void processRestartRetainsElapsedDeadline() {
        assertThat(store.deferRequests(store.generation(), () -> true, 60_000)).isTrue();
        elapsed.addAndGet(10_000);
        var replacement = new EntitlementConfigurationsDataStore(
                preferences, 5, wall::get, elapsed::get);
        assertThat(replacement.retryDelayMillis()).isEqualTo(50_000);
    }

    @Test public void rebootUsesWallClockReconciliation() {
        assertThat(store.deferRequests(store.generation(), () -> true, 60_000)).isTrue();
        wall.addAndGet(10_000);
        elapsed.set(1_000);
        var replacement = new EntitlementConfigurationsDataStore(
                preferences, 6, wall::get, elapsed::get);
        assertThat(replacement.retryDelayMillis()).isEqualTo(50_000);
    }

    @Test public void cacheReplacementRejectsLateDelayAndClearsCurrentDelay() {
        Object generation = store.generation();
        assertThat(store.deferRequests(generation, () -> true, 60_000)).isTrue();
        store.set(null);
        assertThat(store.retryDelayMillis()).isEqualTo(0);
        assertThat(store.deferRequests(generation, () -> true, 60_000)).isFalse();
        assertThat(store.retryDelayMillis()).isEqualTo(0);
    }

    @Test public void overlappingFailureCannotShortenConfirmedSameOwnerDelay() {
        Object generation = store.generation();
        assertThat(store.deferRequests(generation, () -> true, 3_600_000)).isTrue();
        elapsed.addAndGet(10_000);
        assertThat(store.deferRequests(generation, () -> true, 30_000)).isTrue();
        assertThat(store.retryDelayMillis()).isEqualTo(3_590_000);
    }

    @Test public void failedCommitIsNotSyntheticApprovalAndRecoversWithoutCarrierIo() {
        AtomicInteger failures = new AtomicInteger(1);
        SharedPreferences wrapped = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                (proxy, method, arguments) -> {
                    Object value = method.invoke(preferences, arguments);
                    if (!method.getName().equals("edit")) return value;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) value;
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[] {SharedPreferences.Editor.class},
                            (editorProxy, operation, args) -> {
                                if (operation.getName().equals("commit") && failures.getAndDecrement() > 0) {
                                    editor.apply(); // memory is visible even though persistence failed
                                    return false;
                                }
                                Object result = operation.invoke(editor, args);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        store = new EntitlementConfigurationsDataStore(wrapped, 5, wall::get, elapsed::get);
        Object generation = store.generation();
        assertThat(store.deferRequests(generation, () -> true, 60_000)).isFalse();
        assertThat(store.retryDelayMillis()).isEqualTo(-1);
        assertThat(store.reconcileRetryPersistence(generation, () -> true)).isTrue();
        assertThat(store.retryDelayMillis()).isEqualTo(60_000);
    }

    @Test public void carrierDelayIsCappedAtOneDay() {
        assertThat(store.deferRequests(store.generation(), () -> true, 10 * 86_400_000L)).isTrue();
        assertThat(store.retryDelayMillis())
                .isEqualTo(EntitlementConfigurationsDataStore.MAX_RETRY_DELAY_MILLIS);
    }

    @Test public void storedOverlongDeadlineIsCappedAfterRestart() {
        long tenDays = 10 * 86_400_000L;
        assertThat(preferences.edit().putString("RETRY_OWNER", "legacy")
                .putLong("RETRY_NOT_BEFORE_MILLIS", wall.get() + tenDays)
                .putLong("RETRY_ELAPSED_DUE", elapsed.get() + tenDays)
                .putInt("RETRY_BOOT", 5).commit()).isTrue();
        var replacement = new EntitlementConfigurationsDataStore(
                preferences, 6, wall::get, elapsed::get);
        assertThat(replacement.retryDelayMillis())
                .isEqualTo(EntitlementConfigurationsDataStore.MAX_RETRY_DELAY_MILLIS);
    }

    @Test public void canceledOwnerCannotPersistDelay() {
        assertThat(store.deferRequests(store.generation(), () -> false, 60_000)).isFalse();
        assertThat(store.retryDelayMillis()).isEqualTo(0);
    }
}

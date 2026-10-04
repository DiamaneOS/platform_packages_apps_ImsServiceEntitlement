/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.imsserviceentitlement.entitlement;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.UUID;
import java.util.function.LongSupplier;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

class EntitlementConfigurationsDataStore {
    private static final String PREFERENCE_ENTITLEMENT_CHARACTERISTICS =
            "ENTITLEMENT_CHARACTERISTICS";
    private static final String XML_DOCUMENT = "XML_DOCUMENT";
    private static final String ENTITLEMENT_VERSION = "ENTITLEMENT_VERSION";
    private static final String QUERY_TIME_MILLIS = "QUERY_TIME_MILLIS";
    private static final String RETRY_NOT_BEFORE_MILLIS = "RETRY_NOT_BEFORE_MILLIS";
    private static final String CACHE_STAMP = "CACHE_STAMP";
    private static final String RETRY_OWNER = "RETRY_OWNER";
    private static final String RETRY_ELAPSED_DUE = "RETRY_ELAPSED_DUE";
    private static final String RETRY_BOOT = "RETRY_BOOT";
    // Carrier Retry-After cap. Also bounds deadlines stored before the cap or skewed by clock changes.
    static final long MAX_RETRY_DELAY_MILLIS = 24 * 60 * 60 * 1000L;

    private final SharedPreferences mPreferences;
    // Only retry-metadata writers use this gate; no cache/scope monitor spans disk IO.
    private final Object mRetryPersistenceLock = new Object();
    private volatile RetryDeadline mPendingRetry;
    private RetryDeadline mConfirmedRetry; // this monitor only
    private static final class RetryDeadline {
        final String owner;
        final long wallDue;
        final long elapsedDue;
        final int boot;
        RetryDeadline(String owner, long wallDue, long elapsedDue, int boot) {
            this.owner = owner; this.wallDue = wallDue; this.elapsedDue = elapsedDue; this.boot = boot;
        }
    }
    private final int mBootCount;
    private final LongSupplier mWallClock;
    private final LongSupplier mElapsedClock;
    // Identity, rather than a wrapping counter: an old request cannot commit
    // after a reset or another writer, even if the stored XML becomes identical.
    private Object mGeneration = new Object();

    private static final SparseArray<EntitlementConfigurationsDataStore> sInstances =
            new SparseArray<>();

    public static EntitlementConfigurationsDataStore getInstance(Context context, int subId) {
        synchronized (EntitlementConfigurationsDataStore.class) {
            EntitlementConfigurationsDataStore existing = sInstances.get(subId);
            if (existing != null) return existing;
        }
        EntitlementConfigurationsDataStore candidate = new EntitlementConfigurationsDataStore(context, subId);
        synchronized (EntitlementConfigurationsDataStore.class) {
            EntitlementConfigurationsDataStore existing = sInstances.get(subId);
            if (existing != null) return existing;
            sInstances.put(subId, candidate);
            return candidate;
        }
    }

    private EntitlementConfigurationsDataStore(Context context, int subId) {
        this(context.getSharedPreferences(PREFERENCE_ENTITLEMENT_CHARACTERISTICS + "_" + subId,
                Context.MODE_PRIVATE),
                Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                System::currentTimeMillis, SystemClock::elapsedRealtime);
    }

    EntitlementConfigurationsDataStore(SharedPreferences preferences, int bootCount,
            LongSupplier wallClock, LongSupplier elapsedClock) {
        mPreferences = preferences;
        mBootCount = bootCount;
        mWallClock = wallClock;
        mElapsedClock = elapsedClock;
        String owner = preferences.getString(RETRY_OWNER, "");
        if (!owner.isEmpty()) mConfirmedRetry = new RetryDeadline(owner,
                preferences.getLong(RETRY_NOT_BEFORE_MILLIS, 0),
                preferences.getLong(RETRY_ELAPSED_DUE, 0), preferences.getInt(RETRY_BOOT, -1));
    }

    public synchronized void set(int entitlementVersion, String characteristics) {
        mGeneration = new Object();
        mPendingRetry = null;
        mConfirmedRetry = null;
        mPreferences
                .edit()
                .putString(XML_DOCUMENT, characteristics)
                .putString(ENTITLEMENT_VERSION, String.valueOf(entitlementVersion))
                .putLong(QUERY_TIME_MILLIS, System.currentTimeMillis())
                .putString(CACHE_STAMP, UUID.randomUUID().toString())
                .remove(RETRY_NOT_BEFORE_MILLIS)
                .apply();
    }

    public synchronized void set(String characteristics) {
        mGeneration = new Object();
        mPendingRetry = null;
        mConfirmedRetry = null;
        mPreferences
                .edit()
                .putString(XML_DOCUMENT, characteristics)
                .putLong(QUERY_TIME_MILLIS, System.currentTimeMillis())
                .putString(CACHE_STAMP, UUID.randomUUID().toString())
                .remove(RETRY_NOT_BEFORE_MILLIS)
                .apply();
    }

    public synchronized Optional<String> getRawXml() {
        return Optional.ofNullable(mPreferences.getString(XML_DOCUMENT, null));
    }

    boolean deferRequests(Object generation, BooleanSupplier current, long delayMillis) {
        if (delayMillis < 0) throw new IllegalArgumentException("Negative carrier delay");
        delayMillis = Math.min(delayMillis, MAX_RETRY_DELAY_MILLIS);
        synchronized (mRetryPersistenceLock) {
            String owner = commitIfCurrent(generation, current,
                    () -> mPreferences.getString(CACHE_STAMP, "legacy"));
            if (owner == null) return false;
            synchronized (this) {
                // Overlapping 503s cannot shorten either an accepted delay or
                // an earlier valid instruction still awaiting durable storage.
                if (mConfirmedRetry != null && owner.equals(mConfirmedRetry.owner))
                    delayMillis = Math.max(delayMillis, remaining(mConfirmedRetry));
                if (mPendingRetry != null && owner.equals(mPendingRetry.owner))
                    delayMillis = Math.max(delayMillis, remaining(mPendingRetry));
            }
            RetryDeadline proposed = new RetryDeadline(owner,
                    saturatedDue(mWallClock.getAsLong(), delayMillis),
                    saturatedDue(mElapsedClock.getAsLong(), delayMillis), mBootCount);
            return writeRetry(generation, current, proposed);
        }
    }

    /** Repair a failed metadata write before allowing another HTTP/AKA request. */
    boolean reconcileRetryPersistence(Object generation, BooleanSupplier current) {
        if (mPendingRetry == null) return true;
        synchronized (mRetryPersistenceLock) {
            RetryDeadline pending = mPendingRetry;
            if (pending == null) return true;
            String owner = commitIfCurrent(generation, current,
                    () -> mPreferences.getString(CACHE_STAMP, "legacy"));
            if (owner == null) return false;
            if (!owner.equals(pending.owner)) {
                mPendingRetry = null;
                return true;
            }
            return writeRetry(generation, current, pending);
        }
    }

    private boolean writeRetry(Object generation, BooleanSupplier current, RetryDeadline proposed) {
        Boolean admitted = commitIfCurrent(generation, current, () -> {
            mPendingRetry = proposed;
            return true;
        });
        if (!Boolean.TRUE.equals(admitted)) return false;
        // commit updates preference memory before disk. Readers deliberately use
        // only the confirmed snapshot; pending/failed writes remain retryable.
        boolean saved = mPreferences.edit().putString(RETRY_OWNER, proposed.owner)
                .putLong(RETRY_NOT_BEFORE_MILLIS, proposed.wallDue)
                .putLong(RETRY_ELAPSED_DUE, proposed.elapsedDue).putInt(RETRY_BOOT, proposed.boot).commit();
        synchronized (this) {
            if (saved) {
                mConfirmedRetry = proposed;
                if (mPendingRetry == proposed) mPendingRetry = null;
            }
        }
        return saved && Boolean.TRUE.equals(commitIfCurrent(generation, current, () -> true));
    }

    private static long saturatedDue(long now, long delay) {
        now = Math.max(0, now);
        return delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
    }

    synchronized long retryDelayMillis() {
        String owner = mPreferences.getString(CACHE_STAMP, "legacy");
        if (mPendingRetry != null && owner.equals(mPendingRetry.owner)) return -1;
        return mConfirmedRetry != null && owner.equals(mConfirmedRetry.owner)
                ? remaining(mConfirmedRetry) : 0;
    }

    private long remaining(RetryDeadline deadline) {
        boolean sameBoot = mBootCount >= 0 && mBootCount == deadline.boot;
        long due = sameBoot ? deadline.elapsedDue : deadline.wallDue;
        long now = Math.max(0, (sameBoot ? mElapsedClock : mWallClock).getAsLong());
        return due > now ? Math.min(due - now, MAX_RETRY_DELAY_MILLIS) : 0;
    }

    public synchronized long getQueryTimeMillis() {
        return mPreferences.getLong(QUERY_TIME_MILLIS, 0);
    }

    public synchronized Optional<String> getEntitlementVersion() {
        return Optional.ofNullable(mPreferences.getString(ENTITLEMENT_VERSION, null));
    }

    synchronized Object generation() {
        return mGeneration;
    }

    /** The action is a bounded local commit, never a carrier/network operation. */
    synchronized <T> T commitIfCurrent(Object generation, BooleanSupplier current,
            Supplier<T> action) {
        if (generation != mGeneration || !current.getAsBoolean()
                || generation != mGeneration) return null;
        return action.get();
    }
}

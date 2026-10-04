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

package com.android.imsserviceentitlement;

import static com.android.imsserviceentitlement.utils.Executors.getAsyncExecutor;
import static com.android.imsserviceentitlement.utils.Executors.getDirectExecutor;

import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.imsserviceentitlement.WfcActivationController.EntitlementResultCallback;
import com.android.imsserviceentitlement.entitlement.EntitlementResult;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Handles entitlement check from main thread. */
public final class EntitlementUtils {

    public static final String LOG_TAG = "IMSSE-EntitlementUtils";

    private static final Object CHECK_LOCK = new Object();
    private static final class Check {
        volatile boolean cancelled;
        ListenableFuture<EntitlementResult> future; // CHECK_LOCK only.
    }
    private static Check sCheck;

    private EntitlementUtils() {}

    /**
     * Performs the entitlement status check, and passes the result via {@link
     * EntitlementResultCallback}.
     */
    @MainThread
    public static void entitlementCheck(
            ImsEntitlementApi activationApi, EntitlementResultCallback callback) {
        Check check = new Check();
        Check previous;
        synchronized (CHECK_LOCK) {
            previous = sCheck;
            if (previous != null) previous.cancelled = true;
            sCheck = check;
        }
        cancel(previous);
        ListenableFuture<EntitlementResult> future = Futures.submit(
                () -> getEntitlementStatus(activationApi, check), getAsyncExecutor());
        synchronized (CHECK_LOCK) { check.future = future; }
        if (check.cancelled) future.cancel(true);
        Futures.addCallback(future, new FutureCallback<EntitlementResult>() {
            @Override
            public void onSuccess(EntitlementResult result) {
                if (finish(check)) {
                    callback.onEntitlementResult(result != null && activationApi.isResultCurrent(result)
                            ? result : null);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                if (finish(check)) {
                    Log.w(LOG_TAG, "get entitlement status failed");
                    callback.onEntitlementResult(null);
                }
            }
        }, getDirectExecutor());
    }

    private static boolean finish(Check check) {
        synchronized (CHECK_LOCK) {
            if (sCheck != check || check.cancelled) return false;
            sCheck = null;
            return true;
        }
    }

    private static void cancel(Check check) {
        if (check == null) return;
        ListenableFuture<EntitlementResult> future;
        synchronized (CHECK_LOCK) { future = check.future; }
        if (future != null) future.cancel(true);
    }

    /** Cancels the running task without letting its late callback clear a successor. */
    public static void cancelEntitlementCheck() {
        Check check;
        synchronized (CHECK_LOCK) {
            check = sCheck;
            if (check != null) check.cancelled = true;
            sCheck = null;
        }
        cancel(check);
    }

    /**
     * Gets entitlement status via carrier-specific entitlement API over network; returns null on
     * network falure or other unexpected failure from entitlement API.
     */
    @WorkerThread
    @Nullable
    private static EntitlementResult getEntitlementStatus(ImsEntitlementApi activationApi, Check check) {
        try {
            return activationApi.checkEntitlementStatus(() -> !check.cancelled);
        } catch (RuntimeException e) {
            Log.e("WfcActivationActivity", "getEntitlementStatus failed");
            return null;
        }
    }
}

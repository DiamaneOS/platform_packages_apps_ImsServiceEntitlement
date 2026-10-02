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

import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

class EntitlementConfigurationsDataStore {
    private static final String PREFERENCE_ENTITLEMENT_CHARACTERISTICS =
            "ENTITLEMENT_CHARACTERISTICS";
    private static final String XML_DOCUMENT = "XML_DOCUMENT";
    private static final String ENTITLEMENT_VERSION = "ENTITLEMENT_VERSION";
    private static final String QUERY_TIME_MILLIS = "QUERY_TIME_MILLIS";

    private final SharedPreferences mPreferences;
    // Identity, rather than a wrapping counter: an old request cannot commit
    // after a reset or another writer, even if the stored XML becomes identical.
    private Object mGeneration = new Object();

    private static final SparseArray<EntitlementConfigurationsDataStore> sInstances =
            new SparseArray<>();

    public static synchronized EntitlementConfigurationsDataStore getInstance(Context context, int subId) {
        if (sInstances.get(subId) == null) {
            sInstances.put(subId, new EntitlementConfigurationsDataStore(context, subId));
        }
        return sInstances.get(subId);
    }

    private EntitlementConfigurationsDataStore(Context context, int subId) {
        this.mPreferences = context.getSharedPreferences(
                PREFERENCE_ENTITLEMENT_CHARACTERISTICS + "_" + subId,
                Context.MODE_PRIVATE);
    }

    public synchronized void set(int entitlementVersion, String characteristics) {
        mGeneration = new Object();
        mPreferences
                .edit()
                .putString(XML_DOCUMENT, characteristics)
                .putString(ENTITLEMENT_VERSION, String.valueOf(entitlementVersion))
                .putLong(QUERY_TIME_MILLIS, System.currentTimeMillis())
                .apply();
    }

    public synchronized void set(String characteristics) {
        mGeneration = new Object();
        mPreferences
                .edit()
                .putString(XML_DOCUMENT, characteristics)
                .putLong(QUERY_TIME_MILLIS, System.currentTimeMillis())
                .apply();
    }

    public synchronized Optional<String> getRawXml() {
        return Optional.ofNullable(mPreferences.getString(XML_DOCUMENT, null));
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

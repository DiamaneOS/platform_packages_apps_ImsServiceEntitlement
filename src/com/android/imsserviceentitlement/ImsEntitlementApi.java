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

import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.time.temporal.ChronoUnit.SECONDS;
import static com.android.imsserviceentitlement.ts43.Ts43Constants.EntitlementVersion.ENTITLEMENT_VERSION_EIGHT;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.imsserviceentitlement.entitlement.EntitlementConfiguration;
import com.android.imsserviceentitlement.entitlement.EntitlementConfiguration.ClientBehavior;
import com.android.imsserviceentitlement.entitlement.EntitlementResult;
import com.android.imsserviceentitlement.ts43.Ts43Constants.ResponseXmlAttributes;
import com.android.imsserviceentitlement.ts43.Ts43SmsOverIpStatus;
import com.android.imsserviceentitlement.ts43.Ts43VolteStatus;
import com.android.imsserviceentitlement.ts43.Ts43VonrStatus;
import com.android.imsserviceentitlement.ts43.Ts43VowifiStatus;
import com.android.imsserviceentitlement.utils.TelephonyUtils;
import com.android.imsserviceentitlement.utils.XmlDoc;
import com.android.imsserviceentitlement.utils.HttpsUrl;
import com.android.imsserviceentitlement.utils.CarrierTransport;
import com.android.imsserviceentitlement.utils.RequestScope;
import com.android.libraries.entitlement.CarrierConfig;
import com.android.libraries.entitlement.ServiceEntitlement;
import com.android.libraries.entitlement.ServiceEntitlementException;
import com.android.libraries.entitlement.ServiceEntitlementRequest;

import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.BooleanSupplier;

/** Implementation of the entitlement API. */
public class ImsEntitlementApi {
    private static final String TAG = "IMSSE-ImsEntitlementApi";

    private static final int RESPONSE_RETRY_AFTER = 503;
    private static final int RESPONSE_TOKEN_EXPIRED = 511;

    private static final int AUTHENTICATION_RETRIES = 1;

    private final Context mContext;
    private final int mSubId;
    private final ServiceEntitlement mServiceEntitlement;
    private final EntitlementConfiguration mLastEntitlementConfiguration;

    private static final class AcceptedResult {
        final Object generation;
        final EntitlementResult result;
        AcceptedResult(Object generation, EntitlementResult result) {
            this.generation = generation;
            this.result = result;
        }
    }
    // Same minimum as Android's ordinary first failure backoff; zero cannot spin AKA requests.
    private static final long MIN_RETRY_AFTER_SECONDS = 30;
    // A carrier delay is stored and also blocks the activation UI; cap it at the refresh period.
    @VisibleForTesting
    static final long MAX_RETRY_AFTER_SECONDS = 24 * 60 * 60;
    private volatile AcceptedResult mAcceptedResult;
    private boolean mNeedsImsProvisioning;

    @VisibleForTesting
    static Clock sClock = Clock.systemUTC();

    public ImsEntitlementApi(Context context, int subId) {
        this.mContext = context;
        this.mSubId = subId;
        String serverUrl = TelephonyUtils.getEntitlementServerUrl(context, subId);
        if (serverUrl == null) serverUrl = "";
        CarrierConfig carrierConfig = CarrierConfig.builder()
                .setClientTs43(CarrierConfig.CLIENT_TS_43_IMS_ENTITLEMENT)
                .setServerUrl(serverUrl)
                .setUrlConnectionFactory(CarrierTransport::open)
                .build();
        this.mNeedsImsProvisioning = TelephonyUtils.isImsProvisioningRequired(context, subId);
        this.mServiceEntitlement =
                HttpsUrl.isAllowed(serverUrl) ? new ServiceEntitlement(
                        context,
                        carrierConfig,
                        subId,
                        /* saveHttpHistory = */ false,
                        /* bypassEapAkaResponse = */ "") : null;
        this.mLastEntitlementConfiguration = new EntitlementConfiguration(context, subId);
    }

    @VisibleForTesting
    ImsEntitlementApi(
            Context context,
            int subId,
            boolean needsImsProvisioning,
            ServiceEntitlement serviceEntitlement,
            EntitlementConfiguration lastEntitlementConfiguration) {
        this.mContext = context;
        this.mSubId = subId;
        this.mNeedsImsProvisioning = needsImsProvisioning;
        this.mServiceEntitlement = serviceEntitlement;
        this.mLastEntitlementConfiguration = lastEntitlementConfiguration;
    }

    /**
     * Returns WFC entitlement check result from carrier API (over network), or {@code null} on
     * unrecoverable network issue or malformed server response. This is blocking call so should
     * not be called on main thread.
     */
    @Nullable
    public EntitlementResult checkEntitlementStatus() {
        return checkEntitlementStatus(() -> true);
    }

    /** The caller's ownership/cancellation check is repeated before any cache mutation. */
    @Nullable
    public EntitlementResult checkEntitlementStatus(BooleanSupplier ownerCurrent) {
        RequestScope existing = RequestScope.current();
        if (existing == null) {
            try (RequestScope scope = new RequestScope()) {
                return scope.run(() -> checkEntitlementStatus(ownerCurrent));
            }
        }
        BooleanSupplier current = () -> !Thread.currentThread().isInterrupted()
                && existing.isCurrent() && ownerCurrent.getAsBoolean()
                && existing.isCurrent() && !Thread.currentThread().isInterrupted();
        return checkEntitlementStatus(current, mLastEntitlementConfiguration.generation(),
                AUTHENTICATION_RETRIES);
    }

    /** Reconcile a carrier stop instruction locally; do not authenticate again to repair setters. */
    @Nullable
    public EntitlementResult checkPollingEntitlementStatus(BooleanSupplier ownerCurrent) {
        RequestScope scope = RequestScope.current();
        if (scope == null) {
            try (RequestScope owned = new RequestScope()) {
                return owned.run(() -> checkPollingEntitlementStatus(ownerCurrent));
            }
        }
        BooleanSupplier current = () -> scope.isCurrent() && ownerCurrent.getAsBoolean()
                && scope.isCurrent() && !Thread.currentThread().isInterrupted();
        Object generation = mLastEntitlementConfiguration.generation();
        // Snapshot local data under the cache monitor; XML and framework lookups run outside it.
        String rawXml = mLastEntitlementConfiguration.commitIfCurrent(
                generation, current, mLastEntitlementConfiguration::getRawXml);
        XmlDoc document = new XmlDoc(rawXml);
        ClientBehavior behavior = EntitlementConfiguration.entitlementValidation(document);
        int configuredVersion = TelephonyUtils.getEntitlementVersion(mContext, mSubId);
        boolean versionUpgrade = configuredVersion >= ENTITLEMENT_VERSION_EIGHT
                && configuredVersion != mLastEntitlementConfiguration.getEntitlementVersion();
        if (behavior != ClientBehavior.NEEDS_TO_RESET_EXCEPT_VERS
                && behavior != ClientBehavior.NEEDS_TO_RESET_EXCEPT_VERS_UNTIL_SETTING_ON
                || versionUpgrade) {
            return checkEntitlementStatus(current, generation, AUTHENTICATION_RETRIES);
        }
        EntitlementResult result = toEntitlementResult(document, behavior);
        return scope.accept(() -> mLastEntitlementConfiguration.commitIfCurrent(
                generation, current, () -> {
                    mAcceptedResult = new AcceptedResult(generation, result);
                    return result;
                }));
    }

    /** A result can be used only while its accepted cache generation remains current. */
    public boolean isResultCurrent(EntitlementResult result) {
        AcceptedResult accepted = mAcceptedResult;
        return accepted != null && result != null && accepted.result == result
                && !Thread.currentThread().isInterrupted()
                && accepted.generation == mLastEntitlementConfiguration.generation()
                && !Thread.currentThread().isInterrupted();
    }

    @Nullable
    private EntitlementResult checkEntitlementStatus(BooleanSupplier current, Object generation,
            int authenticationRetries) {
        if (!current.getAsBoolean()) return null;
        if (mServiceEntitlement == null) {
            Log.w(TAG, "No valid carrier HTTPS endpoint configured");
            return null;
        }
        Log.d(TAG, "checkEntitlementStatus subId=" + mSubId);
        int entitlementVersion = TelephonyUtils.getEntitlementVersion(mContext, mSubId);
        try {
            if (!mLastEntitlementConfiguration.reconcileRetryPersistence(generation, current)) return null;
            Long deferred = mLastEntitlementConfiguration.commitIfCurrent(generation, current,
                    mLastEntitlementConfiguration::retryDelayMillis);
            if (deferred == null || deferred < 0) return null;
            if (deferred > 0) {
                boolean defaultActive = TelephonyUtils.getDefaultStatus(mContext, mSubId);
                EntitlementResult delayed = EntitlementResult.builder(defaultActive)
                        .setRetryAfterSeconds(deferred / 1000 + (deferred % 1000 == 0 ? 0 : 1))
                        .build();
                return RequestScope.current().accept(() -> mLastEntitlementConfiguration
                        .commitIfCurrent(generation, current, () -> {
                            mAcceptedResult = new AcceptedResult(generation, delayed);
                            return delayed;
                        }));
            }
            ServiceEntitlementRequest request = mLastEntitlementConfiguration.commitIfCurrent(
                    generation, current, () -> {
                        ServiceEntitlementRequest.Builder builder = ServiceEntitlementRequest.builder();
                        mLastEntitlementConfiguration.getToken().ifPresent(
                                builder::setAuthenticationToken);
                        builder.setEntitlementVersion(entitlementVersion + ".0");
                        builder.setAcceptContentType(ServiceEntitlementRequest.ACCEPT_CONTENT_TYPE_XML);
                        if (mNeedsImsProvisioning) {
                            builder.setConfigurationVersion(Integer.parseInt(
                                    mLastEntitlementConfiguration.getVersion()));
                        }
                        return builder.build();
                    });
            if (request == null) return null;
            String rawXml = mServiceEntitlement.queryEntitlementStatus(
                    mNeedsImsProvisioning
                            ? ImmutableList.of(
                            ServiceEntitlement.APP_VOWIFI,
                            ServiceEntitlement.APP_VOLTE,
                            ServiceEntitlement.APP_SMSOIP)
                            : ImmutableList.of(ServiceEntitlement.APP_VOWIFI),
                    request);
            if (!current.getAsBoolean()) return null;
            XmlDoc entitlementXmlDoc = new XmlDoc(rawXml);
            if (!entitlementXmlDoc.isValid()) return null;
            ClientBehavior behavior = EntitlementConfiguration.entitlementValidation(entitlementXmlDoc);
            if (mNeedsImsProvisioning && behavior == ClientBehavior.UNKNOWN_BEHAVIOR) return null;
            EntitlementResult result = toEntitlementResult(entitlementXmlDoc, behavior);
            return RequestScope.current().accept(() -> mLastEntitlementConfiguration.commitIfCurrent(generation, current, () -> {
                mLastEntitlementConfiguration.update(entitlementVersion, rawXml);
                if (mNeedsImsProvisioning && isResetToDefault(behavior)) {
                    mLastEntitlementConfiguration.reset(behavior);
                }
                mAcceptedResult = new AcceptedResult(mLastEntitlementConfiguration.generation(), result);
                return result;
            }));
        } catch (ServiceEntitlementException e) {
            if (e.getErrorCode() == ServiceEntitlementException.ERROR_HTTP_STATUS_NOT_SUCCESS) {
                if (e.getHttpStatus() == RESPONSE_TOKEN_EXPIRED) {
                    if (authenticationRetries <= 0) {
                        Log.d(TAG, "Ran out of the retry count, stop query status.");
                        return null;
                    }
                    Log.d(TAG, "Server asking for full authentication, retry the query.");
                    // Clean up the cached data and perform full authentication next query.
                    Object resetGeneration = mLastEntitlementConfiguration.commitIfCurrent(
                            generation, current, () -> {
                                mLastEntitlementConfiguration.reset();
                                return mLastEntitlementConfiguration.generation();
                            });
                    if (resetGeneration == null) return null;
                    return checkEntitlementStatus(current, resetGeneration, authenticationRetries - 1);
                } else if (e.getHttpStatus() == RESPONSE_RETRY_AFTER && !TextUtils.isEmpty(
                        e.getRetryAfter())) {
                    // For handling the case of HTTP_UNAVAILABLE(503), client would perform the
                    // retry for the delay of Retry-After.
                    long parsedRetryAfter = parseDelaySecondsByRetryAfter(e.getRetryAfter());
                    if (parsedRetryAfter < 0) return null;
                    long retryAfter = Math.min(MAX_RETRY_AFTER_SECONDS, parsedRetryAfter);
                    boolean isDefaultActive = TelephonyUtils.getDefaultStatus(mContext, mSubId);
                    if (!mLastEntitlementConfiguration.deferRequests(generation, current,
                            Math.max(MIN_RETRY_AFTER_SECONDS, retryAfter) * 1000)) return null;
                    return RequestScope.current().accept(() -> mLastEntitlementConfiguration.commitIfCurrent(generation, current,
                            () -> {
                                EntitlementResult result = EntitlementResult.builder(isDefaultActive)
                                        .setRetryAfterSeconds(retryAfter).build();
                                mAcceptedResult = new AcceptedResult(generation, result);
                                return result;
                            }));
                }
            }
            // The library exception can contain response bodies and credentials.
            Log.e(TAG, "Entitlement query failed: code=" + e.getErrorCode()
                    + " http=" + e.getHttpStatus());
        } catch (NumberFormatException e) {
            Log.w(TAG, "Invalid numeric entitlement response");
        }
        return null;
    }

    /**
     * Parses the value of {@link HttpHeaders#RETRY_AFTER}. The possible formats could be a numeric
     * value in second, or a HTTP-date in RFC-1123 date-time format.
     */
    private long parseDelaySecondsByRetryAfter(String retryAfter) {
        try {
            return Long.parseLong(retryAfter);
        } catch (NumberFormatException numberFormatException) {
        }

        try {
            return SECONDS.between(
                    Instant.now(sClock), RFC_1123_DATE_TIME.parse(retryAfter, Instant::from));
        } catch (DateTimeParseException dateTimeParseException) {
        }

        Log.w(TAG, "Invalid retry-after header");
        return -1;
    }

    private EntitlementResult toEntitlementResult(XmlDoc doc, ClientBehavior clientBehavior) {
        boolean isDefaultActive = TelephonyUtils.getDefaultStatus(mContext, mSubId);
        EntitlementResult.Builder builder = EntitlementResult.builder(isDefaultActive);

        if (mNeedsImsProvisioning && isResetToDefault(clientBehavior)) {
            // Keep default values. The shared configuration resets only in the guarded commit.
        } else {
            builder.setVowifiStatus(Ts43VowifiStatus.builder(doc).build())
                    .setVolteStatus(Ts43VolteStatus.builder(doc).build())
                    .setVonrStatus(Ts43VonrStatus.builder(doc).build())
                    .setSmsoveripStatus(Ts43SmsOverIpStatus.builder(doc).build());
            doc.getFromVowifi(ResponseXmlAttributes.SERVER_FLOW_URL)
                    .ifPresent(url -> builder.setEmergencyAddressWebUrl(url));
            doc.getFromVowifi(ResponseXmlAttributes.SERVER_FLOW_USER_DATA)
                    .ifPresent(userData -> builder.setEmergencyAddressWebData(userData));
        }
        return builder.build();
    }

    private boolean isResetToDefault(ClientBehavior clientBehavior) {
        return clientBehavior == ClientBehavior.UNKNOWN_BEHAVIOR
                || clientBehavior == ClientBehavior.NEEDS_TO_RESET
                || clientBehavior == ClientBehavior.NEEDS_TO_RESET_EXCEPT_VERS
                || clientBehavior == ClientBehavior.NEEDS_TO_RESET_EXCEPT_VERS_UNTIL_SETTING_ON;
    }

}

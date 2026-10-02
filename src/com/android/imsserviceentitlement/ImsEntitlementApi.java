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
        BooleanSupplier current = () -> !Thread.currentThread().isInterrupted()
                && ownerCurrent.getAsBoolean() && !Thread.currentThread().isInterrupted();
        mAcceptedResult = null;
        return checkEntitlementStatus(current, mLastEntitlementConfiguration.generation(),
                AUTHENTICATION_RETRIES);
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
            XmlDoc entitlementXmlDoc = new XmlDoc(rawXml);
            if (!entitlementXmlDoc.isValid()) return null;
            ClientBehavior behavior = EntitlementConfiguration.entitlementValidation(entitlementXmlDoc);
            if (mNeedsImsProvisioning && behavior == ClientBehavior.UNKNOWN_BEHAVIOR) return null;
            EntitlementResult result = toEntitlementResult(entitlementXmlDoc, behavior);
            return mLastEntitlementConfiguration.commitIfCurrent(generation, current, () -> {
                mLastEntitlementConfiguration.update(entitlementVersion, rawXml);
                if (mNeedsImsProvisioning && isResetToDefault(behavior)) {
                    mLastEntitlementConfiguration.reset(behavior);
                }
                mAcceptedResult = new AcceptedResult(mLastEntitlementConfiguration.generation(), result);
                return result;
            });
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
                    long retryAfter = parseDelaySecondsByRetryAfter(e.getRetryAfter());
                    if (retryAfter < 0 || retryAfter > Long.MAX_VALUE / 1000) return null;
                    boolean isDefaultActive = TelephonyUtils.getDefaultStatus(mContext, mSubId);
                    return mLastEntitlementConfiguration.commitIfCurrent(generation, current,
                            () -> {
                                EntitlementResult result = EntitlementResult.builder(isDefaultActive)
                                        .setRetryAfterSeconds(retryAfter).build();
                                mAcceptedResult = new AcceptedResult(generation, result);
                                return result;
                            });
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

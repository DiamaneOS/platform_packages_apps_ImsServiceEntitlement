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

import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;
import static com.android.imsserviceentitlement.ts43.Ts43Constants.EntitlementVersion.ENTITLEMENT_VERSION_EIGHT;
import static com.android.imsserviceentitlement.ts43.Ts43Constants.EntitlementVersion.ENTITLEMENT_VERSION_TWO;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.SparseArray;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.imsserviceentitlement.entitlement.EntitlementConfiguration;
import com.android.imsserviceentitlement.entitlement.EntitlementResult;
import com.android.imsserviceentitlement.job.JobManager;
import com.android.imsserviceentitlement.ts43.Ts43Constants.EntitlementStatus;
import com.android.imsserviceentitlement.ts43.Ts43SmsOverIpStatus;
import com.android.imsserviceentitlement.ts43.Ts43VolteStatus;
import com.android.imsserviceentitlement.ts43.Ts43VonrStatus;
import com.android.imsserviceentitlement.ts43.Ts43VowifiStatus;
import com.android.imsserviceentitlement.ts43.Ts43VowifiStatus.AddrStatus;
import com.android.imsserviceentitlement.ts43.Ts43VowifiStatus.ProvStatus;
import com.android.imsserviceentitlement.ts43.Ts43VowifiStatus.TcStatus;
import com.android.imsserviceentitlement.utils.ImsUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.lang.reflect.Field;

@RunWith(AndroidJUnit4.class)
public class ImsEntitlementPollingServiceTest {
    @Rule public final MockitoRule rule = MockitoJUnit.rule();

    @Spy private Context mContext = ApplicationProvider.getApplicationContext();

    @Mock private ImsUtils mImsUtils;
    @Mock private JobParameters mJobParameters;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private SubscriptionInfo mSubscriptionInfo;
    @Mock private ImsEntitlementApi mImsEntitlementApi;
    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private android.telephony.TelephonyManager mTelephonyManager;
    @Mock private android.net.ConnectivityManager mConnectivityManager;

    private static final class TestService extends ImsEntitlementPollingService {
        volatile Boolean retry;
        @Override void finishJob(JobParameters params, boolean needsRetry) { retry = needsRetry; }
    }
    private TestService mService;
    private JobScheduler mScheduler;
    private PersistableBundle mCarrierConfig;

    private static final int SUB_ID = 1;
    private static final int SLOT_ID = 0;
    private static final String KEY_ENTITLEMENT_VERSION_INT =
            "imsserviceentitlement.entitlement_version_int";

    @Before
    public void setUp() throws Exception {
        mService = new TestService();
        mService.attachBaseContext(mContext);
        mService.onCreate();
        mService.onBind(null);
        mService.injectImsEntitlementApi(mImsEntitlementApi);
        when(mImsEntitlementApi.isResultCurrent(any())).thenReturn(true);
        Field jobs = JobManager.class.getDeclaredField("sInstances");
        jobs.setAccessible(true);
        ((android.util.ArrayMap<?, ?>) jobs.get(null)).clear();
        mScheduler = mock(JobScheduler.class);
        when(mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE)).thenReturn(mScheduler);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mConnectivityManager);
        when(mTelephonyManager.createForSubscriptionId(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(mTelephonyManager);
        when(mScheduler.schedule(any())).thenAnswer(invocation -> {
            android.app.job.JobInfo job = invocation.getArgument(0);
            when(mScheduler.getPendingJob(job.getId())).thenReturn(job);
            return JobScheduler.RESULT_SUCCESS;
        });
        when(mImsUtils.setVowifiProvisioned(anyBoolean())).thenReturn(true);
        when(mImsUtils.setVolteProvisioned(anyBoolean())).thenReturn(true);
        when(mImsUtils.setVonrProvisioned(anyBoolean())).thenReturn(true);
        when(mImsUtils.setSmsoipProvisioned(anyBoolean())).thenReturn(true);
        when(mImsUtils.disableWfc()).thenReturn(true);
        setActivedSubscription();
        setupImsUtils();
        setJobParameters();
        setWfcEnabledByUser(true);
        setImsProvisioningBool(false);
        setEntitlementVersion(ENTITLEMENT_VERSION_TWO);
    }

    @After
    public void tearDown() {
        mCarrierConfig = null;
    }

    @Test
    public void doEntitlementCheck_isWfcEnabledByUserFalse_doNothing() throws Exception {
        setWfcEnabledByUser(false);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsEntitlementApi, never()).checkPollingEntitlementStatus(any());
    }


    @Test
    public void doEntitlementCheck_shouldTurnOffWfc_disableWfc() throws Exception {
        EntitlementResult entitlementResult = getEntitlementResult(sDisableVoWiFi);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils).disableWfc();
    }

    @Test
    public void doEntitlementCheck_shouldNotTurnOffWfc_enableWfc() throws Exception {
        EntitlementResult entitlementResult = getEntitlementResult(sEnableVoWiFi);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils, never()).disableWfc();
    }

    @Test
    public void doEntitlementCheck_shouldTurnOffImsApps_setAllProvisionedFalse() throws Exception {
        setImsProvisioningBool(true);
        EntitlementResult entitlementResult = getImsEntitlementResult(
                sDisableVoWiFi,
                sDisableVoLte,
                sDisableSmsoverip
        );
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils).setVolteProvisioned(false);
        verify(mImsUtils).setVowifiProvisioned(false);
        verify(mImsUtils).setSmsoipProvisioned(false);
        verify(mImsUtils, never()).setVonrProvisioned(anyBoolean());
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT);
    }

    @Test
    public void doV8EntitlementCheck_shouldTurnOffImsApps_setAllProvisionedFalse()
            throws Exception {
        setImsProvisioningBool(true);
        setEntitlementVersion(ENTITLEMENT_VERSION_EIGHT);
        EntitlementResult entitlementResult =
                getImsEntitlementResult(
                        sDisableVoWiFi, sDisableVoLte, sDisableVonr, sDisableSmsoverip);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils).setVolteProvisioned(false);
        verify(mImsUtils).setVowifiProvisioned(false);
        verify(mImsUtils).setSmsoipProvisioned(false);
        verify(mImsUtils).setVonrProvisioned(false);
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
    }

    @Test
    public void doEntitlementCheck_shouldTurnOnImsApps_setAllProvisionedTrue() throws Exception {
        setImsProvisioningBool(true);
        EntitlementResult entitlementResult = getImsEntitlementResult(
                sEnableVoWiFi,
                sEnableVoLte,
                sEnableSmsoverip
        );
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils).setVolteProvisioned(true);
        verify(mImsUtils).setVowifiProvisioned(true);
        verify(mImsUtils).setSmsoipProvisioned(true);
        verify(mImsUtils, never()).setVonrProvisioned(anyBoolean());
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT);
    }

    @Test
    public void doV8EntitlementCheck_shouldTurnOnImsApps_setAllProvisionedTrue() throws Exception {
        setImsProvisioningBool(true);
        setEntitlementVersion(ENTITLEMENT_VERSION_EIGHT);
        EntitlementResult entitlementResult =
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableVonr, sEnableSmsoverip);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils).setVolteProvisioned(true);
        verify(mImsUtils).setVowifiProvisioned(true);
        verify(mImsUtils).setSmsoipProvisioned(true);
        verify(mImsUtils).setVonrProvisioned(true);
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
    }

    @Test
    public void onStartJob_noCarrierEndpoint_noQuery() throws Exception {
        setImsProvisioningBool(true);
        mCarrierConfig.putString(
                CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING, "");
        assertThat(mService.onStartJob(mJobParameters)).isTrue();
        awaitTask();
        verify(mImsEntitlementApi, never()).checkPollingEntitlementStatus(any());
    }

    @Test
    public void doV8EntitlementCheck_entitlementResultNull_preservesProvisioning()
            throws Exception {
        setImsProvisioningBool(true);
        setEntitlementVersion(ENTITLEMENT_VERSION_EIGHT);
        EntitlementResult entitlementResult = null;
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils, never()).setVolteProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVowifiProvisioned(anyBoolean());
        verify(mImsUtils, never()).setSmsoipProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVonrProvisioned(anyBoolean());
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED);
    }

    @Test
    public void doEntitlementCheck_ImsEntitlementShouldRetry_rescheduleJob() throws Exception {
        setImsProvisioningBool(true);
        EntitlementResult entitlementResult =
                EntitlementResult.builder(false).setRetryAfterSeconds(120).build();
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils, never()).setVolteProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVowifiProvisioned(anyBoolean());
        verify(mImsUtils, never()).setSmsoipProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVonrProvisioned(anyBoolean());
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT);
        assertThat(mService.retry).isTrue();
    }

    @Test
    public void doEntitlementCheck_WfcEntitlementShouldRetry_rescheduleJob() throws Exception {
        EntitlementResult entitlementResult =
                EntitlementResult.builder(false).setRetryAfterSeconds(120).build();
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(entitlementResult);

        mService.onStartJob(mJobParameters);
        awaitTask();

        verify(mImsUtils, never()).setVolteProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVowifiProvisioned(anyBoolean());
        verify(mImsUtils, never()).setSmsoipProvisioned(anyBoolean());
        verify(mImsUtils, never()).setVonrProvisioned(anyBoolean());
        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT);
        assertThat(mService.retry).isTrue();
    }

    @Test
    public void doEntitlementCheck_runtimeException_entitlementUpdateFail() throws Exception {
        setImsProvisioningBool(true);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenThrow(new RuntimeException());

        mService.onStartJob(mJobParameters);
        awaitTask();

        assertThat(mService.mOngoingTask.getVonrResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED);
    }

    @Test
    public void doWfcEntitlementCheck_runtimeException_entitlementUpdateFail() throws Exception {
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenThrow(new RuntimeException());

        mService.onStartJob(mJobParameters);
        awaitTask();

        assertThat(mService.mOngoingTask.getVowifiResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED);
    }

    @Test
    public void enqueueJob_hasJob() throws Exception {
        Field direct = com.android.imsserviceentitlement.utils.Executors.class
                .getDeclaredField("sUseDirectExecutorForTest");
        try {
            direct.setAccessible(true);
            direct.set(null, true);
            ImsEntitlementPollingService.enqueueJob(mContext, SUB_ID, 0);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally { direct.set(null, false); }

        assertThat(
                mScheduler.getPendingJob(
                        jobIdWithSubId(JobManager.QUERY_ENTITLEMENT_STATUS_JOB_ID, SUB_ID)))
                .isNotNull();
    }

    private void awaitTask() throws Exception {
        mService.mOngoingTask.get();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void failedProvisioningAppliesRemainingSettersAndSchedulesRepair() throws Exception {
        setImsProvisioningBool(true);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableSmsoverip));
        when(mImsUtils.setVowifiProvisioned(true)).thenReturn(false);
        mService.onStartJob(mJobParameters);
        awaitTask();
        verify(mImsUtils).setVolteProvisioned(true);
        verify(mImsUtils).setSmsoipProvisioned(true);
        verify(mImsEntitlementApi, never()).storedPollingEntitlementStatus(any(), any());
        assertThat(mService.mOngoingTask.getVowifiResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED);
        assertThat(mService.retry).isTrue();
    }

    @Test
    public void throwingSetterDoesNotStopRemainingSetters() throws Exception {
        setImsProvisioningBool(true);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableSmsoverip));
        when(mImsUtils.setVolteProvisioned(true)).thenThrow(new RuntimeException());
        mService.onStartJob(mJobParameters);
        awaitTask();
        verify(mImsUtils).setVowifiProvisioned(true);
        verify(mImsUtils).setSmsoipProvisioned(true);
        assertThat(mService.mOngoingTask.getVowifiResult())
                .isEqualTo(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
        assertThat(mService.retry).isTrue();
    }

    @Test
    public void repairRetryReappliesStoredResultWithoutCarrierCheck() throws Exception {
        setImsProvisioningBool(true);
        // No stored refresh period: a completed repair finishes the job without a retry.
        new EntitlementConfiguration(mContext, SUB_ID).reset();
        EntitlementResult result =
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableSmsoverip);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(result);
        when(mImsEntitlementApi.storedPollingEntitlementStatus(any(), any())).thenReturn(result);
        when(mImsUtils.setVolteProvisioned(true)).thenReturn(false, true);
        mService.onStartJob(mJobParameters);
        awaitTask();
        assertThat(mService.retry).isTrue();

        // The scheduler's retry of the same job.
        mService.retry = null;
        mService.onStartJob(mJobParameters);
        awaitTask();
        verify(mImsEntitlementApi).checkPollingEntitlementStatus(any());
        verify(mImsEntitlementApi).storedPollingEntitlementStatus(any(), any());
        verify(mImsUtils, times(2)).setVolteProvisioned(true);
        verify(mImsUtils, times(2)).setSmsoipProvisioned(true);
        assertThat(mService.retry).isFalse();
    }

    @Test
    public void repairRetryWithoutStoredResultQueriesCarrier() throws Exception {
        setImsProvisioningBool(true);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableSmsoverip));
        when(mImsUtils.setSmsoipProvisioned(true)).thenReturn(false, true);
        mService.onStartJob(mJobParameters);
        awaitTask();

        mService.onStartJob(mJobParameters);
        awaitTask();
        verify(mImsEntitlementApi).storedPollingEntitlementStatus(any(), any());
        verify(mImsEntitlementApi, times(2)).checkPollingEntitlementStatus(any());
        verify(mImsUtils, times(2)).setSmsoipProvisioned(true);
    }

    @Test
    public void expiredOwnershipBetweenSettersSchedulesReconciliation() throws Exception {
        setImsProvisioningBool(true);
        when(mImsEntitlementApi.checkPollingEntitlementStatus(any())).thenReturn(
                getImsEntitlementResult(sEnableVoWiFi, sEnableVoLte, sEnableSmsoverip));
        when(mImsUtils.setVowifiProvisioned(true)).thenAnswer(invocation -> {
            mService.mOngoingTask.scope.abort();
            return true;
        });
        mService.onStartJob(mJobParameters);
        awaitTask();
        verify(mImsUtils, never()).setVolteProvisioned(anyBoolean());
        verify(mImsUtils, never()).setSmsoipProvisioned(anyBoolean());
        assertThat(mService.retry).isTrue();
    }

    private void setActivedSubscription() {
        when(mSubscriptionInfo.getSimSlotIndex()).thenReturn(SLOT_ID);
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(mSubscriptionInfo);
        when(mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE))
                .thenReturn(mSubscriptionManager);
    }

    private void setupImsUtils() throws Exception {
        SparseArray<ImsUtils> imsUtilsInstances = new SparseArray<>();
        imsUtilsInstances.put(SUB_ID, mImsUtils);
        Field field = ImsUtils.class.getDeclaredField("sInstances");
        field.setAccessible(true);
        field.set(null, imsUtilsInstances);
    }

    private void setWfcEnabledByUser(boolean isEnabled) {
        when(mImsUtils.isWfcEnabledByUser()).thenReturn(isEnabled);
    }

    private void setJobParameters() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, SUB_ID);
        bundle.putInt(JobManager.EXTRA_SLOT_ID, SLOT_ID);
        when(mJobParameters.getExtras()).thenReturn(bundle);
        when(mJobParameters.getJobId()).thenReturn(jobIdWithSubId(JobManager.QUERY_ENTITLEMENT_STATUS_JOB_ID, SUB_ID));
    }

    private void setImsProvisioningBool(boolean provisioning) {
        initializeCarrierConfig();
        mCarrierConfig.putBoolean(
                CarrierConfigManager.ImsServiceEntitlement.KEY_IMS_PROVISIONING_BOOL,
                provisioning
        );
    }

    private void setEntitlementVersion(int entitlementVersion) {
        initializeCarrierConfig();
        mCarrierConfig.putInt(KEY_ENTITLEMENT_VERSION_INT, entitlementVersion);
    }

    private void initializeCarrierConfig() {
        if (mCarrierConfig == null) {
            mCarrierConfig = new PersistableBundle();
            mCarrierConfig.putString(
                    CarrierConfigManager.ImsServiceEntitlement.KEY_ENTITLEMENT_SERVER_URL_STRING,
                    "https://carrier.example/entitlement");
            when(mCarrierConfigManager.getConfigForSubId(SUB_ID)).thenReturn(mCarrierConfig);
            when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                    .thenReturn(mCarrierConfigManager);
        }
    }

    private static EntitlementResult getEntitlementResult(Ts43VowifiStatus vowifiStatus) {
        return EntitlementResult.builder(false).setVowifiStatus(vowifiStatus).build();
    }

    private static EntitlementResult getImsEntitlementResult(
            Ts43VowifiStatus vowifiStatus,
            Ts43VolteStatus volteStatus,
            Ts43SmsOverIpStatus smsOverIpStatus) {
        return EntitlementResult.builder(false)
                .setVowifiStatus(vowifiStatus)
                .setVolteStatus(volteStatus)
                .setSmsoveripStatus(smsOverIpStatus)
                .build();
    }

    private static EntitlementResult getImsEntitlementResult(
            Ts43VowifiStatus vowifiStatus,
            Ts43VolteStatus volteStatus,
            Ts43VonrStatus vonrStatus,
            Ts43SmsOverIpStatus smsOverIpStatus) {
        return EntitlementResult.builder(false)
                .setVowifiStatus(vowifiStatus)
                .setVolteStatus(volteStatus)
                .setVonrStatus(vonrStatus)
                .setSmsoveripStatus(smsOverIpStatus)
                .build();
    }

    private int jobIdWithSubId(int jobId, int subId) {
        return 1000 * subId + jobId;
    }

    private static final Ts43VowifiStatus sDisableVoWiFi =
            Ts43VowifiStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.DISABLED)
                    .setTcStatus(TcStatus.NOT_AVAILABLE)
                    .setAddrStatus(AddrStatus.NOT_AVAILABLE)
                    .setProvStatus(ProvStatus.NOT_PROVISIONED)
                    .build();

    private static final Ts43VowifiStatus sEnableVoWiFi =
            Ts43VowifiStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.ENABLED)
                    .setTcStatus(TcStatus.AVAILABLE)
                    .setAddrStatus(AddrStatus.AVAILABLE)
                    .setProvStatus(ProvStatus.PROVISIONED)
                    .build();

    private static final Ts43VolteStatus sDisableVoLte =
            Ts43VolteStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.DISABLED)
                    .build();

    private static final Ts43VolteStatus sEnableVoLte =
            Ts43VolteStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.ENABLED)
                    .build();

    private static final Ts43VonrStatus sDisableVonr =
            Ts43VonrStatus.builder()
                    .setHomeEntitlementStatus(EntitlementStatus.DISABLED)
                    .setRoamingEntitlementStatus(EntitlementStatus.DISABLED)
                    .build();

    private static final Ts43VonrStatus sEnableVonr =
            Ts43VonrStatus.builder()
                    .setHomeEntitlementStatus(EntitlementStatus.ENABLED)
                    .setRoamingEntitlementStatus(EntitlementStatus.ENABLED)
                    .build();

    private static final Ts43SmsOverIpStatus sDisableSmsoverip =
            Ts43SmsOverIpStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.DISABLED)
                    .build();

    private static final Ts43SmsOverIpStatus sEnableSmsoverip =
            Ts43SmsOverIpStatus.builder()
                    .setEntitlementStatus(EntitlementStatus.ENABLED)
                    .build();
}

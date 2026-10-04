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

import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__CANCELED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__PURPOSE__POLLING;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__PURPOSE__UNKNOWN_PURPOSE;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__SMSOIP;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__VOLTE;
import static com.android.imsserviceentitlement.ImsServiceEntitlementStatsLog.IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__VOWIFI;
import static com.android.imsserviceentitlement.ts43.Ts43Constants.EntitlementVersion.ENTITLEMENT_VERSION_EIGHT;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import com.android.imsserviceentitlement.utils.RequestScope;
import com.android.imsserviceentitlement.utils.Executors;
import android.os.PersistableBundle;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.android.imsserviceentitlement.entitlement.EntitlementConfiguration;
import com.android.imsserviceentitlement.entitlement.EntitlementConfiguration.ClientBehavior;
import com.android.imsserviceentitlement.entitlement.EntitlementResult;
import com.android.imsserviceentitlement.job.JobManager;
import com.android.imsserviceentitlement.utils.ImsUtils;
import com.android.imsserviceentitlement.utils.MetricsLogger;
import com.android.imsserviceentitlement.utils.TelephonyUtils;
import com.android.imsserviceentitlement.utils.HttpsUrl;

import java.time.Duration;

/**
 * The {@link JobService} for querying entitlement status in the background. The jobId is unique for
 * different subId + job combination, so can run the same job for different subIds w/o cancelling
 * each others. See {@link JobManager}.
 */
public class ImsEntitlementPollingService extends JobService {
    private static final String TAG = "IMSSE-ImsEntitlementPollingService";
    private static final long MAX_REFRESH_SECONDS = 24 * 60 * 60;
    // Downstream retry throttle: an untrusted Retry-After of zero must not create
    // a tight request loop. Longer carrier-requested delays remain unchanged.
    private static final long MIN_RETRY_AFTER_SECONDS = 30;

    public static final ComponentName COMPONENT_NAME =
            ComponentName.unflattenFromString(
                    "com.android.imsserviceentitlement/.ImsEntitlementPollingService");

    private ImsEntitlementApi mImsEntitlementApi;

    /**
     * Cache job id associated {@link EntitlementPollingTask} objects for canceling once job be
     * canceled.
     */
    private final SparseArray<EntitlementPollingTask> mTasks = new SparseArray<>();
    private final Handler mCompletionHandler = new Handler(Looper.getMainLooper());

    @VisibleForTesting
    EntitlementPollingTask mOngoingTask;

    @Override
    @VisibleForTesting
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @VisibleForTesting
    void injectImsEntitlementApi(ImsEntitlementApi imsEntitlementApi) {
        this.mImsEntitlementApi = imsEntitlementApi;
    }

    /** Enqueues a job to query entitlement status. */
    public static void enqueueJob(Context context, int subId, int retryCount) {
        JobManager.getInstance(
                context,
                COMPONENT_NAME,
                subId)
                .queryEntitlementStatusOnceNetworkReady(retryCount);
    }

    /** Enqueues a job to query entitlement status with delay. */
    private static void enqueueJobWithDelay(Context context, int subId, long delayInSeconds) {
        JobManager.getInstance(
                context,
                COMPONENT_NAME,
                subId)
                .queryEntitlementStatusOnceNetworkReady(0, Duration.ofSeconds(delayInSeconds));
    }

    /** Refresh only an active, valid carrier configuration; preserve server stop states. */
    public static void scheduleRefresh(Context context, int subId) {
        if (!TelephonyUtils.isActivedSubId(context, subId)
                || !HttpsUrl.isAllowed(TelephonyUtils.getEntitlementServerUrl(context, subId))) return;
        // A pending provisioning repair schedules the refresh itself once its setters apply.
        // A delayed refresh with the same job ID must not replace it.
        if (JobManager.getInstance(context, COMPONENT_NAME, subId).isRepairPending()) return;
        EntitlementConfiguration configuration = new EntitlementConfiguration(context, subId);
        ClientBehavior behavior = configuration.entitlementValidation();
        if (behavior == ClientBehavior.VALID_DURING_VALIDITY) {
            enqueueJobWithDelay(context, subId,
                    Math.max(MIN_RETRY_AFTER_SECONDS,
                            Math.min(MAX_REFRESH_SECONDS, configuration.getVersValidity())));
        } else if (behavior == ClientBehavior.VALID_WITHOUT_DURATION) {
            enqueueJobWithDelay(context, subId, MAX_REFRESH_SECONDS);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        PersistableBundle bundle = params.getExtras();
        int subId =
                bundle.getInt(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);

        int jobId = params.getJobId();
        Log.d(TAG, "onStartJob: " + jobId);

        if (!SubscriptionManager.isValidSubscriptionId(subId)) return false;

        // if the same job ID is scheduled again, the current one will be cancelled by platform and
        // #onStopJob will be called to removed the job.
        mOngoingTask = new EntitlementPollingTask(params, subId);
        if (mOngoingTask.scheduleTicket < 0) {
            mOngoingTask.scope.close();
            // Keep the scheduler-owned recovery job until its successor is accepted.
            finishJob(params, true);
            return true;
        }
        mTasks.put(jobId, mOngoingTask);
        // One slow carrier must not serialize the other subscription's job.
        EntitlementPollingTask task = mOngoingTask;
        task.scope.arm(() -> mCompletionHandler.post(() -> finishTask(task, true)));
        try { task.executeOnExecutor(Executors.getCarrierExecutor()); }
        catch (java.util.concurrent.RejectedExecutionException saturated) { finishTask(task, true); }
        return true;
    }

    private void finishTask(EntitlementPollingTask task, boolean timedOut) {
        if (mTasks.get(task.mParams.getJobId()) != task || task.finished) return;
        timedOut = !task.scope.complete() || timedOut;
        if (!task.jobManager.isCurrentRun(task.scheduleTicket)) task.mReschedule = true;
        task.finished = true;
        if (timedOut) { task.scope.abort(); task.cancel(true); task.mReschedule = true; }
        task.scope.close();
        mTasks.remove(task.mParams.getJobId());
        // Finish before scheduling a replacement with the same ID. Success,
        // deadline and admission rejection share this one main-thread path.
        // Retain scheduler-owned fallback until a successful refresh replacement
        // is accepted too. Carrier stop states have no refresh and remain terminal.
        boolean retry = task.mReschedule || task.refreshDelay != null;
        if (!retry) task.jobManager.endRepair(task.scheduleTicket);
        finishJob(task.mParams, retry);
        Executors.removeCancelledCarrierWork();
        // JobService queues its finish message on the main looper. Post after
        // that message before dispatching Binder-dependent scheduling/metrics.
        final JobManager manager = task.jobManager;
        final long ticket = task.scheduleTicket;
        mCompletionHandler.post(() -> {
            if (!task.mReschedule && task.refreshDelay != null)
                manager.queryFromCompletedRun(ticket, 0, task.refreshDelay);
        });

    }

    @VisibleForTesting
    void finishJob(JobParameters params, boolean retry) { jobFinished(params, retry); }

    @Override
    public boolean onStopJob(final JobParameters params) {
        int jobId = params.getJobId();
        Log.d(TAG, "onStopJob: " + jobId);
        EntitlementPollingTask task = mTasks.get(jobId);
        if (task != null) {
            task.finished = true;
            task.scope.abort();
            task.cancel(true);
            task.jobManager.endRun(task.scheduleTicket);
            mTasks.remove(jobId);
            Executors.removeCancelledCarrierWork();
        }

        return true;
    }

    @VisibleForTesting
    class EntitlementPollingTask extends AsyncTask<Void, Void, Void> {
        final RequestScope scope = new RequestScope();
        volatile boolean finished;
        final JobManager jobManager;
        final long scheduleTicket;
        private final JobParameters mParams;
        private ImsEntitlementApi mImsEntitlementApi;
        private ImsUtils mImsUtils;
        private TelephonyUtils mTelephonyUtils;
        private MetricsLogger mMetricsLogger;
        private final int mSubid;
        private int mEntitlementVersion;
        private boolean mNeedsImsProvisioning;
        private boolean mReschedule;
        private Duration refreshDelay;
        // A failed setter does not stop the others; the run is retried as a repair.
        private boolean mProvisioningFailed;
        private boolean mLastSetterApplied;

        // States for metrics
        private int mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;
        private int mVolteResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;
        private int mVonrResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;
        private int mSmsoipResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT;

        EntitlementPollingTask(final JobParameters params, int subId) {
            this.mParams = params;
            this.mSubid = subId;
            this.jobManager = JobManager.getInstance(ImsEntitlementPollingService.this, COMPONENT_NAME, subId);
            this.scheduleTicket = jobManager.beginRun(params.getExtras().getLong(
                    JobManager.EXTRA_SCHEDULE_GENERATION, 0));
        }

        private void initializeWorker() {
            int subId = mSubid;
            this.mImsUtils = ImsUtils.getInstance(ImsEntitlementPollingService.this, subId);
            this.mTelephonyUtils = new TelephonyUtils(ImsEntitlementPollingService.this, subId);
            this.mEntitlementVersion =
                    TelephonyUtils.getEntitlementVersion(ImsEntitlementPollingService.this, mSubid);
            this.mNeedsImsProvisioning = TelephonyUtils.isImsProvisioningRequired(
                    ImsEntitlementPollingService.this, mSubid);
            this.mImsEntitlementApi = ImsEntitlementPollingService.this.mImsEntitlementApi != null
                    ? ImsEntitlementPollingService.this.mImsEntitlementApi
                    : new ImsEntitlementApi(ImsEntitlementPollingService.this, subId);
            this.mMetricsLogger = new MetricsLogger(mTelephonyUtils);
        }

        @Override
        protected Void doInBackground(Void... unused) {
            return scope.run(() -> {
                try { return runOwnedCheck(); }
                catch (RuntimeException unavailable) {
                    mReschedule = true;
                    Log.w(TAG, "Carrier check setup unavailable");
                    return null;
                }
                finally {
                    if (mMetricsLogger != null && ownsRun()) {
                        try { sendStatsLogToMetrics(); }
                        catch (RuntimeException unavailable) { Log.w(TAG, "Carrier metrics unavailable"); }
                    }
                }
            });
        }

        private Void runOwnedCheck() {
            if (!ownsRun()) { mReschedule = true; return null; }
            if (!JobManager.isValidJob(ImsEntitlementPollingService.this, mParams)
                    || !HttpsUrl.isAllowed(TelephonyUtils.getEntitlementServerUrl(
                            ImsEntitlementPollingService.this, mSubid))) return null;
            // Binder-dependent setup also occupies a bounded physical worker;
            // it must not prevent the main-thread deadline from finishing a job.
            initializeWorker();
            if (!ownsRun()) { mReschedule = true; return null; }
            if (!JobManager.isValidJob(ImsEntitlementPollingService.this, mParams)) return null;
            int jobId = JobManager.getPureJobId(mParams.getJobId());
            switch (jobId) {
                case JobManager.QUERY_ENTITLEMENT_STATUS_JOB_ID:
                    mMetricsLogger.start(IMS_SERVICE_ENTITLEMENT_UPDATED__PURPOSE__POLLING);
                    doEntitlementCheck();
                    break;
                default:
                    break;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void unused) {
            Log.d(TAG, "JobId:" + mParams.getJobId() + "- Task done.");
            finishTask(this, false);
        }

        @Override
        protected void onCancelled(Void unused) {
            scope.close();
        }

        private void doEntitlementCheck() {
            if (!jobManager.enterProvisioning(scheduleTicket)) { mReschedule = true; return; }
            try {
            if (mNeedsImsProvisioning) {
                // TODO(b/190476343): Unify EntitlementResult and EntitlementConfiguration.
                doImsEntitlementCheck();
            } else {
                doWfcEntitlementCheck();
            }
            } finally { jobManager.leaveProvisioning(scheduleTicket); }
        }

        private boolean ownsRun() {
            return !isCancelled() && !finished && scope.isCurrent()
                    && jobManager.isCurrentRun(scheduleTicket);
        }

        @WorkerThread
        private void doImsEntitlementCheck() {
            try {
                EntitlementResult result = pollingEntitlementStatus();
                if (isCancelled() || !JobManager.isValidJob(
                        ImsEntitlementPollingService.this, mParams) || isCancelled()) return;
                if (result == null) {
                    // A network/parser failure is neither approval nor revocation.
                    mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                    mVolteResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                    mVonrResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                    mSmsoipResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                    mReschedule = true;
                    return;
                }

                if (!isCurrentResult(result)) return;
                if (performRetryIfNeeded(result)) {
                    return;
                }

                if (shouldTurnOffWfc(result)) {
                    if (!applyProvisioning(result, () -> mImsUtils.setVowifiProvisioned(false))) return;
                    mVowifiResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
                } else if (result.getVowifiStatus().vowifiEntitled()) {
                    if (!applyProvisioning(result, () -> mImsUtils.setVowifiProvisioned(true))) return;
                    mVowifiResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
                }

                if (!isCurrentResult(result)) return;
                if (shouldTurnOffVolte(result)) {
                    if (!applyProvisioning(result, () -> mImsUtils.setVolteProvisioned(false))) return;
                    mVolteResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
                } else {
                    if (!applyProvisioning(result, () -> mImsUtils.setVolteProvisioned(true))) return;
                    mVolteResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
                }

                if (!isCurrentResult(result)) return;
                if (mEntitlementVersion >= ENTITLEMENT_VERSION_EIGHT) {
                    if (shouldTurnOffVonrHome(result)) {
                        if (!applyProvisioning(result, () -> mImsUtils.setVonrProvisioned(false))) return;
                        mVonrResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
                    } else {
                        if (!applyProvisioning(result, () -> mImsUtils.setVonrProvisioned(true))) return;
                        mVonrResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
                    }
                }

                if (!isCurrentResult(result)) return;
                if (shouldTurnOffSMSoIP(result)) {
                    if (!applyProvisioning(result, () -> mImsUtils.setSmsoipProvisioned(false))) return;
                    mSmsoipResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
                } else {
                    if (!applyProvisioning(result, () -> mImsUtils.setSmsoipProvisioned(true))) return;
                    mSmsoipResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED);
                }
            } catch (RuntimeException e) {
                mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                mVolteResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                mVonrResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                mSmsoipResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                mReschedule = true;
                Log.d(TAG, "Entitlement operation unavailable");
                return;
            }
            if (mProvisioningFailed) requestRepair();
            else if (ownsRun()) checkVersValidity();
            else mReschedule = true;
        }

        @WorkerThread
        private void doWfcEntitlementCheck() {
            try {
                if (!mImsUtils.isWfcEnabledByUser()) return;
                EntitlementResult result = pollingEntitlementStatus();
                if (isCancelled() || !JobManager.isValidJob(
                        ImsEntitlementPollingService.this, mParams) || isCancelled()) return;
                if (result == null) {
                    mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                    mReschedule = true;
                    return;
                }

                if (!isCurrentResult(result)) return;
                if (performRetryIfNeeded(result)) {
                    return;
                }

                if (shouldTurnOffWfc(result)) {
                    if (!applyProvisioning(result, mImsUtils::disableWfc)) return;
                    mVowifiResult = setterResult(IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__DISABLED);
                } else {
                    mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__ENABLED;
                }
            } catch (RuntimeException e) {
                mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
                mReschedule = true;
                Log.d(TAG, "Entitlement operation unavailable");
                return;
            }
            if (mProvisioningFailed) requestRepair();
            else if (ownsRun()) checkVersValidity();
            else mReschedule = true;
        }

        /**
         * Applies one provisioning setter. Returns {@code false} only when the result is no longer
         * current; a failed setter is remembered and the remaining setters still run.
         */
        private boolean applyProvisioning(EntitlementResult result, java.util.function.Supplier<Boolean> setter) {
            if (!isCurrentResult(result)) return false;
            try { mLastSetterApplied = Boolean.TRUE.equals(setter.get()); }
            catch (RuntimeException unavailable) { mLastSetterApplied = false; }
            if (!mLastSetterApplied) mProvisioningFailed = true;
            return isCurrentResult(result);
        }

        private int setterResult(int appliedResult) {
            return mLastSetterApplied ? appliedResult : IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
        }

        /**
         * The scheduler's retry of a run with a failed setter re-applies the stored carrier result
         * while it is still valid, without authenticating with the carrier again. Without such a
         * result (expired, reset, or the process restarted) the retry queries the carrier.
         */
        private EntitlementResult pollingEntitlementStatus() {
            if (jobManager.isRepairRun(scheduleTicket)) {
                // No older than a healthy client's refresh period.
                EntitlementResult stored = mImsEntitlementApi.storedPollingEntitlementStatus(
                        this::ownsRun, Duration.ofSeconds(MAX_REFRESH_SECONDS));
                if (stored != null) return stored;
            }
            return mImsEntitlementApi.checkPollingEntitlementStatus(this::ownsRun);
        }

        /** Retry with the scheduler's backoff; the refresh is scheduled once all setters apply. */
        private void requestRepair() {
            if (ownsRun()) jobManager.requestRepair(scheduleTicket);
            mReschedule = true;
        }

        private boolean isCurrentResult(EntitlementResult result) {
            // No cache monitor is held across framework Binder setters. Check again
            // after subscription lookups and before each provisioning operation.
            if (!ownsRun()) {
                mReschedule = true;
                return false;
            }
            if (!JobManager.isValidJob(ImsEntitlementPollingService.this, mParams)) return false;
            if (!ownsRun()) {
                mReschedule = true;
                return false;
            }
            if (mImsEntitlementApi.isResultCurrent(result) && ownsRun()) return true;
            // Setters are separate Binder operations. Reconcile if a generation
            // change interrupts provisioning, instead of leaving partial state.
            mReschedule = true;
            return false;
        }

        /**
         * Performs retry if needed. Returns true if {@link ImsEntitlementPollingService} has
         * scheduled.
         */
        private boolean performRetryIfNeeded(@Nullable EntitlementResult result) {
            if (result == null || result.getRetryAfterSeconds() < 0) {
                return false;
            }
            mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__FAILED;
            // Android owns exponential failure backoff. The API persists a carrier
            // not-before deadline, so an earlier framework retry makes no HTTP/AKA request.
            mReschedule = true;
            return true;
        }

        /**
         * Schedules entitlement status check after a VERS.validity time, if the last valid is
         * during validity.
         */
        private void checkVersValidity() {
            EntitlementConfiguration configuration = new EntitlementConfiguration(
                    ImsEntitlementPollingService.this, mSubid);
            ClientBehavior behavior = configuration.entitlementValidation();
            if (behavior == ClientBehavior.VALID_DURING_VALIDITY) {
                refreshDelay = Duration.ofSeconds(Math.max(MIN_RETRY_AFTER_SECONDS,
                        Math.min(MAX_REFRESH_SECONDS, configuration.getVersValidity())));
            } else if (behavior == ClientBehavior.VALID_WITHOUT_DURATION) {
                refreshDelay = Duration.ofSeconds(MAX_REFRESH_SECONDS);
            }
        }

        /**
         * Returns {@code true} when {@code EntitlementResult} says WFC is not activated; Otherwise
         * {@code false} if {@code EntitlementResult} is not of any known pattern.
         */
        private boolean shouldTurnOffWfc(@Nullable EntitlementResult result) {
            if (result == null) {
                Log.d(TAG, "Entitlement API failed to return a result; don't turn off WFC.");
                return false;
            }

            // Only turn off WFC for known patterns indicating WFC not activated.
            return result.getVowifiStatus().serverDataMissing()
                    || result.getVowifiStatus().inProgress()
                    || result.getVowifiStatus().incompatible();
        }

        private boolean shouldTurnOffVolte(@Nullable EntitlementResult result) {
            if (result == null) {
                Log.d(TAG, "Entitlement API failed to return a result; don't turn off VoLTE.");
                return false;
            }

            // Only turn off VoLTE for known patterns indicating VoLTE not activated.
            return !result.getVolteStatus().isActive();
        }

        private boolean shouldTurnOffVonrHome(@Nullable EntitlementResult result) {
            if (result == null) {
                Log.d(TAG, "Entitlement API failed to return a result; don't turn off VoNR.");
                return false;
            }

            // Only turn off VoNR in Home for known patterns indicating VoNR not activated.
            return !result.getVonrStatus().isHomeActive();
        }

        private boolean shouldTurnOffSMSoIP(@Nullable EntitlementResult result) {
            if (result == null) {
                Log.d(TAG, "Entitlement API failed to return a result; don't turn off SMSoIP.");
                return false;
            }

            // Only turn off SMSoIP for known patterns indicating SMSoIP not activated.
            return !result.getSmsoveripStatus().isActive();
        }

        private void sendStatsLogToMetrics() {
            // If no result set, it was cancelled for reasons.
            if (mVowifiResult == IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT) {
                mVowifiResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__CANCELED;
            }
            mMetricsLogger.write(
                    IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__VOWIFI, mVowifiResult);

            if (mNeedsImsProvisioning) {
                if (mVolteResult == IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT) {
                    mVolteResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__CANCELED;
                }
                if (mSmsoipResult == IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__UNKNOWN_RESULT) {
                    mSmsoipResult = IMS_SERVICE_ENTITLEMENT_UPDATED__APP_RESULT__CANCELED;
                }
                mMetricsLogger.write(
                        IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__VOLTE, mVolteResult);
                mMetricsLogger.write(
                        IMS_SERVICE_ENTITLEMENT_UPDATED__SERVICE_TYPE__SMSOIP, mSmsoipResult);
            }
        }

        @VisibleForTesting
        int getVonrResult() {
            return mVonrResult;
        }

        @VisibleForTesting
        int getVowifiResult() {
            return mVowifiResult;
        }
    }
}

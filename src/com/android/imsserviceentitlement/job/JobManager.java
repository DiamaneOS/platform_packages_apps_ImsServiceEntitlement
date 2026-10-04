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

package com.android.imsserviceentitlement.job;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.Handler;
import android.os.Looper;
import com.android.imsserviceentitlement.utils.HttpsUrl;
import android.telephony.SubscriptionManager;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;

import com.android.imsserviceentitlement.utils.TelephonyUtils;
import com.android.imsserviceentitlement.utils.Executors;

import java.time.Duration;

/** Manages all scheduled jobs and provides common job scheduler. */
public class JobManager {
    private static final String TAG = "IMSSE-JobManager";

    private static final int JOB_ID_BASE_INDEX = 1000;

    // Query entitlement status
    public static final int QUERY_ENTITLEMENT_STATUS_JOB_ID = 1;

    public static final String EXTRA_SLOT_ID = "SLOT_ID";
    public static final String EXTRA_RETRY_COUNT = "RETRY_COUNT";
    public static final String EXTRA_SCHEDULE_GENERATION = "SCHEDULE_GENERATION";

    private final Context mContext;
    private final int mSubId;
    private final JobScheduler mJobScheduler;
    private final ComponentName mComponentName;
    private final Handler mSchedulerHandler;
    private final java.util.concurrent.Executor mSchedulingExecutor;
    private long mGeneration;
    private long mAcceptedGeneration;
    private long mRunTicket;
    private long mRunGeneration;
    private Schedule mPending;
    private boolean mDrainPosted;
    private long mProvisioningOwner;
    private static final long SCHEDULER_RETRY_MILLIS = JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS;
    private static final class Schedule {
        final long generation;
        final int failures;
        final Duration delay;
        Schedule(long generation, int failures, Duration delay) {
            this.generation = generation; this.failures = failures; this.delay = delay;
        }
    }

    public synchronized long beginRun(long scheduledGeneration) {
        if (mGeneration == 0 && scheduledGeneration > 0) {
            mGeneration = scheduledGeneration;
            mAcceptedGeneration = scheduledGeneration;
        }
        if (scheduledGeneration != mGeneration) return -1;
        mRunTicket = Math.addExact(mRunTicket, 1);
        mRunGeneration = mGeneration;
        return mRunTicket;
    }
    public synchronized boolean isCurrentRun(long ticket) {
        return ticket == mRunTicket && mRunGeneration == mGeneration;
    }
    public synchronized void endRun(long ticket) {
        if (isCurrentRun(ticket)) mRunTicket = Math.addExact(mRunTicket, 1);
    }
    /** Nonblocking admission retained until physical setters return, even after timeout. */
    public synchronized boolean enterProvisioning(long ticket) {
        if (!isCurrentRun(ticket) || mProvisioningOwner != 0) return false;
        mProvisioningOwner = ticket;
        return true;
    }
    public synchronized void leaveProvisioning(long ticket) {
        if (mProvisioningOwner == ticket) mProvisioningOwner = 0;
    }
    public void queryFromCompletedRun(long ticket, int failures, Duration delay) {
        synchronized (this) {
            if (!isCurrentRun(ticket)) return;
            queueLocked(failures, delay);
        }
    }
    private void queueLocked(int failures, Duration delay) {
        mGeneration = Math.addExact(mGeneration, 1);
        mPending = new Schedule(mGeneration, failures, delay);
        if (!mDrainPosted) { mDrainPosted = true; mSchedulerHandler.post(this::submitSchedule); }
    }
    private void submitSchedule() {
        try { mSchedulingExecutor.execute(this::drainSchedule); }
        catch (java.util.concurrent.RejectedExecutionException saturated) {
            mSchedulerHandler.postDelayed(this::submitSchedule, SCHEDULER_RETRY_MILLIS);
        }
    }
    private void drainSchedule() {
        Schedule intent;
        synchronized (this) { intent = mPending; mPending = null; mDrainPosted = false; }
        if (intent == null) return;
        try {
            if (!TelephonyUtils.isActivedSubId(mContext, mSubId)
                    || !HttpsUrl.isAllowed(TelephonyUtils.getEntitlementServerUrl(mContext, mSubId))) {
                synchronized (this) {
                    if (mGeneration == intent.generation) {
                        mGeneration = mAcceptedGeneration;
                        mRunTicket = Math.addExact(mRunTicket, 1);
                    }
                }
                return;
            }
            JobInfo job = newJobInfoBuilder(QUERY_ENTITLEMENT_STATUS_JOB_ID, intent.failures, intent.generation)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(intent.delay.toMillis()).build();
            // All local producers publish on this one bounded worker. A newer producer
            // queued during Binder lookup wins before the older mutation is issued.
            synchronized (this) { if (mGeneration != intent.generation) return; }
            if (mJobScheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
                synchronized (this) { mAcceptedGeneration = intent.generation; }
                return;
            }
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Carrier job scheduling unavailable");
        }
        synchronized (this) {
            if (mGeneration != intent.generation) return;
            mPending = intent;
            if (!mDrainPosted) { mDrainPosted = true; mSchedulerHandler.postDelayed(this::submitSchedule, SCHEDULER_RETRY_MILLIS); }
        }
    }


    // Cache subscription id associated {@link JobManager} objects for reusing.
    @GuardedBy("JobManager.class")
    private static final ArrayMap<String, JobManager> sInstances = new ArrayMap<>();

    private JobManager(Context context, ComponentName componentName, int subId) {
        this(context, componentName, subId, new Handler(Looper.getMainLooper()));
    }

    @VisibleForTesting
    JobManager(Context context, ComponentName componentName, int subId, Handler handler) {
        this(context, componentName, subId, handler, Executors.getSchedulingExecutor());
    }

    @VisibleForTesting
    JobManager(Context context, ComponentName componentName, int subId, Handler handler,
            java.util.concurrent.Executor scheduler) {
        this.mContext = context;
        this.mComponentName = componentName;
        this.mJobScheduler = context.getSystemService(JobScheduler.class);
        this.mSubId = subId;
        this.mSchedulerHandler = handler;
        this.mSchedulingExecutor = scheduler;
    }

    /** Returns {@link JobManager} instance. */
    public static synchronized JobManager getInstance(
            Context context, ComponentName componentName, int subId) {
        String key = componentName.flattenToShortString() + "." + subId;
        JobManager instance = sInstances.get(key);
        if (instance != null) {
            return instance;
        }

        instance = new JobManager(context, componentName, subId);
        sInstances.put(key, instance);
        return instance;
    }

    private JobInfo.Builder newJobInfoBuilder(int jobId, int retryCount, long generation) {
        JobInfo.Builder builder = new JobInfo.Builder(getJobIdWithSubId(jobId), mComponentName);
        putSubIdAndRetryExtra(builder, retryCount, generation);
        return builder;
    }

    /**
     * Returns a new job id with {@code JOB_ID_BASE_INDEX} for separating job for different
     * subscription id, in order to avoid job be overrided for different SIM on multi SIM device.
     * Returns original {@code jobId} if the subscription id invalid. For example, if subscription
     * id be 8, the job id would be 8001, 8002, etc; if subscription id be -1, the job id would be
     * 1, 2, etc.
     */
    private int getJobIdWithSubId(int jobId) {
        if (SubscriptionManager.isValidSubscriptionId(mSubId)) {
            return JOB_ID_BASE_INDEX * mSubId + jobId;
        }
        return jobId;
    }

    /** Returns job id which remove {@code JOB_ID_BASE_INDEX}. */
    public static int getPureJobId(int jobId) {
        return jobId % JOB_ID_BASE_INDEX;
    }

    private void putSubIdAndRetryExtra(JobInfo.Builder builder, int retryCount, long generation) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, mSubId);
        bundle.putInt(EXTRA_SLOT_ID, TelephonyUtils.getSlotId(mContext, mSubId));
        bundle.putInt(EXTRA_RETRY_COUNT, retryCount);
        bundle.putLong(EXTRA_SCHEDULE_GENERATION, generation);
        builder.setExtras(bundle);
    }

    /** Checks Entitlement Status once has network connection without retry and delay. */
    public void queryEntitlementStatusOnceNetworkReady() {
        queryEntitlementStatusOnceNetworkReady(/* retryCount= */ 0, Duration.ofSeconds(0));
    }

    /** Checks Entitlement Status once has network connection with retry count. */
    public void queryEntitlementStatusOnceNetworkReady(int retryCount) {
        queryEntitlementStatusOnceNetworkReady(retryCount, Duration.ofSeconds(0));
    }

    /** Checks Entitlement Status once has network connection with retry count and delay. */
    public void queryEntitlementStatusOnceNetworkReady(int retryCount, Duration delay) {
        if (retryCount < 0 || delay.isNegative()) throw new IllegalArgumentException("Invalid job request");
        synchronized (this) { queueLocked(retryCount, delay); }

    }


    /**
     * Returns {@code true} if this job's subscription id still actived and still on same slot.
     * Returns {@code false} otherwise.
     */
    public static boolean isValidJob(Context context, final JobParameters params) {
        PersistableBundle bundle = params.getExtras();
        int subId =
                bundle.getInt(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        int slotId = bundle.getInt(EXTRA_SLOT_ID, SubscriptionManager.INVALID_SIM_SLOT_INDEX);

        // Avoids to do anything after user removed or swapped SIM
        if (!TelephonyUtils.isActivedSubId(context, subId)) {
            Log.d(TAG, "Stop reason: SUBID(" + subId + ") not point to active SIM.");
            return false;
        }

        // For example, the job scheduled for slot 1 then SIM been swapped to slot 2 and then start
        // this job. So, let's ignore this case.
        if (TelephonyUtils.getSlotId(context, subId) != slotId) {
            Log.d(TAG, "Stop reason: SLOTID(" + slotId + ") not matched.");
            return false;
        }

        return true;
    }
}

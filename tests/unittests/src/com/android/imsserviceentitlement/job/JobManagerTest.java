// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.job;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import androidx.test.runner.AndroidJUnit4;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Deterministic scheduling races; all subscription/scheduler services are mocked. */
@RunWith(AndroidJUnit4.class)
public final class JobManagerTest {
    private final ArrayDeque<Runnable> ready = new ArrayDeque<>();
    private final ArrayDeque<Runnable> delayed = new ArrayDeque<>();
    private final List<JobInfo> scheduled = new ArrayList<>();
    private JobManager manager;
    private JobScheduler scheduler;
    private CarrierConfigManager configManager;
    private PersistableBundle configuration;

    @Before public void setup() {
        Context context = mock(Context.class);
        Handler handler = new Handler(Looper.getMainLooper()) {
            @Override public boolean sendMessageAtTime(Message message, long uptimeMillis) {
                (uptimeMillis > SystemClock.uptimeMillis() ? delayed : ready)
                        .add(message.getCallback());
                return true;
            }
        };
        scheduler = mock(JobScheduler.class);
        SubscriptionManager subscriptions = mock(SubscriptionManager.class);
        SubscriptionInfo subscription = mock(SubscriptionInfo.class);
        configManager = mock(CarrierConfigManager.class);
        configuration = new PersistableBundle();
        configuration.putString(CarrierConfigManager.ImsServiceEntitlement
                .KEY_ENTITLEMENT_SERVER_URL_STRING, "https://carrier.example/entitlement");
        when(context.getSystemServiceName(JobScheduler.class)).thenReturn(Context.JOB_SCHEDULER_SERVICE);
        when(context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).thenReturn(scheduler);
        when(context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE))
                .thenReturn(subscriptions);
        when(context.getSystemServiceName(CarrierConfigManager.class)).thenReturn(Context.CARRIER_CONFIG_SERVICE);
        when(context.getSystemService(Context.CARRIER_CONFIG_SERVICE)).thenReturn(configManager);
        when(subscriptions.getActiveSubscriptionInfo(1)).thenReturn(subscription);
        when(subscription.getSimSlotIndex()).thenReturn(0);
        when(configManager.getConfigForSubId(1)).thenReturn(configuration);
        when(scheduler.schedule(any())).thenAnswer(call -> {
            scheduled.add(call.getArgument(0)); return JobScheduler.RESULT_SUCCESS;
        });
        manager = new JobManager(context, new ComponentName("de.diamaneos.test", "Polling"), 1, handler, Runnable::run);
    }

    @Test public void predecessorCompletionCannotReplaceSuccessor() {
        long first = manager.beginRun(0);
        manager.queryEntitlementStatusOnceNetworkReady(0, Duration.ofSeconds(10));
        ready.remove().run();
        long successor = manager.beginRun(generation(scheduled.get(0)));
        manager.queryFromCompletedRun(first, 3, Duration.ofMinutes(2));
        assertThat(ready).isEmpty();
        assertThat(manager.isCurrentRun(successor)).isTrue();
    }

    @Test public void latestProducerCoalescesAndOldScheduledJobCannotStart() {
        manager.queryEntitlementStatusOnceNetworkReady(1, Duration.ofSeconds(30));
        manager.queryEntitlementStatusOnceNetworkReady(2, Duration.ofSeconds(60));
        assertThat(ready).hasSize(1);
        ready.remove().run();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getExtras().getInt(JobManager.EXTRA_RETRY_COUNT)).isEqualTo(2);
        assertThat(manager.beginRun(generation(scheduled.get(0)) - 1)).isEqualTo(-1);
    }

    @Test public void producerDuringSubscriptionLookupWins() {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false))
                manager.queryEntitlementStatusOnceNetworkReady(2, Duration.ofSeconds(60));
            return configuration;
        }).when(configManager).getConfigForSubId(1);
        manager.queryEntitlementStatusOnceNetworkReady(1, Duration.ofSeconds(30));
        ready.remove().run();
        assertThat(scheduled).isEmpty();
        ready.remove().run();
        assertThat(scheduled.get(0).getExtras().getInt(JobManager.EXTRA_RETRY_COUNT)).isEqualTo(2);
    }

    @Test public void schedulingFailureRetainsRecoveryUntilAccepted() {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) return JobScheduler.RESULT_FAILURE;
            scheduled.add(call.getArgument(0));
            return JobScheduler.RESULT_SUCCESS;
        }).when(scheduler).schedule(any());
        manager.queryEntitlementStatusOnceNetworkReady(2, Duration.ofSeconds(60));
        ready.remove().run();
        assertThat(delayed).hasSize(1);
        delayed.remove().run();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getExtras().getInt(JobManager.EXTRA_RETRY_COUNT)).isEqualTo(2);
    }

    @Test public void stoppedRunCannotReschedule() {
        long ticket = manager.beginRun(0);
        manager.endRun(ticket);
        manager.queryFromCompletedRun(ticket, 1, Duration.ofSeconds(30));
        assertThat(ready).isEmpty();
    }

    @Test public void restoredJobGenerationIsAdoptedAfterProcessRestart() {
        long ticket = manager.beginRun(41);
        assertThat(ticket).isGreaterThan(0);
        manager.queryFromCompletedRun(ticket, 1, Duration.ofSeconds(30));
        ready.remove().run();
        assertThat(generation(scheduled.get(0))).isEqualTo(42);
    }

    @Test public void expiredPhysicalSetterBlocksSuccessorUntilItReturns() {
        long first = manager.beginRun(0);
        assertThat(manager.enterProvisioning(first)).isTrue();
        manager.queryEntitlementStatusOnceNetworkReady();
        ready.remove().run();
        long next = manager.beginRun(generation(scheduled.get(0)));
        assertThat(manager.enterProvisioning(next)).isFalse();
        manager.leaveProvisioning(first);
        assertThat(manager.enterProvisioning(next)).isTrue();
        manager.leaveProvisioning(next);
    }

    @Test public void abandonedInvalidSuccessorDoesNotPoisonNativeFallback() {
        manager.queryEntitlementStatusOnceNetworkReady();
        ready.remove().run();
        long accepted = generation(scheduled.get(0));
        configuration.putString(CarrierConfigManager.ImsServiceEntitlement
                .KEY_ENTITLEMENT_SERVER_URL_STRING, "");
        manager.queryEntitlementStatusOnceNetworkReady();
        ready.remove().run();
        assertThat(manager.beginRun(accepted)).isGreaterThan(0);
    }

    @Test public void schedulerRetryOfFailedProvisioningIsRepairUntilReplaced() {
        long failed = manager.beginRun(0);
        assertThat(manager.isRepairRun(failed)).isFalse();
        manager.requestRepair(failed);
        assertThat(manager.isRepairPending()).isTrue();
        long retry = manager.beginRun(0);
        assertThat(manager.isRepairRun(retry)).isTrue();
        manager.queryEntitlementStatusOnceNetworkReady(0, Duration.ofHours(24));
        assertThat(manager.isRepairPending()).isFalse();
        ready.remove().run();
        long successor = manager.beginRun(generation(scheduled.get(0)));
        assertThat(manager.isRepairRun(successor)).isFalse();
    }

    @Test public void runFinishedWithoutRetryLeavesNoRepairPending() {
        long failed = manager.beginRun(0);
        manager.requestRepair(failed);
        long retry = manager.beginRun(0);
        manager.endRepair(retry);
        assertThat(manager.isRepairPending()).isFalse();
        assertThat(manager.isRepairRun(manager.beginRun(0))).isFalse();
    }

    @Test public void stoppedRunCannotRequestRepair() {
        long ticket = manager.beginRun(0);
        manager.endRun(ticket);
        manager.requestRepair(ticket);
        assertThat(manager.isRepairPending()).isFalse();
    }

    private static long generation(JobInfo job) {
        return job.getExtras().getLong(JobManager.EXTRA_SCHEDULE_GENERATION);
    }
}

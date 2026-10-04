// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import static org.junit.Assert.*;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local HTML only: no carrier endpoint, telephony API or network request. */
@RunWith(AndroidJUnit4.class)
public final class PortalBridgeTest {
    private WebView view;
    private PortalBridge bridge;
    private final AtomicInteger callbacks = new AtomicInteger();
    private final CountDownLatch callback = new CountDownLatch(1);
    private void ui(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private void load(String origin, String html) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        ui(() -> {
            if (view == null) {
                view = new WebView(InstrumentationRegistry.getInstrumentation().getContext());
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setAllowFileAccess(false);
                view.getSettings().setAllowContentAccess(false);
                bridge = PortalBridge.install(view, "https://portal.example/activation",
                        "CarrierFlow", Set.of("changed"), method -> {
                            callbacks.incrementAndGet(); callback.countDown();
                        });
                assertNotNull("Installed WebView must support the secure bridge", bridge);
            }
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView webView, String url) { loaded.countDown(); }
            });
            view.loadDataWithBaseURL(origin, html, "text/html", "UTF-8", null);
        });
        assertTrue("Local page load timed out", loaded.await(5, TimeUnit.SECONDS));
    }
    @After public void cleanup() {
        ui(() -> {
            if (bridge != null) bridge.close();
            if (view != null) { view.stopLoading(); view.destroy(); }
        });
    }
    @Test public void configuredMainFrameRetainsCarrierMethod() throws Exception {
        load("https://portal.example/activation", "<script>CarrierFlow.changed()</script>");
        assertTrue(callback.await(3, TimeUnit.SECONDS));
        assertEquals(1, callbacks.get());
    }
    @Test public void otherOriginCannotUseBridge() throws Exception {
        load("https://other.example/activation",
                "<script>if(window.DiamaneOSEntitlementPortal)"
                + "DiamaneOSEntitlementPortal.postMessage('changed')</script>");
        assertFalse(callback.await(1, TimeUnit.SECONDS));
    }
    @Test public void sameOriginEmbeddedFrameCannotInvokeNativeCallback() throws Exception {
        load("https://portal.example/activation",
                "<iframe srcdoc=\"<script>if(window.DiamaneOSEntitlementPortal)"
                + "DiamaneOSEntitlementPortal.postMessage('changed')</script>\"></iframe>");
        assertFalse(callback.await(1, TimeUnit.SECONDS));
    }
    @Test public void closedBridgeCannotAcceptLaterNavigation() throws Exception {
        load("https://portal.example/activation", "<p>Local page</p>");
        ui(() -> bridge.close());
        load("https://portal.example/next",
                "<script>if(window.DiamaneOSEntitlementPortal)"
                + "DiamaneOSEntitlementPortal.postMessage('changed')</script>");
        assertFalse(callback.await(1, TimeUnit.SECONDS));
    }
}

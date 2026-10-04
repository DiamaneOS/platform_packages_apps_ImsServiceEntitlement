// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import android.webkit.WebView;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.util.Set;
import java.util.function.Consumer;

/** Carrier method compatibility over an exact-origin, main-frame message channel. */
public final class PortalBridge implements AutoCloseable {
    private static final String CHANNEL = "DiamaneOSEntitlementPortal";
    private final WebView view;
    private final ScriptHandler script;
    private boolean active = true; // WebView/UI thread only.

    private PortalBridge(WebView view, String origin, String controller,
            Set<String> methods, Consumer<String> receive) {
        this.view = view;
        Set<String> allowedOrigins = Set.of(origin);
        WebViewCompat.addWebMessageListener(view, CHANNEL, allowedOrigins,
                (webView, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    if (!active || !isMainFrame || message.getType() != WebMessageCompat.TYPE_STRING
                            || !origin.equals(HttpsUrl.origin(sourceOrigin.toString()))) return;
                    String method = message.getData();
                    if (method != null && methods.contains(method)) receive.accept(method);
                });
        // Preserve the carrier's published method names. Only the main frame
        // gets the shim; Java independently verifies origin and main-frame status.
        StringBuilder shim = new StringBuilder("if(window===window.top){window.")
                .append(controller).append("={");
        for (String method : methods) {
            if (!method.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid internal portal method");
            }
            shim.append(method).append(":function(){window.").append(CHANNEL)
                    .append(".postMessage('").append(method).append("');},");
        }
        shim.append("};}");
        script = WebViewCompat.addDocumentStartJavaScript(view, shim.toString(), allowedOrigins);
    }

    /** Unsupported providers fail closed; no origin-blind legacy bridge fallback. */
    public static PortalBridge install(WebView view, String url, String controller,
            Set<String> methods, Consumer<String> receive) {
        String origin = HttpsUrl.origin(url);
        if (origin == null || !controller.matches("[A-Za-z][A-Za-z0-9_]*")
                || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null;
        return new PortalBridge(view, origin, controller, Set.copyOf(methods), receive);
    }

    @Override public void close() {
        if (!active) return;
        active = false;
        script.remove();
        WebViewCompat.removeWebMessageListener(view, CHANNEL);
    }
}

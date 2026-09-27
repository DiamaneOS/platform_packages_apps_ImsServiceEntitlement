// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.net.URI;
import java.net.URISyntaxException;

/** Shared scheme checks for carrier API requests and portal navigation. */
public final class HttpsUrl {
    private HttpsUrl() {}

    public static boolean isAllowed(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;

/** HTTPS transport injected through the entitlement library's supported factory. */
public final class CarrierTransport extends HttpURLConnection {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpsURLConnection mConnection;
    private InputStream mInput;
    private InputStream mError;

    private CarrierTransport(HttpsURLConnection connection) {
        super(connection.getURL());
        mConnection = connection;
        mConnection.setInstanceFollowRedirects(false);
    }

    public static HttpURLConnection open(URL url) throws IOException {
        if (!HttpsUrl.isAllowed(url.toExternalForm())) {
            throw new IOException("Carrier connection requires HTTPS without user information");
        }
        // Use Android's ordinary TLS trust and hostname verification. No custom
        // verifier, trust store, network binding or redirect bypass is installed.
        URLConnection connection = url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) throw new IOException("HTTPS unavailable");
        return new CarrierTransport((HttpsURLConnection) connection);
    }

    @Override public void connect() throws IOException { mConnection.connect(); }
    @Override public void disconnect() { mConnection.disconnect(); }
    @Override public boolean usingProxy() { return mConnection.usingProxy(); }
    @Override public void setInstanceFollowRedirects(boolean value) {
        // The entitlement library handles redirects explicitly through open().
        if (value) throw new IllegalArgumentException("Implicit redirects are not permitted");
        mConnection.setInstanceFollowRedirects(false);
    }
    @Override public void setRequestMethod(String value) throws ProtocolException {
        mConnection.setRequestMethod(value);
    }
    @Override public String getRequestMethod() { return mConnection.getRequestMethod(); }
    @Override public boolean getInstanceFollowRedirects() {
        return mConnection.getInstanceFollowRedirects();
    }
    @Override public void setConnectTimeout(int value) { mConnection.setConnectTimeout(value); }
    @Override public void setReadTimeout(int value) { mConnection.setReadTimeout(value); }
    @Override public void setDoOutput(boolean value) { mConnection.setDoOutput(value); }
    @Override public int getConnectTimeout() { return mConnection.getConnectTimeout(); }
    @Override public int getReadTimeout() { return mConnection.getReadTimeout(); }
    @Override public boolean getDoOutput() { return mConnection.getDoOutput(); }
    @Override public void setRequestProperty(String key, String value) {
        mConnection.setRequestProperty(key, value);
    }
    @Override public void addRequestProperty(String key, String value) {
        mConnection.addRequestProperty(key, value);
    }
    @Override public String getRequestProperty(String key) {
        return mConnection.getRequestProperty(key);
    }
    @Override public Map<String, List<String>> getRequestProperties() {
        return mConnection.getRequestProperties();
    }
    @Override public OutputStream getOutputStream() throws IOException {
        return mConnection.getOutputStream();
    }
    @Override public int getResponseCode() throws IOException { return mConnection.getResponseCode(); }
    @Override public String getResponseMessage() throws IOException {
        return mConnection.getResponseMessage();
    }
    @Override public String getHeaderField(String name) { return mConnection.getHeaderField(name); }
    @Override public Map<String, List<String>> getHeaderFields() {
        return mConnection.getHeaderFields();
    }
    @Override public synchronized InputStream getInputStream() throws IOException {
        if (mInput == null) mInput = bounded(mConnection.getInputStream());
        return mInput;
    }
    @Override public synchronized InputStream getErrorStream() {
        if (mError == null) {
            InputStream stream = mConnection.getErrorStream();
            if (stream != null) mError = bounded(stream);
        }
        return mError;
    }

    static InputStream bounded(InputStream stream) {
        return new FilterInputStream(stream) {
            private int remaining = MAX_RESPONSE_BYTES;
            private boolean rejected;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (rejected) throw new IOException("Carrier response exceeds size limit");
                if (length == 0) return 0;
                int count = in.read(bytes, offset, Math.min(length, remaining + 1));
                if (count > remaining) {
                    rejected = true;
                    throw new IOException("Carrier response exceeds size limit");
                }
                if (count > 0) remaining -= count;
                return count;
            }
            @Override public long skip(long count) throws IOException {
                byte[] buffer = new byte[4096];
                long skipped = 0;
                while (skipped < count) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
                    if (read == -1) break;
                    skipped += read;
                }
                return skipped;
            }
            @Override public boolean markSupported() { return false; }
            @Override public void mark(int limit) { }
            @Override public void reset() throws IOException {
                throw new IOException("Carrier response cannot be reset");
            }
        };
    }
}

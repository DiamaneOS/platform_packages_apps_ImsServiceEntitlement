// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
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
    private final RequestScope mScope;
    private final Runnable mAbort = this::abortTransport;
    private volatile InputStream mInput;
    private volatile InputStream mError;
    private volatile OutputStream mOutput;
    private int mConnectTimeout;
    private int mReadTimeout;

    private CarrierTransport(HttpsURLConnection connection) {
        super(connection.getURL());
        mConnection = connection;
        mScope = RequestScope.current();
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
        CarrierTransport transport = new CarrierTransport((HttpsURLConnection) connection);
        if (transport.mScope != null) {
            try { transport.mScope.track(transport.mAbort); }
            catch (IOException ended) {
                CarrierExecutors.cleanup(transport.mAbort);
                throw ended;
            }
        }
        return transport;
    }

    private void beforeIo() throws IOException {
        if (mScope == null) return; // Host seam; production checks install a scope.
        mScope.check();
        int remaining = mScope.remainingMillis();
        mConnection.setConnectTimeout(mConnectTimeout > 0 ? Math.min(mConnectTimeout, remaining) : remaining);
        mConnection.setReadTimeout(mReadTimeout > 0 ? Math.min(mReadTimeout, remaining) : remaining);
    }
    private void afterIo() throws IOException { if (mScope != null) mScope.check(); }
    private void abortTransport() {
        // Never acquire a stream here or take a monitor held across a delegate call.
        try { mConnection.disconnect(); } catch (RuntimeException ignored) { }
        for (AutoCloseable stream : new AutoCloseable[] {mInput, mError, mOutput}) {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        }
    }
    @Override public void connect() throws IOException { beforeIo(); mConnection.connect(); afterIo(); }
    @Override public void disconnect() {
        if (mScope != null) mScope.untrack(mAbort);
        mConnection.disconnect();
    }
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
    @Override public void setConnectTimeout(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative timeout");
        mConnectTimeout = value;
        if (mScope == null) mConnection.setConnectTimeout(value);
    }
    @Override public void setReadTimeout(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative timeout");
        mReadTimeout = value;
        if (mScope == null) mConnection.setReadTimeout(value);
    }
    @Override public void setDoOutput(boolean value) { mConnection.setDoOutput(value); }
    @Override public int getConnectTimeout() { return mConnectTimeout; }
    @Override public int getReadTimeout() { return mReadTimeout; }
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
        beforeIo();
        OutputStream stream = mConnection.getOutputStream();
        mOutput = stream;
        try { afterIo(); }
        catch (IOException ended) { CarrierExecutors.cleanup(mAbort); throw ended; }
        return new FilterOutputStream(stream) {
            @Override public void write(int value) throws IOException {
                beforeIo(); out.write(value); afterIo();
            }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                beforeIo(); out.write(bytes, offset, length); afterIo();
            }
            @Override public void flush() throws IOException { beforeIo(); out.flush(); afterIo(); }
        };
    }
    @Override public int getResponseCode() throws IOException {
        beforeIo(); int code = mConnection.getResponseCode(); afterIo(); return code;
    }
    @Override public String getResponseMessage() throws IOException {
        beforeIo(); String message = mConnection.getResponseMessage(); afterIo(); return message;
    }
    @Override public String getHeaderField(String name) {
        try { beforeIo(); String result = mConnection.getHeaderField(name); afterIo(); return result; }
        catch (IOException ended) { return null; }
    }
    @Override public Map<String, List<String>> getHeaderFields() {
        try { beforeIo(); Map<String, List<String>> result = mConnection.getHeaderFields(); afterIo(); return result; }
        catch (IOException ended) { return java.util.Collections.emptyMap(); }
    }
    @Override public InputStream getInputStream() throws IOException {
        beforeIo();
        if (mInput == null) mInput = scopedInput(bounded(mConnection.getInputStream()));
        try { afterIo(); }
        catch (IOException ended) { CarrierExecutors.cleanup(mAbort); throw ended; }
        return mInput;
    }
    @Override public InputStream getErrorStream() {
        try { beforeIo(); } catch (IOException ended) { return null; }
        if (mError == null) {
            InputStream stream = mConnection.getErrorStream();
            if (stream != null) mError = scopedInput(bounded(stream));
        }
        try { afterIo(); } catch (IOException ended) { CarrierExecutors.cleanup(mAbort); return null; }
        return mError;
    }

    private InputStream scopedInput(InputStream stream) {
        return new FilterInputStream(stream) {
            @Override public int read() throws IOException {
                beforeIo(); int result = in.read(); afterIo(); return result;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                beforeIo(); int result = in.read(bytes, offset, length); afterIo(); return result;
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
        };
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

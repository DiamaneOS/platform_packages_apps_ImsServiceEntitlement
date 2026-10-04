// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import org.xml.sax.SAXException;

public final class CarrierBoundaryTest {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }
    private interface Operation { void run() throws Exception; }
    private static void rejected(Operation action) throws Exception {
        try { action.run(); } catch (IOException | SAXException expected) { return; }
        throw new AssertionError("unsafe input accepted");
    }
    private static final class MemoryHttps extends HttpsURLConnection {
        final byte[] body;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean disconnected;
        MemoryHttps(URL url, int bytes) { super(url); body = new byte[bytes]; }
        @Override public void connect() { }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "test-only"; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(body); }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public int getResponseCode() { return 200; }
        @Override public String getResponseMessage() { return "OK"; }
        @Override public String getHeaderField(String name) { return "application/xml"; }
        @Override public Map<String, List<String>> getHeaderFields() {
            return Map.of("Content-Type", List.of("application/xml"));
        }
    }
    public static void main(String[] args) throws Exception {
        for (String url : new String[] {"https://carrier.example/path?a=b", "HTTPS://carrier.example",
                "https://carrier.example:8443/portal#step", "https://[2001:db8::1]/"}) {
            check(HttpsUrl.isAllowed(url));
        }
        for (String url : new String[] {null, "", "http://carrier.example/", "file:///secret",
                "content://secret", "javascript:alert(1)", "https:opaque", "//carrier.example",
                "https://user:secret@carrier.example", "https://carrier.example:0/",
                "https://carrier.example:65536/", "https://carrier.example/ bad"}) {
            check(!HttpsUrl.isAllowed(url));
        }
        check("https://carrier.example".equals(HttpsUrl.origin("https://CARRIER.example:443/path")));
        check("https://carrier.example:8443".equals(HttpsUrl.origin("https://carrier.example:8443/")));
        check(!HttpsUrl.origin("https://other.example").equals(HttpsUrl.origin("https://carrier.example")));
        check(HttpsUrl.origin("https://user@carrier.example/") == null);
        MemoryHttps[] opened = new MemoryHttps[1];
        URL url = new URL(null, "https://carrier.example/api", new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL requested) {
                return opened[0] = new MemoryHttps(requested, 8);
            }
        });
        HttpURLConnection connection = CarrierTransport.open(url);
        check(!connection.getInstanceFollowRedirects());
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(1234);
        connection.setReadTimeout(2345);
        connection.setDoOutput(true);
        connection.addRequestProperty("Accept", "application/xml");
        check(opened[0].getRequestMethod().equals("POST"));
        check(connection.getConnectTimeout() == 1234 && connection.getReadTimeout() == 2345);
        check(opened[0].getDoOutput());
        check(opened[0].getRequestProperty("Accept").equals("application/xml"));
        connection.getOutputStream().write(7);
        check(opened[0].sent.toByteArray()[0] == 7);
        check(connection.getResponseCode() == 200);
        check(connection.getHeaderField("Content-Type").equals("application/xml"));
        check(connection.getInputStream() == connection.getInputStream());
        check(connection.getErrorStream() == connection.getErrorStream());
        check(connection.getInputStream().readAllBytes().length == 8);
        connection.disconnect();
        check(opened[0].disconnected);
        try { connection.setInstanceFollowRedirects(true); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        rejected(() -> CarrierTransport.open(new URL("http://carrier.example/")));
        try (InputStream exact = CarrierTransport.bounded(
                new ByteArrayInputStream(new byte[CarrierTransport.MAX_RESPONSE_BYTES]))) {
            check(exact.readAllBytes().length == CarrierTransport.MAX_RESPONSE_BYTES);
            check(exact.read() == -1);
        }
        rejected(() -> CarrierTransport.bounded(new ByteArrayInputStream(
                new byte[CarrierTransport.MAX_RESPONSE_BYTES + 1])).readAllBytes());
        rejected(() -> CarrierTransport.bounded(new ByteArrayInputStream(
                new byte[CarrierTransport.MAX_RESPONSE_BYTES + 1])).skip(
                        CarrierTransport.MAX_RESPONSE_BYTES + 1L));
        InputStream noReset = CarrierTransport.bounded(new ByteArrayInputStream(new byte[2]));
        check(!noReset.markSupported());
        rejected(noReset::reset);

        check(CarrierXml.parse("<wap-provisioningdoc><characteristic type='VERS'/></wap-provisioningdoc>")
                .getDocumentElement().getNodeName().equals("wap-provisioningdoc"));
        check(CarrierXml.parse("<root value='a&b'/>").getDocumentElement().getAttribute("value")
                .equals("a&b"));
        for (String xml : new String[] {"", "<broken>",
                "<!DOCTYPE root SYSTEM 'file:///never-read'><root/>",
                "<!DOCTYPE root [<!ENTITY value 'data'>]><root>&value;</root>",
                "<wap-provisioningdoc><characteristic type='VERS'><parm name='version' value='NaN'/></characteristic></wap-provisioningdoc>",
                "<wap-provisioningdoc><characteristic type='TOKEN'><parm name='validity' value='9223372036854775807'/></characteristic></wap-provisioningdoc>",
                "<r>".repeat(65) + "</r>".repeat(65),
                "<r>" + "<x/>".repeat(4096) + "</r>",
                "<r>" + "é".repeat(CarrierTransport.MAX_RESPONSE_BYTES / 2) + "</r>"}) {
            rejected(() -> CarrierXml.parse(xml));
        }
        System.out.println("Carrier HTTPS, bounded-stream and XML checks: PASS (no network)");
    }
}

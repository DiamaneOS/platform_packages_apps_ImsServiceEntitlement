// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package com.android.imsserviceentitlement.utils;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, non-resolving XML parsing shared by production and host checks. */
public final class CarrierXml {
    // Downstream parser-containment budgets, not TS.43 carrier requirements.
    // Bound nesting and DOM allocation independently of the transport byte cap.
    private static final int MAX_XML_DEPTH = 64;
    private static final int MAX_XML_ELEMENTS = 4096;

    private CarrierXml() {}

    public static Document parse(String body)
            throws IOException, SAXException, ParserConfigurationException {
        if (body == null || body.length() > CarrierTransport.MAX_RESPONSE_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > CarrierTransport.MAX_RESPONSE_BYTES
                || body.contains("<!DOCTYPE") || body.contains("<!ENTITY")) {
            throw new SAXException("Unsupported carrier XML");
        }
        // Preserve the upstream workaround for carrier portals that send bare
        // ampersands in URL values. Check size/declarations before rewriting.
        body = body.replace("&", "&amp;").replace("&amp;amp;", "&amp;");
        // Bound structure before building a DOM. TS.43 documents are shallow;
        // a response must not turn a privileged app into an unbounded parser.
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        DefaultHandler errors = new DefaultHandler() {
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        };
        reader.setErrorHandler(errors);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External carrier XML references are not permitted");
        });
        reader.setContentHandler(new DefaultHandler() {
            private int depth;
            private int nodes;
            @Override public void startElement(String uri, String local, String name, Attributes a)
                    throws SAXException {
                if (++depth > MAX_XML_DEPTH || ++nodes > MAX_XML_ELEMENTS) throw new SAXException("Carrier XML too complex");
            }
            @Override public void endElement(String uri, String local, String name) { --depth; }
        });
        reader.parse(new InputSource(new StringReader(body)));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(errors);
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External carrier XML references are not permitted");
        });
        Document document = builder.parse(new InputSource(new StringReader(body)));
        // Validate the numeric fields consumed by the configuration/status
        // classes before a response can replace the last known configuration.
        NodeList parameters = document.getElementsByTagName("parm");
        try {
            for (int i = 0; i < parameters.getLength(); ++i) {
                Element parameter = (Element) parameters.item(i);
                if (!(parameter.getParentNode() instanceof Element)) continue;
                String type = ((Element) parameter.getParentNode()).getAttribute("type");
                String name = parameter.getAttribute("name");
                String value = parameter.getAttribute("value");
                if ("VERS".equals(type) && "version".equals(name)) Integer.parseInt(value);
                if (("VERS".equals(type) || "TOKEN".equals(type)) && "validity".equals(name)) {
                    Math.addExact(System.currentTimeMillis(), Math.multiplyExact(Long.parseLong(value), 1000));
                }
                if (("APPLICATION".equals(type) || "RATVoiceEntitleInfoDetails".equals(type))
                        && ("EntitlementStatus".equals(name) || "TC_Status".equals(name)
                        || "AddrStatus".equals(name) || "ProvStatus".equals(name))) {
                    Integer.parseInt(value);
                }
            }
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new SAXException("Invalid carrier numeric field");
        }
        return document;
    }
}

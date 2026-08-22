// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.parser;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.stream.XMLInputFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hardening used to live in five copies and is now in one, which is the whole point of having
 * a test at all: before, pinning it meant writing these assertions five times, so nobody did, and
 * the copies drifted — only one set {@code FEATURE_SECURE_PROCESSING}, only one pinned
 * {@code namespaceAware}, and one built its factory per message. What is asserted here is not the
 * implementation but the four properties every XML entry point of this application depends on.
 */
class SecureXmlTest {

    private static Document parse(String xml) throws Exception {
        return SecureXml.documentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesOrdinaryXml() throws Exception {
        Document doc = parse("<order><id>ORD-42</id></order>");
        assertEquals("order", doc.getDocumentElement().getTagName());
        assertEquals("ORD-42", doc.getDocumentElement().getFirstChild().getTextContent());
    }

    @Test
    void refusesADoctypeAndTheEntitiesItWouldCarry() {
        // The classic XXE payload: a DOCTYPE declaring an entity that reads a local file. It has
        // to fail at the DOCTYPE, before entity resolution is ever reached.
        String xxe = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE f [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<order><id>&x;</id></order>";
        SAXException e = assertThrows(SAXException.class, () -> parse(xxe));
        assertTrue(e.getMessage().contains("DOCTYPE"), "should refuse at the DOCTYPE: " + e.getMessage());
    }

    @Test
    void staysNamespaceUnaware() throws Exception {
        // Load-bearing, and the reason this project's own notes say to call getTagName() and never
        // getLocalName(): with namespace awareness on, getLocalName() is null on a document that
        // declares no namespace, and the flatteners build their dot-notation paths from that name.
        Document doc = parse("<order xmlns=\"urn:acme\"><id>1</id></order>");
        assertNull(doc.getDocumentElement().getLocalName());
        assertEquals("order", doc.getDocumentElement().getTagName());
    }

    @Test
    void handsOutABuilderResetBetweenPayloads() throws Exception {
        // The per-record paths reuse one builder per thread. A document left over from the
        // previous payload leaking into the next is the failure this reset exists to prevent.
        assertEquals("first", parse("<first/>").getDocumentElement().getTagName());
        assertEquals("second", parse("<second/>").getDocumentElement().getTagName());
    }

    @Test
    void givesEachThreadItsOwnBuilder() throws Exception {
        // DocumentBuilder is not thread-safe, and these parsers run on the audit's and the
        // search's worker pools — sharing one instance across them would corrupt both parses.
        var mine = SecureXml.documentBuilder();
        var theirs = new AtomicReference<Object>();
        var done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            theirs.set(SecureXml.documentBuilder());
            done.countDown();
        });
        t.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker thread did not finish");
        assertNotSame(mine, theirs.get());
    }

    @Test
    void streamingReaderIsHardenedAndCoalescing() {
        XMLInputFactory factory = SecureXml.inputFactory();
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.SUPPORT_DTD));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES));
        // Coalescing: a text node split across chunks must arrive as one value, or a payload
        // digest would record a truncated leaf.
        assertEquals(Boolean.TRUE, factory.getProperty(XMLInputFactory.IS_COALESCING));
        assertEquals(Boolean.FALSE, factory.getProperty(XMLInputFactory.IS_NAMESPACE_AWARE));
    }

    @Test
    void evaluatesAnXPathAgainstAParsedDocument() throws Exception {
        Document doc = parse("<order><customer><city>Lyon</city></customer></order>");
        assertEquals("Lyon", SecureXml.xpath().compile("/order/customer/city").evaluate(doc));
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.parser;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;

/**
 * The one place this application hardens an XML parser.
 *
 * <p>It was five places, and the drift had already happened: the same eight-line block appeared in
 * {@code XmlSchemaInferrer}, {@code FlinkSqlService}, {@code MessageFieldExtractorService},
 * {@code MessageMatcher} and {@code XmlExtractUDF}, but only one of them set
 * {@link XMLConstants#FEATURE_SECURE_PROCESSING}, only one pinned {@code setNamespaceAware(false)},
 * and {@code XmlSchemaInferrer} built its factory <em>per call</em> — against this codebase's own
 * rule, written down for the per-record paths, that a {@code DocumentBuilderFactory} is never
 * constructed per message. {@code secureXmlInputFactory()} was a second, byte-identical pair.
 *
 * <p>That is the shape of defect worth removing here, and not for tidiness: XXE hardening is a
 * property that has to hold at <em>every</em> entry point, so five copies means five chances for
 * the next one to be written with seven of the eight lines. One definition can be read, reviewed
 * and changed once.
 *
 * <p>{@code DocumentBuilder}, {@code XPath} and their factories are <strong>not thread-safe</strong>.
 * {@link #documentBuilder()} therefore hands out one per calling thread, {@code reset()} already
 * applied so no state leaks between payloads; callers that own their own lifecycle (a Flink UDF,
 * a per-thread matcher) take {@link #documentBuilderFactory()} and cache the product themselves.
 */
public final class SecureXml {

    private SecureXml() {
    }

    /**
     * One secure {@link DocumentBuilder} per thread, reset before it is handed back.
     *
     * <p>Building a factory per message is expensive — feature negotiation plus parser discovery —
     * and every caller here sits on a bulk path: the cluster audit walks up to 10 000 records per
     * topic to count duplicate keys, and an aggregate query parses up to 100 000 payloads.
     */
    public static DocumentBuilder documentBuilder() {
        DocumentBuilder builder = BUILDERS.get();
        builder.reset();
        return builder;
    }

    /**
     * A hardened factory, for a caller that manages its own {@link DocumentBuilder} lifecycle.
     * Anything parsing on the request or per-record path wants {@link #documentBuilder()} instead.
     */
    public static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        // Namespace-unaware on purpose, and it is load-bearing rather than a default left alone:
        // with it on, getLocalName() returns null on a document with no declared namespace, and
        // the flatteners here read element names to build their dot-notation paths.
        factory.setNamespaceAware(false);
        return factory;
    }

    /**
     * A hardened StAX factory — the streaming reader the digest pipeline and the matcher use where
     * a DOM would cost a tree per payload.
     */
    public static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        // Same reason as above: the element name as written is what the dot-notation paths use.
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        return factory;
    }

    /**
     * A hardened {@link XPath}. Secure processing was set on none of the three XPath call sites
     * before this existed, which is the half of XXE that a {@code DocumentBuilder} does not cover:
     * an expression is evaluated by its own engine, with its own function resolution.
     */
    public static XPath xpath() {
        XPathFactory factory = XPathFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (javax.xml.xpath.XPathFactoryConfigurationException e) {
            throw new IllegalStateException("Cannot secure the XPath factory", e);
        }
        return factory.newXPath();
    }

    private static final ThreadLocal<DocumentBuilder> BUILDERS = ThreadLocal.withInitial(() -> {
        try {
            return documentBuilderFactory().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot create a secure XML document builder", e);
        }
    });

    /**
     * A feature an implementation does not know is not a hardening this application may skip in
     * silence, so an unknown one fails here rather than at the first hostile payload. The JDK's
     * bundled Xerces supports all of the above; a different implementation on the classpath that
     * does not is a deployment we cannot claim to have hardened.
     */
    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(
                "XML parser does not support the security feature " + feature, e);
        }
    }
}

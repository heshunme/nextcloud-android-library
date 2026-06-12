/*
 * Nextcloud Android Library
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: MIT
 */
package com.owncloud.android.lib.resources.files;

import com.owncloud.android.lib.common.network.WebdavEntry;
import com.owncloud.android.lib.resources.files.model.RemoteFile;

import org.apache.jackrabbit.webdav.DavConstants;
import org.apache.jackrabbit.webdav.MultiStatus;
import org.apache.jackrabbit.webdav.MultiStatusResponse;
import org.apache.jackrabbit.webdav.xml.DomUtil;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Streams a WebDAV multistatus body one response at a time.
 */
public class StreamingMultiStatusParser {
    public static final int DEFAULT_BATCH_SIZE = 500;

    private static final String XMLNS_ATTRIBUTE = "xmlns";
    private static final char ATTRIBUTE_QUOTE = '"';
    private static final char ELEMENT_START = '<';
    private static final String ELEMENT_END = ">";
    private static final String ELEMENT_CLOSE_START = "</";
    private static final String MULTISTATUS_START = "<d:multistatus xmlns:d=\"DAV:\">";
    private static final String MULTISTATUS_END = "</d:multistatus>";
    private static final String PREFIX_SEPARATOR = ":";
    private static final String ATTRIBUTE_SEPARATOR = "=\"";
    private static final String SPACE = " ";

    private final int batchSize;
    private final String splitElement;

    public StreamingMultiStatusParser(int batchSize, String splitElement) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }
        this.batchSize = batchSize;
        this.splitElement = splitElement;
    }

    public void parse(InputStream inputStream, RemoteFileSink sink) throws IOException {
        try {
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setNamespaceAware(true);
            parserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            parserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            parserFactory.newSAXParser().parse(new InputSource(inputStream), new MultiStatusHandler(sink));
        } catch (ParserConfigurationException e) {
            throw new IOException("Unable to create WebDAV multistatus parser", e);
        } catch (SAXException e) {
            throw new IOException("Unable to parse WebDAV multistatus", e);
        }
    }

    private RemoteFile readRemoteFile(String responseXml) throws IOException {
        String multiStatusXml = MULTISTATUS_START + responseXml + MULTISTATUS_END;
        try (InputStream responseStream = new ByteArrayInputStream(multiStatusXml.getBytes(StandardCharsets.UTF_8))) {
            Document responseDocument = DomUtil.parseDocument(responseStream);
            Element responseElement = responseDocument.getDocumentElement();
            MultiStatusResponse response = MultiStatus.createFromXml(responseElement).getResponses()[0];
            WebdavEntry entry = new WebdavEntry(response, splitElement);
            return new RemoteFile(entry);
        } catch (ParserConfigurationException e) {
            throw new IOException("Unable to create WebDAV response parser", e);
        } catch (SAXException e) {
            throw new IOException("Unable to parse WebDAV response", e);
        }
    }

    private void writeStartElement(
        String uri,
        String localName,
        String qName,
        Attributes attributes,
        Set<NamespaceDeclaration> namespaceDeclarations,
        StringBuilder xml,
        Deque<String> elementNames
    ) {
        String elementName = elementName(uri, qName, localName, namespaceDeclarations);
        elementNames.push(elementName);
        xml.append(ELEMENT_START);
        xml.append(elementName);
        writeNamespaces(namespaceDeclarations, xml);
        writeAttributes(attributes, xml);
        xml.append(ELEMENT_END);
    }

    private void writeEndElement(Deque<String> elementNames, StringBuilder xml) {
        xml.append(ELEMENT_CLOSE_START);
        xml.append(elementNames.pop());
        xml.append(ELEMENT_END);
    }

    private String elementName(
        String uri,
        String qName,
        String localName,
        Set<NamespaceDeclaration> namespaceDeclarations
    ) {
        if (qName != null && !qName.isEmpty()) {
            return qName;
        }
        String prefix = findPrefix(uri, namespaceDeclarations);
        if (prefix == null || prefix.isEmpty()) {
            return localName;
        }
        return prefix + PREFIX_SEPARATOR + localName;
    }

    private String attributeName(String qName, String localName) {
        if (qName != null && !qName.isEmpty()) {
            return qName;
        }
        return localName;
    }

    private String findPrefix(String uri, Set<NamespaceDeclaration> namespaceDeclarations) {
        if (uri == null || uri.isEmpty() || namespaceDeclarations == null) {
            return null;
        }
        for (NamespaceDeclaration declaration : namespaceDeclarations) {
            if (uri.equals(declaration.uri)) {
                return declaration.prefix;
            }
        }
        return null;
    }

    private void writeNamespaces(Set<NamespaceDeclaration> namespaceDeclarations, StringBuilder xml) {
        Set<String> declaredPrefixes = new HashSet<>();
        for (NamespaceDeclaration declaration : namespaceDeclarations) {
            writeNamespaceIfNeeded(declaration.prefix, declaration.uri, declaredPrefixes, xml);
        }
    }

    private void writeNamespaceIfNeeded(
        String prefix,
        String namespaceUri,
        Set<String> declaredPrefixes,
        StringBuilder xml
    ) {
        if (namespaceUri == null || namespaceUri.isEmpty()) {
            return;
        }

        String normalizedPrefix = prefix == null ? "" : prefix;
        if (!declaredPrefixes.add(normalizedPrefix)) {
            return;
        }

        xml.append(SPACE).append(XMLNS_ATTRIBUTE);
        if (!normalizedPrefix.isEmpty()) {
            xml.append(PREFIX_SEPARATOR).append(normalizedPrefix);
        }
        xml.append(ATTRIBUTE_SEPARATOR)
            .append(escapeAttribute(namespaceUri))
            .append(ATTRIBUTE_QUOTE);
    }

    private void writeAttributes(Attributes attributes, StringBuilder xml) {
        for (int i = 0; i < attributes.getLength(); i++) {
            xml.append(SPACE);
            xml.append(attributeName(attributes.getQName(i), attributes.getLocalName(i)));
            xml.append(ATTRIBUTE_SEPARATOR)
                .append(escapeAttribute(attributes.getValue(i)))
                .append(ATTRIBUTE_QUOTE);
        }
    }

    private String escapeText(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private String escapeAttribute(String text) {
        return escapeText(text)
            .replace("\"", "&quot;");
    }

    private class MultiStatusHandler extends DefaultHandler {
        private final RemoteFileSink sink;
        private final List<RemoteFile> children = new ArrayList<>(batchSize);
        private final Set<NamespaceDeclaration> activeNamespaces = new HashSet<>();
        private final Deque<String> responseElementNames = new ArrayDeque<>();
        private int responseDepth = 0;
        private StringBuilder responseXml;
        private boolean folderRead = false;

        MultiStatusHandler(RemoteFileSink sink) {
            this.sink = sink;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            activeNamespaces.add(new NamespaceDeclaration(prefix, uri));
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (responseDepth == 0 && isDavResponse(uri, localName, qName)) {
                responseXml = new StringBuilder();
            }

            if (responseXml != null) {
                writeStartElement(uri, localName, qName, attributes, activeNamespaces, responseXml, responseElementNames);
                responseDepth++;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (responseXml != null) {
                responseXml.append(escapeText(new String(ch, start, length)));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (responseXml == null) {
                return;
            }

            writeEndElement(responseElementNames, responseXml);
            responseDepth--;
            if (responseDepth == 0) {
                flushResponse();
            }
        }

        @Override
        public void endDocument() throws SAXException {
            flushChildren();
        }

        private boolean isDavResponse(String uri, String localName, String qName) {
            if (DavConstants.XML_RESPONSE.equals(localName) && DavConstants.NAMESPACE.getURI().equals(uri)) {
                return true;
            }
            return qName != null && qName.endsWith(PREFIX_SEPARATOR + DavConstants.XML_RESPONSE);
        }

        private void flushResponse() throws SAXException {
            try {
                RemoteFile remoteFile = readRemoteFile(responseXml.toString());
                responseXml = null;
                if (!folderRead) {
                    sink.onFolder(remoteFile);
                    folderRead = true;
                    return;
                }

                children.add(remoteFile);
                if (children.size() == batchSize) {
                    sink.onChildrenBatch(new ArrayList<>(children));
                    children.clear();
                }
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }

        private void flushChildren() throws SAXException {
            if (children.isEmpty()) {
                return;
            }

            try {
                sink.onChildrenBatch(new ArrayList<>(children));
                children.clear();
            } catch (IOException e) {
                throw new SAXException(e);
            }
        }
    }

}

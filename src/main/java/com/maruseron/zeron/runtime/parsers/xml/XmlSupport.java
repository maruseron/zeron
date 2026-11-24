package com.maruseron.zeron.runtime.parsers.xml;

import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

public final class XmlSupport {
    private XmlSupport() {}

    public static XmlParseResult parse(final String text) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            final var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(final SAXParseException exception) throws SAXParseException {
                    throw exception;
                }

                @Override
                public void fatalError(final SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            final var document = builder.parse(new InputSource(new StringReader(text)));
            return XmlParseResult.success(XmlNode.from(document.getDocumentElement()));
        } catch (final SAXParseException exception) {
            return XmlParseResult.failure(exception.getMessage(), exception.getLineNumber(),
                    exception.getColumnNumber());
        } catch (final Exception exception) {
            return XmlParseResult.failure(exception.getMessage(), 0, 0);
        }
    }
}

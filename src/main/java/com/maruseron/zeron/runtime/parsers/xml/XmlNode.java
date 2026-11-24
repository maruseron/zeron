package com.maruseron.zeron.runtime.parsers.xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XmlNode {
    private final String name;
    private final String namespace;
    private final String text;
    private final List<String> attributeNames;
    private final Map<String, String> attributes;
    private final List<XmlNode> children;

    XmlNode(final String name, final String namespace, final String text,
            final Map<String, String> attributes, final List<XmlNode> children) {
        this.name = name;
        this.namespace = namespace;
        this.text = text;
        this.attributes = Map.copyOf(attributes);
        this.attributeNames = List.copyOf(attributes.keySet());
        this.children = List.copyOf(children);
    }

    public String qualifiedName() { return name; }
    public String namespaceUri() { return namespace == null ? "" : namespace; }
    public String text() { return text; }
    public Object[] attributeNames() { return attributeNames.toArray(); }
    public String attribute(final String name) { return attributes.get(name); }
    public int childCount() { return children.size(); }
    public XmlNode child(final int index) {
        return index >= 0 && index < children.size() ? children.get(index) : null;
    }

    static XmlNode from(final org.w3c.dom.Element element) {
        final var attributes = new LinkedHashMap<String, String>();
        final var attributeNodes = element.getAttributes();
        for (var index = 0; index < attributeNodes.getLength(); index++) {
            final var attribute = attributeNodes.item(index);
            attributes.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        final var children = new ArrayList<XmlNode>();
        final var text = new StringBuilder();
        final var childNodes = element.getChildNodes();
        for (var index = 0; index < childNodes.getLength(); index++) {
            final var child = childNodes.item(index);
            if (child instanceof org.w3c.dom.Element childElement) {
                children.add(from(childElement));
            } else if (child instanceof org.w3c.dom.Text textNode) {
                text.append(textNode.getData());
            } else if (child instanceof org.w3c.dom.CDATASection cdata) {
                text.append(cdata.getData());
            }
        }
        return new XmlNode(element.getNodeName(), element.getNamespaceURI(), text.toString(),
                attributes, children);
    }
}

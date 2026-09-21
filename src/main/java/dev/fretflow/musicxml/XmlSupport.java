package dev.fretflow.musicxml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public final class XmlSupport {
    private XmlSupport() { }

    public static Document parse(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    public static List<Element> children(Element parent, String tag) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName(element).equals(tag)) result.add(element);
        }
        return result;
    }

    public static Element child(Element parent, String tag) {
        return children(parent, tag).stream().findFirst().orElse(null);
    }

    public static String childText(Element parent, String tag, String fallback) {
        var child = child(parent, tag);
        return child == null ? fallback : child.getTextContent().trim();
    }

    public static int childInt(Element parent, String tag, int fallback) {
        try {
            return Integer.parseInt(childText(parent, tag, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static String localName(Element element) {
        String local = element.getLocalName();
        if (local != null) return local;
        String name = element.getTagName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}

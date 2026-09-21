package dev.fretflow.musicxml;

import dev.fretflow.model.Fingering;
import dev.fretflow.model.FretPosition;
import dev.fretflow.model.InstrumentConfig;
import dev.fretflow.model.ParsedScore;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MusicXmlAnnotator {
    public byte[] annotate(ParsedScore score, InstrumentConfig instrument, List<Fingering> fingerings) throws Exception {
        Document document = XmlSupport.parse(score.xmlBytes());
        Element root = document.getDocumentElement();
        Element part = findPart(root, score.partId());
        if (part == null) throw new IllegalArgumentException("Selected part disappeared while annotating MusicXML");

        Map<Integer, FretPosition> byOrdinal = new HashMap<>();
        for (var fingering : fingerings) {
            for (var position : fingering.positions()) byOrdinal.put(position.note().ordinal(), position);
        }

        int ordinal = 0;
        for (var measure : XmlSupport.children(part, "measure")) {
            for (var note : XmlSupport.children(measure, "note")) {
                if (XmlSupport.child(note, "pitch") == null) continue;
                var position = byOrdinal.get(ordinal++);
                if (position != null) addTechnical(document, note, position);
            }
        }
        configureTabStaff(document, root, part, score.partId(), instrument);

        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        var output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private Element findPart(Element root, String id) {
        for (var part : XmlSupport.children(root, "part")) if (part.getAttribute("id").equals(id)) return part;
        return null;
    }

    private void addTechnical(Document document, Element note, FretPosition position) {
        Element notations = XmlSupport.child(note, "notations");
        if (notations == null) {
            notations = document.createElement("notations");
            Node insertBefore = firstChildNamed(note, "lyric", "play", "listen");
            if (insertBefore == null) note.appendChild(notations); else note.insertBefore(notations, insertBefore);
        }
        Element technical = XmlSupport.child(notations, "technical");
        if (technical == null) {
            technical = document.createElement("technical");
            notations.appendChild(technical);
        }
        removeChildren(technical, "string");
        removeChildren(technical, "fret");
        appendText(document, technical, "string", String.valueOf(position.stringNumber()));
        appendText(document, technical, "fret", String.valueOf(position.fret()));
    }

    private void configureTabStaff(Document document, Element root, Element part, String partId, InstrumentConfig instrument) {
        var measures = XmlSupport.children(part, "measure");
        if (measures.isEmpty()) return;
        Element firstMeasure = measures.get(0);
        Element attributes = XmlSupport.child(firstMeasure, "attributes");
        if (attributes == null) {
            attributes = document.createElement("attributes");
            Node first = firstMeasure.getFirstChild();
            if (first == null) firstMeasure.appendChild(attributes); else firstMeasure.insertBefore(attributes, first);
        }

        Element clef = XmlSupport.child(attributes, "clef");
        if (clef == null) {
            clef = document.createElement("clef");
            attributes.appendChild(clef);
        }
        setChildText(document, clef, "sign", "TAB");
        setChildText(document, clef, "line", instrument.stringCount() == 4 ? "3" : "5");

        Element details = XmlSupport.child(attributes, "staff-details");
        if (details == null) {
            details = document.createElement("staff-details");
            attributes.appendChild(details);
        }
        removeChildren(details, "staff-lines");
        removeChildren(details, "staff-tuning");
        appendText(document, details, "staff-lines", String.valueOf(instrument.stringCount()));
        for (int lowIndex = 0; lowIndex < instrument.stringCount(); lowIndex++) {
            int midi = instrument.openMidi().get(lowIndex);
            Element tuning = document.createElement("staff-tuning");
            tuning.setAttribute("line", String.valueOf(lowIndex + 1));
            details.appendChild(tuning);
            appendText(document, tuning, "tuning-step", stepFor(midi));
            int alter = alterFor(midi);
            if (alter != 0) appendText(document, tuning, "tuning-alter", String.valueOf(alter));
            appendText(document, tuning, "tuning-octave", String.valueOf(midi / 12 - 1));
        }

        var partList = XmlSupport.child(root, "part-list");
        if (partList != null) {
            for (var scorePart : XmlSupport.children(partList, "score-part")) {
                if (!scorePart.getAttribute("id").equals(partId)) continue;
                var name = XmlSupport.child(scorePart, "part-name");
                if (name != null && !name.getTextContent().contains("TAB")) name.setTextContent(name.getTextContent() + " TAB");
            }
        }
    }

    private String stepFor(int midi) {
        return switch (Math.floorMod(midi, 12)) {
            case 0, 1 -> "C";
            case 2, 3 -> "D";
            case 4 -> "E";
            case 5, 6 -> "F";
            case 7, 8 -> "G";
            case 9, 10 -> "A";
            default -> "B";
        };
    }

    private int alterFor(int midi) {
        return switch (Math.floorMod(midi, 12)) {
            case 1, 3, 6, 8, 10 -> 1;
            default -> 0;
        };
    }

    private void setChildText(Document document, Element parent, String tag, String value) {
        var child = XmlSupport.child(parent, tag);
        if (child == null) {
            child = document.createElement(tag);
            parent.appendChild(child);
        }
        child.setTextContent(value);
    }

    private void appendText(Document document, Element parent, String tag, String value) {
        var child = document.createElement(tag);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private void removeChildren(Element parent, String tag) {
        for (var child : List.copyOf(XmlSupport.children(parent, tag))) parent.removeChild(child);
    }

    private Node firstChildNamed(Element parent, String... names) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) continue;
            for (String name : names) if (XmlSupport.localName(element).equals(name)) return element;
        }
        return null;
    }
}

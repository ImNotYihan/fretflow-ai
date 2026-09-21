package dev.fretflow.musicxml;

import dev.fretflow.model.NoteEvent;
import dev.fretflow.model.ParsedScore;
import dev.fretflow.model.Pitch;
import dev.fretflow.model.ScoreNote;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

public final class MusicXmlParser {
    private static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;

    public ParsedScore parse(byte[] upload) throws Exception {
        if (upload == null || upload.length == 0) throw new IllegalArgumentException("The uploaded file is empty");
        byte[] xml = isZip(upload) ? extractMxl(upload) : upload;
        var document = XmlSupport.parse(xml);
        var root = document.getDocumentElement();
        if (!XmlSupport.localName(root).equals("score-partwise")) {
            throw new IllegalArgumentException("Only MusicXML score-partwise files are supported");
        }

        var partNames = readPartNames(root);
        var parts = XmlSupport.children(root, "part");
        if (parts.isEmpty()) throw new IllegalArgumentException("No <part> was found in the MusicXML file");
        Element selected = choosePart(parts, partNames);
        String partId = selected.getAttribute("id");
        String partName = partNames.getOrDefault(partId, partId.isBlank() ? "Selected part" : partId);
        String title = scoreTitle(root);

        var warnings = new ArrayList<String>();
        if (parts.size() > 1) warnings.add("The score contains " + parts.size() + " parts; converted “" + partName + "”.");
        var events = parseEvents(selected, warnings);
        if (events.isEmpty()) throw new IllegalArgumentException("The selected part does not contain pitched notes");
        return new ParsedScore(title, partId, partName, events, xml, warnings);
    }

    private Map<String, String> readPartNames(Element root) {
        var names = new HashMap<String, String>();
        var partList = XmlSupport.child(root, "part-list");
        if (partList == null) return names;
        for (var scorePart : XmlSupport.children(partList, "score-part")) {
            names.put(scorePart.getAttribute("id"), XmlSupport.childText(scorePart, "part-name", scorePart.getAttribute("id")));
        }
        return names;
    }

    private Element choosePart(List<Element> parts, Map<String, String> partNames) {
        return parts.stream().max(Comparator.comparingInt(part -> {
            String name = partNames.getOrDefault(part.getAttribute("id"), "").toLowerCase();
            int frettedBonus = name.matches(".*(guitar|bass|\\u5409\\u4ed6|\\u8d1d\\u65af).*" ) ? 1_000_000 : 0;
            return frettedBonus + countPitchedNotes(part);
        })).orElse(parts.get(0));
    }

    private int countPitchedNotes(Element part) {
        int count = 0;
        for (var measure : XmlSupport.children(part, "measure")) {
            for (var note : XmlSupport.children(measure, "note")) if (XmlSupport.child(note, "pitch") != null) count++;
        }
        return count;
    }

    private List<NoteEvent> parseEvents(Element part, List<String> warnings) {
        var result = new ArrayList<NoteEvent>();
        int ordinal = 0;
        int eventIndex = 0;
        for (var measure : XmlSupport.children(part, "measure")) {
            String number = measure.getAttribute("number");
            if (number.isBlank()) number = String.valueOf(result.size() + 1);
            int cursor = 0;
            int lastStart = 0;
            var byOffset = new LinkedHashMap<Integer, EventBuilder>();

            for (var item = measure.getFirstChild(); item != null; item = item.getNextSibling()) {
                if (!(item instanceof Element element)) continue;
                String kind = XmlSupport.localName(element);
                if (kind.equals("backup")) {
                    cursor = Math.max(0, cursor - XmlSupport.childInt(element, "duration", 0));
                    continue;
                }
                if (kind.equals("forward")) {
                    cursor += Math.max(0, XmlSupport.childInt(element, "duration", 0));
                    continue;
                }
                if (!kind.equals("note")) continue;

                int duration = Math.max(0, XmlSupport.childInt(element, "duration", 0));
                boolean chord = XmlSupport.child(element, "chord") != null;
                int start = chord ? lastStart : cursor;
                var pitchElement = XmlSupport.child(element, "pitch");
                if (pitchElement != null) {
                    String step = XmlSupport.childText(pitchElement, "step", "C");
                    int alter = XmlSupport.childInt(pitchElement, "alter", 0);
                    int octave = XmlSupport.childInt(pitchElement, "octave", 4);
                    var note = new ScoreNote(ordinal++, Pitch.of(step, alter, octave));
                    final int eventDuration = duration;
                    byOffset.computeIfAbsent(start, ignored -> new EventBuilder(start, eventDuration)).notes.add(note);
                }
                if (!chord) {
                    lastStart = start;
                    cursor += duration;
                }
            }

            var ordered = byOffset.values().stream().sorted(Comparator.comparingInt(e -> e.offset)).toList();
            for (var builder : ordered) {
                result.add(new NoteEvent(eventIndex++, number, builder.offset, Math.max(1, builder.duration), List.copyOf(builder.notes)));
            }
        }
        if (result.stream().anyMatch(e -> e.notes().size() > 6)) {
            warnings.add("Some chords contain more than six notes and may not fit the selected instrument.");
        }
        return result;
    }

    private String scoreTitle(Element root) {
        var work = XmlSupport.child(root, "work");
        String title = work == null ? "" : XmlSupport.childText(work, "work-title", "");
        if (title.isBlank()) {
            var movement = XmlSupport.child(root, "movement-title");
            if (movement != null) title = movement.getTextContent().trim();
        }
        return title.isBlank() ? "Untitled score" : title;
    }

    private boolean isZip(byte[] data) {
        return data.length >= 4 && data[0] == 'P' && data[1] == 'K';
    }

    private byte[] extractMxl(byte[] data) throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                var out = new ByteArrayOutputStream();
                zip.transferTo(out);
                total += out.size();
                if (total > MAX_UNCOMPRESSED_BYTES) throw new IllegalArgumentException("Compressed MusicXML expands beyond 16 MB");
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        String rootPath = null;
        var container = entries.get("META-INF/container.xml");
        if (container != null) {
            var doc = XmlSupport.parse(container);
            var rootfiles = doc.getElementsByTagName("rootfile");
            if (rootfiles.getLength() > 0) rootPath = ((Element) rootfiles.item(0)).getAttribute("full-path");
        }
        if (rootPath != null && entries.containsKey(rootPath)) return entries.get(rootPath);
        return entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(".musicxml") || e.getKey().endsWith(".xml"))
                .filter(e -> !e.getKey().equals("META-INF/container.xml"))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No MusicXML score was found inside the .mxl file"));
    }

    private static final class EventBuilder {
        private final int offset;
        private final int duration;
        private final List<ScoreNote> notes = new ArrayList<>();

        private EventBuilder(int offset, int duration) {
            this.offset = offset;
            this.duration = duration;
        }
    }
}

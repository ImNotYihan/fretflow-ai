package dev.fretflow.musicxml;

import dev.fretflow.model.NoteEvent;
import dev.fretflow.model.ParsedScore;
import dev.fretflow.model.Pitch;
import dev.fretflow.model.ScoreNote;
import dev.fretflow.model.Technique;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        if (events.isEmpty()) throw new IllegalArgumentException("The selected part does not contain playable notes");
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
            return frettedBonus + countPlayableNotes(part);
        })).orElse(parts.get(0));
    }

    private int countPlayableNotes(Element part) {
        int count = 0;
        for (var measure : XmlSupport.children(part, "measure")) {
            for (var note : XmlSupport.children(measure, "note")) {
                if (XmlSupport.child(note, "pitch") != null || isMutedNote(note)) count++;
            }
        }
        return count;
    }

    private List<NoteEvent> parseEvents(Element part, List<String> warnings) {
        var result = new ArrayList<NoteEvent>();
        int ordinal = 0;
        int eventIndex = 0;
        int skippedUnpitched = 0;
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
                var techniques = parseTechniques(element);
                Pitch pitch = null;
                if (pitchElement != null) {
                    String step = XmlSupport.childText(pitchElement, "step", "C");
                    int alter = XmlSupport.childInt(pitchElement, "alter", 0);
                    int octave = XmlSupport.childInt(pitchElement, "octave", 4);
                    pitch = Pitch.of(step, alter, octave);
                }
                boolean muted = techniques.stream().anyMatch(technique ->
                        technique.kind() == Technique.Kind.DEAD_NOTE || technique.kind() == Technique.Kind.GHOST_NOTE);
                if (pitch != null || muted) {
                    var sourcePosition = sourcePosition(element);
                    var note = new ScoreNote(ordinal++, pitch, techniques, sourcePosition.stringNumber(), sourcePosition.fret());
                    final int eventDuration = duration;
                    byOffset.computeIfAbsent(start, ignored -> new EventBuilder(start, eventDuration)).notes.add(note);
                } else if (XmlSupport.child(element, "unpitched") != null) {
                    skippedUnpitched++;
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
        if (skippedUnpitched > 0) {
            warnings.add("Skipped " + skippedUnpitched + " unpitched note(s) without a dead/ghost-note mark.");
        }
        return result;
    }

    private List<Technique> parseTechniques(Element note) {
        var techniques = new ArrayList<Technique>();
        Element notehead = XmlSupport.child(note, "notehead");
        if (notehead != null) {
            String shape = normalizeMark(notehead.getTextContent());
            boolean parenthesized = notehead.getAttribute("parentheses").equalsIgnoreCase("yes");
            if (shape.equals("x") || shape.equals("circle x") || shape.equals("square x")) {
                addTechnique(techniques, new Technique(parenthesized
                        ? Technique.Kind.GHOST_NOTE : Technique.Kind.DEAD_NOTE));
            } else if (parenthesized) {
                addTechnique(techniques, new Technique(Technique.Kind.GHOST_NOTE));
            }
        }

        Element notations = XmlSupport.child(note, "notations");
        if (notations == null) return List.copyOf(techniques);
        Element technical = XmlSupport.child(notations, "technical");
        if (technical != null) {
            for (Element mark : XmlSupport.children(technical, "hammer-on")) {
                addTechnique(techniques, connection(Technique.Kind.HAMMER_ON, mark));
            }
            for (Element mark : XmlSupport.children(technical, "pull-off")) {
                addTechnique(techniques, connection(Technique.Kind.PULL_OFF, mark));
            }
            for (Element mark : XmlSupport.children(technical, "harmonic")) {
                String detail = XmlSupport.child(mark, "artificial") != null ? "artificial"
                        : XmlSupport.child(mark, "natural") != null ? "natural" : "";
                addTechnique(techniques, new Technique(Technique.Kind.HARMONIC,
                        Technique.Phase.SINGLE, 1, detail));
            }
            if (XmlSupport.child(technical, "snap-pizzicato") != null) {
                addTechnique(techniques, new Technique(Technique.Kind.SLAP,
                        Technique.Phase.SINGLE, 1, "snap-pizzicato"));
            }
            for (Element mark : XmlSupport.children(technical, "pluck")) {
                addNamedTechnique(techniques, mark.getTextContent(), false);
            }
            for (Element mark : XmlSupport.children(technical, "other-technical")) {
                addNamedTechnique(techniques, mark.getTextContent(), true);
            }
        }
        for (Element mark : XmlSupport.children(notations, "slide")) {
            addTechnique(techniques, connection(Technique.Kind.SLIDE, mark));
        }
        return List.copyOf(techniques);
    }

    private Technique connection(Technique.Kind kind, Element mark) {
        return new Technique(kind, Technique.phaseFromMusicXml(mark.getAttribute("type")),
                integerAttribute(mark, "number", 1), mark.getTextContent().trim());
    }

    private void addNamedTechnique(List<Technique> techniques, String value, boolean allowShortNames) {
        String mark = normalizeMark(value);
        if (mark.equals("slap") || mark.equals("slap thumb") || mark.equals("thumb")
                || mark.equals("thumb slap") || (allowShortNames && (mark.equals("s") || mark.equals("t")))) {
            addTechnique(techniques, new Technique(Technique.Kind.SLAP));
        } else if (mark.equals("pop") || mark.equals("popping") || (allowShortNames && mark.equals("p"))) {
            addTechnique(techniques, new Technique(Technique.Kind.POP));
        } else if (allowShortNames && (mark.equals("dead") || mark.equals("dead note")
                || mark.equals("muted") || mark.equals("mute") || mark.equals("x"))) {
            addTechnique(techniques, new Technique(Technique.Kind.DEAD_NOTE));
        } else if (allowShortNames && (mark.equals("ghost") || mark.equals("ghost note") || mark.equals("(x)"))) {
            addTechnique(techniques, new Technique(Technique.Kind.GHOST_NOTE));
        }
    }

    private void addTechnique(List<Technique> techniques, Technique candidate) {
        boolean duplicate = techniques.stream().anyMatch(existing -> existing.kind() == candidate.kind()
                && existing.phase() == candidate.phase() && existing.number() == candidate.number());
        if (!duplicate) techniques.add(candidate);
    }

    private SourcePosition sourcePosition(Element note) {
        Element notations = XmlSupport.child(note, "notations");
        Element technical = notations == null ? null : XmlSupport.child(notations, "technical");
        if (technical == null) return new SourcePosition(null, null);
        Element string = XmlSupport.child(technical, "string");
        Element fret = XmlSupport.child(technical, "fret");
        return new SourcePosition(integerText(string), integerText(fret));
    }

    private Integer integerText(Element element) {
        if (element == null) return null;
        try {
            return Integer.valueOf(element.getTextContent().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int integerAttribute(Element element, String name, int fallback) {
        try {
            return Integer.parseInt(element.getAttribute(name));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalizeMark(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ");
    }

    private boolean isMutedNote(Element note) {
        return parseTechniques(note).stream().anyMatch(technique ->
                technique.kind() == Technique.Kind.DEAD_NOTE || technique.kind() == Technique.Kind.GHOST_NOTE);
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

    private record SourcePosition(Integer stringNumber, Integer fret) { }
}

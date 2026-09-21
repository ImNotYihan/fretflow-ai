package dev.fretflow.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record InstrumentConfig(String type, String name, List<Integer> openMidi, List<String> labels, int maxFret) {
    public InstrumentConfig {
        openMidi = List.copyOf(openMidi);
        labels = List.copyOf(labels);
        if (openMidi.size() != labels.size()) throw new IllegalArgumentException("Tuning labels do not match strings");
    }

    public int stringCount() { return openMidi.size(); }

    public static InstrumentConfig from(String type, String tuning) {
        String normalizedType = type == null ? "guitar" : type.toLowerCase(Locale.ROOT);
        String value = tuning == null ? "standard" : tuning.trim();
        if (value.contains(",")) return custom(normalizedType, value);

        if (normalizedType.equals("bass")) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "drop-d", "dropd" -> preset("bass", "Bass · Drop D", "D1,A1,D2,G2", 24);
                case "five-string", "5-string", "5string" -> preset("bass", "5-string Bass · Standard", "B0,E1,A1,D2,G2", 24);
                default -> preset("bass", "Bass · Standard", "E1,A1,D2,G2", 24);
            };
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "drop-d", "dropd" -> preset("guitar", "Guitar · Drop D", "D2,A2,D3,G3,B3,E4", 24);
            case "dadgad" -> preset("guitar", "Guitar · DADGAD", "D2,A2,D3,G3,A3,D4", 24);
            default -> preset("guitar", "Guitar · Standard", "E2,A2,D3,G3,B3,E4", 24);
        };
    }

    private static InstrumentConfig custom(String type, String tuning) {
        var tokens = Arrays.stream(tuning.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (tokens.size() < 4 || tokens.size() > 8) {
            throw new IllegalArgumentException("Custom tuning must contain 4–8 notes, low to high");
        }
        var midi = new ArrayList<Integer>();
        var labels = new ArrayList<String>();
        for (String token : tokens) {
            var pitch = parsePitch(token);
            midi.add(pitch.midi());
            labels.add(token.replaceAll("-?\\d+$", ""));
        }
        for (int i = 1; i < midi.size(); i++) {
            if (midi.get(i) <= midi.get(i - 1)) throw new IllegalArgumentException("Tuning must be ordered low to high");
        }
        return new InstrumentConfig(type, capitalize(type) + " · Custom", midi, labels, 24);
    }

    private static InstrumentConfig preset(String type, String name, String tuning, int maxFret) {
        var tokens = tuning.split(",");
        var midi = new ArrayList<Integer>();
        var labels = new ArrayList<String>();
        for (String token : tokens) {
            var pitch = parsePitch(token);
            midi.add(pitch.midi());
            labels.add(token.replaceAll("\\d+$", ""));
        }
        return new InstrumentConfig(type, name, midi, labels, maxFret);
    }

    private static Pitch parsePitch(String token) {
        var matcher = java.util.regex.Pattern.compile("(?i)^([A-G])([#b]?)(-?\\d+)$").matcher(token.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid tuning note: " + token);
        int alter = matcher.group(2).equals("#") ? 1 : matcher.group(2).equals("b") ? -1 : 0;
        return Pitch.of(matcher.group(1), alter, Integer.parseInt(matcher.group(3)));
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "Instrument";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}

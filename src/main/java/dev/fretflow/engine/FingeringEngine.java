package dev.fretflow.engine;

import dev.fretflow.model.Fingering;
import dev.fretflow.model.FretPosition;
import dev.fretflow.model.InstrumentConfig;
import dev.fretflow.model.NoteEvent;
import dev.fretflow.model.ParsedScore;
import dev.fretflow.model.ScoreNote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FingeringEngine {
    private static final int MAX_CANDIDATES = 96;

    public EngineResult optimize(ParsedScore score, InstrumentConfig instrument, String style) {
        var warnings = new ArrayList<String>();
        var allCandidates = new ArrayList<List<Fingering>>();
        for (var event : score.events()) {
            var candidates = candidatesFor(event, instrument, style);
            if (candidates.isEmpty()) {
                String pitches = event.notes().stream().map(n -> n.pitch().displayName()).reduce((a, b) -> a + ", " + b).orElse("note");
                warnings.add("Measure " + event.measure() + ": no valid fingering for " + pitches + ".");
                candidates = List.of(new Fingering(event.index(), List.of(), 500));
            }
            allCandidates.add(candidates);
        }
        return new EngineResult(dynamicProgramming(allCandidates, style), allCandidates, warnings);
    }

    public List<Fingering> candidatesFor(NoteEvent event, InstrumentConfig instrument, String style) {
        var noteOptions = new ArrayList<List<FretPosition>>();
        for (ScoreNote note : event.notes()) {
            var options = new ArrayList<FretPosition>();
            for (int lowIndex = 0; lowIndex < instrument.stringCount(); lowIndex++) {
                int fret = note.pitch().midi() - instrument.openMidi().get(lowIndex);
                if (fret >= 0 && fret <= instrument.maxFret()) {
                    int stringNumber = instrument.stringCount() - lowIndex;
                    options.add(new FretPosition(stringNumber, fret, note));
                }
            }
            if (options.isEmpty()) return List.of();
            noteOptions.add(options);
        }

        var raw = new ArrayList<List<FretPosition>>();
        buildCombinations(noteOptions, 0, new ArrayList<>(), new HashSet<>(), raw);
        return raw.stream()
                .map(positions -> new Fingering(event.index(), positions, localCost(positions, style)))
                .sorted(Comparator.comparingDouble(Fingering::localCost))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private void buildCombinations(List<List<FretPosition>> options, int noteIndex, List<FretPosition> current,
                                   Set<Integer> usedStrings, List<List<FretPosition>> result) {
        if (result.size() >= MAX_CANDIDATES * 4) return;
        if (noteIndex == options.size()) {
            result.add(List.copyOf(current));
            return;
        }
        for (var position : options.get(noteIndex)) {
            if (!usedStrings.add(position.stringNumber())) continue;
            current.add(position);
            buildCombinations(options, noteIndex + 1, current, usedStrings, result);
            current.remove(current.size() - 1);
            usedStrings.remove(position.stringNumber());
        }
    }

    private double localCost(List<FretPosition> positions, String styleName) {
        String style = styleName == null ? "balanced" : styleName.toLowerCase(Locale.ROOT);
        var frets = positions.stream().filter(p -> p.fret() > 0).mapToInt(FretPosition::fret).toArray();
        int span = frets.length < 2 ? 0 : Arrays.stream(frets).max().orElse(0) - Arrays.stream(frets).min().orElse(0);
        double average = frets.length == 0 ? 0 : Arrays.stream(frets).average().orElse(0);
        int open = (int) positions.stream().filter(p -> p.fret() == 0).count();
        int minString = positions.stream().mapToInt(FretPosition::stringNumber).min().orElse(1);
        int maxString = positions.stream().mapToInt(FretPosition::stringNumber).max().orElse(1);
        int skippedStrings = Math.max(0, (maxString - minString + 1) - positions.size());

        double cost = span * span * 1.8 + skippedStrings * 1.1 + Math.max(0, average - 12) * 0.3;
        if (span > 4) cost += (span - 4) * 8;
        switch (style) {
            case "beginner" -> cost += average * 0.16 - open * 0.7 + span * 1.2;
            case "low-fret" -> cost += average * 0.28 - open * 0.35;
            case "compact" -> cost += span * 2.4 + open * 0.15;
            default -> cost += average * 0.07 - open * 0.2;
        }
        return cost;
    }

    private List<Fingering> dynamicProgramming(List<List<Fingering>> layers, String style) {
        if (layers.isEmpty()) return List.of();
        var costs = new ArrayList<double[]>();
        var previous = new ArrayList<int[]>();
        for (int i = 0; i < layers.size(); i++) {
            var currentLayer = layers.get(i);
            double[] currentCosts = new double[currentLayer.size()];
            int[] currentPrevious = new int[currentLayer.size()];
            Arrays.fill(currentCosts, Double.POSITIVE_INFINITY);
            Arrays.fill(currentPrevious, -1);
            if (i == 0) {
                for (int j = 0; j < currentLayer.size(); j++) currentCosts[j] = currentLayer.get(j).localCost();
            } else {
                var priorLayer = layers.get(i - 1);
                var priorCosts = costs.get(i - 1);
                for (int j = 0; j < currentLayer.size(); j++) {
                    for (int k = 0; k < priorLayer.size(); k++) {
                        double candidate = priorCosts[k] + currentLayer.get(j).localCost()
                                + transitionCost(priorLayer.get(k), currentLayer.get(j), style);
                        if (candidate < currentCosts[j]) {
                            currentCosts[j] = candidate;
                            currentPrevious[j] = k;
                        }
                    }
                }
            }
            costs.add(currentCosts);
            previous.add(currentPrevious);
        }

        int selected = indexOfMinimum(costs.get(costs.size() - 1));
        var reversed = new ArrayList<Fingering>();
        for (int layer = layers.size() - 1; layer >= 0; layer--) {
            reversed.add(layers.get(layer).get(selected));
            selected = previous.get(layer)[selected];
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private double transitionCost(Fingering from, Fingering to, String styleName) {
        if (from.positions().isEmpty() || to.positions().isEmpty()) return 0;
        double shift = Math.abs(from.handPosition() - to.handPosition());
        double weight = "beginner".equalsIgnoreCase(styleName) ? 1.9 : 1.25;
        double cost = shift * weight;
        if (shift > 5) cost += (shift - 5) * 2.5;
        double fromString = from.positions().stream().mapToInt(FretPosition::stringNumber).average().orElse(1);
        double toString = to.positions().stream().mapToInt(FretPosition::stringNumber).average().orElse(1);
        return cost + Math.abs(fromString - toString) * 0.25;
    }

    private int indexOfMinimum(double[] values) {
        int index = 0;
        for (int i = 1; i < values.length; i++) if (values[i] < values[index]) index = i;
        return index;
    }

    public record EngineResult(List<Fingering> selected, List<List<Fingering>> candidates, List<String> warnings) { }
}

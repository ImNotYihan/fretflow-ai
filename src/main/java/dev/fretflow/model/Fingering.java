package dev.fretflow.model;

import java.util.Comparator;
import java.util.List;

public record Fingering(int eventIndex, List<FretPosition> positions, double localCost) {
    public Fingering {
        positions = positions.stream()
                .sorted(Comparator.comparingInt(FretPosition::stringNumber))
                .toList();
    }

    public double handPosition() {
        return positions.stream().filter(p -> p.fret() > 0).mapToInt(FretPosition::fret).average().orElse(0);
    }

    public int fretSpan() {
        var fretted = positions.stream().filter(p -> p.fret() > 0).mapToInt(FretPosition::fret).toArray();
        if (fretted.length < 2) return 0;
        return java.util.Arrays.stream(fretted).max().orElse(0) - java.util.Arrays.stream(fretted).min().orElse(0);
    }

    public String compact() {
        return positions.stream().map(FretPosition::compact).reduce((a, b) -> a + "," + b).orElse("-");
    }
}


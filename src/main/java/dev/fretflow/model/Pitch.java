package dev.fretflow.model;

public record Pitch(String step, int alter, int octave, int midi) {
    public static Pitch of(String step, int alter, int octave) {
        int semitone = switch (step.toUpperCase()) {
            case "C" -> 0;
            case "D" -> 2;
            case "E" -> 4;
            case "F" -> 5;
            case "G" -> 7;
            case "A" -> 9;
            case "B" -> 11;
            default -> throw new IllegalArgumentException("Unknown pitch step: " + step);
        };
        return new Pitch(step.toUpperCase(), alter, octave, (octave + 1) * 12 + semitone + alter);
    }

    public String displayName() {
        String accidental = alter == 0 ? "" : alter > 0 ? "#".repeat(alter) : "b".repeat(-alter);
        return step + accidental + octave;
    }
}


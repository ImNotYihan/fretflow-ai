package dev.fretflow.model;

import java.util.List;

public record ScoreNote(
        int ordinal,
        Pitch pitch,
        List<Technique> techniques,
        Integer sourceString,
        Integer sourceFret
) {
    public ScoreNote {
        techniques = techniques == null ? List.of() : List.copyOf(techniques);
    }

    public ScoreNote(int ordinal, Pitch pitch) {
        this(ordinal, pitch, List.of(), null, null);
    }

    public boolean hasTechnique(Technique.Kind kind) {
        return techniques.stream().anyMatch(technique -> technique.kind() == kind);
    }

    public boolean isMuted() {
        return hasTechnique(Technique.Kind.DEAD_NOTE) || hasTechnique(Technique.Kind.GHOST_NOTE);
    }

    public String displayName() {
        return pitch == null ? "muted note" : pitch.displayName();
    }
}

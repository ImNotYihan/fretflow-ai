package dev.fretflow.model;

import java.util.List;

public record NoteEvent(
        int index,
        String measure,
        int offset,
        int duration,
        List<ScoreNote> notes
) { }


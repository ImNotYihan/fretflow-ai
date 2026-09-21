package dev.fretflow.model;

import java.util.List;

public record ParsedScore(
        String title,
        String partId,
        String partName,
        List<NoteEvent> events,
        byte[] xmlBytes,
        List<String> warnings
) { }


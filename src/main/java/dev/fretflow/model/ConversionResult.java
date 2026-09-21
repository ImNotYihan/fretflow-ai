package dev.fretflow.model;

import java.util.List;

public record ConversionResult(
        ParsedScore score,
        InstrumentConfig instrument,
        List<Fingering> fingerings,
        String asciiTab,
        byte[] annotatedMusicXml,
        String aiStatus,
        List<String> warnings
) { }


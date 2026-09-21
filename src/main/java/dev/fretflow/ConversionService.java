package dev.fretflow;

import dev.fretflow.ai.AiOptimizer;
import dev.fretflow.engine.FingeringEngine;
import dev.fretflow.model.ConversionResult;
import dev.fretflow.model.InstrumentConfig;
import dev.fretflow.musicxml.MusicXmlAnnotator;
import dev.fretflow.musicxml.MusicXmlParser;
import dev.fretflow.render.TabRenderer;

import java.util.ArrayList;
import java.util.List;

public final class ConversionService {
    private final MusicXmlParser parser = new MusicXmlParser();
    private final FingeringEngine engine = new FingeringEngine();
    private final AiOptimizer ai = new AiOptimizer();
    private final TabRenderer renderer = new TabRenderer();
    private final MusicXmlAnnotator annotator = new MusicXmlAnnotator();

    public ConversionResult convert(byte[] input, RequestOptions options) throws Exception {
        var score = parser.parse(input);
        var instrument = InstrumentConfig.from(options.instrument(), options.tuning());
        var local = engine.optimize(score, instrument, options.style());
        var aiResult = ai.optimize(score, instrument, local.selected(), local.candidates(),
                new AiOptimizer.AiSettings(options.aiProvider(), options.apiKey(), options.aiBaseUrl(), options.aiModel(), options.style()));
        var warnings = new ArrayList<String>();
        warnings.addAll(score.warnings());
        warnings.addAll(local.warnings());
        warnings.addAll(aiResult.warnings());
        String ascii = renderer.render(score, instrument, aiResult.fingerings());
        byte[] annotated = annotator.annotate(score, instrument, aiResult.fingerings());
        return new ConversionResult(score, instrument, aiResult.fingerings(), ascii, annotated, aiResult.status(), List.copyOf(warnings));
    }

    public record RequestOptions(String instrument, String tuning, String style, String aiProvider,
                                 String apiKey, String aiBaseUrl, String aiModel) { }
}

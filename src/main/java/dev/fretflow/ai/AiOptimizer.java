package dev.fretflow.ai;

import dev.fretflow.model.Fingering;
import dev.fretflow.model.InstrumentConfig;
import dev.fretflow.model.ParsedScore;
import dev.fretflow.util.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AiOptimizer {
    private static final int EVENTS_PER_REQUEST = 60;
    private static final int MAX_AI_EVENTS = 240;
    private static final int OPTIONS_PER_EVENT = 10;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();

    public AiResult optimize(ParsedScore score, InstrumentConfig instrument, List<Fingering> baseline,
                             List<List<Fingering>> candidates, AiSettings settings) {
        if (settings.provider() == null || settings.provider().equalsIgnoreCase("none") || settings.apiKey().isBlank()) {
            return new AiResult(baseline, "Smart algorithm", List.of());
        }
        try {
            URI endpoint = endpoint(settings);
            String model = model(settings);
            var selected = new ArrayList<>(baseline);
            int limit = Math.min(score.events().size(), MAX_AI_EVENTS);
            int accepted = 0;
            for (int start = 0; start < limit; start += EVENTS_PER_REQUEST) {
                int end = Math.min(limit, start + EVENTS_PER_REQUEST);
                String prompt = prompt(score, instrument, candidates, settings.style(), start, end);
                String content = call(endpoint, model, settings.apiKey(), prompt);
                Map<Integer, Integer> choices = parseChoices(content);
                for (var choice : choices.entrySet()) {
                    int event = choice.getKey();
                    int option = choice.getValue();
                    if (event >= start && event < end && option >= 0 && option < Math.min(OPTIONS_PER_EVENT, candidates.get(event).size())) {
                        selected.set(event, candidates.get(event).get(option));
                        accepted++;
                    }
                }
            }
            var warnings = new ArrayList<String>();
            if (score.events().size() > MAX_AI_EVENTS) {
                warnings.add("AI optimized the first " + MAX_AI_EVENTS + " events; the remaining events use the local ergonomic algorithm.");
            }
            if (accepted == 0) warnings.add("The AI response contained no valid candidate choices; local fingerings were retained.");
            return new AiResult(List.copyOf(selected), "AI refined · " + model, warnings);
        } catch (Exception error) {
            return new AiResult(baseline, "AI fallback · Smart algorithm",
                    List.of("AI optimization was unavailable (" + safeMessage(error) + "); local fingerings were retained."));
        }
    }

    private URI endpoint(AiSettings settings) {
        String base = settings.baseUrl().trim();
        if (base.isBlank()) {
            base = settings.provider().equalsIgnoreCase("deepseek")
                    ? "https://api.deepseek.com" : "https://api.openai.com/v1";
        }
        base = base.replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) return URI.create(base);
        URI uri = URI.create(base + "/chat/completions");
        if (!uri.getScheme().equalsIgnoreCase("https") && !isLocal(uri.getHost())) {
            throw new IllegalArgumentException("Custom AI endpoints must use HTTPS");
        }
        return uri;
    }

    private boolean isLocal(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private String model(AiSettings settings) {
        if (!settings.model().isBlank()) return settings.model().trim();
        return settings.provider().equalsIgnoreCase("deepseek") ? "deepseek-chat" : "gpt-4.1-mini";
    }

    private String prompt(ParsedScore score, InstrumentConfig instrument, List<List<Fingering>> candidates,
                          String style, int start, int end) {
        var prompt = new StringBuilder();
        prompt.append("You are an expert ").append(instrument.type()).append(" fingering editor. ")
                .append("Choose exactly one listed candidate per musical event. Optimize for a natural hand shape, minimal position shifts, ")
                .append("playability for a ").append(style).append(" player, and musical phrasing. Never invent a candidate. ")
                .append("Return only lines in the form E<number>=<option>, with no prose.\n")
                .append("Tuning low-to-high MIDI: ").append(instrument.openMidi()).append(".\n");
        for (int eventIndex = start; eventIndex < end; eventIndex++) {
            var event = score.events().get(eventIndex);
            prompt.append('E').append(eventIndex).append(" measure=").append(event.measure()).append(" notes=")
                    .append(event.notes().stream().map(n -> n.pitch().displayName()).toList()).append(" options ");
            int optionLimit = Math.min(OPTIONS_PER_EVENT, candidates.get(eventIndex).size());
            for (int option = 0; option < optionLimit; option++) {
                if (option > 0) prompt.append(" | ");
                prompt.append(option).append('=').append(candidates.get(eventIndex).get(option).compact());
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private String call(URI endpoint, String model, String apiKey, String prompt) throws Exception {
        String body = "{\"model\":" + JsonUtil.quote(model)
                + ",\"messages\":[{\"role\":\"system\",\"content\":\"Return only validated fingering candidate selections.\"},"
                + "{\"role\":\"user\",\"content\":" + JsonUtil.quote(prompt) + "}],\"temperature\":0.2}";
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("provider returned HTTP " + response.statusCode());
        }
        String content = extractJsonString(response.body(), "content");
        if (content == null) throw new IllegalStateException("provider response had no message content");
        return content;
    }

    private Map<Integer, Integer> parseChoices(String content) {
        var result = new HashMap<Integer, Integer>();
        var matcher = Pattern.compile("(?m)^\\s*E(\\d+)\\s*=\\s*(\\d+)\\s*$").matcher(content);
        while (matcher.find()) result.put(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        return result;
    }

    private String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyAt = json.indexOf(marker);
        while (keyAt >= 0) {
            int colon = json.indexOf(':', keyAt + marker.length());
            int quote = colon < 0 ? -1 : json.indexOf('"', colon + 1);
            if (quote >= 0) {
                var out = new StringBuilder();
                boolean escaped = false;
                for (int i = quote + 1; i < json.length(); i++) {
                    char c = json.charAt(i);
                    if (escaped) {
                        switch (c) {
                            case 'n' -> out.append('\n');
                            case 'r' -> out.append('\r');
                            case 't' -> out.append('\t');
                            case 'b' -> out.append('\b');
                            case 'f' -> out.append('\f');
                            case 'u' -> {
                                if (i + 4 < json.length()) {
                                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                    i += 4;
                                }
                            }
                            default -> out.append(c);
                        }
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        return out.toString();
                    } else {
                        out.append(c);
                    }
                }
            }
            keyAt = json.indexOf(marker, keyAt + marker.length());
        }
        return null;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        String cleaned = message.replaceAll("[\\r\\n]+", " ");
        return cleaned.substring(0, Math.min(120, cleaned.length()));
    }

    public record AiSettings(String provider, String apiKey, String baseUrl, String model, String style) {
        public AiSettings {
            provider = provider == null ? "none" : provider.toLowerCase(Locale.ROOT);
            apiKey = apiKey == null ? "" : apiKey;
            baseUrl = baseUrl == null ? "" : baseUrl;
            model = model == null ? "" : model;
            style = style == null ? "balanced" : style;
        }
    }

    public record AiResult(List<Fingering> fingerings, String status, List<String> warnings) { }
}

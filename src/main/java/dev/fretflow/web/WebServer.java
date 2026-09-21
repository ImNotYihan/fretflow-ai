package dev.fretflow.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.fretflow.ConversionService;
import dev.fretflow.model.ConversionResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

public final class WebServer {
    private static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private final HttpServer server;
    private final ConversionService conversion = new ConversionService();

    public WebServer(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/health", this::health);
        server.createContext("/api/convert", this::convert);
        server.createContext("/", this::staticFile);
    }

    public void start() { server.start(); }

    public int port() { return server.getAddress().getPort(); }

    private void health(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\",\"app\":\"FretFlow AI\"}");
    }

    private void convert(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            byte[] body = readLimited(exchange);
            var headers = exchange.getRequestHeaders();
            var options = new ConversionService.RequestOptions(
                    header(headers.getFirst("X-Instrument"), "guitar"),
                    header(headers.getFirst("X-Tuning"), "standard"),
                    header(headers.getFirst("X-Style"), "balanced"),
                    header(headers.getFirst("X-AI-Provider"), "none"),
                    header(headers.getFirst("X-API-Key"), ""),
                    header(headers.getFirst("X-AI-Base-URL"), ""),
                    header(headers.getFirst("X-AI-Model"), "")
            );
            var result = conversion.convert(body, options);
            sendJson(exchange, 200, resultJson(result));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, "{\"error\":" + JsonUtil.quote(message(error)) + "}");
        } catch (Exception error) {
            error.printStackTrace(System.err);
            sendJson(exchange, 500, "{\"error\":" + JsonUtil.quote("Conversion failed: " + message(error)) + "}");
        }
    }

    private byte[] readLimited(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared) > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("File is larger than the 8 MB limit");
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid Content-Length header");
            }
        }
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                if (total > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("File is larger than the 8 MB limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private String resultJson(ConversionResult result) {
        int noteCount = result.score().events().stream().mapToInt(e -> e.notes().size()).sum();
        long measureCount = result.score().events().stream().map(e -> e.measure()).distinct().count();
        double averageFret = result.fingerings().stream().flatMap(f -> f.positions().stream())
                .filter(p -> p.fret() > 0).mapToInt(p -> p.fret()).average().orElse(0);
        var json = new StringBuilder("{");
        json.append("\"title\":").append(JsonUtil.quote(result.score().title())).append(',');
        json.append("\"partName\":").append(JsonUtil.quote(result.score().partName())).append(',');
        json.append("\"instrument\":").append(JsonUtil.quote(result.instrument().name())).append(',');
        json.append("\"aiStatus\":").append(JsonUtil.quote(result.aiStatus())).append(',');
        json.append("\"stats\":{\"notes\":").append(noteCount)
                .append(",\"events\":").append(result.score().events().size())
                .append(",\"measures\":").append(measureCount)
                .append(",\"averageFret\":").append(String.format(Locale.ROOT, "%.1f", averageFret)).append("},");
        json.append("\"warnings\":[");
        for (int i = 0; i < result.warnings().size(); i++) {
            if (i > 0) json.append(',');
            json.append(JsonUtil.quote(result.warnings().get(i)));
        }
        json.append("],\"events\":[");
        for (int i = 0; i < result.score().events().size(); i++) {
            if (i > 0) json.append(',');
            var event = result.score().events().get(i);
            var fingering = result.fingerings().get(i);
            json.append("{\"index\":").append(event.index())
                    .append(",\"measure\":").append(JsonUtil.quote(event.measure()))
                    .append(",\"notes\":[");
            for (int n = 0; n < event.notes().size(); n++) {
                if (n > 0) json.append(',');
                json.append(JsonUtil.quote(event.notes().get(n).pitch().displayName()));
            }
            json.append("],\"positions\":[");
            for (int p = 0; p < fingering.positions().size(); p++) {
                if (p > 0) json.append(',');
                var position = fingering.positions().get(p);
                json.append("{\"string\":").append(position.stringNumber())
                        .append(",\"fret\":").append(position.fret())
                        .append(",\"pitch\":").append(JsonUtil.quote(position.note().pitch().displayName())).append('}');
            }
            json.append("]}");
        }
        json.append("],\"asciiTab\":").append(JsonUtil.quote(result.asciiTab())).append(',');
        json.append("\"musicXmlBase64\":").append(JsonUtil.quote(Base64.getEncoder().encodeToString(result.annotatedMusicXml())));
        return json.append('}').toString();
    }

    private void staticFile(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        Set<String> allowed = new HashSet<>(Set.of("/index.html", "/app.css", "/app.js", "/favicon.svg"));
        if (!allowed.contains(path)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        try (var resource = getClass().getResourceAsStream("/web" + path)) {
            if (resource == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] data = resource.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(path));
            exchange.getResponseHeaders().set("Cache-Control", path.equals("/index.html") ? "no-cache" : "public, max-age=3600");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; connect-src 'self'");
            exchange.sendResponseHeaders(200, exchange.getRequestMethod().equals("HEAD") ? -1 : data.length);
            if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(data);
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private String contentType(String path) {
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "text/html; charset=utf-8";
    }

    private String header(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}

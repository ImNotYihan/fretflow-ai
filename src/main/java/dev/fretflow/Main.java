package dev.fretflow;

import dev.fretflow.web.WebServer;

import java.awt.Desktop;
import java.net.URI;

public final class Main {
    private Main() { }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        boolean openBrowser = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = requireValue(args, ++i, "--host");
                case "--port" -> port = Integer.parseInt(requireValue(args, ++i, "--port"));
                case "--no-browser" -> openBrowser = false;
                case "--help", "-h" -> {
                    System.out.println("Usage: ./run.sh [--host 127.0.0.1] [--port 8080] [--no-browser]");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        var server = new WebServer(host, port);
        server.start();
        String url = "http://" + (host.equals("0.0.0.0") ? "127.0.0.1" : host) + ":" + server.port();
        System.out.println("\n  FretFlow AI is ready: " + url);
        System.out.println("  Press Ctrl+C to stop.\n");
        if (openBrowser && Desktop.isDesktopSupported()) {
            try { Desktop.getDesktop().browse(URI.create(url)); } catch (Exception ignored) { }
        }
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " requires a value");
        return args[index];
    }
}

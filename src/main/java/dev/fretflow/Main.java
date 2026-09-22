package dev.fretflow;

import dev.fretflow.desktop.DesktopApp;

import java.awt.GraphicsEnvironment;

public final class Main {
    public static final String VERSION = "1.0.1";

    private Main() { }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--gui"))) {
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("FretFlow's desktop interface needs a graphical environment.");
                System.err.println("Use --help to see the command-line conversion options.");
                System.exit(2);
            }
            DesktopApp.launch();
            return;
        }

        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-V"))) {
            System.out.println("FretFlow AI " + VERSION);
            return;
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            CliApplication.printUsage(System.out);
            return;
        }

        int exitCode = CliApplication.run(args, System.out, System.err);
        if (exitCode != 0) System.exit(exitCode);
    }
}

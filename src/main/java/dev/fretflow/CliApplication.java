package dev.fretflow;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class CliApplication {
    private static final long MAX_INPUT_BYTES = 8L * 1024 * 1024;

    private CliApplication() { }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            Options options = parse(args);
            validateInput(options.input());

            byte[] input = Files.readAllBytes(options.input());
            String apiKey = readApiKey(options);
            var request = new ConversionService.RequestOptions(
                    options.instrument(), options.tuning(), options.style(), options.aiProvider(),
                    apiKey, options.aiBaseUrl(), options.aiModel());
            var result = new ConversionService().convert(input, request);

            Path xmlOutput = options.xmlOutput() == null
                    ? siblingOutput(options.input(), "-tab.musicxml") : options.xmlOutput();
            Path tabOutput = options.tabOutput() == null
                    ? siblingOutput(options.input(), "-tab.txt") : options.tabOutput();
            ensureDifferentFromInput(options.input(), xmlOutput);
            ensureDifferentFromInput(options.input(), tabOutput);
            if (xmlOutput.toAbsolutePath().normalize().equals(tabOutput.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("MusicXML and ASCII TAB outputs must use different paths");
            }
            createParent(xmlOutput);
            createParent(tabOutput);
            Files.write(xmlOutput, result.annotatedMusicXml());
            Files.writeString(tabOutput, result.asciiTab(), StandardCharsets.UTF_8);

            int noteCount = result.score().events().stream().mapToInt(event -> event.notes().size()).sum();
            out.println("Converted: " + options.input().toAbsolutePath());
            out.println("Score: " + result.score().title() + " · " + result.instrument().name());
            out.println("Events: " + result.score().events().size() + " · Notes: " + noteCount);
            out.println("Optimizer: " + result.aiStatus());
            for (String warning : result.warnings()) out.println("Warning: " + warning);
            out.println("Annotated MusicXML: " + xmlOutput.toAbsolutePath());
            out.println("ASCII TAB: " + tabOutput.toAbsolutePath());
            return 0;
        } catch (IllegalArgumentException error) {
            err.println("Error: " + safeMessage(error));
            err.println("Run with --help for usage.");
            return 2;
        } catch (Exception error) {
            err.println("Conversion failed: " + safeMessage(error));
            return 1;
        }
    }

    public static void printUsage(PrintStream out) {
        out.println("FretFlow AI " + Main.VERSION + " — local MusicXML-to-TAB desktop compiler");
        out.println();
        out.println("Desktop mode:");
        out.println("  java -jar fretflow-ai.jar");
        out.println();
        out.println("Command-line mode:");
        out.println("  java -jar fretflow-ai.jar --input SCORE [options]");
        out.println();
        out.println("Options:");
        out.println("  --input FILE          .musicxml, .xml, or .mxl score (required)");
        out.println("  --output FILE         annotated MusicXML output");
        out.println("  --tab FILE            ASCII TAB output");
        out.println("  --instrument TYPE     guitar (default) or bass");
        out.println("  --tuning NAME         standard, drop-d, dadgad, five-string, or custom notes");
        out.println("  --style NAME          balanced (default), beginner, low-position, or compact");
        out.println("  --ai-provider NAME    none (default), deepseek, or openai");
        out.println("  --api-key-env NAME    read the optional AI key from this environment variable");
        out.println("  --ai-base-url URL     optional OpenAI-compatible API base URL");
        out.println("  --ai-model NAME       optional provider model name");
        out.println("  --version             print the application version");
        out.println("  --help                print this help");
        out.println();
        out.println("When output paths are omitted, FretFlow writes SCORE-tab.musicxml and SCORE-tab.txt");
        out.println("beside the input file.");
    }

    private static Options parse(String[] args) {
        Path input = null;
        Path xmlOutput = null;
        Path tabOutput = null;
        String instrument = "guitar";
        String tuning = "standard";
        String style = "balanced";
        String aiProvider = "none";
        String apiKeyEnv = "";
        String aiBaseUrl = "";
        String aiModel = "";

        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            switch (flag) {
                case "--input" -> input = Path.of(requireValue(args, ++index, flag));
                case "--output" -> xmlOutput = Path.of(requireValue(args, ++index, flag));
                case "--tab" -> tabOutput = Path.of(requireValue(args, ++index, flag));
                case "--instrument" -> instrument = requireValue(args, ++index, flag);
                case "--tuning" -> tuning = requireValue(args, ++index, flag);
                case "--style" -> style = requireValue(args, ++index, flag);
                case "--ai-provider" -> aiProvider = requireValue(args, ++index, flag).toLowerCase(Locale.ROOT);
                case "--api-key-env" -> apiKeyEnv = requireValue(args, ++index, flag);
                case "--ai-base-url" -> aiBaseUrl = requireValue(args, ++index, flag);
                case "--ai-model" -> aiModel = requireValue(args, ++index, flag);
                default -> throw new IllegalArgumentException("Unknown option: " + flag);
            }
        }
        if (input == null) throw new IllegalArgumentException("--input is required in command-line mode");
        if (!aiProvider.equals("none") && !aiProvider.equals("deepseek") && !aiProvider.equals("openai")) {
            throw new IllegalArgumentException("--ai-provider must be none, deepseek, or openai");
        }
        instrument = instrument.toLowerCase(Locale.ROOT);
        style = style.toLowerCase(Locale.ROOT);
        validateSelections(instrument, tuning, style);
        return new Options(input, xmlOutput, tabOutput, instrument, tuning, style, aiProvider,
                apiKeyEnv, aiBaseUrl, aiModel);
    }

    private static void validateSelections(String instrument, String tuning, String style) {
        if (!instrument.equals("guitar") && !instrument.equals("bass")) {
            throw new IllegalArgumentException("--instrument must be guitar or bass");
        }
        if (!style.equals("balanced") && !style.equals("beginner")
                && !style.equals("low-position") && !style.equals("compact")) {
            throw new IllegalArgumentException("--style must be balanced, beginner, low-position, or compact");
        }
        if (tuning.contains(",")) return;
        String normalized = tuning.toLowerCase(Locale.ROOT);
        boolean valid = instrument.equals("guitar")
                ? normalized.equals("standard") || normalized.equals("drop-d") || normalized.equals("dropd")
                    || normalized.equals("dadgad")
                : normalized.equals("standard") || normalized.equals("drop-d") || normalized.equals("dropd")
                    || normalized.equals("five-string") || normalized.equals("5-string") || normalized.equals("5string");
        if (!valid) throw new IllegalArgumentException("Unsupported tuning for " + instrument + ": " + tuning);
    }

    private static void validateInput(Path input) throws Exception {
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("Input file does not exist: " + input);
        if (Files.size(input) > MAX_INPUT_BYTES) throw new IllegalArgumentException("Input is larger than the 8 MB limit");
        String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".musicxml") && !name.endsWith(".xml") && !name.endsWith(".mxl")) {
            throw new IllegalArgumentException("Input must be a .musicxml, .xml, or .mxl file");
        }
    }

    private static String readApiKey(Options options) {
        if (options.aiProvider().equals("none")) return "";
        if (options.apiKeyEnv().isBlank()) {
            throw new IllegalArgumentException("--api-key-env is required when AI refinement is enabled");
        }
        String value = System.getenv(options.apiKeyEnv());
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Environment variable " + options.apiKeyEnv() + " is empty or undefined");
        }
        return value;
    }

    private static Path siblingOutput(Path input, String suffix) {
        String fileName = input.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
        Path parent = input.toAbsolutePath().getParent();
        return parent.resolve(baseName + suffix);
    }

    private static void ensureDifferentFromInput(Path input, Path output) {
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("An output path cannot overwrite the input score");
        }
    }

    private static void createParent(Path output) throws Exception {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " requires a value");
        return args[index];
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n]+", " ");
    }

    private record Options(Path input, Path xmlOutput, Path tabOutput, String instrument,
                           String tuning, String style, String aiProvider, String apiKeyEnv,
                           String aiBaseUrl, String aiModel) { }
}

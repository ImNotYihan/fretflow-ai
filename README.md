# FretFlow AI

FretFlow AI is a local desktop application that compiles electric-guitar and bass MusicXML scores into ergonomic, playable TAB fingerings. Version 1.0.1 runs directly on the user's computer: it does not start a website, local HTTP server, or browser.

The deterministic engine guarantees the correct pitch for every generated string/fret position. Dynamic programming then reduces string skips, awkward stretches, and unnecessary position shifts. Optional DeepSeek or OpenAI-compatible refinement can rerank already validated candidates without being allowed to invent impossible fingerings.

## Desktop features

- Native Java desktop interface for macOS, Windows, and Linux
- `.musicxml`, `.xml`, and compressed `.mxl` input
- Guitar tunings: standard, Drop D, and DADGAD
- Bass tunings: 4-string standard, 4-string Drop D, and 5-string standard
- Balanced, beginner, low-position, and compact optimization styles
- Local ASCII TAB preview with score statistics and warnings
- Annotated MusicXML export with `<string>` / `<fret>` data and TAB staff settings
- Technique-aware bass TAB for slap/thumb, pop, dead notes, ghost notes, hammer-ons, pull-offs, slides, and harmonics
- MusicXML 4.0 technique preservation, including same-string fingering for adjacent legato and slide pairs
- Optional DeepSeek or OpenAI-compatible AI refinement
- Command-line mode for scripts and batch conversion
- Pure Java 17 with no third-party dependencies, database, account, browser, or web server

## Build and run on your computer

Install a JDK 17 or newer, then clone the repository.

macOS or Linux:

```bash
git clone https://github.com/ImNotYihan/fretflow-ai.git
cd fretflow-ai
./run.sh
```

Windows:

```bat
git clone https://github.com/ImNotYihan/fretflow-ai.git
cd fretflow-ai
run.bat
```

The launch script compiles the source into `dist/fretflow-ai.jar` and opens the desktop interface. You can also build without launching:

```bash
./build.sh
java -jar dist/fretflow-ai.jar
```

On Windows, use `build.bat` followed by:

```bat
java -jar dist\fretflow-ai.jar
```

## Using the desktop app

1. Choose a `.musicxml`, `.xml`, or `.mxl` score.
2. Select guitar or bass, the tuning, and an optimization style.
3. Select **Generate playable TAB**.
4. Review the local TAB preview and save the ASCII TAB or annotated MusicXML output.

FretFlow reads performance marks already present in the source MusicXML. Slap/thumb and pop can be encoded with
`<other-technical>` text (`slap`, `thumb`, `pop`, `S`, `T`, or `P`) or descriptive `<pluck>` text. Standard
MusicXML `<hammer-on>`, `<pull-off>`, `<slide>`, and `<harmonic>` elements are preserved. An `x` notehead is
treated as a dead note; a parenthesized notehead is treated as a ghost note. See
[`examples/slap-bass.musicxml`](examples/slap-bass.musicxml) for a complete example.

ASCII TAB uses `S` for slap/thumb, `P` for pop, `x` for dead notes, `(x)` for ghost notes, `h` for hammer-ons,
`p` for pull-offs, `/` or `\` for slides, and angle brackets for harmonics.

The default optimizer is fully offline. If AI refinement is selected, only pitch and validated fingering-candidate context is sent to the configured provider.

## Command-line mode

Convert a score without opening the interface:

```bash
java -jar dist/fretflow-ai.jar \
  --input examples/mini-riff.musicxml \
  --output build/mini-riff-tab.musicxml \
  --tab build/mini-riff-tab.txt \
  --instrument guitar \
  --tuning standard \
  --style balanced
```

If `--output` and `--tab` are omitted, both files are written beside the input score. Run `java -jar dist/fretflow-ai.jar --help` for every option.

For optional AI refinement, keep the API key out of shell history by passing the name of an environment variable:

```bash
export DEEPSEEK_API_KEY="your-key"
java -jar dist/fretflow-ai.jar \
  --input score.musicxml \
  --ai-provider deepseek \
  --api-key-env DEEPSEEK_API_KEY
```

API keys entered in the desktop app remain only in the current process and are never saved to disk or logs.

## How the compiler works

```text
MusicXML / MXL file
      │
      ▼
Parse parts, pitches, chords, rhythmic positions, and performance techniques
      │
      ▼
Enumerate every valid string + fret combination for the tuning
      │
      ▼
Score span, string skip, high position, and open-string cost
      │
      ▼
Technique-aware dynamic programming minimizes position shifts across the phrase
      │
      ├── Optional AI reranking among validated candidates
      ▼
Desktop preview + ASCII TAB + annotated MusicXML files
```

For multi-part scores, FretFlow prefers a part whose name contains Guitar or Bass, then falls back to the part with the most pitched notes. Chord notes are assigned to distinct strings. Notes outside the selected instrument's range produce a warning instead of a fabricated fingering.

## Tests

```bash
./test.sh
```

The suite covers MusicXML and MXL parsing, chord grouping, slap-bass techniques, dead and ghost notes, same-string legato constraints, string/fret pitch invariants, TAB annotation export, ASCII rendering, JSON escaping, the complete conversion service, and local command-line file generation. Continuous integration also builds the executable JAR and performs a packaged conversion.

## Project structure

```text
src/main/java/dev/fretflow/
├── ai/           # Optional DeepSeek/OpenAI-compatible refinement
├── desktop/      # Native Swing desktop interface
├── engine/       # Candidate generation and dynamic programming
├── model/        # Score, instrument, and fingering models
├── musicxml/     # Secure MXL/MusicXML parsing and TAB annotation export
├── render/       # ASCII TAB rendering
└── util/         # Shared utilities
examples/         # Ready-to-convert sample score
build.sh/.bat     # Reproducible local compiler
run.sh/.bat       # Build and launch the desktop application
```

## Current limitations

- One fretted-instrument part is converted per request. Multi-guitar projects are reduced to the automatically selected part.
- Technique marks must be present in the input MusicXML; FretFlow preserves and renders them but does not invent performance intent from plain notes.
- Bends and techniques other than the supported slap/thumb, pop, dead/ghost note, hammer-on, pull-off, slide, and harmonic set are preserved in MusicXML but are not shown in ASCII TAB.
- Music notation applications differ slightly in how they import a MusicXML TAB staff. The exported technical string/fret data is preserved for further editing in MuseScore and other compatible editors.
- AI calls may incur provider charges and send pitch plus fingering-candidate context to that provider.

## License

[MIT](LICENSE)

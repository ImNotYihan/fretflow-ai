# FretFlow AI

Turn electric guitar and bass MusicXML scores into ergonomic, playable TAB fingerings.

FretFlow first uses a deterministic local engine to guarantee that every fingering produces the correct pitch. Dynamic programming then reduces string skips, awkward stretches, and unnecessary position shifts. Players can optionally enter a DeepSeek or OpenAI API Key to let an LLM refine the result from a set of already validated candidates. No account is required, and API Keys are never stored on disk.

## Features

- Accepts `.musicxml`, `.xml`, and compressed `.mxl` files
- Guitar tunings: standard, Drop D, and DADGAD
- Bass tunings: 4-string standard, 4-string Drop D, and 5-string standard
- Four optimization styles: balanced, beginner, low position, and compact
- Optional DeepSeek or OpenAI-compatible Chat Completions refinement
- In-browser ASCII TAB preview and a note-by-note string/fret path
- Exports MusicXML with `<string>` / `<fret>` technical annotations and TAB staff settings
- Pure Java with no third-party dependencies, database, or account system

## Quick start

Requirement: JDK 17 or newer.

macOS / Linux:

```bash
git clone https://github.com/ImNotYihan/fretflow-ai.git
cd fretflow-ai
./run.sh
```

Windows:

```bat
run.bat
```

Your browser opens [http://127.0.0.1:8080](http://127.0.0.1:8080) automatically. Upload `examples/mini-riff.musicxml` to try the complete workflow.

Optional launch flags:

```bash
./run.sh --port 9090 --no-browser
./run.sh --host 0.0.0.0   # Allow LAN access; read the security notes below first.
```

## AI refinement

1. Select DeepSeek or OpenAI in the web interface.
2. Enter your own API Key. Leave the model blank to use the default shown in the interface.
3. Select **Generate playable TAB**.

The backend uses the OpenAI-compatible `/chat/completions` protocol. Advanced settings accept a compatible endpoint URL; public endpoints must use HTTPS, while localhost may use HTTP. The model cannot invent arbitrary fret positions—it can only select from candidates whose pitch and string constraints were validated locally.

> The API Key remains in the current browser field and is forwarded only for the active conversion. FretFlow never writes it to files, a database, or application logs. For an internet-facing deployment, disable client-side keys or add a server-side key proxy, authentication, and rate limiting.

## How the fingering engine works

```text
MusicXML / MXL
      │
      ▼
Parse parts, pitches, chords, and rhythmic positions
      │
      ▼
Enumerate every valid string + fret combination for the tuning
      │
      ▼
Score local cost: span / string skip / high position / open string
      │
      ▼
Dynamic programming minimizes position shifts across the full phrase
      │
      ├── Optional AI reranking within validated candidates
      ▼
ASCII TAB + annotated MusicXML
```

For multi-part scores, FretFlow prefers a part whose name contains Guitar or Bass, then falls back to the part with the most pitched notes. Chord notes are assigned to distinct strings. Notes outside the selected instrument's range produce a warning instead of a fabricated fingering.

## Tests

```bash
./test.sh
```

The test suite covers MusicXML and MXL parsing, chord grouping, string/fret pitch invariants, TAB annotation export, ASCII rendering, JSON escaping, and the complete conversion pipeline.

## Project structure

```text
src/main/java/dev/fretflow/
├── ai/           # DeepSeek/OpenAI-compatible client and response validation
├── engine/       # Candidate generation and dynamic programming
├── model/        # Score, instrument, and fingering models
├── musicxml/     # Secure MXL/MusicXML parsing and TAB annotation export
├── render/       # ASCII TAB rendering
└── web/          # Dependency-free HTTP server
src/main/resources/web/  # Responsive web interface
examples/                # Ready-to-upload sample score
```

## Current limitations

- One fretted-instrument part is converted per request. Multi-guitar projects are reduced to the automatically selected part.
- TAB focuses on string and fret placement. Bends, slides, hammer-ons, pull-offs, and harmonics are not inferred yet.
- Music notation applications differ slightly in how they import a MusicXML TAB staff. The exported `<technical>` string/fret data is preserved for further editing in MuseScore and other compatible editors.
- AI calls may incur provider charges and send pitch plus fingering-candidate context to that provider.

## License

[MIT](LICENSE)

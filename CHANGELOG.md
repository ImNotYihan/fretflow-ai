# Changelog

## 1.0.1

- Added technique-aware bass TAB for slap/thumb, pop, dead notes, ghost notes, hammer-ons, pull-offs, slides, and harmonics.
- Added MusicXML 4.0 parsing and preservation for standard fretted-instrument technique elements and slap/pop extension marks.
- Added support for unpitched `x` dead notes without inventing a sounding pitch.
- Kept adjacent hammer-on, pull-off, and slide pairs on the same string during fingering optimization.
- Added technique symbols and a legend to the ASCII TAB preview.
- Added an end-to-end slap-bass example and regression coverage for every supported technique.

## 1.0.0

- Replaced the localhost website with a native Java Swing desktop application.
- Added reproducible macOS/Linux and Windows build scripts that produce an executable JAR.
- Added a command-line compiler for local and automated MusicXML-to-TAB conversion.
- Added local save flows for ASCII TAB and annotated MusicXML output.
- Preserved all deterministic fingering, tuning, optimization, and optional AI-refinement features.
- Expanded tests and continuous integration to verify the packaged JAR.

## 0.0.1

- Initial web-based release.

# Peekturn

Independent Android EPUB reader for people learning English through sustained reading.

## Work boundaries

- Keep the unmodified Readium navigator; do not replace it with a custom paginator or flattened chapter text.
- VibeReading is a separate codebase, not a source or edit target. Reuse lessons, not its code or assets.
- Production surfaces show actual local data and actual provider results. No demo answers, placeholder books or fabricated reading statistics.
- Keep credentials, private book text and temporary verification data out of tracked files and logs.
- Behavior changes require verification on Android, not only unit tests.

## Code structure

- Build in layers, a sandwich rather than spaghetti: pure decision/policy functions, a thin bridge or controller that only moves data between boundaries, and a surface layer that only renders or hosts. Each layer has one responsibility and its own tests.
- A single change should land in one layer. Never let a UI class own parsing, persistence or conversion, and never repeat a rule in two places.
- Prefer composing small, named units over one class that does everything; keep orchestration explicit and local.

## Verification scope

- Default to targeted tests for the current change's affected behavior, callers and shared contracts. Do not run the full suite for ordinary features, bug fixes or small refactors.
- Run the full suite only for a large refactor. Choose the affected test scope before running checks, and report exactly what ran; targeted checks must not be described as full-suite verification.
- Keep Android smoke verification for behavior changes, scoped to the changed interaction. Build or collect the APK separately when needed; do not use packaging as an indirect reason to run the full suite.

## Task context

- For code changes, shared actors, boundaries and state ownership: [architecture](agent-doc/essential/ARCHITECTURE.md).
- For feature work, design or verification: follow the [task routes](agent-doc/reference/AGENTS.md) to the affected owner.
- Domain terminology: [glossary](CONTEXT.md).
- Build and run commands: [README](README.md#build).
- Dependency, dictionary and audio attribution: [third-party notices](THIRD_PARTY_NOTICES.md).
- Documentation changes: [ownership conventions](agent-doc/essential/doc-conventions.md).

## Current development context

- Whole-book audio preparation has been removed: slow synthesis does not justify a separate whole-book job, saved-audio library or keep-alive service. Preserve live narration's Readium lookahead, upcoming-sentence synthesis, sentence cache and cached replay.
- The reader starts live narration directly. A centre tap toggles the bottom reading tools; there is no top "Reading tools" title or close bar. Preserve the expanded transport and the hidden-tools previous, play/pause and next controls.
- Library, Statistics and Settings use bottom navigation to identify the current section, without repeating the app name or page title above the content. Keep content-section headings and actions such as book import.

## Development handoff

After each development task, update this Agent context with what changed and any lasting decisions. Keep it concise and current; replace superseded context instead of appending a chronological diary.

After each substantial feature or refactor, produce a local signed, non-debuggable production APK using the existing release key. Verify the package, signature and installation, and report its local path, version, checks and unverified limits. A debug APK is not a substitute. Commit, push, tag and publish still require explicit authorization.

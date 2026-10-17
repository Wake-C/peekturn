# Margin

Independent Android EPUB reader for people learning English through sustained reading.

## Work boundaries

- Keep the unmodified Readium navigator; do not replace it with a custom paginator or flattened chapter text.
- VibeReading is a separate codebase, not a source or edit target. Reuse lessons, not its code or assets.
- Production surfaces show actual local data and actual provider results. No demo answers, placeholder books or fabricated reading statistics.
- Keep credentials, private book text and temporary verification data out of tracked files and logs.
- Behavior changes require verification on Android, not only unit tests.

## Task context

- For code changes, shared actors, boundaries and state ownership: [architecture](agent-doc/essential/ARCHITECTURE.md).
- For feature work, design or verification: follow the [task routes](agent-doc/reference/AGENTS.md) to the affected owner.
- Domain terminology: [glossary](CONTEXT.md).
- Build and run commands: [README](README.md#build-and-run).
- Dependency, dictionary and audio attribution: [third-party notices](THIRD_PARTY_NOTICES.md).
- Documentation changes: [ownership conventions](agent-doc/essential/doc-conventions.md).

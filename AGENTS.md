# Margin

Independent Android English EPUB reader. The accepted product and acceptance criteria are in [docs/SPEC.md](docs/SPEC.md); architectural ownership and runtime constraints are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Stable vocabulary is in [CONTEXT.md](CONTEXT.md). Documentation ownership is in [docs/doc-conventions.md](docs/doc-conventions.md).

- Readium owns EPUB parsing, rendering, navigation and locators. Do not introduce a custom paginator or flatten the original EPUB.
- Do not copy source or assets from the adjacent VibeReading project. New implementations may use the documented lessons and independently sourced dependencies/data.
- Production surfaces show actual local data and actual provider results. No demo answers, placeholder books or fabricated reading statistics.
- Keep credentials, private book text and temporary verification data out of tracked files and logs.
- Use the build manifests for dependency versions and README for build/run commands. Validate changed behavior on Android, not only in unit tests.
- No duplicate CLAUDE.md/GEMINI.md rule bodies: this runtime reads AGENTS.md directly.

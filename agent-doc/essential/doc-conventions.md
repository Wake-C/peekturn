# Documentation ownership

## Placement

`agent-doc/` is the home for durable agent-facing product and technical documentation. Repository-wide architecture and documentation ownership live in `essential/`; task-specific material lives in `reference/`. Do not create a parallel `docs/` tree.

- [Root AGENTS.md](../../AGENTS.md): repository-wide constraints and task entry routes. Compatibility filenames exist only for runtimes that need them, as symlinks or thin redirects to this canonical entry.
- [essential/ARCHITECTURE.md](ARCHITECTURE.md): actors, main flow, system boundaries, code ownership and shared technical constraints. Exact dependency and SDK versions belong to build manifests.
- [reference/AGENTS.md](../reference/AGENTS.md): conditional routing to feature owners, product-wide requirements and verification evidence.
- [reference/SPEC.md](../reference/SPEC.md): product scope, shared visual design, accepted prototype reference and cross-feature acceptance criteria.
- Feature directories under `reference/`: `SPEC.md` owns behavior; `INTEGRATION.md` owns non-obvious implementation ownership, state lifetimes and native constraints. A local `AGENTS.md` selects these sources and any cross-feature boundary needed for the task.
- [reference/verification/VERIFICATION.md](../reference/verification/VERIFICATION.md): dated build evidence and explicit unverified boundaries. Old observations and pass counts do not define current requirements or establish a fresh verification result.
- [Root CONTEXT.md](../../CONTEXT.md): stable vocabulary for Margin's single reading/learning domain, not settings schemas, requirements or task history. Feature directories do not create separate domain glossaries.
- [Root README.md](../../README.md): user introduction, first use and runnable build/install instructions.
- [Root THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md): independently sourced library/data attribution and rebuild provenance.

## Maintenance

Keep each detailed fact at its owner and link to it from other surfaces. New feature requirements and integration constraints belong to the affected feature directory, not the shared architecture overview. Code owns implementation details that do not need a durable integration constraint.

Add scoped agent context only for distinct rules or loading conditions; do not pair it with a README that repeats the same routing. Keep documents with the same owner and loading condition together. Shared architecture is not a catalog of every feature's internals.

Screenshots, scratch providers, credentials and machine-local setup stay outside shared context; local verification artifacts remain ignored. Verification history records evidence, not context-file inventories or documentation-maintenance reports.

# Documentation ownership

- `AGENTS.md`: repository-wide constraints and routing only.
- `docs/SPEC.md`: accepted product behavior, scope and acceptance criteria.
- `docs/ARCHITECTURE.md`: actors, main flows, module ownership and non-obvious runtime constraints.
- `CONTEXT.md`: stable reading/learning terms only; this project currently has one domain context.
- `README.md`: user introduction and runnable build/install instructions.
- `docs/VERIFICATION.md`: exercised checks and explicit unverified acceptance boundaries for the delivered implementation.
- `THIRD_PARTY_NOTICES.md`: independently sourced library/data attribution; build manifests own exact dependency versions.

Link to the owning document rather than copying its facts. Screenshots and scratch provider logs are verification artifacts, not mandatory agent context. Machine-local scripts, keys and device state stay outside tracked documentation. Add scoped context only when a real ownership or loading boundary emerges.

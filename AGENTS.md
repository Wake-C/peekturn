# Peekturn

## Purpose

An Android app for people who know some English and want to enjoy books, articles and videos that still feel difficult. Connect unfamiliar words with their meaning in context, pronunciation and the scenes where they occur.

Help people stay with content they care about and finish more of it. Reading, listening and watching remain the main experience; learning tools support them.

## UX taste

- Keep help close at hand, brief and easy to dismiss.
- Protect continuity. Looking up a word, opening controls or changing presentation should preserve the user's place and playback intent.
- Trust familiar reading and playback habits. Do not explain or duplicate actions the interface already makes clear.
- Keep frequent actions within reach; place occasional choices and technical details outside the main experience.
- Make shared actions, reading preferences and controls consistent across books and videos.
- Keep normal operation quiet. Show honest empty states and clear, recoverable failures.

## UI taste

- Calm, polished and reader-like, not a technical dashboard.
- Use typography, spacing and alignment to establish hierarchy before adding borders, cards or decoration.
- Keep controls compact but comfortable to touch. Minimal does not mean cramped, obscure or low-contrast.
- Use familiar icons where their meaning is clear, with accessible labels. Keep visible labels where they help.
- Prefer a few meaningful choices and direct previews over fine-grained adjustment.
- Extend the existing visual language. New features should feel like part of the same app.

## Code preferences

- Prefer mature, widely used, actively maintained libraries and platform APIs over building common capabilities from scratch. Check fit, license, maintenance and integration cost.
- Use intended APIs and extension points rather than rebuilding library internals. Readium owns EPUB layout and navigation; preserve the original publication and its locators.
- Reuse existing project solutions before adding dependencies or abstractions. Keep custom code small and specific to Peekturn.
- Give each rule one owner. Keep policy, parsing and persistence below the UI, with explicit, local orchestration.
- Do not add complexity for hypothetical needs. Explain material tradeoffs before expanding scope.

## Validation

- Add or update the smallest useful regression tests for changed behavior and its callers. Run targeted tests; reserve full suites for cross-cutting changes or requested release gates, not packaging alone.
- Verify behavior changes end to end through real screens and controls in the installed signed production app. Repeat the affected journey and relevant reopen, background and offline transitions.
- Use real content and services when the behavior depends on them. Mocks, controller calls and successful builds do not replace user-level acceptance.
- Inspect device screenshots for UI consistency, readability, spacing and touch targets. Verify audible behavior when changing speech or playback.
- After substantial features or refactors, build and verify a signed production APK using the existing release key. Report its path, version, installation result and checks performed.
- Report passed, failed and unavailable checks separately. A skipped or blocked check is not a pass. Documentation-only changes need document checks, not an app build.

## Working boundaries

- Read the affected code and tests before changing behavior. Preserve unrelated work in a dirty worktree.
- Keep credentials and reading content private, including in logs and test artifacts. Never clear user data or uninstall the app to bypass a test or installation failure.
- Commit, push, tag and publish only on explicit request. A version-tag push can publish automatically.
- VibeReading is a separate product, not a source of code or assets.
- Update this guide only when intent, design principles or working standards change. Keep implementation detail beside code and history in Git; do not append task diaries or feature inventories. README files are for users.

## Read when relevant

- [Integration constraints](agent-doc/essential/ARCHITECTURE.md): external library and platform traps, only for the integration being changed.
- [Validation and delivery](agent-doc/essential/DELIVERY.md): test entry points, local production packaging and device checks.
- [Terms](CONTEXT.md) and [third-party notices](THIRD_PARTY_NOTICES.md): terminology and provenance when needed.

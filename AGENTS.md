# Peekturn

An Android English-learning reader for books and YouTube videos. Help should be brief, useful and easy to leave; the book or video remains the main experience.

## Engineering taste

- Readium owns the book layout and navigation. Preserve the original EPUB and its reading position; no custom paginator or flattened chapter text.
- Small policy functions, thin controllers, quiet surfaces. Parsing and persistence belong below the UI. One rule has one owner.
- Prefer the platform and existing modules over another framework or abstraction. Keep orchestration explicit and local.
- Show real books, real provider answers and real reading activity. Honest empty and failure states are better than invented content.
- Credentials and reading content stay private. Support logs explain what failed without exposing keys, passages, provider answers or private paths.
- VibeReading is a separate product, not a source of code or assets.

## Where things live

Application wiring is in `PeekturnApp.kt`; feature packages under `app/src/main/java/app/peekturn/reader/` are named for their work: `reader`, `video`, `learning`, `speech`, `narration`, `webimport`, `data`, `ui` and `diagnostics`. Tests mirror those packages. The implementation and its inline comments explain the behavior.

- [Integration constraints](agent-doc/essential/ARCHITECTURE.md): Readium, WebView and system-speech traps that are expensive to rediscover.
- [Delivery](agent-doc/essential/DELIVERY.md): building, signing, installing and publishing.
- [Terms](CONTEXT.md) and [third-party notices](THIRD_PARTY_NOTICES.md): vocabulary and provenance, not feature specifications.

## Evidence and context

A behavior change needs an Android smoke check and the smallest useful target tests for the affected behavior and callers. Do not run a full suite for a small edit or merely to produce a signed local APK. Broader suites belong to cross-cutting refactors or explicitly requested public-release gates. Local packaging is separate: default to `tools/local-release.sh`, which builds/signs/verifies the APK without tests, lint or an AAB. Run chosen target tests independently and report what actually ran and what remains unverified.

README files are for users: audience, learning pain points, core capabilities, design goals and minimal setup. Keep them short, without build commands, engine/cache internals, test diaries or per-build version edits. Context should earn its place. Keep operational knowledge and hard-won external constraints in `agent-doc/essential/`; keep implementation detail beside the code. No parallel feature specs, frozen task assignments, acceptance snapshots, rolling verification diary or routine documentation update for every feature. Temporary evidence belongs in ignored `verification-local/` or delivery artifacts; history belongs in Git.

Commit, push, tag and publish only on explicit request. A version-tag push can publish a release automatically.

## Current development context

- Whole-book audio preparation has been removed: slow synthesis does not justify a separate whole-book job, saved-audio library or keep-alive service. Preserve live narration's Readium lookahead, upcoming-sentence synthesis, sentence cache and cached replay.
- The reader starts live narration directly. A centre tap toggles the bottom reading tools; there is no top "Reading tools" title or close bar. Preserve the expanded transport and the hidden-tools previous, play/pause and next controls.
- Reader typography uses four size presets (small/medium/large/extra large), three spacing levels and a folded More section, not continuous sliders. Keep legacy custom values until their field is explicitly changed. Apply commits the appearance draft together, Cancel changes nothing, and Readium's visible-word anchor still owns reflow. Reader and Settings share direct previews of the same five existing themes; do not add themes or change palette values as part of control styling.
- Successful lookup/example speech and reading transport show no engine/provider notices or cache/reserve counters. Keep play/pause/preparing controls and real failures/retry/diagnostic details. Provider selection, limitations and required attribution stay in Settings and third-party notices; do not change speech routing to hide presentation metadata.
- Library, Video, Statistics and Settings use bottom navigation to identify the current section, without repeating the app name or page title above the content. Keep content-section headings and actions such as book import. Book/video entries open from their whole content area, with options menus separate; do not add Continue/Open buttons, Continue watching captions, tap-to-open instructions or decorative right arrows that repeat this action. Keep title, author and real progress, accessibility labels and explicit playback/resume controls.
- Video A uses NewPipeExtractor and Media3, separate from EPUB and sharing model profiles/statistics. Media preparation must not wait for network captions. Only explicit subtitle choices survive as user intent; no English track means an honest notice, not silent foreign-language fallback. DB v4 preserves this distinction and per-video audio-only choices without deleting positions or time.
- Preserve A's independent layers: portrait tools are a fixed 96dp two-row footer (plus system inset), hidden by default and toggled by a picture tap. Elapsed time/seek/total time share the first row; compact previous/play-pause/next stay screen-centred, with speed at the far right, and no close X. Reserve the same footer height in caption scrolling. Fullscreen previous/play-pause/next remain centered, with time/progress below. Chrome visibility never moves subtitles. Explanations are centered overlays in fullscreen, never side rails. No in-picture portrait transport or instructional "tap to explain" copy. Audio-only mode stays in Video, removes the picture and always shows bottom transport; timestamp taps seek and play, word taps only look up.
- Sentence organization stays in player settings and runs locally with punctuation, abbreviation and speaker rules, not paid model calls. It can split inside source cues and join across them without changing words or punctuation. Preserve source word starts; duplicate/backward anchors belong to one displayed playback group, never separate no-op transport targets. Distinct nearby source anchors may remain navigable with (~); lack of word timing alone must not collapse a long transcript into one row. Never evenly estimate word times. Coalesce playback row ranges first and materialize each final group's text once; same-anchor runs must not repeatedly copy a growing transcript. Highlight and previous/next share this displayed timeline; the latest-started row owns overlaps, and transport targets must advance to a distinct source start. No arbitrary 12-second/30-word cut through a sentence. Old model-group caches must not substitute for local results; keep original caption caches independent.
- Video HTTP diagnostics report bounded status codes, domain categories and redirect counts through the existing developer log. Never log raw URLs, Location values, cookies, authorization, response bodies or exception messages. Diagnostics must not change redirects, cookies, TLS or proxy behavior, and a writer failure must not interrupt playback.
- Playback settings remember the chosen height globally (initially 1080p), using the nearest lower real MP4 rendition or lowest higher one without changing the preference. VideoPlaybackService owns one player/session beyond Activity exit, including lock-screen controls and background listening time. Audio-only must use a real independent stream, never a hidden video download. Save each video's mode before switching; the old global flag is only a fallback for unset rows, and save failures must be visible. Mode/quality switches preserve clock, speed and pause ownership. Media3 keeps a short back buffer plus one app-wide 1 GiB private LRU fragment cache, shared across video and audio streams. It is not a per-video quota, a symmetric progress window, a full download library or a guaranteed 15-minute preload. Use opaque content identities for cross-session cache reuse; streams without enough identity metadata get an opaque key held by that resolved stream for same-session replay only. Signed URLs and redirect addresses must never enter cache keys or metadata. No account login or Premium entitlement is implied. Model HTTP 402 means balance/payment failure, not JSON incompatibility.

## Development handoff

After each development task, update this Agent context with what changed and any lasting decisions. Keep it concise and current; replace superseded context instead of appending a chronological diary.

After each substantial feature or refactor, produce a local signed, non-debuggable production APK using the existing release key and the fast local packaging script; packaging must not expand the test scope. Verify the package, signature and installation, and report its local path, version, checks and unverified limits. A debug APK is not a substitute. Commit, push, tag and publish still require explicit authorization.

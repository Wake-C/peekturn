# Verification

Verified on 2026-10-15. This record describes the native Android implementation, not the browser prototype.

## Environment and commands

Android API 35 arm64 emulator, JDK 17, repository Gradle wrapper. SDK location, synthetic EPUBs, controlled providers and diagnostic images remain under ignored `verification-local/` or host tooling. No credentials are part of these artifacts.

Executed:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n app.margin.reader/.MainActivity
```

- 60 unit tests passed, zero failures, errors or skips.
- Debug APK built, installed and launched successfully.
- Android lint: zero errors, 36 warnings. Remaining warnings are pinned-dependency upgrade suggestions, Kotlin convenience API suggestions, the transitive Timber logging preference, the configuration-size fallback in popup measurement, and an obsolete resource-directory version qualifier. They were not suppressed.

## Test-first regression evidence

Parent executed failing regressions before the corresponding behavioral fixes, then observed green in the full suite:

- Structurally invalid persisted model preferences no longer crash startup; compatible saved choices are retained.
- Field-transform preference updates prevent queued changes to different settings overwriting each other. The new interface was introduced after its compile-red test; its behavioral persistence regression then passed.
- Validated partial explanation fields survive a later malformed field independently of network chunk boundaries.
- Readium 3.1 selection bounds include its missing bottom page-padding offset.
- Growing explanation content stays on a stable side of the selection when available space permits it.
- Optional publication image/font/stylesheet failures no longer close an otherwise readable book.
- Served EPUB HTML excludes authored executable content while preserving static text/layout and original archive bytes.
- Unsafe decoded fragment identifiers cannot reach the pinned navigator's native JavaScript interpolation.
- Quick Explain consumption survives popup adjustment and native toolbar recreation during handle dragging.

Additional behavior coverage includes dictionary normalization, US/UK voice selection, partial/cancelled/truncated SSE responses, local-calendar reading-time boundaries, foreground/idle accounting, independent book positions, deletion cascades, last-book startup routing and actual statistics aggregation. Red checks that failed only because of test-fixture compilation or missing Robolectric setup were corrected and were not counted as behavior regressions.

## Android runtime observations

### Library and reading

- Fresh installation showed an empty library, with no fake books or statistics.
- Android document picker imported a generated valid English EPUB with four chapters and original verification text. Readium displayed headings and paragraphs and responded to navigation.
- Three page advances followed by force-stop/relaunch reopened the same book with the same visible passages (10 through 12), not the first page.
- Explicit return to Library stayed in Library. Statistics and Settings remained accessible.
- In-book search returned actual occurrences and supported native locator navigation.
- Serif/sans, warm/dark paper and page/scroll preferences were applied and persisted. A restart reopened a noninitial position with the saved warm/sans/scroll preferences.
- The Focus shell and popup were inspected at normal font scale. At 1.5× system font scale, reader preference controls and popup Adjust/Retry/Close stayed accessible; the answer body scrolled to its examples. The emulator was returned to its original 1.0 font scale afterward.

### Learning and provider behavior

- A user-supplied live compatible provider successfully completed the app's real connection test and returned a structured contextual explanation for a selected word. The final installed build's connection test also succeeded.
- Controlled on-device requests contained the exact selected word/phrase, book/chapter titles and 30 words on each side when that much native context was available.
- Final Quick Explain sequence: initial long-press produced one request; Adjust plus native handle expansion produced no additional request; explicit Explain produced the second request for `reluctant to leave`; Close plus a new selection rearmed automatic explanation.
- Controlled HTTP 503 displayed an explicit provider failure. A truncated structured response retained available fields and displayed an incomplete/token-limit error rather than success.
- Offline dictionary lookup returned the bundled entry for `reluctant` with emulator Wi-Fi and mobile data disabled.
- Missing US voice produced a clear message and a working route to Android speech settings. Audible US/UK pronunciation quality was not verified; the emulator initially had no usable matching installed voice. Voice choice and no-wrong-accent substitution have unit coverage.
- The live credential was removed from the emulator after verification and cleared from the verification session. No key is embedded in the APK or committed repository. Persisted model JSON was checked to contain an empty API-key field; protected key material was stored separately.

### Publication isolation

A synthetic EPUB contained a missing local image plus remote script, inline script, event handler, remote CSS and remote image references targeting a local probe.

- Before the fixes, it returned to Library and the probe recorded six requests, including executed-script and event-handler beacons.
- After the fixes, the book remained readable despite the optional missing image, and the probe received zero additional requests. Page navigation and selected-word lookup still worked, demonstrating that the required Readium toolkit scripts remained functional.
- SHA-256 comparison confirmed that the stored original EPUB archive matched the imported source bytes; protection transforms served HTML, not the original book file.

## Review and context-system validation

Independent source reviews covered reader/persistence/startup/statistics and learning/parser/popup/dictionary/speech. Confirmed findings were corrected; final scoped re-reviews reported no actionable remaining findings. These are source-review results, separate from the runtime observations above.

Root `AGENTS.md` routes to the specification, architecture, glossary and documentation conventions. There is one domain glossary and no duplicate compatibility rule files. Build versions remain in Gradle; runtime evidence remains here; machine-local setup and scratch artifacts are ignored.

## Remaining evidence boundaries

- No physical user phone was available. OEM selection behavior and audible pronunciation quality need device-specific acceptance.
- This run used generated EPUB fixtures, including a deliberately adversarial one; it is not a claim of compatibility with every publisher's EPUB or every DRM format.
- Live verification establishes compatibility with the supplied tested endpoint/model, not every OpenAI-compatible server or strict-schema capability.
- The delivered APK is a debug build for testing. Store signing, store publication, a public Git remote and cloud services were not configured or claimed.

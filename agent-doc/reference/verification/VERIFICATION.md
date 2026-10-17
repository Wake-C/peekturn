# Verification

Historical observations for specific builds, not a current test result or a product contract. Current behavior belongs to the [feature owners](../AGENTS.md), and cross-feature acceptance criteria belong to the [product specification](../SPEC.md#acceptance); later entries describe later builds without rewriting the earlier evidence.

The initial native implementation was verified on 2026-10-15. The observations below concern the Android application, not the browser prototype.

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

## Source review

Independent source reviews covered reader/persistence/startup/statistics and learning/parser/popup/dictionary/speech. Confirmed findings were corrected; final scoped re-reviews reported no actionable remaining findings. These are source-review results, separate from the runtime observations above.

## Remaining evidence boundaries

- No physical user phone was available. OEM selection behavior and audible pronunciation quality need device-specific acceptance.
- This run used generated EPUB fixtures, including a deliberately adversarial one; it is not a claim of compatibility with every publisher's EPUB or every DRM format.
- Live verification establishes compatibility with the supplied tested endpoint/model, not every OpenAI-compatible server or strict-schema capability.
- The delivered APK is a debug build for testing. Store signing, store publication, a public Git remote and cloud services were not configured or claimed.

## Fullscreen, popup, OEM speech and classic themes update (2026-10-16)

Executed `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, installed the updated APK and launched the application on the API 35 emulator. This update's full suite contains 85 passing tests, with zero failures, errors or skips. Lint reported zero errors and 37 warnings.

Observed native behavior:

- Default reading hides both system bars and chrome. The actual WebView bounds expanded from `[0,294][1080,2108]` in the original implementation to `[0,0][1080,2400]`.
- Four center-tap control toggles preserved the persisted locator and full viewport. A system top-edge swipe temporarily exposed system chrome without changing the stored locator or viewport.
- Enabling the status bar alone produced a stable `[0,63][1080,2400]` WebView and left navigation hidden. The independent preference survived force-stop/relaunch; immersive defaults were restored after this check.
- App Classic White and Warm Paper choices updated native screen surfaces and system-icon contrast. Reading white/warm paper was independently selected; persisted state retained app theme `warm` with reading paper `light`.
- White and warm fullscreen reading, readable overlay controls, the context excerpt with selected-word emphasis, and persistent popup actions were inspected on rendered Android surfaces. At 1.5× system font size the answer scrolled to examples while header speaker/close and footer actions stayed accessible. Font scale was restored to 1.0 afterward.
- Controlled streaming requests supplied actual selected-text snippets. The popup's “In your book” section displayed the source snapshot, independently of generated context. No real credential was used for these update checks.
- The system speech preview now reached Android language selection and reported genuinely missing English voice data on this emulator, instead of treating every unenumerated voice as an install failure. The real OnePlus engine is unavailable here; compatibility with its empty/generic/rejected enumeration cases is covered at the production availability-decision interface, not claimed as an on-device OnePlus result.

Theme contrast calculations for dark/classic/warm respectively: main text on background 13.70/13.79/11.31, secondary text on surface 6.23/6.63/6.19, accent on surface 7.78/6.90/6.24, essential outline on surface 4.09/4.23/4.09. These checked roles exceed 4.5:1 text and 3:1 graphical thresholds.

The speech availability tests were written before the new compatibility interface and observed failing to compile for that missing interface, then passed after implementation. The initial chrome policy tests were first executed after their policy implementation already existed; no failing-before assertion is claimed for those tests. Actual fullscreen/chrome geometry is supported by the runtime checks above. Independent read-only reviews of the reader/theme and speech/popup changes reported no actionable findings.

## Typography, focused lookup and model profiles update (2026-10-16)

Final package: `app.margin.reader` version `1.1.0`, version code `2`, universal debug APK. Executed `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`, installed the resulting APK and cold-launched it on the API 35 arm64 emulator. The final full suite contains 108 tests, with zero failures, errors or skips. Lint reported zero errors and 39 warnings; this is not a warning-free build.

### Typesetting and stable position

The initial native inspection found a `10,000%` Readium font property while the rendered paragraph remained 16px. Stored percentages now become Readium multipliers, and generic typefaces apply at the supported root property.

- Changing actual controls produced 27.8448px text, 60.7555px line height, 38.9827px paragraph spacing and 335.786px text width, versus the measured 16px, 25.6px, 11.2px and 371.429px baseline. The computed family changed to `sans-serif`. Chapter position changed from 2/5 to 4/19 after reflow.
- Independent top/bottom distances of 80dp/103dp produced actual WebView bounds `[0,212][1080,2131]`. Background/resume retained those bounds. Turning immersive reading off reserved both system bars, producing `[0,275][1080,2068]`; default immersive behavior hides both.
- A tap within the bottom padding band, outside the text WebView, advanced from 6/11 to 7/11 when no selection was active.
- A visible quote inside paragraph 11 remained on the visible page after increasing font size. The check scoped the text range to its actual paragraph, rather than accepting another repeated occurrence in the fixture. The anchor is intra-paragraph, not the paragraph start.
- The rendered reader showed the current chapter at top left and current/total chapter pages at bottom left without opening controls. Light/warm passive-label contrast was increased above 4.5:1.

### Profiles and bounded lookup reuse

- Created a second saved model profile through the actual Settings UI, selected it, and reopened the first profile's editor. The first profile retained its independent endpoint, model and credential.
- Private settings JSON contained both profile configurations and the selected profile ID, without API-key fields in those configurations. Credentials remain separately protected per profile.
- A controlled provider received one request for a real selected word. Dictionary/Explain round trips did not add a request. Closing and reopening the identical completed lookup in the same reader session also reused the result. The final installed APK's repeated tab round trip again produced exactly one request.
- Final popup inspection showed meaning first, then generated contextual meaning and examples. Both the AI and dictionary views omitted the original source excerpt. The dictionary displayed the actual bundled entry.
- These update checks used fixture-only provider credentials and controlled SSE responses, not the earlier live-provider credential. They establish app request/session/UI behavior, not a new live-provider acceptance result.

### Adversarial review and regression evidence

Independent read-only adversaries examined reader/persistence/layout and learning/cache/profile boundaries. They established no P0 or P1 findings and four P2 findings. All four were fixed and re-reviewed without further actionable P0/P1/P2 findings in their scopes:

- Deleting the final profile while credential access was unavailable could preserve ciphertext under a reused profile ID and resurrect the deleted key after unlock. A fresh unconfigured replacement ID prevents reuse. The regression failed by assertion before the fix.
- Malformed legacy settings JSON could prevent recovery of an independently stored legacy credential and lose that credential on the next save. Credential recovery no longer depends on parsing the settings JSON. The regression failed by assertion before the fix.
- A typography change during Readium's debounced chapter transition could attach the new document's text quote to the old native locator. Resource-identity validation now defers the change until the document and locator agree. The chapter-transition regression failed by assertion before the fix.
- Padding-band fallback gestures lacked the native navigator's explicit selection ownership guard. They now clear an active selection instead of paging and reject stale asynchronous callbacks. The test first failed to compile for the missing guard interface, then passed after implementation. The emulator already suppressed the first selected-text padding tap in the baseline check; no device-level reproduction of unwanted paging is claimed.

An earlier scoped review also identified paragraph-start anchoring during typography changes; the visible-range anchor above corrects it. Its helper tests first failed to compile for the absent interface. Tests first executed after implementation are not counted as assertion-red evidence.

### Final main-agent visual acceptance

After the adversarial fixes, the main agent inspected rendered Library, Statistics, saved-profile Settings and Reader screens against Focus C, followed by the final installed AI and dictionary popups at 1.0 and 1.5 system font scales. The controlled answer's meaning, context, two examples and fixed actions were visible without scrolling at both scales. This does not imply every provider answer fits without scrolling.

The visual pass removed split-letter fallback-cover titles in favor of a simple book-initial cover with the full title outside. Near-zero coarse progress is labeled as active reading rather than a misleading 0% read. Import remains an icon-only plus; saved profiles and the single Appearance choice remain available.

System font scale was restored to 1.0 after inspection. Private scratch fixtures, screenshots and provider logs remain ignored. No physical OnePlus device was available; OEM speech behavior and audible accent quality remain device-specific acceptance boundaries.

## Recorded words, available-English speech and temperature update (2026-10-16)

Final package: `app.margin.reader` version `1.2.0`, version code `3`, universal debug APK. The full `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` run passed with 147 tests, zero failures, errors or skips. After grouping recording-credit labels to avoid an orphaned license suffix, `:app:assembleDebug :app:lintDebug` was run again, followed by install and cold launch. Final lint: zero errors, 42 warnings. Warnings were not suppressed or presented as a clean lint result.

### Recurring speech failure and source verification

The previous `configureSpeech` policy rejected a usable English voice when its country differed from the preferred US/UK accent. Word and sentence actions shared that gate. The regression `ukRequestWithOnlyInstalledUsEnglishStillSpeaksWithNotice` was run against that implementation and failed by assertion, then passed after English-country fallback became allowed. Non-English and unavailable voices remain rejected.

Free Dictionary API metadata worked for one common word but returned HTTP 522 for others; its direct recording request timed out. That intermediary is not used by the app. Direct Commons metadata returned source, creator and per-file license. Its UK hello Ogg Vorbis recording downloaded successfully, 14,145 bytes. There is no Cambridge/Oxford scraping or promise of complete word/accent coverage.

Source tests caught the real Commons `extmetadata` wrapper shape before packaging: artist and license values are nested objects. Their positive recording/credit tests failed by assertion before the parser fix. Additional coverage exercises missing files, wrong word/accent, unavailable attribution, unsupported licenses, unsafe media/source URLs, redirects, oversized metadata/audio, cancellation and phrase isolation.

### Android audio and UI observations

- The final Android app played UK hello from Association Shtooka/Judith Franck under CC BY 3.0 US, and US hello from Dvortygirl as public domain. Each displayed the actual creator, source and license. Native NuPlayer/AudioTrack logs recorded 48,510 delivered frames for the UK recording, not merely a pending UI state.
- An independently installed open-source eSpeak engine supplied real offline English TTS for verification. Native binding and AudioTrack output confirmed sentence synthesis. This engine is a test-environment dependency, not bundled in Margin and not required as the user's engine.
- With emulator Wi-Fi and mobile data disabled, the word speaker fell back to installed English system speech with a nonerror recording-unavailable notice. The sentence action also worked offline. Networking was restored afterward.
- In the real reading popup, a UK-preferred lookup for reluctant used system speech when its exact Commons file was absent. Example speakers used system speech directly. Meaning, context and examples stayed first; no source excerpt returned.
- Enabling Auto-pronounce and opening a fresh lookup produced one engine AudioTrack completion. Dictionary/Explain round trips left that count at one and did not add a model request. Auto-pronounce was restored to off afterward.
- The main agent inspected the final profile editor, pronunciation controls, recording attribution and learning popup. Normal and 1.5 system text sizes retained readable controls/credits; source and license labels no longer split into isolated suffixes. Font scale was restored to 1.0, and the temporary emulator default-engine selection was restored.

### Profile temperature and compatibility

The first numeric-temperature request regression failed by assertion before implementation. Final client tests use a nondefault 1.2 on both explanation and connection-test HTTP bodies. They verify numeric serialization, absent reasoning overrides, visible provider rejection without hidden retry, and rejection of invalid temperatures before any request. Store/session tests cover independent profile temperatures, protected-key retention, malformed legacy values and temperature-sensitive answer reuse.

On Android, an invalid 2.5 draft showed validation and disabled the actual connection-test button. After choosing 0.7, the real connection test reached a controlled local provider with numeric temperature 0.7 and neither thinking nor reasoning_effort. Saving and reopening the editor retained 0.7. Actual reading-popup requests carried the same value; tab switching did not add requests. The second saved profile retained its independent 0.3.

Removing a valid legacy thinking flag initially raised a false corrupt-preferences warning. Its regression failed by assertion, then passed after migration-only rewriting was separated from invalid-value repair. Existing keys survived.

### Adversarial fixes and evidence limits

Independent profile and speech adversaries re-reviewed the repaired source and reported no remaining evidence-backed P0/P1/P2 findings in their assigned scopes. Confirmed repairs include stale prepared callbacks after recording fallback, obsolete TTS initialization errors aborting newer recordings, the false migration warning, and the parent's audio-focus ownership finding.

All three playback-ownership regressions were observed assertion-red before their fixes. The focus fixture was then corrected to account for automatic shadow utterance completion and to compare framework focus objects rather than a shadow wrapper; its final positive checks prove fallback speech and release of the original focus. Compilation, local HTTPS-fixture and post-completion assertion errors are harness corrections, not additional product-regression evidence.

These update model requests used fixture-only credentials and a controlled SSE provider, not a new live DeepSeek/Doubao acceptance run. No physical OnePlus device was attached; its engine behavior and perceived accent quality are not claimed as device-tested. Recorded coverage/connectivity remains conditional, and sentence speech still requires some usable English TTS capability. Private screenshots and logs remain ignored. The controlled model provider used for this update was stopped and its script removed; earlier local verification artifacts were not deleted.

## Reader controls, selection, search and free word audio update (2026-10-16)

Final package: `app.margin.reader` version `1.2.0`, version code `3`, universal debug APK. In an isolated detached checkout of `d00c736` with only this change applied, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passed with 174 tests in 25 classes, zero failures, errors or skips. Lint reported zero errors and 43 warnings, not a warning-free build. The APK is 56,293,374 bytes against 56,374,583 for the pre-change build: no speech model or voice data was added, and no paid audio service, API key or bundled engine is present.

### Baseline-red regression evidence

- Voice normalization: an isolated baseline worktree probe called `configureSpeech` with a voice reporting `Locale("eng","GBR")`, successful voice selection and `LANG_NOT_SUPPORTED` from the language API. It failed by assertion: expected `Available`, was `Unsupported`. The permanent case is `iso3EngineIdentifiersStillNameEnglishAndTheRequestedCountry`.
- Held-pointer ownership: the equivalent probe (`holdStart`, then a session clear, then assert the pointer is still held) failed by assertion against the pre-fix tree before the permanent regression was added.
- Adjust after Quick Explain: the baseline probe failed by assertion because `adjust()` permanently consumed Quick Explain for that selection.
- The rewritten `SelectionSession` interface makes the new session tests compile-red against the baseline by design; no compatibility shim was retained, so assertion-level red evidence is claimed only for the three probes above.

### Native behavior on the API 35 emulator

Reading controls, with synthetic fixtures and no production data:

- The bottom rail renders exactly three 48 dp actions (chapters, search, reading appearance); the back and close actions are gone. Back hides the rail and stays in the reader; a second Back returns to the library.
- Paragraph geometry measured through the WebView was identical with the rail shown and hidden, so showing controls does not repaginate or move the reading position.
- The current chapter title renders top-left from the publication's own titles, visible while chrome is hidden; at 1.5x system text the label stayed at its capped size instead of reaching the first book line.

Search:

- Rows are full width with the resolved chapter title on its own line and a bounded, ellipsis-marked snippet; the matched word is highlighted. The pre-change build wrapped whole paragraphs in rounded buttons that clipped them, which no longer occurs.
- The query field, result count and Close footer stayed fixed while the result list scrolled independently; "More results" grew the list from 18 to 36 loaded matches; an unmatched query reported "No matches.".
- Tapping a result left the sheet and opened that locator: the reader showed chapter page 5/5 containing the selected passage.
- At 1.5x system text the field, status and footer stayed visible with the keyboard up, and rows wrapped to two-line snippets without clipping.

Settings at 1.0x and 1.5x system text:

- Appearance is three side-by-side theme tiles, wrapping to two per row at 1.5x; Pronunciation is one auto-pronounce row, one US/UK selector and adjacent Word/Sentence preview actions, with the provider notice, errors and system-speech entry one tap away under "Audio details".

Selection and lookup:

- Quick Explain on, raw hold-and-drag: the native toolbar was created while the pointer was still down, no read or request happened before release, the released read was confirmed, and exactly one popup and one request carried the released phrase. The mid-gesture screenshot shows no popup.
- Adjust kept the native selection, both handles and the highlight, and closed the popup; a subsequent end-handle drag produced one confirmed release read and one new request for the revised range.
- Quick Explain off: releasing a drag produced no popup and no request, the highlight and handles stayed usable, and explicit Explain sent the exact released phrase.
- Explanation rendering: meaning, contextual meaning and both examples rendered with the popup sized to its content.

Speech:

- Sentence preview and sentence actions used the installed system engine with real native audio frames and the notice that the requested accent is not guaranteed, rather than a false "not installed" error.
- Word audio for both UK and US came from the public provider and played through native media decode with real audio frames; with networking disabled the action fell back to system speech with a truthful notice and no stale credit. The shown credit was `Audio: Youdao Dictionary`.
- Provider sentence synthesis was probed separately and answered HTTP 500 for all 15 tested example sentences, so phrases and examples intentionally remain on system speech rather than being routed to that endpoint.

### Defects found during review and runtime, and their evidence

- The explanation body rendered empty with the footer mid-card in an interim build. The cause was the popup footer growing to its incoming maximum height while its buttons filled that height, starving the weighted scrollable body. Reproduced on that build, and rendering was confirmed restored after the footer returned to its measured height. This is a build-specific defect of the interim state, not of the delivered source.
- An early candidate release explained the initial word while the finger was still down. The source-input clear ran on pointer-down, and the session clear reset the physical pointer flag, so the mid-gesture toolbar looked released. Reproduced with a mid-hold screenshot and the decision trace; the fix keeps pointer ownership separate from selection state and requires an observed release.
- Native selection reads that returned A, B, B confirmed B; the agreement check ran before each read instead of after it. Corrected with a permanent session-level case.

### Remaining evidence boundaries

- No physical OnePlus was available. That engine's identifier reporting, handle behavior and perceived accent quality remain unverified; the emulator installed engine is a different implementation.
- The word-audio provider is an unofficial public endpoint used without permission or licence claims: availability, coverage and accent quality are not guaranteed, only the requested word is sent, and nothing is cached to disk. Recorded playback frames are not evidence of accent quality.
- The popup is absent from uiautomator dumps on this emulator, so its footer geometry was derived from screenshots; a measured ~20 px offset between the drawn label and the responding touch target was observed once and not independently measured from the view tree.
- These checks used a controlled synthetic provider, not a new live commercial-model acceptance run. Device state (font scale 1.0, Quick Explain on, dark theme, UK accent, auto-pronounce off) was restored and the temporary provider was stopped.
- Raw multitouch injection was required: `adb shell input motionevent` is a no-op on this emulator and `input draganddrop` cancels a selection during its move phase.

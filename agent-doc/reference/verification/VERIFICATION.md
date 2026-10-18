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
- An independently installed open-source eSpeak engine supplied real offline English TTS for verification. Native binding and AudioTrack output confirmed sentence synthesis. This engine is a test-environment dependency, not bundled in Peekturn and not required as the user's engine.
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

## Debug APK packaging workflow (2026-10-16)

The packaging orchestration suite passed all nine tests, covering metadata-selected artifacts, checksums and receipts, dirty-worktree reporting, failed gates retaining prior deliveries, invalid/missing/split outputs, toolchain prerequisites and argument handling.

`tools/package-apk.sh` completed against a temporary detached checkout of commit `d00c736`, with only the packaging script added. The run passed 147 Android unit tests with no failures, errors or skips, assembled the universal debug APK, and passed lint with zero errors and 42 warnings. The collected APK's checksum, debug signature, package ID `app.margin.reader` and version `1.2.0` (code `3`) were independently checked. No device installation, launch or feature acceptance was performed; the receipt records runtime verification as `not_run`.

A separate invocation against the concurrently edited working tree stopped during Kotlin compilation at the unresolved `TextToSpeech.getDefaultEngine()` call in `speech/PronunciationPlayer.kt` and collected no APK. The committed-snapshot success does not validate that changing tree. Temporary build logs and the validated delivery remain outside tracked files.

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

The adversarial pass on the committed change found one wiring defect: `ACTION_CANCEL` (a system gesture or another window taking the touch, with the finger possibly still down) was recorded as an observed release, so a frozen mid-gesture range could be explained automatically. `ReaderActivity` now ends a cancelled stream without recording a release, the decision is pinned by a session-level regression over the Android action mapping, and the learning integration note records the rule.

The review of this delivery (standards, security, spec and adversarial axes) found no P0 or P1 issue and two P2 defects, both inside the live selection read. An answered read that carries no selection was reported as unavailable instead of empty, so an explicit action could tell the user "The selection could not be read" when nothing was selected. The explicit Explain, Dictionary and Copy read also reused the automatic path's pointer gate, so a finger resting on the page turned the user's own command into a failed read. The read rule now lives in `readLiveSelection`, which takes the navigator through a lambda so its four answers are testable; explicit actions read without the pointer gate; and `NativeSelectionReadTest` pins confirmation, moving ranges, empty and unavailable. Restoring the earlier classification in a scratch copy reproduced `expected:<Empty> but was:<Unavailable>`. The same review corrected two documentation overclaims: the toolbar's tap claim and the explicit read's validation. After the fixes the isolated run reported 181 tests with no failures, errors or skips and lint with zero errors and 43 warnings, and the explicit Explain action still opened the released phrase on the emulator.

## Award-level interface pass (2026-10-16)

A design pass judged every shipped surface from rendered screens rather than source: 48 screenshots
across the three themes and two text scales, produced by one capture agent, then read independently by
a craft critic, a needs/accessibility critic, and the main agent. The pass found the interface coherent
but not yet considered: several defects were palette and craft problems that source reading cannot show.

Corrected in this pass:

- The secondary colour family was green in all three schemes while the accepted direction is ink-blue,
  warm text and a gold accent, which put a green selected state on chips, the navigation indicator and
  the pronunciation selector. The family is now warm brown/gold in every scheme, so selected states
  belong to the one accent.
- The manifest's type scale fell back to framework defaults for headlineSmall, titleSmall, labelLarge
  and labelMedium, which left screen titles in stock Roboto next to the serif voice; the scale is now
  stated explicitly and display sizes carry negative tracking.
- The reading rail was drawn in exactly the page paper colour, so it erased roughly 40% of the last
  visible text line instead of reading as a control surface. It now uses the theme's raised container
  with a hairline border, with no change to its footprint.
- Book text selection was the platform's blue in every theme; it now follows the theme accent through
  Readium's own selection variable.
- The rail's three glyphs came from three visual families (heavy filled list, hairline magnifier, heavy
  filled "Tt"); the rail now draws its own two marks on one 24dp grid at one stroke weight, and the
  appearance mark reads "Aa" rather than "Ad".
- Search results repeated the same chapter label on every row, ended snippets on dangling fragments and
  highlighted matches in a hue-less grey; rows now group consecutive matches under one chapter heading,
  snippets end at a sentence, and the highlight uses the container pair that survives all three themes.
- Lookup context arrived with block text glued together ("the bookChapter 1, passage 1"), in the request
  and in the answer's echo; both sides are now collapsed and cut back to their nearest sentence edge.
- The library's navigation bar had no edge so the last shelf row was sliced mid-glyph; the bar now takes
  the theme's surface step with a hairline divider, and the list clears it. The focus cover no longer
  repeats the author line printed beneath it, and the bare "Reading" chip became a labelled progress line.
- Statistics metric values wrapped and orphaned their unit; they now use the compact form, and chart bars
  carry the app's smallest corner radius. Appearance keeps three equal tiles at every text scale, stacking
  only past 1.8x where three columns no longer fit.

Evidence: the fixes were re-captured in `verification-local/runtime-awards/round2/` (54 screenshots,
overwritten after the last two corrections), including a chrome/page pixel comparison proving the rail
still does not repaginate, and the provider request log showing the context payload changed. The unit
suite passed at 186 tests before the last corrections and 192 after, with lint, assemble and an
independent security and standards review of the pass.

A test that pinned the selection tint's exact rgba string while re-deriving the expected value from the
same production expression was deleted: it could not fail for a wrong colour, and the rendered
screenshot is the real check. Remaining known gaps: audible and motion behaviour, and any surface at
display sizes beyond those captured.

## Online sentence speech (2026-10-16)

Sentences and phrases no longer depend on the device's speech engine. A selected phrase or a generated
example is spoken by a public, key-free speech endpoint (Google Translate's `translate_tts`), requested
with the preferred US or UK locale; words gained that same endpoint as a middle tier, so a word the
dictionary has no recording for still speaks instead of falling silent. Device speech remains the
fallback on every path.

Measured on the API 35 emulator with a controlled network, per-track frame counts from
`dumpsys media.audio_flinger`, client `app.margin.reader`:

- phrase "reluctant to leave the familiar streets behind. A new city meant new" - 135360 frames at 24000 Hz = 5.640 s
- example, UK - 184320 frames at 24000 Hz = 7.680 s; the same example, US - 190656 frames at 24000 Hz = 7.944 s
- settings sentence preview, UK - 85248 frames at 24000 Hz = 3.552 s, credit "Audio: Google Translate" visible
- settings word preview "hello", UK - 63825 frames at 48000 Hz = 1.330 s, credit "Audio: Youdao Dictionary"; US - 60096 frames

Online plays carry no TextToSpeech or GoogleTTSService log line and the audio belongs to the app's own
client; the offline plays carry eight TTS lines and the audio belongs to com.google.android.tts. The
en-GB payload is 61440 bytes at 64 kbps = 7.680 s, matching its frame count exactly, and the two
accents differ by the service's own 2112-byte and 264 ms margin for that text, so the accent was
honoured rather than substituted.

With networking removed, both a word and an example showed the notice "No online audio is available for
this text; using system speech. Using system English; requested accent is not guaranteed." with no
provider credit, no stale credit and no error, and playback came from the installed engine.

Attribution persists after playback ends: with the popup open after a completed word clip the credit row
still read "Audio: Youdao Dictionary" (checked directly on the device). The settings disclosure now
names every route and was read back from the rendered screen: "The text you ask to hear is sent to a
public speech service: recorded word audio comes from Youdao Dictionary when it has one, and Google
Translate speech supplies the rest. Both are free and key-free, unofficial and unlicensed, and neither
is guaranteed to stay available. Only the text you ask to hear is sent, never the book's context, and
system speech is the fallback."

Verified: 203 unit tests with no failures, errors or skips; lint and isolated assembly. A security and
standards review of this change found only wording and disclosure gaps, all corrected before the
commit: the settings paragraph that still described the old preview chain, and the in-app notice that
named only the word service while a word could also travel to the speech service.

Evidence limits: this host and emulator have no direct route to Google (the device reaches the
dictionary service directly but receives no packet from translate.google.com), so the online sentence
path was exercised through the machine's own local proxy and, without it, correctly fell back to device
speech. A phone with ordinary internet is not affected by that environment. The word middle tier could
not be exercised because the bundled dictionary contains every word of the fixture and the dictionary
recording service answered for every word probed, so no fixture word triggers it. The service is
undocumented and unendorsed; availability, coverage and voice quality are not guaranteed.

## Microsoft read-aloud sentences and device-speech diagnosis (2026-10-17)

The Google sentence route was removed: it is unreachable from the reader's network, which is why
sentences still did not speak after the previous round. Measured from the development host and from the
emulator: direct requests to `translate.google.com` fail (no response, 100% packet loss from the guest)
while `speech.platform.bing.com` answers directly (voice list HTTP 200, 171 KB). Sentences, phrases and
words with no dictionary recording now go to Microsoft's read-aloud service, which is the service the
widely used open-source Edge client wraps; Baidu's translate speech GET is the second online fallback and
device speech the last one.

The handshake needed an independent Kotlin implementation: a plain SHA-256 token is refused with HTTP 403,
and the service now requires a two-part token whose second half mixes a non-standard digest state with the
standard round function. The port is asserted against five reference tokens produced by the established
client implementation, plus thirteen further timestamps checked during development.

Emulator evidence, no proxy (this network reaches Bing directly and Google not at all):

- Settings sentence preview: 70848 frames at 48000 Hz = 1.476 s, credit "Audio: Microsoft Read Aloud",
  zero TextToSpeech lines in the log for that window.
- Popup phrase speaker: 129024 frames at 48000 Hz = 2.688 s, credit "Audio: Microsoft Read Aloud", tunnel
  `CONNECT speech.platform.bing.com:443` with 50440 bytes answered, no TextToSpeech synthesis.
- Offline: sentence and word both showed "No online audio is available for this text; using system
  speech.", no stale credit, no error, and the installed engine produced 24434 frames for the word.
- The popup's example speaker was not exercised because the local explanation provider stopped answering
  during that session; the same popup's phrase speaker did play.
- The US/UK frame pair could not be captured: the emulator's data path wedged mid-session (connections
  opened while payloads stopped moving in both directions, unaffected by an adb restart, an airplane
  cycle or a reboot). The build maps the accent to `en-US-AriaNeural` and `en-GB-SoniaNeural`, and the
  reference service produces distinct audio per voice, but that pair is not claimed as device-verified.

A real defect was found and fixed while making the device-speech tests honest: one utterance listener was
shared by every engine, so a released engine that delivered a callback late could cancel a request that
had already been handed to the next engine - on a device, silence with no error. Every listener now
requires its own engine as well as the request identity.

Device speech itself was also made less presumptuous, because a Chinese OEM phone was reported as having
no English voice while its engine demonstrably spoke: after the documented per-engine probes, engines that
did start get one final attempt with their own current voice (English text only, labelled as a substituted
voice), and a request that still fails now carries a short report of what each engine said - engine names,
whether voices were listed, each language probe's answer and the verdict. "Not installed" is reported only
when an engine says so. On this emulator the engine reported a usable English voice, so the failure report
was not exercised; the user's phone is the device that will show it.

Verified: 232 unit tests with no failures, errors or skips; lint and isolated assembly. A security and
standards review of this round found that the new WebSocket route followed redirects (the text could have
been re-sent to another host) and that a single oversized frame was copied before the size check; both are
fixed, and the shared classification message is no longer duplicated as a literal.

## Speech failure evidence and build identity (2026-10-17)

A phone in mainland China still reported that no English voice existed after the Microsoft route was
added, and no build could be told apart because every previous package shared version code 3. The
version is now 1.3.0 (code 4), and a request that ends without a speaker reports why.

Changes:

- Each online route now records where it stopped - resolve, connect, TLS, a rejected upgrade with its
  HTTP status, a response status, a first-byte stall, no audio bytes, an unusable answer, an oversized
  answer - plus the elapsed milliseconds. Nothing about the selected text is recorded.
- On total failure the message carries two halves: `online: bing=<outcome> <ms>ms; baidu=<outcome>
  <ms>ms` and the existing device report, preceded by the device clock in UTC and the app version. The
  copy action that already existed for the device report now copies both. Nothing is shown on success.
- Each online route is bounded to about 4 seconds instead of 10, so a blocked route cannot leave the
  reader waiting for two timeouts before the fallback speaks.
- The Microsoft handshake handles clock skew the way the service requires: a 401/403 that carries a
  Date header is retried once with the token derived from the server's time, and the measured skew is
  reported. A wrong phone clock is otherwise invisible.
- The device verdict gained the state it was missing: an engine that accepted the English utterance and
  then failed it is reported as a playback failure, not as "no installed engine offered an English
  voice". That false negative is what made a working Chinese OEM phone look voiceless; it is the most
  likely reading of the user's message.

Verified: 247 unit tests with no failures, errors or skips; lint and isolated assembly. Not verified:
the user's device (still unreachable), and the US/UK frame pair on an emulator whose data path is
wedged. The report format is the evidence path: the reader can copy it and send it back.

### Review of the diagnostics round and its corrections (2026-10-17)

Four independent reviews (standards, privacy, specification, adversarial) covered \`08e589c..61f1c33\`.
The privacy axis was clean; the others found six defects, all in the diagnostic path rather than in
playback, and all corrected before this record:

- The report never reached the reading popup - only Settings rendered it - so a failure while reading
  showed an error with nothing to copy, and any stop (including the popup's own "Speech settings" and a
  pause) erased it. The report now renders under the error in both places with the same copy action, a
  stop keeps it, and only a new request or the reader dismissing it clears it.
- Text that the services decline (beyond their length limit) was recorded as a failed route with
  fabricated "no audio 0ms" evidence. A declined request is now recorded as declined and the message
  says the text is too long for online audio.
- A route that supplied the clip was still described as failed when playback then failed. The message
  now distinguishes "online audio was found but could not play".
- Word requests omitted the dictionary route from the report and hardcoded "both online routes", and
  the dictionary attempt used a longer bound than the others. Every route that runs is recorded in
  order, the message counts the routes actually attempted, and every route shares the same bound.
- Timeouts before a response were always attributed to the connection, hiding TLS and header stalls.
  The observed stage is kept, with "connect" only when nothing was observed.
- An offered English voice that accepted the utterance and then failed had no verdict. It now reports a
  playback failure with the engine evidence, the fourth device-speech outcome.

Verified after the corrections: 256 unit tests with no failures, errors or skips; lint and isolated
assembly. Still unverified: the reader's own phone (the report it can now copy is the next evidence),
the US/UK frame pair, and the service's acceptance of a skew-corrected token on a genuinely wrong clock.

### Self-probing the speech paths on a controlled device (2026-10-17)

The reader's own phone is not reachable from here, so two reachable ends were tested from this side
instead of reporting another guess.

**The failure report, end to end.** A fresh emulator was created from the API 35 image, the delivered
1.3.0 (4) package installed, and the app's HTTPS blocked in both address families for its own uid
(IPv4 was not enough: the first attempt still reached the service over IPv6). With the device's speech
engine disabled as well, the Settings sentence preview produced exactly the report the change exists to
provide, read back from the rendered UI:

```
Both online audio routes failed, and the device's speech engine did not start. Open system speech settings to check it.

online: bing=handshake not completed 2100ms; baidu=connect failed 46ms
device clock: 2026-10-17T09:34:39.365264Z; app: 1.3.0 (4)
Device speech report: engines tried: system default engine; voices listed: not asked; result: the device's speech engine did not start.
```

The copy action was exercised: it changed to "Copied" and the report was placed on the clipboard.

**Route independence.** With Microsoft's range rejected and Baidu untouched, the sentence preview still
played, credited "Audio: Microsoft Read Aloud" at 77184 frames (48 kHz, 1.608 s), so a blocked fallback
does not disturb the primary. With everything blocked for the app, both routes failed as reported and
the engine fallback spoke with the truthful notice.

**A precision defect found by the probe and fixed.** A refused connection on the Microsoft route was
labelled "handshake not completed" rather than a connection failure, because OkHttp wraps the platform
exception; the stage mapping now walks the cause chain (resolve, connect, TLS, timeout-after-headers)
instead of trusting the outermost type, with tests. The remaining wording there is still generic for a
blocked upgrade, noted rather than claimed fixed.

**What this proves and what it cannot.** It proves the report reaches the reader in Settings, is
copyable, distinguishes the two online routes, distinguishes a missing engine from playback failure and
carries the clock and version. It cannot prove the reader's own carrier path, the behaviour of their
OEM engine binary, or audible quality. A fake engine that reports voices and then fails to speak was
designed but not built; the verdict for that case is covered by unit tests instead.

## Chinese interface, two more themes and chapter naming (2026-10-17)

**Interface language.** Every shell, reading, lookup and speech string now lives in resources with an
English source and a Simplified Chinese translation (`values-zh`), and Settings offers Follow system /
English / 中文 through the official per-app locale API with `android:localeConfig`. A Chinese reader gets
a Chinese interface automatically; a learner can keep the interface in English. Book text, chapter
titles, dictionary data and IPA are never translated, and the speech diagnostic report stays in English
on purpose because it is the vocabulary a reader sends back for support.

**Themes.** Appearance now offers five schemes: the existing Focus Dark, Classic White and Warm Paper,
plus Midnight (true black for AMOLED) and Sage (a soft green-grey paper). Midnight's ink measures
17.05:1 on its canvas and 16.44:1 on its surface; Sage measures 12.80:1 and 13.76:1, with muted text at
6.82:1, so both clear the contrast floor the other schemes meet. Reader paper, reader ink, selection tint
and system-bar icon contrast follow each scheme, and the persistence whitelist accepts the new ids
instead of silently falling back to dark.

**Chapter naming.** The label resolved a chapter only when the table of contents named that exact
resource, so a book that titles a part and leaves its chapters untitled showed nothing. A titled part now
names the untitled chapters beneath it through the table of contents' own hierarchy; a named chapter
never lends its name to a sibling or another file, and an ambiguous or untitled position still stays
blank rather than being invented. On the emulator fixture the label continues to read
"Opening the book" with "1 / 5" beneath it.

Verified on the probe emulator: the app launches in English, switching to 中文 redraws the shell
(书架/统计/设置) and every Settings label, the choice and theme survive a cold start (persisted
`appTheme: "sage"`, Chinese interface), and the Follow-system chip names the device's own language
("跟随系统（英语）") rather than the app's override, which needed reading the platform locale list because
`Resources.getSystem()` follows the per-app locale.

Verified: 265 unit tests with no failures, errors or skips; lint and isolated assembly. Not verified:
the Chinese reader dialogs and lookup popup on a screen (the probe device has no book and the fixture
device was used for the label check), and the five themes at 1.5 text scale in Chinese, which are string-
and layout-reviewed only.

## Real-book chapter names and a whole-book page estimate (2026-10-18)

**Fixture.** The reader's copy of *The Alchemist* now lives outside tracked files at
`verification-local/fixtures/the-alchemist.epub` (ignored directory), so real-book checks use the same
publication without committing private book text. Its structure exercises the hard cases: 18 reading-order
resources, a 204-entry nested NCX, a titled part page followed by a long untitled body file, and XHTML
documents whose `<title>` is the file name.

**File names are not chapter names.** Readium reports the resource's own name when its table-of-contents
match fails, and the reading-order link carries the file name too; the label used to print `part0005`
above a chapter the NCX calls "Prologue", and `part0006` on an untitled separator page. A reported title
that equals the resource file name or its stem is now discarded at both resolution points (locator title
and reading-order title), so resolution continues to the real table-of-contents name and an untitled
resource stays blank. Device evidence: the Prologue page reads "Prologue" and the separator page shows no
chapter name; the unmatched body file of Part One also stays blank, as the table of contents names only
the part page and the earlier rule forbids lending a name across resources.

**Section names run forward.** A resource the table of contents does not name keeps the nearest preceding
named section until the next named section begins, the way mainstream readers render a running header.
Part One's title page itself is in the table of contents while its 47-page body file is not, so the body
now reads "Part One"; Part Two's own entry stops the name and its body reads "Part Two".

**One page counter.** The bottom-left showed a chapter counter and, later, a book estimate beside it; two pairs
in one corner forced the reader to reconcile two meanings. It now shows only the page within the whole
book and the book's page total for the current layout. The counter is derived because Readium paginates
one resource at a time: a chapter of at least five pages gives the layout's characters-per-page density,
the text before the current resource converts to pages before adding the exact page within it, and the
publication's character total converts to the book total. The density is cached per layout signature in
display-only preferences, so restarts and short chapters still show a counter.

Device evidence on the API 35 probe emulator, Chinese interface, Sage theme, 176% font: Part One reads
`28 / 557`, the two-page Prologue reads `24 / 557` (so a short chapter still shows the counter), the
second Prologue page reads `25 / 557`, and after a force-stop and cold start into the same Prologue the
counter is still `24 / 557`, proving the cached density is used. The 557 total matches the character
model (187,776 characters over about 337 characters per page). At 100% font the same counter is not
re-measured on device.

Delivered as `margin-1.3.3-7-debug.apk` (versionCode 7); the delivered APK installed over the previous
build on the probe emulator, launched into the Prologue, and read "Prologue" with `25 / 557` at 176%
font, confirming the single whole-book counter and the forward section name.

Verified: 270 unit tests with no failures, errors or skips. Not verified: the estimate against a second
publication, and page counts after a rotation that changes the viewport (the signature includes viewport
size, but no device rotation was exercised this round).

## Web page import (2026-10-18)

**What was built.** The add control offers a file or a link, and a shared link opens the same flow. The
link is fetched directly first; anything that is not clearly the article (403, non-HTML, a challenge page)
falls back to the app's own browser, which uses the device's network and can show a login or challenge for
the reader to complete. The vendored converter (Mozilla Readability plus the save-as-ebook extractor and
EPUB writer, both unmodified) runs inside that page and hands the archive to the existing importer, so no
site-specific code exists.

**Network evidence.** From the host, `curl` with a browser user agent got HTTP 403 for openai.com, and
headless Chrome got Cloudflare's "Just a moment"; the emulator's real Chrome loaded the OpenAI news page.
Inside the app, the import browser's WebView (user agent without the `; wv` token) loaded and extracted an
OpenAI page without a challenge.

**Device evidence (API 35 probe emulator).** Shared
`https://www.anthropic.com/news/claude-discovers-novel-enzyme-system`: extraction produced 12,611
characters of reader-mode content, the generated EPUB imported, and the reader opened at
"Claude discovers a novel enzyme system" with `1 / 28`; article intro, italics and images render.
`https://paulgraham.com/greatwork.html` imported as "How to Do Great Work" with `1 / 168`.
`https://openai.com/index/gpt-4o-system-card/` imported as "GPT-4o System Card" with `1 / 46`, which
confirms the browser passes the bot wall; that particular page's main content is a navigation list, so its
first pages are links rather than prose. The direct activity path with a pasted link was exercised by
starting the import browser for the Anthropic URL and pressing "Import this page", which produced the same
`1 / 28` book.

Delivered as `margin-1.4.0-8-debug.apk` (versionCode 8) from `package.MR3Xya`
(SHA-256 `be33ec5b5fedab97203ecf23e2e1bdaa7c24a0f9f05f2eb1397b5aec2878a56b`). The delivered APK installed
over the previous build; with the reader on top, a shared Paul Graham link reached the import browser and
produced a book (the reader reopened at `1 / 168`), and a shared Anthropic link imported at `1 / 28`.
Forwarding an EPUB through the share activity reached the existing importer (it refused the test's
ungranted MediaStore URI with SecurityException, which proves the URI was forwarded); a real share-sheet
grant was not available to this test harness.

Verified: 276 unit tests (link policy, direct-fetch acceptance, chunk assembly) with no failures, errors or
skips; the pipeline ran on the device for three publications. Not verified: an X/Twitter long-form article
or thread, an interactive challenge completed by hand, rotation during import, and multiple images from
cross-origin CDNs that refuse a CORS fetch.

### Review findings and fixes (2026-10-18)

The implementation review found five defects; all were fixed and the affected paths re-checked.

1. **Bridge trust boundary.** `MarginBridge` was callable from any frame, so an embedded third-party
   frame could push its own archive or cancel the reader's transfer. Each attempt now mints a token,
   installs it in the top document just before starting, and the bridge refuses chunk and finish calls
   without it; cross-origin frames cannot read that variable.
2. **Unbounded direct response.** `contentLength() == -1` (chunked or decompressed) bypassed the 12 MiB
   check because the body was buffered first. The body is now streamed and refused past the limit.
3. **Broken retry.** `WebEpubBridge` settled permanently and `EpubChunkAssembler` kept its indices, so
   "Import this page" after a failure could never succeed. Both are reset at the start of every attempt.
4. **Redirect base URL.** Direct fetch returned only the HTML and loaded it against the original URL, so
   relative resources from a redirected page resolved wrongly. The final response URL is now the base.
5. **Cleartext promise.** The policy accepted `http://`, which the network security config blocks in the
   WebView. Only https is accepted, and the dialog says so in both languages.

A separate defect was found while testing: sharing a link while the reader was on top did nothing,
because the intent reached the task without reaching the library screen. External shares and content
views now enter through `ImportShareActivity`, which requires no library screen and forwards links to the
import browser or EPUB streams to the importer.

Device evidence for this round: with the reader open, a shared Paul Graham link opened the import browser
instead of being dropped; a shared Anthropic link imported to `1 / 28`; the delivered APK installed and
imported the same article. 277 unit tests pass, including the https-only policy, shared-text punctuation,
assembler reset and chunk ordering.


## Complete article import, bilingual learning and reading controls (2026-10-18)

### Article completeness

The supplied X URL contains a trailing numbered-list `1`, giving a 20-digit status id that returns
“Post Not Found”. The reachable 19-digit URL is
`https://x.com/HiTw93/status/2042632355756806281`; ids are never silently rewritten by the app.

The real cause of the preview-only EPUB was the vendored box-based visibility check: an article
container with `display: contents` has no box, so its rendered descendants were deleted from the
Readability clone. A generic host-side visibility correction preserves those descendants without
editing vendor files or adding an X-specific scraper.

On the API 35 emulator, the reachable URL imported into Readium. Inspection of the actual saved
164,285-byte EPUB found one chapter, 6,695 normalized text characters including the title, the opening
“Before AI”, and the final paragraph “Now it matters even more.” The 20-digit URL displayed an
unavailable-page message and the private books directory gained no file.

The real Chromium/vendor regression harness runs with:
`npm ci --prefix tools/tests/webimport && npm test --prefix tools/tests/webimport`.
Both cases pass: the unpatched vendor drops the synthetic article's end marker, while the host fix
preserves it; the actual EPUB writer also retains that marker. Dependencies are pinned in the local
test package. It uses installed Chromium (or `CHROMIUM_PATH`), not a browser bundled into the APK.

### Language and layout

Android screenshots cover Chinese and English Settings, the full-width five-theme picker, the
typography/font picker and reading Options, at the normal 412dp width and at 320dp width with 1.3x
system text. The English toolbar uses “Text”, not a split “Typography” label. Its four docked actions
remain one line at that tested narrow width.

A temporary local SSE provider and a synthetic verification EPUB exercised the real selection-popup
path without a paid provider request. Chinese mode displayed Chinese meaning/context and English
examples with Chinese translations beneath; switching to English requested/displayed English and no
translation. The temporary provider/profile were removed afterward. This verifies request policy,
decoding and UI, not the linguistic quality of any particular remote model.

### Real typography checks

WebView inspection initially found the newly registered Literata face in `error` state: the existing
publication CSP did not allow the new local font directory. The fix adds only
`https://readium/assets/fonts/` to `font-src`; script and network policy remain restricted.
Readium then loaded all five bundled families, including real regular/italic/bold faces, with different
measured glyph widths. Selecting Source Serif 4 through the sheet changed the computed paragraph face.

A renderer-level spacing probe exposed Readium 3.1's factor-of-two preference conversion:
the requested `0.08` produced `0.04rem`. A temporary pinned-toolkit CSS test failed on that exact
comparison, passed after the adapter corrected the scale, then was removed rather than retaining
test-only access to toolkit internals. Preference persistence and stale-draft non-clobber regressions
remain in the suite; the old value-forwarding assertion was removed instead of being re-pinned.

Revealing the dock left the native WebView at 412 × 826 CSS pixels with the same 2,468-pixel document
width. Options exposed immersive mode, scrolling, Quick Explain and auto-pronounce; turning immersive
mode off kept the dock above the system navigation area. Settings and typography no longer duplicate
these switches.

Limits: captures shorter than 500 normalized characters are currently refused, including some
legitimate short posts. The length check is a guard against observed shells, not a universal proof of
article completeness. Interactive X login and every site's paywall/virtualized content are not covered.
Android 28 locale behavior is covered by Robolectric, not a physical API 28 device. No physical-phone
or all-OEM visual claim is made.

Final delivery: `app/build/outputs/delivery/package.bKpUKp/margin-1.5.0-9-debug.apk`,
version 1.5.0 (9), SHA-256
`fc5ccf4a01ac664f0c10cd217c8fd4b0b616bc779bf35b535c72999aa84882b2`.
`./tools/package-apk.sh` completed with 308 unit tests, zero failures/errors/skips, lint and assembly.
The two real Chromium converter tests passed separately. The APK contains 12 font-face TTF files and
five complete OFL license files.

The final delivery APK installed and resumed the real X article in Readium. WebView inspection
confirmed the article's final paragraph, a loaded Source Serif 4 face and persisted `0.08rem` letter
spacing (1.28909 CSS pixels at the tested text size). The earlier half-scale spacing was no longer
present. All temporary provider routing, mock profile and viewport overrides were removed/restored.

## Four review-finding fixes (2026-10-18)

### Checks and package

Executed `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and
`npm test --prefix tools/tests/webimport`, then `./tools/package-apk.sh`.

- 321 unit tests in 43 suites: zero failures, errors or skips.
- Android lint: zero errors, 62 warnings and 2 hints (64 reported issues). Warnings were not
  suppressed, and this is not a warning-free build.
- Real Chromium/vendored converter tests: 2 passed, 0 failed.
- Final package: `app/build/outputs/delivery/package.nWlplI/margin-1.5.1-10-debug.apk`,
  `app.margin.reader` 1.5.1 (10), debug. SHA-256
  `b99fc6de2ae04c9e1d43b3e67643f9e00a6dd9fcaae727c69df56bbaff25da7a`; `shasum -a 256 -c`
  verified the collected file. Receipt revision `c289a36`, dirty worktree `true`.

### Test-first regression evidence

An isolated copy of the pre-fix checkout was given matching regressions before the fixes. Its
targeted run completed 27 tests with 6 failures: three preview refusals, two example-validation
cases and one queued-options case. The copy's existing probes printed:

```
duplicateExamples: ACCEPTED, examples=2
thirdExample: rejected but visible snapshot count=3
preview=692 chars, decision=Import
two option updates from same UI snapshot: quickExplain=false, scrolling=true
```

The current suite contains the surviving equivalents and passed them:
`repeatedRootExamplesAreRejected`, `aRejectedThirdExampleNeverEntersTheInvalidSnapshot`,
`queued options toggles from one stale snapshot preserve both`, the four article-preview tests
(including the 692-character preview and Chinese CTA), and
`everyFailureCategoryMapsToItsOwnLocalizedMessage`. The baseline copy and its probes were
temporary review artifacts and are not retained in the repository.

### Android observations

The collected APK installed with `adb -s emulator-5556 install -r`, reported version 1.5.1 (10), and
cold-launched `app.margin.reader/.MainActivity` on the API 35 emulator.

- The real `https://x.com/HiTw93/status/2042632355756806281` share imported into Readium as
  library book 12. The saved EPUB was 164,288 bytes with 14 entries and 6,774 normalized text
  characters including the title; its final paragraph ended with the exact sentence
  “Now it matters even more.” Readium displayed `1 / 7`.
- A synthetic 1,212-byte HTTPS HTML page (953 normalized article characters) containing an explicit
  “Continue reading the full article” link was opened through the same share/import interface. The
  Chinese UI reported “只读到了页面预览。请打开全文或登录后，再重试导入。” and the library remained at
  12 books; no EPUB was written for the refused capture.
- The default DeepSeek profile had no API key. Selecting text and choosing Explain in the Chinese
  UI displayed the distinct configuration message
  “请在设置中检查模型配置：填写 API 密钥、选择模型，并确认接口地址和温度。” with Retry, rather than
  the former generic explanation error. No provider request or credential was needed.
- Two Options switches (continuous scrolling and Quick Explain) were tapped back-to-back from the
  same open sheet. Settings persisted `quickExplain:true` and `reading.scroll:true`; after a
  force-stop and cold relaunch, both remained enabled in the reopened Options sheet. Immersive
  reading stayed enabled and auto-pronounce stayed disabled.

### Limits

The preview refusal used a public HTTPS echo page, not site-specific paywalls, login flows or a
site matrix; it establishes the policy at the real import bridge, not every publisher's markup. No
live provider request was made, so provider compatibility and model output quality are not
re-verified here; every localization category is covered only by the unit mapping except the
configuration case exercised on device. The Android-native selection action labels remained English
(“Explain”, “Dictionary”, “Copy”) in the Chinese session; this round changed the import and popup
copy, not that native menu. The package is debug-signed for testing; no store signing or remote
publication was performed.

## Continuous narration and app-scoped explanation cache (2026-10-18)

Final package: `app.margin.reader` version `1.6.0`, version code `11`, debug.
Delivery: `app/build/outputs/delivery/package.XEBXmC/margin-1.6.0-11-debug.apk`, SHA-256
`ab1bd86ed7d486a6ec04d4c9dc13caec11f11576c146d70b4cfd450f492bfc65`; the receipt records revision
`c289a36`, dirty worktree `true`, all three packaging gates passed, and runtime verification
`not_run`. The collected APK was installed with `adb -s emulator-5556 install -r` and cold-launched;
the package reported `1.6.0` / `11`.

### Checks

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: 391 tests in 51 classes,
  zero failures, errors or skips.
- Android lint: zero errors, 65 warnings, 3 hints, not a warning-free build.
- Real Chromium/vendored converter suite: 5 passed, 0 failed (`article title`, `display: contents`
  and full-article end paragraph).
- Targeted `ExplanationCacheTest` + `LearningSessionTest`: 26 tests, zero failures. The FIFTEEN-DAY
  regression
  `expiryIsAbsoluteAtFifteenDaysAndRepeatedReadsDoNotExtendIt` passed with the fake clock, as did
  future-clock rejection, LRU batch trim, generation-gated clear, corrupt/wrong-language rejection
  and no-secret-in-name coverage.
- Baseline-red evidence: narration new-contract artifact `artifact://3320`, explanation-cache
  artifact `artifact://3618`. The title regression was run in an isolated snapshot with the old
  `margin-import.js`; exactly 2 positive headline tests failed and the profile negative passed
  (`Example Journal` vs the BlogPosting headline; `Synthetic Author (@synthetic) on X` vs the
  article heading). No shared checkout was reverted.

### Android observations

- **Add menu and title.** The library plus control opened a `DropdownMenu` anchored below it with
  only `From a file` / `From a link`; no centered source chooser appeared. The file choice opened
  the system document picker immediately, and the link choice opened the URL dialog. The real
  `https://x.com/HiTw93/status/2042632355756806281` share imported a 6,755-character EPUB whose
  `dc:title` is `How I Turn Learning Into a Workflow in the AI Era` (not the profile title); its
  final paragraph still ends “Now it matters even more.” The same URL with a trailing `1` appended
  to the 19-digit status id (`…62811`) showed the real X “Post not found” page and refusal, and the
  library stayed at the previous maximum id 15; no silent id repair or success entry occurred.
- **Caches in Settings.** Settings showed adjacent `Explanation cache` (`Saved 15 days · 1 GB
  limit`) and `Sentence audio cache` (`No expiry · 4 GB limit`) rows. The explanation dialog showed
  `Stored: 2.0 kB`; its clear emptied `no_backup/explanations`. The audio dialog showed
  `Stored: 286 kB`; its clear stopped narration and emptied `no_backup/sentence-audio`. Neither clear touched books, reading progress or settings; the two
  synthetic test books created for this run were removed afterward.
- **Explanation cache reuse.** A controlled local HTTP provider (`127.0.0.1:8768` via
  `adb reverse`) was configured as the active profile; no paid endpoint or real credential was
  used. Selecting `was` in `Margin Acceptance` produced a complete answer and one 642-byte cache
  entry. After `force-stop` and cold launch, selecting the same word/context showed the identical
  complete answer with **zero** new provider requests in the provider log. Selecting a different
  text (`She`, then `streets`) produced one new request each, proving different-selection isolation;
  exact context/book/language identity isolation is unit-tested (`sameTermDifferentContextBookLanguageOrModelNeverSharesAKey`). A separate negative regression proves an answer with empty before/after context is neither persisted nor reused across occurrences (`explanationsWithoutContextAreNeverPersistedOrReused`, `explanationsWithoutContextAreNotReusedAcrossOccurrences`). The first provider fixture returned string examples and correctly failed validation;
  the test provider was then corrected to the app's object schema with Chinese translations, not
  the app. The cache uses a 15-day absolute TTL from completion; reads do not extend it.
- **Narration audio and lookahead.** Starting from the visible `First listen` page synthesized the
  heading, `Today is a perfect day.`, `Dr. Smith reads at 3.5 words per second.`, `The room…`, the
  226-character `At dusk…harborlight.` sentence and `FINAL_CHAPTER_MARKER`. Cache keys were the
  expected opaque SHA-256 names; the repeated `Today` sentence existed as one 14,688-byte clip.
  The long sentence was one 85,968-byte clip; `dumpsys media.audio_flinger` recorded its
  24 kHz track for 0x53F40 = 343,872 frames = 14.328 s before the next track, so it was not
  truncated at the 190-character request boundary. HTTPS capture during playback held six
  `speech.platform.bing.com` SNI occurrences and three new TCP handshakes spaced 2.64 s and 5.81 s
  apart, not one chapter burst.
- **Controls and background.** The controls changed pause to `Play`, resume to `Pause`, showed
  `Playing saved audio` for prefetched/cached clips, changed speed from `1×` to `1.25×`, and
  previous/next moved sentences. Screen-off left `MediaSession` `PLAYING` while
  `mWakefulness=Asleep`; `dumpsys media.audio_flinger` showed an active 24 kHz app track
  (0x20CE0 = 134,368 frames), and `cmd media_session dispatch pause/play` changed the session to
  `PAUSED`/`PLAYING`. The emulator has no secure keyguard, so a lockscreen visual panel was not
  captured; the media-session state and audio output are the background evidence.
- **Offline durable cache.** With Wi-Fi/data off, airplane mode on, and after `force-stop` and cold
  launch, starting narration showed `Playing saved audio` and produced real 24 kHz app audio tracks
  with unchanged cache files; no network was available. The cache clear action removed only
  sentence audio; popup word/example pronunciation audio remains memory-only and is untouched by that action.
- **Uncached offline failure.** With the cache empty and airplane mode on, starting narration on an uncached sentence showed the localized “This sentence could not be fetched…” message with Retry and no system-speech fallback or fabricated success.
- **Start location (red then green).** On the pre-fix build, `Margin-Narration-Middle.epub`
  (`Yesterday was ordinary. Today is a perfect day.`), searching `perfect` and starting narration
  wrote `Yesterday was ordinary.` first (`a3f87632…8ea3`), then Today: the old block-start failure.
  After the fix, the same scenario's first spoken/requested/cache unit was
  `Today is a perfect day.` (`eada15cf…ed4b`), and the cache contained only that key. The search
  locator's exact before/highlight/after supplies the start sentence; a shared
  `SentenceStartSkipper` trims leading sentences in that block on every utterance-window
  tokenization, while other blocks pass through. The skipper tests cover target later, target
  first, an absent target falling back after eight segments, repeated start-block tokenization and
  reset. No `Yesterday` or suffix-fragment clip was synthesized in the green run.

- **Private service and awaited clear.** The delivered service no longer accepts an external
  ACTION_START/STOP or opens a book from `onStartCommand`; control binding carries a random
  per-process token and every binder transaction checks the caller UID, while normal Media3
  controller access remains. Settings clear binds with `BIND_AUTO_CREATE`, calls `binder.stop()`,
  waits for cancellation, takes the shared synthesis gate and only then clears. A cache clear
  generation refuses late writes; the in-flight clear regression released a blocked synthesis after
  clear and observed zero bytes and no late write.

### Limits and cleanup

Synthetic EPUBs, the local provider and its profile were used; no private book text, user key or
paid request was involved. The provider log and host fixtures remain ignored under `verification-local/`; the local provider,
`adb reverse`, on-device fixture copies and the temporary model profile key were removed after the
run, and the original profile endpoint/model/temperature were restored. The package is debug-signed for
testing; no physical phone, store signing, release build or remote publication was exercised. The
emulator had no secure lockscreen, so visual lockscreen controls remain unverified beyond the media
session/audio state above. The title import used the signed-in X session already present; it is one
real article, not a site matrix. The explanation run used the controlled fixture provider, not a
live commercial model. Settings clear was exercised on-device when narration was idle; the
active in-flight stop→cancel→gate→clear race is covered by the unit regression, not a separate
device run.

## Docked narration, selected-sentence entry and article authors (2026-10-18)

Delivery: `app/build/outputs/delivery/package.zye8fd/margin-1.6.1-12-debug.apk`,
`app.margin.reader` 1.6.1 (12), debug-signed. SHA-256:
`79f54b0d95eb0a3678e604ee5e9e28fec059652fde8828e0ac71d4e98d6e5bf6`.
The final collected APK installed and cold-launched on API 35 `emulator-5556`; package manager
reported the expected version. No commit, history rewrite, push or store publication was performed.

### Checks and corrected failures

- `./tools/package-apk.sh`: 395 unit tests, zero failures/ignored; lint and assembly passed.
  Lint still reports warnings, so this is not a warning-free build.
- `npm test --prefix tools/tests/webimport`: 10 Chromium conversion regressions passed, including
  author byline, card-only fallback, absent author, conflicting identity, cross-origin same-slug
  rejection, existing structured author, headline and complete-body preservation.
- `node --test tools/tests/reader/narration_anchor.test.mjs`: two real Chromium DOM-probe
  regressions passed, covering a nested inline selection and an absent selection.
- Integration caught a Float/Double height-state mismatch, then a test that incorrectly expected
  the trailing context to lose its leading space. Height state now uses primitive Float state;
  the locator regression checks preservation of the complete before/highlight/after source.
- Native selected-start initially failed despite a readable `perfect` selection: the new probe
  returned `JSON.stringify(...)`, then Android encoded that string again. The callback was not a
  JSON object. Both narration probes now return objects, matching Readium's callback contract.
  The corrected native run resolved the block and started the containing complete sentence.

### Android observations

- The ordinary toolbar and narration transport occupy one full-width bottom slot. Screenshots
  showed book text and the page/progression label above it, without the previous floating card.
  X stopped narration and immediately restored Contents/Search/Text/Options.
- Center taps hid and restored the transport. The visible WebView stayed at 751 CSS pixels high,
  on the same resource and scroll position while hidden. During a cached sentence, the media
  session remained PLAYING and AudioFlinger reported an active 24 kHz track for the app while
  the toolbar was hidden.
- Long-pressing `perfect` in `Yesterday was ordinary. Today is a perfect day.` with Quick Explain
  off exposed the localized read-aloud action first. Playback highlighted and cached the complete
  `Today is a perfect day.` sentence, not the previous sentence or the selected-word suffix.
  Next highlighted the following paragraph; Previous returned to the prior sentence. Speed
  changed from 1x to 1.25x.
- With Quick Explain on, releasing the same selection opened the explanation popup directly.
  Its existing missing-model-configuration message was shown; no commercial model request was
  used for this interaction check.
- Visual checks covered Chinese at 1080x2400, English at 320dp width with 1.3 font scale, a
  840x1280 compact viewport, paginated and scrolling modes, immersive/system-bar modes, and
  gesture/three-button navigation. The long English selection action remained visible before
  overflow. Transport controls fit above the three-button navigation area.
- Sharing `https://x.com/HiTw93/status/2042632355756806281` through the real native importer
  produced `dc:creator=Tw93` and the article headline
  `How I Turn Learning Into a Workflow in the AI Era`. The EPUB retained the final sentence
  `Now it matters even more.`; the SQLite library record and visible bookshelf both showed Tw93.
  The pre-fix import had an empty author. Existing imported books are not retroactively rewritten.

### Limits and cleanup

The interaction matrix preceded the final primitive-state allocation-only change; the final APK
reran all packaging gates and was installed/cold-launched separately. No physical phone or new
lockscreen/background-service matrix was tested in this UI change. Article verification covers
the real X article above and generic synthetic metadata cases, not every publisher.

Temporary library entries and their on-device EPUB fixture were removed. The initially empty
sentence-audio cache was cleared through Settings. Interface language, reading options, viewport,
font scale, navigation mode and network were restored; the temporary WebView port forward was
removed. Screenshots remain ignored under `verification-local/`; no credentials or private book
text were added to tracked files.

## Dynamic toolbar space (2026-10-18)

Version 1.6.2 (13) removes the permanent bottom reservation introduced in 1.6.1.
Delivery: `app/build/outputs/delivery/margin-1.6.2-13-debug.apk`, debug-signed.
SHA-256: `d7e206e118cb39fa6546eba939419bd0def11135cbff5ef87bd8b9f28c91646c`.

Only `ReaderChromePolicyTest` and `ReflowAnchorTest` were run with `:app:assembleDebug`:
10 tests passed. The full unit suite and lint were deliberately not rerun for this correction.
The assembled APK was installed and cold-launched on API 35 `emulator-5556`.

Native bounds showed the ordinary toolbar reducing the WebView bottom from 2337 to 2166 pixels;
hiding it restored 2337. The counter moved from y=2347 back to y=2176 while shown, then returned
to y=2347 when hidden. Narration used the same dynamic space, retained PLAYING while hidden, and
reclaimed all toolbar space even after its extra cached-audio status row had appeared.
X restored the ordinary toolbar, whose space was also released on the next center tap.
Paginated reading with system bars retained their own inset: WebView bottom was 2103 with controls
and 2274 without them. This supersedes the previous fixed-height/no-repagination toolbar contract.

Screenshots under ignored `verification-local/dynamic-*-1.6.2.png` show the rendered result.
The synthetic EPUB and generated audio were removed; scrolling and immersive preferences were
restored. No physical-phone or unrelated feature matrix was rerun.

## System reading font default (2026-10-18)

Version 1.6.3 (14), debug APK:
`app/build/outputs/delivery/margin-1.6.3-14-debug.apk`.
SHA-256: `1048667aa89dcb167fadda85fd7360f6def34d0d9b25f480749bb1ae053cf586`.

Only `ReaderFontsTest` and `ReaderPreferencesTest` were run with `:app:assembleDebug`:
9 tests passed. No full-suite or lint run. The APK installed and launched on API 35
`emulator-5556`. The existing Source Serif 4 choice survived the update. Choosing System font
rendered the paragraph with computed `font-family: system-ui, sans-serif` and no loaded packaged
font faces; the system selection survived force-stop/cold launch. Choosing Source Serif 4 again
loaded its packaged face and restored the paragraph family. The screenshot is ignored at
`verification-local/reader-system-font-1.6.3.png`. Readium's existing heading styling was unchanged.

The synthetic book was removed and the original explicit font choice restored. An emulator proves
the platform-font path, not every manufacturer's downloadable-font integration. No physical phone
or unrelated feature matrix was tested.

## Deep-position toolbar reflow regression (2026-10-18)

Version 1.6.4 (15), debug APK:
`app/build/outputs/delivery/margin-1.6.4-15-debug.apk`.
SHA-256: `1aa4958e94d065b3b6ac8ead792e6224ca2a1b5d605aa72838fbfbfed6dde3f7`.
Installed on API 35 `emulator-5556`, with package version confirmed.

### Failing baseline and cause

The 1.6.3 build reproduced the reported distant jump using a synthetic single chapter with
700 paragraphs and 41 SVG illustrations. At the middle text position, toolbar hide changed
WebView height from 761 to 826 CSS pixels but kept `scrollX=126720`: visible content changed
from paragraphs 349/350 to illustration/paragraph 374. At illustration 340, the same action
kept `scrollX=123428.57` and replaced it with paragraphs 364/365.

Pinned Readium 3.1 source confirms that height-only resizing does not recompute its native scroll
position. CSS columns reflow, so preserving that pixel offset preserves neither text nor images.
Input dispatch consumes center taps before the directional adapter; this was not an edge-zone bug.

The first anchored candidate fixed the distant jump but failed repeated toggles: recapturing the
new page's first visible character on each change walked from paragraph 349 toward 345 after six
taps. Retaining the original precise anchor while it remains visible fixed that second failure.
An isolated pre-fix DOM-probe snapshot also failed the image-before-caption regression while
passing the text visibility case. No shared worktree was reverted.

### Focused verification

- `ReflowAnchorTest`, `ChromeReflowTest`, `ReaderChromePolicyTest`: 34 tests passed with
  `:app:assembleDebug`. No full-suite or lint run.
- Real Chromium reader regressions: five passed, covering native selection probes, image-before-
  caption precedence, visible words within a long paragraph, and ten alternating viewport heights
  restoring an exact character in repeated text. Moving away makes that retained anchor unusable.
- Native deep-text run: ten alternating taps kept the original paragraph-349 character offset 75
  visible. Hidden/shown heights were 826/761, with scroll offsets alternating exactly between
  118080 and 126720. The final shown layout returned to its original position, without ratcheting.
- Native image run: ten alternating taps retained illustration 340. Scroll offsets alternated
  between 115200 and 123428.57 as heights changed; the image remained visible throughout.
- Rapid triple then double center taps respected final visibility parity and kept the original
  text character visible.
- An explicit right-edge page turn advanced normally. Subsequent toolbar toggles retained the new
  page's anchor rather than restoring the preceding illustration.
- Narration transport at the deep position survived four toggles with the same illustration
  visible. Live playback remained PLAYING while controls were hidden; X restored the ordinary bar.

The original scrolling preference was restored. The synthetic EPUB and generated sentence audio
were removed through the app's scoped controls, and the temporary WebView port forward was removed.
Screenshots remain ignored under `verification-local/deep-reflow-*.png`. Verification used the
synthetic illustrated publication, not the user's private book; no physical-phone or unrelated
feature matrix was run.

## Accepted A overlays, sentence-time resume and bottom explanations (2026-10-18)

Delivery: `app/build/outputs/delivery/margin-1.7.0-16-debug.apk`, `app.margin.reader`
1.7.0 (16), debug-signed. SHA-256:
`6e8b6ba47f99293b31e231ed601a6477f69a28ee2ca4833f60089a0870fc004a`.
The final APK installed and cold-launched on API 35 `emulator-5556`; its package version was
confirmed. Production changes remain uncommitted. The accepted throwaway prototype alone was
captured on `prototype/reader-overlay-a` at `3002e148f3bacb4cdb7954e7451644c37e674198` using an
isolated Git index; no main-branch checkout/index or existing history was rewritten.

### Targeted checks

- `ReaderChromePolicyTest`, `ReaderControlsTest`, `ReflowAnchorTest`, `PopupGeometryTest`,
  `NarrationEnginePauseTest`, `NarrationResumePolicyTest`: 29 passed, zero failures/ignored.
- `node --test tools/tests/reader/viewport_reflow.test.mjs`: two real-Chromium typography-anchor
  regressions passed. Dynamic-chrome restoration and selection-narration tests were removed with
  their obsolete production paths, not repinned to the new implementation.
- An isolated copy restored only the former pause semantics (discard checkpoint, always resume
  at zero), retaining the new constructor interface so the tests could execute. Of the three
  actual-engine/ShadowMediaPlayer cases, pause-at-45s and paused-speed-change failed; deliberate
  new-intent playback from zero passed. The current implementation passed all three.
- No full unit suite or lint run. The final touch-target-only polish was assembled separately
  and exercised on Android, without rerunning unrelated tests.
- Prompt/request/schema, parser and shared answer validation sources were SHA-256 identical
  before/after this task (`ExplanationClient.kt`, `ExplanationParser.kt`,
  `ExplanationValidation.kt`). Only the explanation container/positioning changed.

### Android observations

- All four A states were reached: hidden normal reading, expanded four-menu tools, expanded
  narration card above that menu, and collapsed narration transport centred in the footer.
  The final previous/play-pause/next icon centres were x=414.5/540.5/666.5 on the 1080px device;
  the group centre is independent of the page counter at the left. Controls have 48dp targets.
- A 700-paragraph chapter with 41 SVG illustrations stayed at illustration 340 through ten
  menu toggles. Every measurement retained `innerHeight=826` CSS pixels and `scrollX=115200`;
  there was no viewport resize, repagination or accumulated position drift.
- Contents exposed the binoculars action; the existing whole-publication search found
  `harborlight` in the actual EPUB paragraph, not its chapter title. Back returned to Contents.
  With Quick Explain off, native long press showed Explain/Dictionary/Copy and no narration item.
- The actual Microsoft MP3 path paused for lookup at 2702ms and resumed at 2702ms. A later run
  paused at 9494ms, then prepared, sought, completed the seek at 9494ms and started at 9494ms.
  The temporary probe sampled real `MediaPlayer.currentPosition`, not a UI timer. Changing speed
  during a manual pause retained its 3170ms checkpoint. A manually paused lookup remained paused
  after close. Following sentences began at zero rather than inheriting the previous offset.
- The bottom-docked explanation displayed a multi-line Chinese meaning, context, and two English
  examples with Chinese translations from a controlled loopback SSE provider. Its normal
  term/tab/scroll/close/adjust/speaker content remained. This was a fixture response, not a
  commercial model quality check.
- Visual checks covered Chinese at 1080x2400 and English at 320dp width with 1.3 font scale,
  immersive/system-bar modes, and the four tool states. The final English menu labels
  Contents/Layout/Features/Listen fit on one line. No physical phone or new lockscreen matrix ran.

### Cleanup and evidence limits

The real-player timing probe and its call sites were removed before the final build. Both
synthetic books and generated sentence audio were removed; only the loopback fixture's own
explanation-cache records were deleted. The temporary provider profile/server and ADB forwards
were removed. The original profile selection, language, reading switches, viewport, font scale
and Pinyin keyboard subtype were restored. No real credentials or private book passages were
used for requests or logs. Screenshots remain ignored under `verification-local/overlay-*.png`.

The retained prototype preview is served from an external temporary copy, not the Android source
tree; its illustrative content and simulated audio are not shipped. Existing narration cache
quotas/providers and explanation prompts/formats were intentionally unchanged. Physical-device,
commercial-model and unrelated feature coverage are not implied by this focused verification.


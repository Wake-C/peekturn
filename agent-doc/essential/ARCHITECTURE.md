# Integration constraints

## Readium and Android WebView

- A Readium locator identifies a reading position; page indexes do not. Fragment snapshot restoration and publication lifetime interact: the app reopens from its stored locator and closes publication resources after view teardown.
- Chrome is an overlay. Changing the navigator's measured bounds or insets repaginates the EPUB and can move the visible text. Readium has no public vertical-padding preference; the viewport adapter replaces container padding instead of adding another layer.
- Chromium creates and recreates selection ActionMode during dragging. It is not finger release. Finishing it can destroy selection; an empty menu hides the toolbar while preserving the native mode. Automatic lookup depends on observed release and agreeing asynchronous native reads, not elapsed time or menu creation.
- WebView selection reads and Readium location callbacks can arrive after a new gesture. A location callback during a held selection is not permission to clear it. Native selection geometry, resource identity and lifecycle belong together.
- EPUB content is untrusted even when local. Sanitization/CSP and safe locator identifiers protect the navigator's JavaScript boundary; missing optional images or styles are not fatal book failures.

Owners: [ReaderActivity](../../app/src/main/java/app/peekturn/reader/reader/ReaderActivity.kt), [SelectionSession](../../app/src/main/java/app/peekturn/reader/reader/SelectionSession.kt), [ReaderViewport](../../app/src/main/java/app/peekturn/reader/reader/ReaderViewport.kt), [PublicationContentPolicy](../../app/src/main/java/app/peekturn/reader/reader/PublicationContentPolicy.kt).

## Speech providers and device engines

- Popup pronunciation and foreground narration have different lifetimes. Readium's narration session adapter already owns audio focus and notification transport; adding another focus owner causes competing playback decisions. A lookup may resume only the narration pause it owns, never a prior manual pause.
- Android TTS availability, voice metadata and actual synthesis are different evidence. OEM engines can accept English while reporting another voice locale; ISO3 language/country codes also occur. An accepted request does not prove installed assets or correct pronunciation. Actual callbacks, engine version and voice facts distinguish these failures; the shared policy and diagnostics live in code.
- System speech leaves voice and language selection to Android's preferred engine. Android clients cache both their connection and default voice; reconcile the system preference before each request and after initialization, and refresh on foreground return even if the package did not change. Internal activity navigation must not masquerade as an external settings return. Superseded initialization failures/timeouts must not fail the current system choice.
- Refreshing a connection and changing audio identity are separate events. Known system voices keep a stable cache namespace based on the bound engine version, reported voice and render settings. Missing identity metadata permits synthesis but limits reuse to one connection. Android exposes no universal private model revision; a provider that replaces a model without changing its reported voice information needs an explicit cache clear.
- Some device speech services disconnect after pause/background. Callbacks from a released engine must not affect its replacement. Service recovery stays with the same system engine and is bounded across unknown-voice nonce changes. System narration uses complete cached WAV files, so a paused offset is reusable only for the same resolved file and reading intent.
- Android TTS may serialize synthesis even when two file requests are queued. Bounded application concurrency cannot guarantee parallel model inference or uninterrupted playback when sustained synthesis is slower than playback. Avoid Binder metadata polling on cache hits: an engine may serialize those calls behind inference. Paragraph lookahead must stay on Readium's content iterator and preserve its locators, not flatten the chapter.
- Readium's derived narration locator can lag the actual started utterance by one sentence. Bridge only that immediate sentence using the original locator context; never guess farther ahead. The current block's remainder is playable reserve, not a complete following paragraph. Iterator traversal also goes backward for transport history, so a forward start-sentence filter must not consume unrelated earlier blocks.
- Microsoft read-aloud, Baidu `gettts` and Youdao `dictvoice` are unofficial public protocols, not licensed stable APIs. Endpoint/token details belong in their clients; provider promises cannot be inferred from a successful sample. [Notices](../../THIRD_PARTY_NOTICES.md#online-sentence-speech) own attribution.

Owners: [SystemSpeech](../../app/src/main/java/app/peekturn/reader/speech/SystemSpeech.kt), [SpeechDiagnosis](../../app/src/main/java/app/peekturn/reader/speech/SpeechDiagnosis.kt), [SystemSpeechRenderer](../../app/src/main/java/app/peekturn/reader/narration/SystemSpeechRenderer.kt), [NarrationBuffer](../../app/src/main/java/app/peekturn/reader/narration/NarrationBuffer.kt), [NarrationPlayback](../../app/src/main/java/app/peekturn/reader/narration/NarrationPlayback.kt).

## YouTube source redirects

- A working network and cached media fragments do not guarantee source resolution. Google/YouTube can redirect through a challenge route and issue an anonymous session cookie; discarding that cookie repeats the redirect until OkHttp's follow-up limit. Keep only server-issued cookies in the source client's bounded memory jar, respecting host/domain/path/secure flags and expiry. Never import browser/account cookies or persist/log cookie values.
- Source resolution, Media3 READY, advancing playback and caption availability are separate checks. The opt-in Android `VideoPlaybackLiveTest` (`videoLiveId` instrumentation argument) exercises the real service/player and Read/Watch switching. A subtitle HTTP 429 does not imply that video preparation failed; a metadata-only pass does not establish playback.

Owners: [YouTubeSource](../../app/src/main/java/app/peekturn/reader/video/YouTubeSource.kt), [VideoSessionCookies](../../app/src/main/java/app/peekturn/reader/video/VideoSessionCookies.kt).

## Media3 streaming cache

- A custom cache key does not by itself keep a signed media URL off disk: `CacheDataSource` also writes the redirected URI into content metadata. Strip `ContentMetadata.KEY_REDIRECTED_URI` before committing cache metadata. Persist only opaque content identities, not temporary stream addresses.
- A custom `CacheDataSink.Factory` needs the same cache instance as `CacheDataSource.Factory`; the source factory does not inject it. Unidentified streams and cache initialization failures must still be able to stream normally.
- A resolved progressive stream without enough length/revision metadata needs a private session key, not the raw URI and not disabled replay caching. Hold that opaque key in the resolved descriptor; do not reuse it across fresh resolutions where content identity cannot be proved.
- Source resolution, forward buffering, retained samples and disk fragments are separate layers. A subtitle seek reuses the player, but an uncached target still needs network bytes. Disk caching is not an offline-download promise.

Owners: [VideoMediaCache](../../app/src/main/java/app/peekturn/reader/video/VideoMediaCache.kt), [VideoViewModel](../../app/src/main/java/app/peekturn/reader/video/VideoViewModel.kt).

## Web capture and model responses

- A direct HTTP fetch and a real logged-in WebView can receive different pages because of bot protection or account walls. A rendered shell is not evidence of a complete article. Conversion stays local; the vendored extractor/EPUB writer remains unmodified, with app glue outside it.
- The web bridge token distinguishes import attempts, not frames. It is not an origin trust boundary.
- OpenAI-compatible transport does not guarantee strict-schema support. Provider output still needs local validation. Prompt changes also change cached-answer identity; otherwise old answers can hide the new instructions.
- Cache clearing must cancel producers or advance a generation before deletion. Removing files alone lets in-flight work recreate them.

Owners: [WebImportActivity](../../app/src/main/java/app/peekturn/reader/webimport/WebImportActivity.kt), [WebEpubBridge](../../app/src/main/java/app/peekturn/reader/webimport/WebEpubBridge.kt), [ExplanationClient](../../app/src/main/java/app/peekturn/reader/learning/ExplanationClient.kt), [ExplanationCache](../../app/src/main/java/app/peekturn/reader/learning/ExplanationCache.kt). Converter sources and licences: [third-party notices](../../THIRD_PARTY_NOTICES.md#web-page-import).

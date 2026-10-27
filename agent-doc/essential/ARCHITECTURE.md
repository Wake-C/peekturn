# Integration constraints

Read only the section relevant to the change. Current behavior and ownership live in code and tests; this file records external constraints that are easy to miss.

## Readium and WebView

- Readium locators identify reading positions; page indexes depend on layout. Resizing the navigator or changing insets repaginates the EPUB, so showing controls must not resize it.
- Chromium can recreate selection ActionMode while the finger is still dragging. Menu creation is not selection completion; finishing ActionMode can destroy the selection. Asynchronous selection reads and location callbacks can outlive the gesture that caused them.
- Readium's derived narration locator can lag the started utterance. Preserve original locator context rather than guessing positions from flattened text.
- Local EPUBs are still untrusted content. Preserve sanitization, CSP and the WebView bridge boundary.

## Android speech

- An installed engine, accepted language request or voice listing does not prove usable English synthesis. Check actual callbacks and audible output on the target device.
- Android TTS clients cache connections and default voices; system settings can change without the engine package changing. Some engines disconnect in the background or serialize synthesis and Binder metadata calls.
- Readium narration already owns audio focus and notification transport. A second owner can cause competing playback decisions.
- Public speech endpoints are not necessarily licensed or stable APIs. See [third-party notices](../../THIRD_PARTY_NOTICES.md) before changing providers.

## YouTube and Media3

- Google challenge redirects can loop if server-issued anonymous cookies are discarded. Preserve the source client's bounded memory-only cookie handling; do not import browser/login cookies or persist cookie values.
- Source resolution, playable media and captions can fail independently. Subtitle HTTP 429 does not establish media failure.
- Opaque cache keys alone do not protect signed URLs: Media3 also persists redirect URIs in cache metadata and request URIs in DownloadIndex. Keep temporary addresses out of both.
- A custom CacheDataSink factory needs the same cache instance as CacheDataSource; it is not injected automatically. Cached fragments do not prove a complete offline download.

## Web import and model providers

- HTTP fetches and logged-in WebViews can receive different content. A rendered page shell does not prove a complete article. A bridge request token does not establish frame origin.
- OpenAI-compatible transport does not guarantee strict-schema support. Validate responses locally; model HTTP 402 is a payment/balance failure, not schema incompatibility.

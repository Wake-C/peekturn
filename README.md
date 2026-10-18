# Margin

An Android EPUB reader for people who learn English by reading real books. A quiet, book-first interface with contextual help when needed and a return to the same reading position.

## Features

- Original EPUB import through Readium, plus on-device article import from HTTPS links or Android sharing, preserving available article titles and authors. Preview cards and unavailable pages are not saved as successful articles.
- Overlay reading tools with Contents / Layout / Features / Listen. Expanded narration floats above that menu; collapsed narration leaves three centred controls and the page counter at the left, without reflowing the book.
- System reading font by default, with five optional bundled families: Literata, Source Serif 4, Noto Serif, Atkinson Hyperlegible and Source Sans 3. Font size, letter/line/paragraph spacing, margins and top/bottom text distances remain adjustable.
- Five app-wide themes and an interface language choice (Follow system, English or 简体中文), presented as full-width selections rather than cramped grids.
- Reading positions survive restarts and typography changes using Readium locators, not page indexes.
- Contextual explanations follow the interface language: Chinese meaning/context with English examples and Chinese translations, or an English learning experience. A complete answer is reused across restarts for 15 days when the exact context, book/chapter identity, language and provider configuration match; a manual clear or different identity requests again.
- Offline dictionary, online word/example pronunciation with system speech fallback, US/UK accent selection and optional autoplay.
- Continuous sentence read-aloud from the visible position, with play/pause, previous/next, speed, stop and retry controls, screen-off playback and a private offline sentence-audio cache.
- Actual foreground reading-time statistics, with idle detection and local daily storage.

The app contains no sample library, AI answers or invented statistics. The interactive design prototype is a separate artifact, not shipped application code.

## Build and run

Build requirements: JDK 17 and Android SDK platform 35. Set `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir=...`. An Android 8.0+ device or emulator is needed only for installation and runtime checks.

To run checks and collect a versioned debug APK with its SHA-256 and build receipt:

```sh
./tools/package-apk.sh
```

This command also requires a POSIX shell, Python 3 and Git. See the [delivery runbook](agent-doc/reference/delivery/RUNBOOK.md) for outputs, explicit device installation and failure handling. It does not change source or versions and does not configure release signing.

For assembly alone, use `./gradlew :app:assembleDebug`. The debug APK is universal. The Gradle wrapper selects an installed JDK 17 on macOS if `JAVA_HOME` is unset; on other platforms supply Java 17. Windows users can invoke `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug` with JDK 17.

## First use

1. Use the library’s plus button to import an EPUB file or an HTTPS article link. You can also share a file/link from another app; links provide an address, not the other browser’s DOM or cookies. Open/login to the page in Margin when required.
2. Tap the page centre for Contents, Layout, Features and Listen. Contents includes a binoculars button for whole-book text search. Layout opens typography; Features groups immersive reading, scrolling, Quick Explain and auto-pronounce. Global Settings contains appearance, language, AI profiles, pronunciation and cache controls.
3. Add model profiles in Settings using HTTPS base URL, model identifier, API key and temperature. Select the active profile without deleting the others. Temperature changes randomness, not speed; provider support varies. A connection test is a real request and may incur charges.
4. Select a word or phrase and choose Explain or Dictionary. Quick Explain opens help directly. Switching tabs preserves the same answer; explicit Retry requests a new one. A completed answer stays reusable after reopening the book or restarting the app for 15 days when the exact context, book/chapter identity, language and provider configuration still match; Settings → Explanation cache shows usage and clears it.
5. Use the term and example speakers. A single word uses the public Youdao Dictionary word-audio service when it answers; a phrase, an example, or a word the dictionary cannot voice uses Microsoft’s public read-aloud speech service — the one Microsoft Edge uses — which is requested for text up to about 190 characters, and, when that answers nothing, the same text is retried once against the key-free Baidu Translate speech endpoint. Whatever does not answer online falls back to your configured Android speech engine. When the preferred accent is unavailable — including on the Baidu fallback, which offers a single English voice — another usable English voice is accepted with a notice, not an installation error. Settings has separate word and sentence previews, and its “Audio details” expansion explains the online routes. Audio is credited to the service that actually played, with a link; no service states a creator or licence.
6. Use **Listen** to start continuous narration from the current page. Expand the tools for previous/next, play/pause, speed and stop; tap the page centre to collapse them into three centred footer controls without changing text layout. Pause resumes at the audio time inside the sentence, not its beginning. Closing a lookup automatically continues only narration that the lookup interrupted; an existing manual pause stays paused. Sentence generation, offline caching, screen-off playback and the Microsoft-only narration route remain unchanged.

To check system speech, open Settings → Pronunciation and try **Preview English sentence**. If a speaker still fails, the message carries a copyable report of what each online route and the device's engines answered, shown under the error in the reading popup and in Settings; copy it when asking for help. That preview tries the Microsoft read-aloud service, then Baidu Translate, so it only reaches your system speech engine when neither can answer; if it fails, use the speech-settings action to choose an English-capable text-to-speech engine or download English voice data. A phone assistant's built-in voice is not necessarily available to Android text-to-speech.

## Privacy and data

Books, progress, preferences and statistics are local. Model requests contain only the selected text, bounded nearby snippets and available book/chapter titles. API keys use Android Keystore-backed encryption and app backup is disabled. Completed model answers are stored in app-private, no-backup storage for at most 15 days and 1 GiB, keyed by the exact context, book/chapter identity, language and provider configuration; Settings shows usage and clears them. Offline dictionary lookup does not contact a provider. A word-audio action sends exactly one word to the public Youdao Dictionary endpoint — never its sentence, book text or chapter titles — and the returned popup audio is played from memory rather than stored. That endpoint is unofficial, needs no key and is not guaranteed to answer. A phrase, an example, or a word whose dictionary audio is missing goes to Microsoft’s public read-aloud speech service, and when that answers nothing the same text is retried once against the key-free Baidu Translate speech endpoint; both are equally unofficial and key-free. In every popup case only the text you asked to hear leaves the device — a word, a phrase or a sentence — never surrounding context, book or chapter titles, and never credentials; text longer than about 190 characters is not sent at all. Popup audio is not cached or bundled, and system speech remains its fallback. Continuous narration is separate: it sends the current sentence and at most two following sentences to Microsoft’s service only, never falls back to another voice, and stores complete sentence clips privately (up to 4 GiB, no expiry) for offline replay. Settings shows both caches’ usage and clears either one without touching books, progress or configuration.

See the [selection-context contract](agent-doc/reference/learning/SPEC.md#selection-context) for exact snippet limits and [provider integration](agent-doc/reference/learning/INTEGRATION.md) for structured-output compatibility. Provider/model support varies; malformed, truncated and refused output is not displayed as successful completion.

## Project documentation

- [Product scope, design and acceptance](agent-doc/reference/SPEC.md)
- [Architecture and shared ownership](agent-doc/essential/ARCHITECTURE.md)
- [Feature requirements and integration routes](agent-doc/reference/AGENTS.md)
- [Domain terminology](CONTEXT.md)
- [Verification evidence and remaining boundaries](agent-doc/reference/verification/VERIFICATION.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The offline dictionary is independently built from ECDICT upstream. Its source revision, hashes, license and rebuild command are recorded in the [notices](THIRD_PARTY_NOTICES.md#ecdict-offline-dictionary).

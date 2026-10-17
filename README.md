# Margin

An Android EPUB reader for people who learn English by reading real books. A quiet, book-first interface with contextual help when needed and a return to the same reading position.

## Features

- Original EPUB import and rendering through Readium, chapter navigation, in-book search, configurable typography and page/scroll reading.
- Optional immersive fullscreen reading, enabled by default; center-tap overlay controls, quiet chapter/page indicators, and independently adjustable top/bottom content spacing.
- One Focus Dark, Classic White or Warm Paper appearance choice across the entire app and reader.
- Automatic reopening of the last-read book at its stored Readium locator. Reading positions survive app restarts and typography changes.
- Structured contextual AI explanations with meaning, contextual usage, UK/US IPA and playable examples. Saved provider profiles switch without repeated setup and have independent temperatures. Successful answers are reused within the reader session; dictionary tabs do not repeat paid requests.
- Optional Quick Explain, explicit offline dictionary, online word and sentence pronunciation audio with system speech as the fallback, preferred US/UK accent and optional autoplay.
- Actual foreground reading-time statistics, with idle detection and local daily storage.

The app contains no sample library, AI answers or invented statistics. The interactive design prototype is a separate artifact, not shipped application code.

## Build and run

Requirements: JDK 17, Android SDK platform 35, an Android 8.0+ device or emulator. Set `ANDROID_HOME` or an ignored `local.properties` containing `sdk.dir=...`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.margin.reader/.MainActivity
```

The debug APK is universal. The Gradle wrapper selects an installed JDK 17 on macOS if `JAVA_HOME` is unset; on other platforms supply Java 17. Windows users can invoke `java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug` with JDK 17.

## First use

1. Use the library’s plus button to add a readable, DRM-free `.epub`, or open/share one from another app.
2. Tap the page centre to show reading controls — chapter contents, search and appearance, plus typography, size, line/paragraph spacing, horizontal margins and top/bottom distances; immersive mode can be disabled. The single Appearance choice applies everywhere.
3. Add model profiles in Settings using HTTPS base URL, model identifier, API key and temperature. Select the active profile without deleting the others. Temperature changes randomness, not speed; provider support varies. A connection test is a real request and may incur charges.
4. Select a word or phrase and choose Explain or Dictionary. Quick Explain opens help directly. Switching tabs preserves the same answer; explicit Retry requests a new one.
5. Use the term and example speakers. A single word uses the public Youdao Dictionary word-audio service when it answers; a phrase or example uses the public Google Translate speech service, which is requested for text up to about 190 characters. Whatever does not answer online falls back to your configured Android speech engine, and the word path also tries the speech service before that. When the preferred accent is unavailable, another usable English voice is accepted with a notice, not an installation error. Settings has separate word and sentence previews, and its “Audio details” expansion explains both online routes. Audio is credited to the service that actually played, with a link; neither service states a creator or licence.

To check system speech, open Settings → Pronunciation and try **Preview English sentence**. That preview tries the online speech service first, so it only reaches your system speech engine when the service cannot answer; if it fails, use the speech-settings action to choose an English-capable text-to-speech engine or download English voice data. A phone assistant's built-in voice is not necessarily available to Android text-to-speech.

## Privacy and data

Books, progress, preferences and statistics are local. Model requests contain only the selected text, bounded nearby snippets and available book/chapter titles. API keys use Android Keystore-backed encryption and app backup is disabled. Offline dictionary lookup does not contact a provider. A word-audio action sends exactly one word to the public Youdao Dictionary endpoint — never its sentence, book text or chapter titles — and the returned audio is played from memory rather than stored. That endpoint is unofficial, needs no key and is not guaranteed to answer. A phrase or example goes instead to the public Google Translate speech service, which is equally unofficial and key-free, and a word whose dictionary audio is missing uses that service too. In every case only the text you asked to hear leaves the device — a word, a phrase or a sentence — never surrounding context, book or chapter titles, and never credentials; text longer than about 190 characters is not sent at all. Nothing is downloaded to disk, and no audio is cached or bundled. System speech remains the fallback wherever an online route cannot answer, and your Android speech engine may itself use network voices when no usable offline English voice is installed.

See the [selection-context contract](agent-doc/reference/learning/SPEC.md#selection-context) for exact snippet limits and [provider integration](agent-doc/reference/learning/INTEGRATION.md) for structured-output compatibility. Provider/model support varies; malformed, truncated and refused output is not displayed as successful completion.

## Project documentation

- [Product scope, design and acceptance](agent-doc/reference/SPEC.md)
- [Architecture and shared ownership](agent-doc/essential/ARCHITECTURE.md)
- [Feature requirements and integration routes](agent-doc/reference/AGENTS.md)
- [Domain terminology](CONTEXT.md)
- [Verification evidence and remaining boundaries](agent-doc/reference/verification/VERIFICATION.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The offline dictionary is independently built from ECDICT upstream. Its source revision, hashes, license and rebuild command are recorded in the [notices](THIRD_PARTY_NOTICES.md#ecdict-offline-dictionary).

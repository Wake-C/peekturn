# Margin

An Android EPUB reader for people who learn English by reading real books. Focus is a quiet dark interface: a book, contextual help when needed, and a return to the same sentence.

## Features

- Original EPUB import and rendering through Readium, chapter navigation, in-book search, configurable typography and page/scroll reading.
- Automatic reopening of the last-read book at its stored Readium locator. Reading positions survive app restarts and typography changes.
- Structured, streamed contextual AI explanations with meaning, contextual usage, UK/US IPA and playable examples. One configurable OpenAI-compatible model; no prompt editor or chapter translation.
- Optional Quick Explain, explicit offline dictionary, and Android system pronunciation with US/UK preference and optional autoplay.
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

1. Import a readable, DRM-free `.epub` using the document picker or an external Open With/Share action.
2. Read normally. Settings inside the reader control type, spacing, margins, paper and page/scroll mode.
3. Configure your model's HTTPS base URL, model identifier and API key in Settings. Connection testing uses a real request and may incur provider charges.
4. Select a word or phrase and choose Explain or Dictionary. Enable Quick Explain for automatic lookup from a new selection.
5. Use the term/example speakers. Install the desired English voice through Android speech settings if unavailable.

## Privacy and data

Books, progress, preferences and statistics are local. Model requests contain only the selected text, bounded nearby snippets and available book/chapter titles. API keys use Android Keystore-backed encryption and app backup is disabled. Offline dictionary lookup does not contact a provider. System speech may use network voices if the engine has no installed offline equivalent; voice capabilities are engine-dependent.

Readium supplies up to roughly 200 characters on each side of a selection in the pinned implementation. Margin further caps this to the last 30 words before and first 30 after. Complete paragraphs and a minimum context length are not guaranteed.

The OpenAI endpoint uses strict JSON schema mode. DeepSeek and custom compatible endpoints use JSON object mode plus application validation. Provider/model support varies; malformed, truncated and refused output is not displayed as successful completion.

## Project documentation

- [Product specification](docs/SPEC.md)
- [Architecture and ownership](docs/ARCHITECTURE.md)
- [Domain terminology](CONTEXT.md)
- [Verification evidence and remaining boundaries](docs/VERIFICATION.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The offline dictionary is independently built from ECDICT upstream. Its source revision, hashes, license and rebuild command are recorded in the notices. No source or assets were copied from VibeReading.

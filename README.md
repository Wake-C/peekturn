# Peekturn

<p align="center"><img src="docs/images/icon.png" width="96" alt="Peekturn app icon"></p>

An Android reader for finishing real English books.

[简体中文](README.zh-CN.md) · Version 1.0.0 · Android 8.0+

## Have you ever finished an English book?

Not a graded reader. Not a summary. A real book, from the first page to the last.

There is no shortage of English reading apps. They promise more features, more translations, more dashboards, more ways to turn reading into a course. But after all that, did your English actually grow?

Vocabulary and a feel for words do not come from collecting definitions. They come from meeting the same word again and again, in different contexts, until its meaning becomes something you can sense rather than recite.

So Peekturn does only the core work:

- read the book aloud;
- show a word's core meaning;
- show what that word means in this sentence;
- give example sentences that make the usage concrete.

The goal is not to memorise a definition and move on. It is to understand a word through its use and its culture, while you keep reading without stopping.

## The reading surface

<p align="center">
  <img src="docs/images/reader-tools.png" width="31%" alt="Reader tools">
  <img src="docs/images/reader-expanded.png" width="31%" alt="Expanded narration controls">
  <img src="docs/images/reader-compact.png" width="31%" alt="Compact narration controls">
</p>
<p align="center"><sub>Accepted A interaction prototype, shown in Simplified Chinese. Demo text, not a bundled book.</sub></p>

## What it does

- Imports original EPUB files through Readium, plus articles from HTTPS links or Android sharing. Available titles and authors are kept; preview pages and unavailable articles are not saved as successes.
- Keeps the book in Readium's own navigator. Reading positions use locators, not page numbers, so they survive restarts and typography changes.
- Opens contextual help on a selected word or phrase: a core meaning, a meaning in this context, and examples. Quick Explain opens it directly.
- Keeps completed explanations for 15 days when the exact context, book or chapter identity, interface language and model profile still match. Settings shows cache usage and clears it.
- Includes an offline dictionary, online word and example pronunciation with system speech as fallback, US or UK accent choice and optional autoplay.
- Reads the current sentence and a bounded lookahead aloud, with play, pause, previous, next, speed, stop and retry. Narration keeps playing with the screen off and stores sentence audio privately for offline replay.
- Supports five bundled reading fonts, adjustable size, spacing and margins, five app themes, and an interface language choice: Follow system, English or 简体中文.
- Records actual foreground reading time with idle detection. There are no invented statistics, sample books or demo answers.

## What it does not do

- No whole-book translation or flattened chapter text.
- No vocabulary drills, flashcards or streak mechanics.
- No cloud library, accounts or sync.
- No sample library and no fabricated reading data.

## Build

Requirements: JDK 17 and Android SDK platform 35. Set `ANDROID_HOME`, or put `sdk.dir=...` in an untracked `local.properties`.

To run checks and collect a versioned debug APK with its SHA-256 and build receipt:

```sh
./tools/package-apk.sh
```

This needs a POSIX shell, Python 3 and Git. It runs the debug unit tests, Android lint and APK assembly, and does not change source or versions. It does not configure release signing.

To build only the debug APK:

```sh
./gradlew :app:assembleDebug
```

The debug APK is universal. On macOS the Gradle wrapper selects an installed JDK 17 when `JAVA_HOME` is unset. On other platforms, provide Java 17.

## Privacy

Books, progress, preferences and reading statistics stay on the device. Model requests contain only the selected text, bounded nearby snippets and available book or chapter titles. API keys are encrypted with Android Keystore, and app backup is disabled.

Word and sentence audio uses public, key-free services. Only the text you asked to hear leaves the device, never surrounding book text or credentials. The offline dictionary does not contact a provider. See the [third-party notices](THIRD_PARTY_NOTICES.md) for the exact routes and attribution.

## Documentation

- [Domain terminology](CONTEXT.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Apache-2.0 license](LICENSE)
- [Release process](RELEASE.md)

The offline dictionary is built independently from ECDICT. Its source revision, hashes, licence and rebuild command are recorded in the [notices](THIRD_PARTY_NOTICES.md#ecdict-offline-dictionary).

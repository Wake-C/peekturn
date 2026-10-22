# Peekturn

<p align="center"><img src="assets/images/icon.png" width="96" alt="Peekturn app icon"></p>

An Android reader for finishing real English books.

**[Download the Android APK](https://github.com/Wake-C/peekturn/releases/latest)** · [简体中文](README.zh-CN.md) · Version 1.2.3 · Android 8.0+

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

## See it in motion

<p align="center"><img src="assets/video/peekturn-intro.gif" width="58%" alt="Peekturn intro: reading, narration, word lookup and URL import"></p>

A 28-second tour that loops: read, listen, look up a word, and import an article from a URL.

## What it does

- Imports original EPUB books, and articles from a URL or Android sharing. Imported books keep their available titles and authors.
- Keeps your place when you close the app, restart the phone or change the text size.
- Shows an explanation for the selected word or phrase, including its meaning in the current sentence and examples.
- Includes an offline dictionary, online pronunciation for words and examples, a US or UK accent choice, and optional autoplay.
- Reads continuously from the current sentence, with play, pause, sentence and paragraph navigation, speed and stop. It prepares upcoming paragraphs, keeps playing with the screen off, and saves completed clips privately for offline replay, including Android system speech.
- Offers five reading fonts, adjustable size, spacing and margins, five paper and dark themes with blue-accent controls, and an interface language choice: Follow system, English or 简体中文.
- Shows your actual reading time. There are no invented statistics or demo answers.
- A fresh install includes *The Prophet* by Kahlil Gibran from Project Gutenberg. It is public domain in the USA and stays in your local library like any imported book.

Explanations use an AI provider you configure yourself. Add an HTTPS endpoint, model name and API key in Settings. Peekturn does not include a key or a canned answer. A connection test is a real request and may incur charges.

For system speech, select **Android system speech** in **Settings → Pronunciation**, then open **System speech settings** to choose Android's preferred engine. Manage models, voices and language in that engine's app; Peekturn leaves them untouched. Engine changes apply on the next speech request, and returning from system or engine settings refreshes cached connections without restarting Peekturn.

System narration caches are separated by engine version, reported voice and synthesis settings. Refreshing a connection preserves audio for the same reported configuration. Engines that cannot identify their voice reuse clips only within that connection. If an engine changes its internal model but reports the same voice information, clear sentence audio in Settings. An engine that generates audio more slowly than playback can still exhaust the reserve.

Cold narration prepares the current clip, two complete following paragraphs and at least 15 seconds of playable audio before starting. It then builds toward eight following paragraphs and an adaptive time reserve. End-of-book, unavailable content and resource bounds can leave a smaller reserve; the reader shows what is actually ready. Preparation allows at most two active synthesis requests while building reserve and one when healthy; the engine may still serialize its own model inference. Pause retains completed clips without continuing preparation.

In **Reading features**, enable **Volume keys for narration** to use volume up for the previous sentence and volume down for the next sentence during a narration session in the reader; this is off by default.

Settings includes a live developer report with recent diagnostics, a 16 KiB copy limit and a Clear action. Clearing the report does not remove books, preferences or sentence audio.

## Read from the web

Paste an https link, or share it from another app. Peekturn extracts the article and adds it to your library as a book.

Works with long-form posts on X, articles on OpenAI and Anthropic, Paul Graham's essays, and many standard blog article pages. If a site asks you to sign in or complete a verification, do it in the in-app browser, then import the page. A page that shows only a preview, or no readable article, is refused.

## What it does not do

- No whole-book translation. The book keeps its original layout.
- No vocabulary drills, flashcards or streak mechanics.
- No cloud library, accounts or sync.
- No invented sample library and no fabricated reading data. The one included book is a real public-domain edition.

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

For a signed release, see the [release process](RELEASE.md).

## Privacy

Books, progress, preferences and reading statistics stay on the device. There are no accounts and no cloud library.

When you ask for an explanation, only the selected text, a short surrounding snippet and the available book or chapter title are sent to the provider you configured. Your API key is encrypted on the device. Online pronunciation sends the requested word or sentence. Continuous narration also sends bounded upcoming paragraphs to the selected provider; Android system engines may themselves use network voices. The offline dictionary does not contact a provider. See the [third-party notices](THIRD_PARTY_NOTICES.md) for the exact audio routes and attribution.

## More

- [Release process](RELEASE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Apache-2.0 license](LICENSE)

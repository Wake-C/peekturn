# Peekturn

<p align="center"><img src="docs/images/icon.png" width="96" alt="Peekturn app icon"></p>

An Android reader for finishing real English books.

**[Download the Android APK](https://github.com/Wake-C/peekturn/releases/latest)** · [简体中文](README.zh-CN.md) · Version 1.0.1 · Android 8.0+

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

<p align="center"><a href="https://github.com/Wake-C/peekturn/raw/refs/heads/main/docs/video/peekturn-intro.mp4"><img src="docs/video/peekturn-intro.gif" width="58%" alt="Peekturn intro preview: reading, narration, word lookup and URL import"></a></p>

A 34-second tour: read, listen, look up a word, and import an article from a URL. The preview above loops automatically. [Download the full MP4](https://github.com/Wake-C/peekturn/raw/refs/heads/main/docs/video/peekturn-intro.mp4).

## What it does

- Imports original EPUB books, and articles from a URL or Android sharing. Imported books keep their available titles and authors.
- Keeps your place when you close the app, restart the phone or change the text size.
- Shows an explanation for the selected word or phrase, including its meaning in the current sentence and examples.
- Includes an offline dictionary, online pronunciation for words and examples, a US or UK accent choice, and optional autoplay.
- Reads the current sentence and the next few sentences aloud, with play, pause, previous, next, speed and stop. It keeps playing with the screen off, and sentences you have heard can be replayed offline.
- Offers five reading fonts, adjustable size, spacing and margins, five themes, and an interface language choice: Follow system, English or 简体中文.
- Shows your actual reading time. There are no invented statistics or demo answers.
- A fresh install includes *The Prophet* by Kahlil Gibran from Project Gutenberg. It is public domain in the USA and stays in your local library like any imported book.

Explanations use an AI provider you configure yourself. Add an HTTPS endpoint, model name and API key in Settings. Peekturn does not include a key or a canned answer. A connection test is a real request and may incur charges.

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

When you ask for an explanation, only the selected text, a short surrounding snippet and the available book or chapter title are sent to the provider you configured. Your API key is encrypted on the device. Word and sentence audio uses public, key-free services; only the text you asked to hear leaves the device. The offline dictionary does not contact a provider. See the [third-party notices](THIRD_PARTY_NOTICES.md) for the exact audio routes and attribution.

## More

- [Release process](RELEASE.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Apache-2.0 license](LICENSE)

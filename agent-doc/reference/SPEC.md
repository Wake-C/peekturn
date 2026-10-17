# Margin: product specification

Status: authorized implementation. Android phone first. Focus is the accepted visual direction.

## Product

Margin is an English EPUB reader for sustained, immersive learning. Readers already understand most of the book and request help with unfamiliar words or phrases.

The primary flow is Library → Read → Select → Understand/listen → Return to the same reading position. Statistics describe real reading activity, not inferred vocabulary mastery.

## Scope

Required: EPUB library/import/removal, chapter navigation, book search, font/size/line-height/paragraph spacing/margins and page/scroll choices, persistent reading position, automatic last-book reopening, contextual structured AI explanation, model configuration, offline dictionary, word/example pronunciation, Quick Explain, and actual reading-time statistics.

Excluded: TXT/PDF/other formats, whole-book translation, saved vocabulary/sentences, flashcards, custom prompts, FTP/Web companion, cloud accounts/sync, simulated page curl, audiobook/background narration. No production sample books, fake answers, or fabricated statistics.

## Visual design

Focus: dark ink-blue surfaces (#171E23 background, #20292F surface), warm light text (#E9E8DF), restrained muted text (#A0AAAC), warm gold accent (#D9B783), thin borders (#354147). The library prioritizes the last-read book, cover and direct Continue action. Library, Statistics, Settings are the main destinations. Empty library offers EPUB import instead of invented content.

Appearance is one persistent theme choice: Focus Dark, Classic White, or Warm Paper. It updates Library, Statistics, Settings, reader paper, dialogs, popup and system-icon contrast together. Theme changes preserve model profiles and reading position; there is no second reading-theme setting.

Reader chrome is quiet, and its reading toolbar keeps only chapter contents, search and appearance. Settings stays compact: appearance themes sit side by side and wrap to fewer per row once text is scaled up, related controls stay adjacent, and secondary explanations or the system-speech route stay behind an expandable details control, while errors and notices remain visible without expanding it. Word explanation is a compact but adequately sized popup without a dim scrim, prioritizing meaning, contextual meaning and examples at first glance. Term metadata, speaker and tab visuals are restrained while touch targets remain accessible. Brand name: Margin; original simple margin/book mark. UI language: English. Chinese dictionary meanings may be displayed as dictionary data. A plus icon is the primary add-book action; routine navigation avoids repeated instructional or marketing copy.

Design provenance: the accepted Focus direction is preserved on the local Git branch `prototype/focus-reference`, under `prototype/phone.html` (`variant=C`). That branch is a throwaway interactive design reference; its demo content and browser implementation are not part of the production app.

## Acceptance

1. Fresh install has empty library, usable import and settings, no network requirement to read.
2. Import a real English EPUB; verify chapter/text order, cover, images and nested inline formatting in Readium.
3. Change typography and reading mode; navigate/search; position remains stable across reflow. Search rows show their chapter and snippet, the query field stays reachable, and the top-left chapter title is the actual current chapter while chrome is hidden.
4. Read to a noninitial location; the bottom toolbar offers chapter contents, search and appearance with usable touch targets; background/force-stop/relaunch: last book opens at that location without briefly persisting the first page. Return to Library remains there until a fresh launch or explicit book open.
5. Select repeated words and a multiword phrase; requests contain the selected location's snippets and titles, within the 30-word-per-side cap.
6. Normal/Quick Explain behavior works relative to gesture release, including Adjust in both Quick modes, old-request isolation, partial failures, explicit retry, missing credentials and real connection testing.
7. Offline dictionary works with network disabled. Pronunciation handles both available and unavailable US/UK voices without misleading fallback: word audio sends only the word, sentence audio sends only the text asked for, and device speech remains the fallback when a service is unavailable.
8. Anchored popup remains in usable phone bounds; long answers scroll independently and word/example speech actions are reachable.
9. Reading-time collection uses actual foreground/idle behavior; restart retains totals; empty stats are honest.
10. Build/install/launch the APK; exercise controlled-provider success/error scenarios and document live-provider/physical-device gaps separately. Unit tests cover uncertain boundaries, not source wording.

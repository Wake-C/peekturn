# Peekturn: product specification

Status: authorized implementation. Android phone first. Focus is the accepted visual direction.

## Product

Peekturn is an English EPUB reader for sustained, immersive learning. Readers already understand most of the book and request help with unfamiliar words or phrases.

The primary flow is Library → Read → Select → Understand/listen → Return to the same reading position. Statistics describe real reading activity, not inferred vocabulary mastery.

## Scope

Required: EPUB library/import/removal, chapter navigation, book search, font/size/line-height/paragraph spacing/margins and page/scroll choices, persistent reading position, automatic last-book reopening, contextual structured AI explanation, model configuration, offline dictionary, word/example pronunciation, continuous sentence read-aloud with a foreground media session, Quick Explain, and actual reading-time statistics.

Excluded: TXT/PDF/other formats, whole-book translation, saved vocabulary/sentences, flashcards, custom prompts, FTP/Web companion, cloud accounts/sync, simulated page curl, and audiobook production or a downloadable whole-book audio file. Continuous sentence narration is in scope; it is not an audiobook asset, and its cache contains only service-synthesized sentence clips. No production sample books, fake answers, or fabricated statistics.

## Visual design

Focus: dark ink-blue surfaces (#171E23 background, #20292F surface), warm light text (#E9E8DF), restrained muted text (#A0AAAC), warm gold accent (#D9B783), thin borders (#354147). The library prioritizes the last-read book, cover and direct Continue action. Library, Statistics, Settings are the main destinations. Empty library offers EPUB import instead of invented content.

Appearance is one persistent theme choice: Focus Dark, Classic White, Warm Paper, Midnight (true black for AMOLED screens), or Sage (a soft green-grey paper). It updates Library, Statistics, Settings, reader paper, dialogs, popup and system-icon contrast together. Theme changes preserve model profiles and reading position; there is no second reading-theme setting.

Reader chrome follows the accepted A overlay layout: Contents, Layout, Features and Listen. Contents owns the binoculars entry to whole-book search. Typography and behavior have separate sheets; Quick Explain, immersive mode, scrolling and auto-pronounce live under Features. Narration expands above the menu and collapses into three centred footer controls, with one page counter at the left. Controls do not change the publication viewport. Global Settings uses full-width disclosure rows and theme previews, not a 3+2 grid; profile actions share an overflow menu. Surfaces use the app's semantic colors and readable natural wrapping. English and Simplified Chinese are supported across shell, reader and external import; book text, chapter titles, dictionary data and IPA are not translated. Existing explanation prompts and output formatting are unchanged.

Design provenance: the throwaway interactive prototypes that led to the accepted Focus direction and the accepted A reader layout were design references only. Their demo content and browser implementation are not part of the production app, and they are not part of this repository.

## Acceptance

1. Fresh install has empty library, usable import and settings, no network requirement to read.
2. Import a real English EPUB; verify chapter/text order, cover, images and nested inline formatting in Readium.
3. Change typography and reading mode; navigate/search; position remains stable across reflow. Search rows show their chapter and snippet, the query field stays reachable, and the top-left chapter title is the actual current chapter while chrome is hidden.
4. At a deep illustrated reading location, all four reader/narration chrome states keep navigator dimensions and visible content unchanged. Footer transport is centred independently of the page counter. Background/force-stop/relaunch preserves typography and locator; return to Library stays there until a fresh launch or explicit open.
5. Select repeated words and a multiword phrase; requests contain the selected location's snippets and titles, within the 30-word-per-side cap.
6. Normal/Quick Explain behavior works relative to gesture release, including Adjust in both Quick modes, old-request isolation, partial failures, explicit retry, missing credentials and real connection testing. A completed answer is reused without a new request only after restart when the exact context, book/chapter identity, language and provider configuration match; a different identity, a retry or a manual clear requests again, and a record expires after 15 days.
7. Offline dictionary works with network disabled. Pronunciation handles both available and unavailable US/UK voices without misleading fallback: word audio sends only the word, sentence audio sends only the text asked for, and device speech remains the fallback when a service is unavailable.
8. The bottom explanation surface remains within safe phone bounds; multi-line meanings and long answers scroll without hiding close/adjust or example speech actions. Prompt, schema and response formatting remain unchanged.
9. Reading-time collection uses actual foreground/idle behavior; restart retains totals; empty stats are honest.
10. Build/install/launch the APK; exercise controlled-provider success/error scenarios and document live-provider/physical-device gaps separately. Unit tests cover uncertain boundaries, not source wording.
11. Listen starts from the current visible block and uses the provider selected in Settings without cross-provider fallback. Microsoft and Baidu keep bounded lookahead and provider-separated complete-sentence cache entries; their pause/lookup interruption resumes at the saved audio time. Android system speech uses the shared English preparation policy, bypasses that cache and restarts the sentence on resume. Next/previous/retry/stop invalidate lookup-resume ownership, and manual pause is never undone by lookup close. Long sentences finish or fail explicitly, never truncate; the media session supports screen-off playback.
12. Settings → Developer logs can copy a fresh bounded report after a feature failure or process restart. The report includes actual system/engine capability and failure evidence plus sanitized crash records, without private reading content, credentials or raw exception messages. Copy/Close stay reachable at narrow widths and large font sizes.

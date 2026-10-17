# Margin: implementation specification

Status: authorized implementation. Android phone first. Focus is the accepted visual direction.

## Product

Margin is an English EPUB reader for sustained, immersive learning. Readers already understand most of the book and request help with unfamiliar words or phrases. The application is independent of VibeReading: reuse learned requirements and failure cases, not its code or assets.

The primary flow is Library → Read → Select → Understand/listen → Return to the same reading position. Statistics describe real reading activity, not inferred vocabulary mastery.

## Scope

Required: EPUB library/import/removal, chapter navigation, book search, font/size/line-height/paragraph spacing/margins and page/scroll choices, persistent reading position, automatic last-book reopening, contextual structured AI explanation, model configuration, offline dictionary, word/example pronunciation, Quick Explain, and actual reading-time statistics.

Excluded: TXT/PDF/other formats, whole-book translation, saved vocabulary/sentences, flashcards, custom prompts, FTP/Web companion, cloud accounts/sync, simulated page curl, audiobook/background narration. No production sample books, fake answers, or fabricated statistics.

## Visual design

Focus: dark ink-blue surfaces (#171E23 background, #20292F surface), warm light text (#E9E8DF), restrained muted text (#A0AAAC), warm gold accent (#D9B783), thin borders (#354147). The library prioritizes the last-read book, cover and direct Continue action. Library, Statistics, Settings are the main destinations. Empty library offers EPUB import instead of invented content.

Reader chrome is quiet. Word explanation is a compact anchored popup without a dim scrim; term/speaker and actions remain reachable, answer body scrolls. Highlight the source selection; display US/UK beside the term speaker. Each example has its own speaker. Brand name: Margin; original simple margin/book mark. UI language: English. Chinese dictionary meanings may be displayed as dictionary data.

Design provenance: the accepted Focus direction is preserved on the local Git branch `prototype/focus-reference`, under `prototype/phone.html` (`variant=C`). That branch is a throwaway interactive design reference; its demo content and browser implementation are not part of the production app.

## Library and original books

Import via Android document picker and ACTION_VIEW/ACTION_SEND readable content URI. EPUB only. Copy the original archive to app-private files; metadata, covers and reading order come from Readium. Do not flatten chapters into new plain-text books or implement another paginator. Show import failure without adding a corrupt library entry. Clean up files created by failed imports. File reads are bounded; never log source URI tokens or private passages. EPUB scripts must not execute app capabilities.

Display actual title, author and cover where available; a generated typographic cover is an honest fallback. Find books by title/author. Removal requires confirmation, removes local book/cover/location/time data, and does not modify the original external file.

## Reading and restoration

Use Readium navigation and supported EPUB preferences. Support chapter list, paginated and continuous-scrolling layouts, serif/sans choices, font size, line height, paragraph spacing, margins, and dark/warm/light paper.

Persist Readium locator JSON for each book. Progress percentages and page counts are display derivatives, never restoration identifiers. Persist position serially and flush on background/exit. Restoration passes the persisted locator into navigator creation; initial callbacks must not overwrite it with a default first-page location.

Remember the last successfully opened book. On a new app launch, reopen that book directly at its saved locator. Returning intentionally to Library during the same session must not immediately reopen it. A missing/deleted/unreadable last book falls back to Library with a clear explanation, not a launch loop. No books means Library. Rotation and process recreation must not restore stale runtime page indexes. Model setup and popup dismissal never change reading position.

## Selection context

Exact selected word or phrase is the target. Use Readium selection locator text.before/highlight/after and available book/chapter titles. Trim before to its last 30 whitespace-delimited words and after to its first 30; each source snippet remains limited by what Readium provides (approximately 200 characters per side in the inspected implementation). Sixty surrounding words is a ceiling, not a guaranteed minimum. No full-paragraph reconstruction, chapter parsing, or text search for another occurrence of the word. Missing context is allowed and must not be invented.

Normal selection opens actions Explain, Dictionary, Copy. Quick Explain is a global persisted preference, default off: a newly established selection starts one request. Handle adjustment must not create repeated automatic requests. Adjust selection closes/cancels the popup while preserving the source selection for adjustment; explicit Explain submits the changed phrase. Popup close clears selection. Navigation clears lookup and cancels work.

## AI explanation

One active user-configured OpenAI-compatible chat-completions configuration: display name, base URL, model, API key, thinking toggle (default off). API keys are protected with Android Keystore-backed encryption, excluded from logs/backups, and never embedded in code. Missing credentials produce a configuration message; offline reading/dictionary remain available. Connection test must use the configured endpoint and report real success/failure.

App-owned instructions request: term, partOfSpeech, ukIpa, usIpa, meaning, context, examples (one or two full strings). Easy English appropriate to an ordinary eighth-grade reader; contextual meaning, not a dictionary dump. Selection remains the target; normalize only direct word forms; phrases stay phrases. Source text and titles are user data, not system instructions. No custom-prompt editor.

Request schema-constrained structured output when supported. JSON mode is a compatibility option inside the client, not a promise that any provider supports strict schemas. Validate response fields; no regex guessing of a different answer. Support streamed content and progressive complete fields, keep reasoning separate, and never expose raw JSON or incomplete example text as playable content. Refusal, empty answer, malformed output, HTTP errors, EOF without terminal completion, and token truncation are errors. Keep useful validated partial content labelled incomplete. Retry is explicit and uses latest saved configuration with the original context. Dismiss/leave/new selection cancels the request and ignores old callbacks. Do not add automatic charge-incurring retry loops.

## Dictionary

Bundle a real offline English dictionary with verified upstream attribution. Lookup runs off the UI thread; normalize whitespace/case. Show available phonetic/part-of-speech/meaning fields. Missing entries and unsupported queries have honest states. Dictionary is a secondary explicit action, never a silent substitute for an AI response. Asset and first-use extraction must work without network.

## Pronunciation

Android system TextToSpeech, not LLM-generated audio. Speak exact selected term/phrase or complete example text, never IPA. Global US/UK preference, US default. Select the matching locale and prefer an installed offline voice; do not silently use the other accent. Missing engine/data/locale gives a clear action to system speech settings. No microphone permission.

Manual term speaker is available before AI completes. Playback toggles Stop; a new utterance replaces rather than queues. Auto-pronounce is global, off by default, once per new lookup across Explain/Dictionary. Switching tabs or retrying does not repeat it. Stop on close, adjust, leaving reader, or background. Late initialization/callbacks cannot play obsolete text. Release resources and respect audio focus/media routing.

## Statistics

Persist daily reading-time increments per book. Count only foreground reader time while the user interacted within two minutes; periodic flush at most 60 seconds plus lifecycle/exit flush. Use monotonic time for elapsed time and local calendar dates for aggregation. UI: today, current week (Monday start), cumulative time; recent 7/30 days and recent books with accumulated time. Empty data shows an empty state. No historic backfill or fabricated estimates. Book deletion removes its time records. Popup lookups count as interaction, not separate learning statistics.

## Acceptance

1. Fresh install has empty library, usable import and settings, no network requirement to read.
2. Import a real English EPUB; verify chapter/text order, cover, images and nested inline formatting in Readium.
3. Change typography and reading mode; navigate/search; position remains stable across reflow.
4. Read to a noninitial location, background/force-stop/relaunch: last book opens at that location without briefly persisting the first page. Return to Library remains there until a fresh launch or explicit book open.
5. Select repeated words and a multiword phrase; requests contain the selected location's snippets and titles, within the 30-word-per-side cap.
6. Normal/Quick Explain behavior, adjust selection, old-request isolation, partial failures, explicit retry, missing credentials and real connection testing work.
7. Offline dictionary works with network disabled. Pronunciation handles both available and unavailable US/UK voices without misleading fallback.
8. Anchored popup remains in usable phone bounds; long answers scroll independently and word/example speech actions are reachable.
9. Reading-time collection uses actual foreground/idle behavior; restart retains totals; empty stats are honest.
10. Build/install/launch the APK; exercise controlled-provider success/error scenarios and document live-provider/physical-device gaps separately. Unit tests cover uncertain boundaries, not source wording.

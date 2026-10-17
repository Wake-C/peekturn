# Reader integration

Code paths are relative to `app/src/main/java/app/margin/reader/`.

Last-book reopening is a cold-entry decision. Initial navigator construction uses the stored locator; persisted progress is never replaced by a guessed page index. Screen state and saved publication location are different lifetimes.

`reader/ReaderActivity.kt` deliberately disables automatic Fragment snapshot restoration with `super.onCreate(null)`. It opens the publication and constructs the navigator with the durable locator instead of restoring a transient page-index tree. Publication resources close only after Fragment view teardown. Preserve that restoration boundary when changing Activity or navigator lifecycle handling.

`reader/PublicationContentPolicy.kt` owns publication-content security. A public Readium container/resource transform serves sanitized HTML with a local-only Content Security Policy before Readium injects its trusted scripts. Original archives are unchanged. Authored executable content and automatic remote resources are excluded; explicitly activated external HTTP(S) links remain browser actions.

`reader/NavigationSafety.kt` owns decoded fragment validation across publication links, restored locations, search/TOC navigation and served internal links. The pinned navigator interpolates HTML IDs into native JavaScript, so unsafe string-literal characters must not reach that interface.

`reader/SelectionAnchor.kt` adapts the pinned navigator's selection rectangle: its top includes page padding but its bottom does not. Reassess this adapter when upgrading Readium; never apply a second density conversion to native pixel coordinates.

`reader/ResourceFailurePolicy.kt` distinguishes fatal HTML spine failures from optional image/font/stylesheet failures. An optional resource failure must not erase last-book recovery or close an otherwise readable book.

`reader/ReaderChromePolicy.kt` owns control visibility, center-tap ownership, Back precedence and system-bar preference interpretation. Chrome overlays never participate in navigator measurement; cutouts and explicitly enabled bars reserve stable insets. System edge reveals, popup focus and chrome toggles must not repaginate the book. The toolbar consumes taps inside itself, so a gesture near its controls cannot reach the page beneath. `reader/ReaderChrome.kt` renders the passive chapter/page labels inside those reserved insets and caps their own text scaling so they cannot grow into the book text.

`reader/ReaderPreferences.kt` translates stored font percentages to Readium's multiplier and applies generic typefaces through supported root properties. `reader/ReaderViewport.kt` owns stable native top/bottom text distances, while Readium owns horizontal margins and typesetting. Padding-band input shares native selection ownership: active selection is cleared instead of turning a page, and stale asynchronous selection reads cannot navigate.

`reader/ReflowAnchor.kt` captures the visible text range before a typography change, rather than the start of a long containing paragraph. The probe resource must match the native locator resource; a pending chapter transition defers the change until their identities agree. Current chapter and current/total chapter pages come from navigator callbacks and are passive overlays, never persisted progress. `reader/ChapterTitles.kt` resolves a locator's real chapter name when the locator itself carries no usable title. `ChapterTitles.of(tableOfContents, readingOrder)` builds the index once per publication and `titleFor(locator)` prefers this publication's table-of-contents title for the locator's resource — matching authored hrefs before normalizing fragments, queries and directory-only prefixes, and accepting a shortened entry only when it resolves to exactly one reading-order resource — then falls back to the spine/reading-order link title. It feeds the passive label, the chapter name sent with a lookup and the chapter line of each search result; an unknown chapter stays blank rather than invented, the resolver only collapses and trims whitespace, and the resolved label is display state only that must not be written back to the stored locator.

`ui/BookSearchDialog.kt` owns search presentation: a fixed query field and status with independently scrolling results, and full-width rows built from the reader's resolved chapter title for that match — the same resolver as the top-left reading label, not a bare locator title — plus a match-centred snippet (whitespace collapsed, bounded context on each side, the match emphasised) instead of rendering a whole publication paragraph inside a button. The reader Activity owns query state, the Readium search iterator and paging, and opens a row's locator through the same safety validation as any other publication location.

Publication input bounds are owned by `reader/EpubImporter.kt`; they are checked against actual archive/resource bytes.

# Reader integration

Code paths are relative to `app/src/main/java/app/margin/reader/`.

Last-book reopening is a cold-entry decision. Initial navigator construction uses the stored locator; persisted progress is never replaced by a guessed page index. Screen state and saved publication location are different lifetimes.

`reader/ReaderActivity.kt` deliberately disables automatic Fragment snapshot restoration with `super.onCreate(null)`. It opens the publication and constructs the navigator with the durable locator instead of restoring a transient page-index tree. Publication resources close only after Fragment view teardown. Preserve that restoration boundary when changing Activity or navigator lifecycle handling.

`reader/PublicationContentPolicy.kt` owns publication-content security. A public Readium container/resource transform serves sanitized HTML with a local-only Content Security Policy before Readium injects its trusted scripts. Original archives are unchanged. Authored executable content and automatic remote resources are excluded; explicitly activated external HTTP(S) links remain browser actions.

`reader/NavigationSafety.kt` owns decoded fragment validation across publication links, restored locations, search/TOC navigation and served internal links. The pinned navigator interpolates HTML IDs into native JavaScript, so unsafe string-literal characters must not reach that interface.

`reader/SelectionAnchor.kt` adapts the pinned navigator's selection rectangle: its top includes page padding but its bottom does not. Reassess this adapter when upgrading Readium; never apply a second density conversion to native pixel coordinates.

`reader/ResourceFailurePolicy.kt` distinguishes fatal HTML spine failures from optional image/font/stylesheet failures. An optional resource failure must not erase last-book recovery or close an otherwise readable book.

`reader/ReaderChromePolicy.kt` owns control visibility, center-tap ownership, Back precedence and system-bar preference interpretation. Chrome overlays never participate in navigator measurement; cutouts and explicitly enabled bars reserve stable insets. System edge reveals, popup focus and chrome toggles must not repaginate the book.

`reader/ReaderPreferences.kt` translates stored font percentages to Readium's multiplier and applies generic typefaces through supported root properties. `reader/ReaderViewport.kt` owns stable native top/bottom text distances, while Readium owns horizontal margins and typesetting. Padding-band input shares native selection ownership: active selection is cleared instead of turning a page, and stale asynchronous selection reads cannot navigate.

`reader/ReflowAnchor.kt` captures the visible text range before a typography change, rather than the start of a long containing paragraph. The probe resource must match the native locator resource; a pending chapter transition defers the change until their identities agree. Current chapter and current/total chapter pages come from navigator callbacks and are passive overlays, never persisted progress.

Publication input bounds are owned by `reader/EpubImporter.kt`; they are checked against actual archive/resource bytes.

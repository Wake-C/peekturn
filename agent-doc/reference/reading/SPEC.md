# Reading requirements

## Library and original books

Import via Android document picker and ACTION_VIEW/ACTION_SEND readable content URI. EPUB only. Copy the original archive to app-private files; metadata, covers and reading order come from Readium. Do not flatten chapters into new plain-text books or implement another paginator. Show import failure without adding a corrupt library entry. Clean up files created by failed imports. File reads are bounded; never log source URI tokens or private passages. EPUB scripts must not execute app capabilities.

Display actual title, author and cover where available; a generated typographic cover is an honest fallback. Find books by title/author. Removal requires confirmation, removes local book/cover/location/time data, and does not modify the original external file.

## Reading and restoration

Use Readium navigation and supported EPUB preferences. Font size, serif/sans typeface, line height, paragraph spacing and horizontal margins must affect rendered book content, not only saved controls. Font-size percentage is translated into the renderer's scale units. Independent top/bottom content distances are configurable with displayed values; they reserve comfortable text boundaries while reading gestures remain usable.

Immersive reading is one persistent option, enabled by default. Enabled hides system status/navigation bars, temporarily revealable by edge swipe; disabled retains normal system bars. Reading controls remain overlays hidden on book entry; center tap toggles them without changing the navigator viewport or repaginating. Back dismisses lookup/panels before hiding controls or leaving.

The reading toolbar offers chapter contents, in-book search and reading appearance only; returning to the library and hiding the toolbar are not duplicated as toolbar buttons. Its icons keep consistent weight and contrast across themes and every touch target stays at least 48dp.

A passive chapter label stays at the top-left and actual current/total chapter-page position at bottom-left in paginated reading. The label shows the actual current chapter title, falling back to the publication's table-of-contents and reading-order titles when the locator supplies none, updates when the chapter changes, shortens long titles instead of overlapping the page, and remains visible while chrome is hidden. Because these labels live in the narrow band reserved outside the book text, their own text scaling is capped so they cannot grow onto the first line, while book text keeps scaling normally. Counts update after reflow and never become stored reading positions. Readium paginates one resource at a time, so the chapter count is exact and a whole-book page total is derived: measured chapter pages divided by that chapter's Readium positions gives the pages-per-position density of the current typography, applied to the publication's position total. The estimate appears only after at least five measured pages, is labeled approximate, is sampled once per chapter per layout signature (font, spacing, margins, insets, viewport), and is recomputed when that signature changes; no chapter count is ever presented as the book total. Resource file names are never shown as chapter names, even when the table of contents or the reading-order link supplies one. Scroll mode shows honest chapter/progression information rather than claiming a fixed page count. Passive labels do not intercept gestures. Controls remain legible across all themes and focus/keyboard transitions.

Persist Readium locator JSON for each book. Progress percentages and page counts are display derivatives, never restoration identifiers. Persist position serially and flush on background/exit. Restoration passes the persisted locator into navigator creation; initial callbacks must not overwrite it with a default first-page location.

Remember the last successfully opened book. On a new app launch, reopen that book directly at its saved locator. Returning intentionally to Library during the same session must not immediately reopen it. A missing/deleted/unreadable last book falls back to Library with a clear explanation, not a launch loop. No books means Library. Rotation and process recreation must not restore stale runtime page indexes. Model setup and popup dismissal never change reading position.

## Search

In-book search queries the opened publication and offers real occurrences only. The query field and its action stay fixed while results scroll independently, so the field and the first results remain reachable on small screens, with the keyboard open and at large system text sizes. A result is a full-width clickable row, not a rounded button that clips its text: it shows a short snippet of the surrounding text with the matched text distinguishable, plus the actual chapter resolved for that match the same way as the top-left reading label; that label is omitted when the publication names no chapter for the location, and none is fabricated. Selecting a result navigates to that occurrence's actual locator. Empty, running and failed searches report their own honest state instead of a silent blank list.

## Statistics

Persist daily reading-time increments per book. Count only foreground reader time while the user interacted within two minutes; periodic flush at most 60 seconds plus lifecycle/exit flush. Use monotonic time for elapsed time and local calendar dates for aggregation. UI: today, current week (Monday start), cumulative time; recent 7/30 days and recent books with accumulated time. Empty data shows an empty state. No historic backfill or fabricated estimates. Book deletion removes its time records. Popup lookups count as interaction, not separate learning statistics.

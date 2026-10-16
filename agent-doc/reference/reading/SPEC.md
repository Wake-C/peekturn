# Reading requirements

## Library and original books

Import via Android document picker and ACTION_VIEW/ACTION_SEND readable content URI. EPUB only. Copy the original archive to app-private files; metadata, covers and reading order come from Readium. Do not flatten chapters into new plain-text books or implement another paginator. Show import failure without adding a corrupt library entry. Clean up files created by failed imports. File reads are bounded; never log source URI tokens or private passages. EPUB scripts must not execute app capabilities.

Display actual title, author and cover where available; a generated typographic cover is an honest fallback. Find books by title/author. Removal requires confirmation, removes local book/cover/location/time data, and does not modify the original external file.

## Reading and restoration

Use Readium navigation and supported EPUB preferences. Font size, serif/sans typeface, line height, paragraph spacing and horizontal margins must affect rendered book content, not only saved controls. Font-size percentage is translated into the renderer's scale units. Independent top/bottom content distances are configurable with displayed values; they reserve comfortable text boundaries while reading gestures remain usable.

Immersive reading is one persistent option, enabled by default. Enabled hides system status/navigation bars, temporarily revealable by edge swipe; disabled retains normal system bars. Reading controls remain overlays hidden on book entry; center tap toggles them without changing the navigator viewport or repaginating. Back dismisses lookup/panels before hiding controls or leaving.

A passive chapter label stays at the top-left and actual current/total chapter-page position at bottom-left in paginated reading. Counts update after reflow and never become stored reading positions. Scroll mode shows honest chapter/progression information rather than claiming a fixed page count. Passive labels do not intercept gestures. Controls remain legible across all themes and focus/keyboard transitions.

Persist Readium locator JSON for each book. Progress percentages and page counts are display derivatives, never restoration identifiers. Persist position serially and flush on background/exit. Restoration passes the persisted locator into navigator creation; initial callbacks must not overwrite it with a default first-page location.

Remember the last successfully opened book. On a new app launch, reopen that book directly at its saved locator. Returning intentionally to Library during the same session must not immediately reopen it. A missing/deleted/unreadable last book falls back to Library with a clear explanation, not a launch loop. No books means Library. Rotation and process recreation must not restore stale runtime page indexes. Model setup and popup dismissal never change reading position.

## Statistics

Persist daily reading-time increments per book. Count only foreground reader time while the user interacted within two minutes; periodic flush at most 60 seconds plus lifecycle/exit flush. Use monotonic time for elapsed time and local calendar dates for aggregation. UI: today, current week (Monday start), cumulative time; recent 7/30 days and recent books with accumulated time. Empty data shows an empty state. No historic backfill or fabricated estimates. Book deletion removes its time records. Popup lookups count as interaction, not separate learning statistics.

package app.peekturn.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.model.Book
import app.peekturn.reader.video.VideoItem
import app.peekturn.reader.video.VideoLibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LibraryEntryTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    private val book = Book(1, "The Prophet", "Kahlil Gibran", "fixture.epub", progression = .25, lastReadAt = 1)
    private val video = VideoItem(1, "u4LUix-BU0s", "Featured video", "Channel", 60_000, null,
        positionMs = 15_000, lastWatchedAt = 1)

    private fun noDuplicateContinueText() {
        listOf("Continue", "Continue watching", "继续", "继续阅读", "继续观看").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun featuredBookOpensFromItsTitleMetadataAndBlankAreaWithoutAContinueButton() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            PeekturnTheme { LibraryScreen(listOf(book), { opened += it.id }, {}, {}, {}) }
        }
        compose.onNodeWithText(book.title).assertIsDisplayed()
        noDuplicateContinueText()
        compose.onNodeWithText(book.title).performClick()
        compose.onNodeWithText(book.author).performClick()
        compose.onNode(hasText(book.title) and hasClickAction()).performTouchInput {
            click(Offset(width * .9f, height - 2f))
        }
        compose.runOnIdle { assertEquals(listOf(1L, 1L, 1L), opened) }
        compose.onNodeWithContentDescription(text(R.string.shell_book_options, book.title)).performClick()
        compose.onNodeWithText(text(R.string.shell_remove)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(3, opened.size) }
    }

    @Test fun shelfEntryOpensAndImportRemainsASeparateAction() {
        var opened = 0
        var imported = 0
        compose.setContent {
            PeekturnTheme { LibraryScreen(listOf(book.copy(lastReadAt = 0)), { opened++ }, { imported++ }, {}, {}) }
        }
        compose.onNodeWithText(book.title).performClick()
        compose.onNodeWithContentDescription(text(R.string.shell_add_book)).performClick()
        compose.onNodeWithText(text(R.string.shell_import_from_file)).performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, imported) }
    }

    @Test fun videoEntriesOpenWithoutContinueCopyWhileOptionsAndImportStaySeparate() {
        val second = video.copy(id = 2, title = "Another video", lastWatchedAt = 0)
        val opened = mutableListOf<Long>()
        var imported = 0
        var removed = 0
        compose.setContent {
            PeekturnTheme { VideoLibraryScreen(listOf(video, second), { opened += it.id }, { imported++ }, { removed++ }) }
        }
        noDuplicateContinueText()
        compose.onAllNodesWithText(video.title).assertCountEquals(2)[0].performClick()
        compose.onNodeWithText(second.title).performClick()
        compose.onNodeWithText(text(R.string.video_ui_import_video)).performClick()
        compose.onNodeWithContentDescription(text(R.string.video_ui_video_options, second.title)).performClick()
        compose.onNodeWithText(text(R.string.video_ui_remove)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1L, 2L), opened)
            assertEquals(1, imported)
            assertEquals(0, removed)
        }
    }
}

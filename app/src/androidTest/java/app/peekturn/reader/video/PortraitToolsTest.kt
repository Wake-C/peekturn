package app.peekturn.reader.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Device-rendered geometry and actions, independent of YouTube/network availability. */
class PortraitToolsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun description(id: Int) = context.getString(id)
    private fun hasProgressBarRangeInfo() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    // Material extends the slider's accessibility bounds horizontally beyond its visual layout.
    private fun sliderLayout() = compose.onNode(hasProgressBarRangeInfo()).fetchSemanticsNode()
        .layoutInfo.coordinates.boundsInRoot()
    private val fixture get() = VideoPlaybackState(
        positionMs = 40_000, durationMs = 120_000,
        captions = CaptionDocument("fixture", "en", "en", false, "fixture", listOf(
            CaptionCue(0, 0, 30_000, "First sentence."),
            CaptionCue(1, 30_000, 60_000, "Second sentence."),
            CaptionCue(2, 60_000, 120_000, "Third sentence.")
        ))
    )

    @Test fun narrowToolsKeepTimesOnOneRowAndTransportCentredWithSpeedOutside() {
        var pixelsPerDp = 1f
        compose.setContent {
            val density = LocalDensity.current
            pixelsPerDp = density.density
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(320.dp)) {
                        PortraitTools(fixture.copy(audioOnly = true), {}, {}, {}, {}, {}, Modifier.testTag("tools"))
                    }
                }
            }
        }
        val panel = compose.onNodeWithTag("tools").fetchSemanticsNode().boundsInRoot
        val elapsed = compose.onNodeWithText("00:40").fetchSemanticsNode().boundsInRoot
        val total = compose.onNodeWithText("02:00").fetchSemanticsNode().boundsInRoot
        val progress = sliderLayout()
        val previous = compose.onNodeWithContentDescription(description(R.string.video_ui_previous_subtitle)).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription(description(R.string.video_ui_play)).fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithContentDescription(description(R.string.video_ui_next_subtitle)).fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithContentDescription("${description(R.string.video_ui_speed)}, 1×").fetchSemanticsNode().boundsInRoot
        assertEquals(elapsed.center.y, total.center.y, 1f)
        assertEquals(elapsed.center.y, progress.center.y, 1f)
        assertTrue("elapsed=$elapsed, slider=$progress", elapsed.right <= progress.left)
        assertTrue("slider=$progress, total=$total", progress.right <= total.left)
        assertEquals(panel.center.x, play.center.x, 1f)
        assertEquals(play.center.x - previous.center.x, next.center.x - play.center.x, 1f)
        assertEquals(48f * pixelsPerDp, next.center.x - play.center.x, 1f)
        assertTrue(speed.left >= next.right)
        assertTrue(speed.right <= panel.right)
        assertTrue(previous.height >= 48f * pixelsPerDp - 1)
        // Navigation padding is platform-owned; the first two rows themselves occupy exactly 96dp.
        assertEquals(96f * pixelsPerDp, play.bottom - panel.top, 1f)
        compose.onNodeWithContentDescription(description(R.string.video_ui_hide_controls)).assertDoesNotExist()
    }

    @Test fun readingToolsHaveNoClockOrSeekAndFollowStaysLeftOfCenteredTransport() {
        var follows = 0
        var following by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                PeekturnTheme(theme = "dark") {
                    Box(Modifier.width(320.dp)) {
                        PortraitTools(fixture.copy(readMode = true, audioOnly = true), {}, {}, {}, {}, {},
                            Modifier.testTag("tools"), following = following, onFollow = { follows++; following = true })
                    }
                }
            }
        }
        compose.onNode(hasProgressBarRangeInfo()).assertDoesNotExist()
        compose.onNodeWithText("00:40").assertDoesNotExist()
        compose.onNodeWithText("02:00").assertDoesNotExist()
        val panel = compose.onNodeWithTag("tools").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(description(R.string.video_ui_follow)).assertDoesNotExist()
        val followButton = compose.onNodeWithContentDescription(description(R.string.video_ui_follow))
        followButton.assertIsNotSelected()
        followButton.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            description(R.string.video_ui_follow_paused)))
        val follow = followButton.fetchSemanticsNode().boundsInRoot
        val previous = compose.onNodeWithContentDescription(description(R.string.video_ui_previous_subtitle)).fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithContentDescription(description(R.string.video_ui_play)).fetchSemanticsNode().boundsInRoot
        assertEquals(panel.center.x, play.center.x, 1f)
        assertTrue("follow=$follow previous=$previous", follow.right <= previous.left)
        followButton.performClick()
        followButton.assertIsSelected()
        followButton.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            description(R.string.video_ui_following)))
        assertEquals(follow, followButton.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { assertEquals(1, follows) }
    }

    @Test fun followWithoutCaptionsIsDisabled() {
        compose.setContent {
            PeekturnTheme {
                PortraitTools(VideoPlaybackState(readMode = true), {}, {}, {}, {}, {},
                    onFollow = { fail("No captions to follow") })
            }
        }
        compose.onNodeWithContentDescription(description(R.string.video_ui_follow)).assertIsNotEnabled()
    }

    @Test fun readingParagraphIsTextWithoutTimestampAndWordLookupDoesNotSeek() {
        var selected: CaptionSelection? = null
        val cue = CaptionCue(1, 24_000, 48_000, "A remarkable story.")
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                Box(Modifier.width(320.dp)) {
                    VideoReadingParagraph(cue = cue, current = true, onLookup = { selected = it })
                }
            }
        }
        compose.onNodeWithText(cue.text).assertIsDisplayed()
        compose.onNodeWithText("00:24", substring = true).assertDoesNotExist()
        compose.onNodeWithText(description(R.string.video_ui_captions)).assertDoesNotExist()
        // The first glyph is inside the first word, exercising the existing caption selection path.
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(cue.text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val wordPoint = layouts.single().getBoundingBox(0).center
        compose.onNodeWithText(cue.text).performTouchInput { click(wordPoint) }
        compose.runOnIdle { assertNotNull(selected) }
    }

    @Test fun roundThumbIsCentredOnTheThinTrackAndClocks() {
        var pixelsPerDp = 1f
        compose.setContent {
            pixelsPerDp = LocalDensity.current.density
            PeekturnTheme(theme = "dark") {
                Box(Modifier.width(320.dp)) {
                    PortraitTools(fixture, {}, {}, {}, {}, {}, Modifier.testTag("tools"))
                }
            }
        }
        val panel = compose.onNodeWithTag("tools").fetchSemanticsNode().boundsInRoot
        val track = sliderLayout()
        val clock = compose.onNodeWithText("00:40").fetchSemanticsNode().boundsInRoot
        val thumbWidth = 10f * pixelsPerDp
        val thumbX = track.left + thumbWidth / 2 + (track.width - thumbWidth) / 3 - panel.left
        val image = compose.onNodeWithTag("tools").captureToImage().toPixelMap()
        // Four dp above/below the track centre is inside the 5dp-radius thumb, outside the
        // 1.5dp-radius track. This catches a small circle top-aligned in Material's taller slot.
        for (offset in listOf(-4f, 4f)) {
            val color = image[thumbX.roundToInt(), (clock.center.y - panel.top + offset * pixelsPerDp).roundToInt()]
            assertTrue("Thumb missing at offset $offset: $color", color.red > 0.6f && color.green > 0.6f && color.blue > 0.6f)
        }
    }

    @Test fun transportAndSpeedUseTheSuppliedActionsWithoutMovingTheButtons() {
        var state by mutableStateOf(fixture)
        var previous = 0
        var next = 0
        var toggles = 0
        var speed = 1f
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                PortraitTools(state, {}, { previous++ }, {
                    toggles++
                    state = state.copy(playRequested = !state.playRequested)
                }, { next++ }, { speed = it })
            }
        }
        val before = compose.onNodeWithContentDescription(description(R.string.video_ui_play)).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(description(R.string.video_ui_previous_subtitle)).performClick()
        compose.onNodeWithContentDescription(description(R.string.video_ui_next_subtitle)).performClick()
        compose.onNodeWithContentDescription(description(R.string.video_ui_play)).performClick()
        val after = compose.onNodeWithContentDescription(description(R.string.video_ui_pause)).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithContentDescription(description(R.string.video_ui_pause)).performClick()
        compose.onNodeWithContentDescription("${description(R.string.video_ui_speed)}, 1×").performClick()
        compose.onNodeWithText("1.25×").performClick()
        compose.runOnIdle {
            assertEquals(1, previous)
            assertEquals(1, next)
            assertEquals(2, toggles)
            assertEquals(1.25f, speed)
        }
    }

    @Test fun accessibleSeekCommitsARealTargetAndUnknownDurationDisablesSeeking() {
        var state by mutableStateOf(fixture)
        val targets = mutableListOf<Long>()
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                PortraitTools(state, { targets += it }, {}, {}, {}, {})
            }
        }
        compose.onNode(hasProgressBarRangeInfo()).performSemanticsAction(SemanticsActions.SetProgress) { it(70_000f) }
        compose.runOnIdle {
            assertEquals(listOf(70_000L), targets)
            state = state.copy(durationMs = 0)
        }
        compose.onNodeWithText("--:--").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo()).assertIsNotEnabled()
    }

    @Test fun hourLongClocksFitAtNarrowWidthAndReplayKeepsTheSmallButton() {
        compose.setContent {
            PeekturnTheme(theme = "dark") {
                Box(Modifier.width(320.dp)) {
                    PortraitTools(fixture.copy(positionMs = 36_000_000, durationMs = 43_200_000, ended = true),
                        {}, {}, {}, {}, {}, Modifier.testTag("tools"))
                }
            }
        }
        compose.onNodeWithText("10:00:00").assertIsDisplayed()
        compose.onNodeWithText("12:00:00").assertIsDisplayed()
        val elapsed = compose.onNodeWithText("10:00:00").fetchSemanticsNode().boundsInRoot
        val total = compose.onNodeWithText("12:00:00").fetchSemanticsNode().boundsInRoot
        val progress = sliderLayout()
        assertTrue("elapsed=$elapsed, slider=$progress", elapsed.right <= progress.left)
        assertTrue("slider=$progress, total=$total", progress.right <= total.left)
        compose.onNodeWithContentDescription(description(R.string.video_ui_replay)).assertIsDisplayed()
    }
}

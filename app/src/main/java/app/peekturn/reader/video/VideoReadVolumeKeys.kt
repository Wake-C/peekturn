package app.peekturn.reader.video

import android.view.KeyEvent
import app.peekturn.reader.reader.NarrationVolumeKeyAction
import app.peekturn.reader.reader.narrationVolumeKeyAction

/**
 * Video Read mode opts into sentence navigation without changing the book-only volume preference.
 * A captured press stays ours through key-up, even if a mode or popup changes in between.
 */
internal class VideoReadVolumeKeys {
    private val captured = mutableSetOf<Int>()

    fun action(
        foreground: Boolean,
        state: VideoPlaybackState?,
        keyCode: Int,
        eventAction: Int,
        repeatCount: Int
    ): NarrationVolumeKeyAction? {
        if (eventAction == KeyEvent.ACTION_UP && captured.remove(keyCode)) {
            return NarrationVolumeKeyAction.CONSUME
        }
        if (keyCode in captured && (eventAction != KeyEvent.ACTION_DOWN || repeatCount > 0)) {
            return NarrationVolumeKeyAction.CONSUME
        }
        // Never take over a held volume key whose first press belonged to Android or a dialog.
        if (eventAction != KeyEvent.ACTION_DOWN || repeatCount != 0) return null
        captured.remove(keyCode)
        val canNavigate = state != null && state.readMode && state.phase == VideoPhase.Ready &&
            !state.modeSaving && !state.qualityChanging && state.lookup == null && state.displayedCues.isNotEmpty()
        val action = narrationVolumeKeyAction(foreground, canNavigate,
            keyCode, eventAction, repeatCount) ?: return null
        captured += keyCode
        return action
    }

    fun clear() = captured.clear()
}

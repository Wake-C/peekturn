package app.peekturn.reader.learning

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FloatingLookupCardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun outsideTapDismissesOnReleaseAndCancelledEdgeGestureKeepsCard() {
        var dismissals = 0
        val card = CardOverlayView(context) { dismissals++ }
        card.layout(0, 0, 320, 200)
        fun touch(action: Int) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, -24f, 40f, 0)
            try { card.dispatchTouchEvent(event) } finally { event.recycle() }
        }

        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_CANCEL)
        assertEquals(0, dismissals)
        touch(MotionEvent.ACTION_DOWN)
        assertEquals(0, dismissals)
        touch(MotionEvent.ACTION_UP)
        assertEquals(1, dismissals)
    }

    @Test fun legacyBackIsConsumedAndOnlyDismissesOnRelease() {
        var dismissals = 0
        val card = CardOverlayView(context) { dismissals++ }
        assertTrue(card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertEquals(0, dismissals)
        assertTrue(card.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(1, dismissals)
    }
}

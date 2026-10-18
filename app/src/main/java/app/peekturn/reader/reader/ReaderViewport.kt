package app.peekturn.reader.reader

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Readium 3.1 has fixed native vertical padding but no public vertical-padding preference.
 * Adapt only its WebView container, replacing (not adding to) that fixed padding. All text,
 * pagination, selection and locators remain owned by the unmodified navigator.
 */
internal class ReaderViewport(private val insets: () -> ReaderTextInsets) : FragmentManager.FragmentLifecycleCallbacks() {
    private data class Container(val view: View, val observer: ViewTreeObserver, val listener: ViewTreeObserver.OnPreDrawListener)
    private val containers = mutableMapOf<Fragment, Container>()

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        val group = v as? ViewGroup ?: return
        if ((0 until group.childCount).none { group.getChildAt(it) is WebView }) return
        val padding = insets()
        // Readium also writes padding on RESUMED. That requests a layout but need not
        // change the container's bounds, so OnLayoutChangeListener is insufficient.
        val listener = ViewTreeObserver.OnPreDrawListener { !apply(v, padding) }
        val observer = v.viewTreeObserver
        containers[f] = Container(v, observer, listener)
        observer.addOnPreDrawListener(listener)
        apply(v, padding)
    }

    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
        containers.remove(f)?.let { container ->
            val observer = if (container.observer.isAlive) container.observer else container.view.viewTreeObserver
            observer.removeOnPreDrawListener(container.listener)
        }
    }

    private fun apply(view: View, padding: ReaderTextInsets): Boolean {
        val density = view.resources.displayMetrics.density
        val top = (padding.topDp * density).roundToInt()
        val bottom = (padding.bottomDp * density).roundToInt()
        val changed = view.paddingTop != top || view.paddingBottom != bottom
        if (changed) view.setPadding(0, top, 0, bottom)
        return changed
    }
}

/** Only receives touches unhandled by Readium, such as its native padding bands.
 * It never intercepts the book's selection, links, swipes or handle adjustment.
 */
internal class ReaderHostView(context: Context, onTap: (Float, Float) -> Unit, onSwipe: (Boolean) -> Unit) : FrameLayout(context) {
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(e.x, width.toFloat()); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || abs(e2.x - e1.x) < 48 * resources.displayMetrics.density || abs(velocityX) <= abs(velocityY)) return false
            onSwipe(velocityX < 0)
            return true
        }
    })
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}

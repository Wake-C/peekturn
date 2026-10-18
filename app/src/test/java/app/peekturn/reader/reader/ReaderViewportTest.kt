package app.peekturn.reader.reader

import android.app.Application
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReaderViewportTest {
    @Test fun `text geometry replaces native defaults and independently measures both distances`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val web = WebView(activity)
        // Robolectric's WebView provider does not implement setFrame. Measure the
        // same native content box with a sibling View; keep WebView detection real.
        val content = View(activity)
        val root = FrameLayout(activity).apply {
            setPadding(0, 20, 0, 20)
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        val density = root.resources.displayMetrics.density
        val top = (48 * density).roundToInt()
        val bottom = (96 * density).roundToInt()
        val viewport = ReaderViewport { ReaderTextInsets(48.0, 96.0) }
        val fragment = Fragment()
        viewport.onFragmentViewCreated(activity.supportFragmentManager, fragment, root, null)
        val width = 500
        val height = top + bottom + 800
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        assertEquals(top, content.top)
        assertEquals(height - bottom, content.bottom)
        assertEquals(800, content.height)
        // A same-bounds layout following a native RESUMED padding write must not
        // draw the default padding. Pre-draw repairs it and requests another layout.
        repeat(2) {
            root.setPadding(0, 20, 0, 20)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(top, root.paddingTop)
            assertEquals(bottom, root.paddingBottom)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            root.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(top, content.top)
            assertEquals(height - bottom, content.bottom)
            assertEquals(800, content.height)
        }
        viewport.onFragmentViewDestroyed(activity.supportFragmentManager, fragment)
        root.setPadding(0, 20, 0, 20)
        root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(20, root.paddingTop)
        assertEquals(20, root.paddingBottom)
        web.destroy()
        controller.pause().stop().destroy()
    }
}

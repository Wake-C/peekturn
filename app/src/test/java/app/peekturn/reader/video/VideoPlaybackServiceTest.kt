package app.peekturn.reader.video

import android.content.Intent
import app.peekturn.reader.PeekturnApp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = PeekturnApp::class, sdk = [28])
class VideoPlaybackServiceTest {
    @Test fun localBindingUsesTheRegisteredMediaSessionPlayerAndBackgroundDoesNotPause() {
        val controller = Robolectric.buildService(VideoPlaybackService::class.java).create()
        try {
            val service = controller.get()
            val binder = service.onBind(Intent().setAction(VideoPlaybackService.ACTION_LOCAL)) as VideoPlaybackService.LocalBinder
            assertSame(service, binder.service)
            assertEquals(1, service.sessions.size)
            assertSame(service.model.player, service.sessions.single().player)
            service.model.player.play()
            service.model.onBackground()
            assertTrue(service.model.player.playWhenReady)
            service.model.player.pause()
            service.model.onForeground()
            assertFalse(service.model.player.playWhenReady)
        } finally {
            controller.destroy()
            ApplicationProvider.getApplicationContext<PeekturnApp>().graph.speech.shutdown()
        }
    }
}

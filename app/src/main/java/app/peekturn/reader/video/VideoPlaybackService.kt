package app.peekturn.reader.video

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.peekturn.reader.PeekturnApp

/** The service, not an Activity, owns the single player and its position/caption session. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    internal lateinit var model: VideoViewModel
        private set
    private var session: MediaSession? = null
    private val localBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        internal val service get() = this@VideoPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        val graph = (application as PeekturnApp).graph
        model = ViewModelProvider(this, VideoViewModel.Factory(applicationContext, graph))[VideoViewModel::class.java]
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(0x5650).setChannelId("video_playback").build())
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, VideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, model.player).setSessionActivity(activity).build()
        addSession(session!!)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL) localBinder else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        viewModelStore.clear()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_LOCAL = "app.peekturn.reader.video.BIND"
    }
}

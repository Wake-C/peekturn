package app.peekturn.reader.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R

/** Media3 owns the foreground lifetime and network-requirement restarts. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoDownloadService : DownloadService(NOTIFICATION_ID, 1_000L) {
    override fun onCreate() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.video_download_channel), NotificationManager.IMPORTANCE_LOW)
        )
        super.onCreate()
    }

    override fun getDownloadManager() = (application as PeekturnApp).graph.videoDownloads.manager
    override fun getScheduler() = PlatformScheduler(this, 0x5645)
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL).buildProgressNotification(this,
            android.R.drawable.stat_sys_download, null, getString(R.string.video_download_notification), downloads, notMetRequirements)

    companion object {
        private const val CHANNEL = "video_downloads"
        private const val NOTIFICATION_ID = 0x5644
    }
}

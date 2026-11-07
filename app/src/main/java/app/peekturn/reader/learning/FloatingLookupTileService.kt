package app.peekturn.reader.learning

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** A system shortcut, not a second capture owner. Every start still needs the OS projection grant. */
class FloatingLookupTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // PendingIntent overload needs API 34.
    override fun onClick() {
        super.onClick()
        if (FloatingLookupService.active.value) {
            startService(FloatingLookupService.stopIntent(this))
            qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
        } else {
            val intent = Intent(this, FloatingLookupLaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        qsTile?.let {
            it.state = if (FloatingLookupService.active.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    companion object {
        fun refresh(context: android.content.Context) {
            requestListeningState(context, ComponentName(context, FloatingLookupTileService::class.java))
        }
    }
}

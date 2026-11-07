package app.peekturn.reader.learning

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** Permission trampoline for Quick Settings: never opens the library or bypasses OS consent. */
class FloatingLookupLaunchActivity : AppCompatActivity() {
    private val overlayGrant = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) requestCapture() else finish()
    }
    private val captureGrant = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null && Settings.canDrawOverlays(this)) {
            startForegroundService(FloatingLookupService.startIntent(this, result.resultCode, result.data!!))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (FloatingLookupService.active.value) { finish(); return }
        if (Settings.canDrawOverlays(this)) requestCapture()
        else overlayGrant.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Match the in-app flow: a selected app window cannot supply full-screen OCR coordinates.
        val request = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        captureGrant.launch(request)
    }
}

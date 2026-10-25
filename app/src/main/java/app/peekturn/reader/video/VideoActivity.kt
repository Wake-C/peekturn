package app.peekturn.reader.video

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.peekturn.reader.AppLanguage
import app.peekturn.reader.MainActivity
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.ui.PeekturnTheme
import app.peekturn.reader.ui.isDarkTheme
import app.peekturn.reader.ui.peekturnColorScheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The only public entry to a video session. It accepts a stored entry id or a public canonical
 * YouTube link that already passed share routing; arbitrary media URLs are not a valid request.
 */
class VideoActivity : AppCompatActivity() {
    private val graph get() = (application as PeekturnApp).graph
    private lateinit var vm: VideoViewModel
    private var resumed = false
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as VideoPlaybackService.LocalBinder).service
            vm = service.model
            val id = videoId(intent)
            val url = videoUrl(intent)
            // A notification opens the existing session without resolving it again. If Android
            // restored an empty notification intent after process death, return to the real list.
            if (id == null && url == null && vm.state.value.phase == VideoPhase.Idle) {
                service.stopSelf()
                startActivity(MainActivity.videoIntent(this@VideoActivity))
                finish()
                return
            }
            if (id != null || url != null) vm.start(id, url)
            if (resumed) vm.onForeground()
            showSession()
        }
        override fun onServiceDisconnected(name: ComponentName?) { finish() }
    }
    /** A shared/imported link must return to the Video list, not to whatever was below it. */
    private var openedFromShare = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.apply(this, graph.store.settings.value.uiLanguage)
        applyAppTheme(graph.store.settings.value.appTheme)
        applySystemBars(isLandscape())
        openedFromShare = videoUrl(intent) != null
        val service = Intent(this, VideoPlaybackService::class.java)
        startService(service)
        bound = bindService(service.setAction(VideoPlaybackService.ACTION_LOCAL), connection, Context.BIND_AUTO_CREATE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isLandscape()) exitFullscreen() else leaveVideo()
            }
        })
    }

    private fun showSession() {
        setContent {
            val settings by graph.store.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.uiLanguage) { AppLanguage.apply(this@VideoActivity, settings.uiLanguage) }
            DisposableEffect(settings.appTheme) {
                applyAppTheme(settings.appTheme)
                applySystemBars(isLandscape())
                onDispose { }
            }
            LaunchedEffect(LocalConfiguration.current.orientation) { applySystemBars(isLandscape()) }
            PeekturnTheme(theme = settings.appTheme) {
                VideoScreen(
                    vm = vm,
                    settings = settings,
                    speech = graph.speech,
                    onBack = ::leaveVideo,
                    onEnterFullscreen = ::enterFullscreen,
                    onExitFullscreen = ::exitFullscreen
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.state.map { it.isPlaying }.distinctUntilChanged().collect { playing ->
                    if (playing) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedFromShare = videoUrl(intent) != null
        if (::vm.isInitialized && (videoId(intent) != null || videoUrl(intent) != null)) {
            vm.start(videoId(intent), videoUrl(intent))
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (::vm.isInitialized) vm.onForeground()
    }

    override fun onStop() {
        resumed = false
        if (!isChangingConfigurations && ::vm.isInitialized) {
            graph.speech.stop()
            vm.onBackground()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars(isLandscape())
    }

    /** Popup touches are genuine explanation interactions; the video tracker owns the 120s window. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        // Only explanation surfaces are forwarded: seeking and transport touches must not reach
        // the learning-time tracker at all.
        if (::vm.isInitialized && vm.state.value.lookup != null) vm.interact()
    }

    private fun leaveVideo() {
        if (openedFromShare || isTaskRoot) {
            // A share-opened player returns to the Video tab, even when a reader sits underneath.
            startActivity(MainActivity.videoIntent(this))
            finish()
        } else {
            finish()
        }
    }

    private fun enterFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun exitFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun applyAppTheme(theme: String) {
        val background = peekturnColorScheme(theme).background.toArgb()
        window.setBackgroundDrawable(ColorDrawable(background))
        val style = if (!isDarkTheme(theme)) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    /** Portrait keeps the bars over the app surface; landscape is the A full-screen picture. */
    private fun applySystemBars(landscape: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            val darkIcons = !isDarkTheme(graph.store.settings.value.appTheme)
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_VIDEO_URL = "video_url"

        fun intent(context: Context, id: Long): Intent =
            Intent(context, VideoActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun importIntent(context: Context, url: String): Intent =
            Intent(context, VideoActivity::class.java)
                .putExtra(EXTRA_VIDEO_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Null means the intent carried no entry id (a link-only import). */
        private fun videoId(intent: Intent): Long? =
            intent.getLongExtra(EXTRA_VIDEO_ID, -1L).takeIf { it > 0L }

        private fun videoUrl(intent: Intent): String? =
            intent.getStringExtra(EXTRA_VIDEO_URL)?.takeIf { it.isNotBlank() }
    }
}

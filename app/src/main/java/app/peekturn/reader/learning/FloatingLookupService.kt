package app.peekturn.reader.learning

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.WindowManager
import android.widget.Toast
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.peekturn.reader.MainActivity
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import app.peekturn.reader.model.LookupContext
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.ui.PeekturnIcons
import app.peekturn.reader.ui.PeekturnTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** One explicit full-display sharing session; no image or OCR result is saved to disk or logged. */
class FloatingLookupService : Service() {
    companion object {
        private const val ACTION_START = "app.peekturn.reader.floating.START"
        private const val ACTION_STOP = "app.peekturn.reader.floating.STOP"
        private const val CHANNEL = "floating_lookup"
        private const val NOTIFICATION = 0x5042
        val active = MutableStateFlow(false)

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, FloatingLookupService::class.java).setAction(ACTION_START)
                .putExtra("result_code", resultCode).putExtra("projection_data", data)
        fun stopIntent(context: Context): Intent =
            Intent(context, FloatingLookupService::class.java).setAction(ACTION_STOP)
    }

    private val graph get() = (application as PeekturnApp).graph
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayOwner()
    private val recognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by recognizerDelegate
    private val sessionDelegate = lazy { LearningSession(source = graph.explanations, capacity = 1) }
    private val session by sessionDelegate
    private val speechDelegate = lazy { PronunciationPlayer(applicationContext) }
    private val speech by speechDelegate
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var capture: Job? = null
    private var bubble: DragOverlayView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dockJob: Job? = null
    private var docked = false
    private var card: CardOverlayView? = null
    private var current: LookupContext? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var stopping = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopping) return
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            stopLookup(if (locked) "projection-stopped-while-locked" else "projection-stopped")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLookup("stop-requested")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || projection != null || stopping || !Settings.canDrawOverlays(this)) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            createNotification()
            val consent = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projection_data", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("projection_data")
            if (consent == null) { stopSelf(); return START_NOT_STICKY }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val sessionProjection = manager.getMediaProjection(intent.getIntExtra("result_code", 0), consent)
            projection = sessionProjection
            sessionProjection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            readDisplaySize()
            // A consent token may create exactly one virtual display on Android 14+. Keep that
            // display, with no surface between user-initiated lookups.
            display = sessionProjection.createVirtualDisplay("Peekturn lookup", screenWidth, screenHeight,
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, null, Handler(Looper.getMainLooper()))
            owner.start()
            showBubble()
            active.value = true
            graph.developerLog.recordEvent("floating-lookup", "started")
            FloatingLookupTileService.refresh(this)
        } catch (failure: Exception) {
            graph.developerLog.recordFailure("floating-lookup", "start", failure)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopLookup(event: String) {
        if (stopping) return
        stopping = true
        if (active.value) graph.developerLog.recordEvent("floating-lookup", event)
        stopSelf()
    }

    private fun createNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.floating_lookup_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.peekturn_monochrome)
            .setContentTitle(getString(R.string.floating_lookup_title))
            .setContentText(getString(R.string.floating_lookup_notification))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.floating_lookup_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION, notification)
    }

    private fun readDisplaySize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION") val size = android.graphics.Point().also { windows.defaultDisplay.getRealSize(it) }
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        capture?.cancel()
        closeCard()
        display?.setSurface(null)
        readDisplaySize()
        // Resize the existing display; a second createVirtualDisplay would invalidate the grant.
        display?.resize(screenWidth, screenHeight, resources.configuration.densityDpi)
        bubbleParams?.let { params ->
            params.x = if (docked) dockX(params.x, screenWidth, params.width)
                else params.x.coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
            params.y = params.y.coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))
            bubble?.let { windows.updateViewLayout(it, params); it.visibility = View.VISIBLE }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun params(width: Int, height: Int, x: Int, y: Int, focusable: Boolean = false): WindowManager.LayoutParams =
        WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (focusable) 0 else
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL),
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun overlay(content: ComposeView.() -> Unit): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        content()
    }

    private fun dockBubble() {
        if (capture?.isActive == true) return
        val p = bubbleParams ?: return
        val view = bubble ?: return
        p.x = dockX(p.x, screenWidth, p.width)
        windows.updateViewLayout(view, p)
        docked = true
    }

    private fun scheduleDock() {
        dockJob?.cancel()
        dockJob = scope.launch { delay(3_200); dockBubble() }
    }

    private fun showBubble() {
        val size = dp(54)
        val height = dp(68)
        val p = params(size, height, screenWidth - size - dp(12), screenHeight / 3).apply {
            // Without NO_LIMITS Android clamps the window back inside the display, so a half-
            // hidden icon would still sit a full window-width away from the actual edge.
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        var dragging by mutableStateOf(false)
        val view = DragOverlayView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            contentDescription = getString(R.string.floating_lookup_drag)
            if (Build.VERSION.SDK_INT >= 29) systemGestureExclusionRects = listOf(Rect(0, 0, size, height))
            addView(overlay { setContent {
                val settings by graph.store.settings.collectAsState()
                PeekturnTheme(settings.appTheme) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        if (dragging) Box(Modifier.offset(y = 11.5.dp).size(17.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        }
                        Surface(modifier = Modifier.offset(y = 30.dp).size(33.dp),
                            shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .76f),
                            shadowElevation = 2.dp) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(PeekturnIcons.Search, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } }, FrameLayout.LayoutParams(size, height))
        }
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        view.onDragTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dockJob?.cancel()
                    startX = event.rawX; startY = event.rawY
                    originX = p.x; originY = p.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(8)) {
                        dragging = true
                        if (docked) {
                            originX = if (p.x < 0) 0 else screenWidth - size
                            docked = false
                        }
                    }
                    if (dragging) {
                        p.x = (originX + dx).roundToInt().coerceIn(0, (screenWidth - size).coerceAtLeast(0))
                        p.y = (originY + dy).roundToInt().coerceIn(0, (screenHeight - height + dp(16)).coerceAtLeast(0))
                        windows.updateViewLayout(view, p)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        lookupAt(p.x + size / 2f, p.y + dp(20).toFloat())
                        scheduleDock()
                    } else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    scheduleDock()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener {
            if (docked) {
                // Edge navigation can take a swipe from a half-hidden icon. Tap to bring it
                // into the safe drag area; a second tap opens the Stop control.
                p.x = if (p.x < 0) dp(12) else screenWidth - size - dp(12)
                docked = false
                windows.updateViewLayout(view, p)
                scheduleDock()
            } else showStopControl()
        }
        windows.addView(view, p)
        bubble = view
        bubbleParams = p
        scheduleDock()
    }

    private fun lookupAt(x: Float, y: Float) {
        if (capture?.isActive == true || display == null) return
        closeCard()
        capture = scope.launch {
            bubble?.visibility = View.INVISIBLE
            try {
                // Let the compositor remove our icon before acquiring the next frame.
                delay(100)
                val bitmap = captureFrame()
                val result = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult() }
                    finally { bitmap.recycle() }
                val found = screenLookup(screenLines(result), x, y, dp(10).toFloat())
                if (found == null) Toast.makeText(this@FloatingLookupService,
                    R.string.floating_lookup_no_word, Toast.LENGTH_SHORT).show()
                else showCard(found, x, y)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(this@FloatingLookupService, R.string.floating_lookup_failed, Toast.LENGTH_SHORT).show()
            } finally {
                bubble?.visibility = if (card == null) View.VISIBLE else View.GONE
                if (card == null) scheduleDock()
            }
        }
    }

    private suspend fun captureFrame(): Bitmap {
        val source = checkNotNull(display)
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        val frame = CompletableDeferred<Image>()
        reader.setOnImageAvailableListener({ images ->
            val image = images.acquireLatestImage()
            if (image != null && !frame.complete(image)) image.close()
        }, Handler(Looper.getMainLooper()))
        try {
            source.setSurface(reader.surface)
            val image = withTimeout(2_500) { frame.await() }
            try {
                return withContext(Dispatchers.Default) { image.toBitmap() }
            } finally { image.close() }
        } finally {
            source.setSurface(null)
            reader.setOnImageAvailableListener(null, null)
            frame.cancel()
            reader.close()
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val widthWithPadding = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
        val padded = Bitmap.createBitmap(widthWithPadding, height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            return Bitmap.createBitmap(padded, 0, 0, width, height)
        } finally { padded.recycle() }
    }

    private fun showStopControl() {
        if (capture?.isActive == true) return
        if (card != null) { closeCard(); return }
        val bubblePosition = bubbleParams ?: return
        val width = dp(120)
        val left = (bubblePosition.x + bubblePosition.width - width)
            .coerceIn(dp(12), (screenWidth - width - dp(12)).coerceAtLeast(dp(12)))
        val top = (bubblePosition.y + bubblePosition.height + dp(8))
            .coerceAtMost(screenHeight - dp(80))
        val view = cardOverlay {
            setContent {
                val settings by graph.store.settings.collectAsState()
                PeekturnTheme(settings.appTheme) {
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { stopLookup("stop-requested") }) {
                                Text(getString(R.string.floating_lookup_stop),
                                    color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = ::closeCard) {
                                Icon(PeekturnIcons.Close,
                                    contentDescription = getString(R.string.shell_close))
                            }
                        }
                    }
                }
            }
        }
        windows.addView(view, params(width, WindowManager.LayoutParams.WRAP_CONTENT, left, top, focusable = true))
        view.requestFocus()
        card = view
    }

    private fun showCard(context: LookupContext, x: Float, y: Float) {
        val width = minOf(dp(420), screenWidth - dp(24))
        val safeTop = dp(40)
        val height = minOf(dp(440), screenHeight - safeTop - dp(24))
        val left = (x - width / 2).roundToInt().coerceIn(dp(12), (screenWidth - width - dp(12)).coerceAtLeast(dp(12)))
        val below = y.roundToInt() + dp(24)
        val top = if (below + height < screenHeight - dp(12)) below
            else (y - height - dp(24)).roundToInt().coerceAtLeast(safeTop)
        val p = params(width, WindowManager.LayoutParams.WRAP_CONTENT, left, top, focusable = true)
        current = context
        val view = cardOverlay {
            setContent {
                val settings by graph.store.settings.collectAsState()
                PeekturnTheme(settings.appTheme) {
                    LearningPopup(context, settings, session, graph.dictionary, speech,
                        onClose = ::closeCard, onCancel = ::closeCard,
                        externalOverlay = true, onOpenSettings = {
                            closeCard()
                            startActivity(MainActivity.settingsIntent(this@FloatingLookupService))
                        })
                }
            }
        }
        windows.addView(view, p)
        view.requestFocus()
        card = view
        bubble?.visibility = View.GONE
    }

    private fun cardOverlay(content: ComposeView.() -> Unit): CardOverlayView = CardOverlayView(this, ::closeCard).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        addView(overlay(content), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun closeCard() {
        current?.let(session::dismiss)
        current = null
        speech.stop()
        card?.let { windows.removeView(it) }
        card = null
        if (!stopping && capture?.isActive != true) {
            bubble?.visibility = View.VISIBLE
            scheduleDock()
        }
    }

    override fun onDestroy() {
        stopping = true
        if (active.value) graph.developerLog.recordEvent("floating-lookup", "service-destroyed")
        active.value = false
        FloatingLookupTileService.refresh(this)
        dockJob?.cancel()
        capture?.cancel()
        closeCard()
        bubble?.let { windows.removeView(it) }
        bubble = null
        display?.release()
        display = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        if (recognizerDelegate.isInitialized()) recognizer.close()
        if (sessionDelegate.isInitialized()) session.close()
        if (speechDelegate.isInitialized()) speech.shutdown()
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }
}

/** Window center sits on the nearest side, leaving exactly half of the round icon visible. */
internal fun dockX(x: Int, screenWidth: Int, windowWidth: Int): Int =
    if (x + windowWidth / 2 < screenWidth / 2) -windowWidth / 2 else screenWidth - windowWidth / 2

// A visible card owns the gesture until Back commits or an outside tap finishes. Dismissing on
// ACTION_OUTSIDE (the initial touch) would let the same edge swipe navigate the underlying app.
internal class CardOverlayView(context: Context, private val dismiss: () -> Unit) : FrameLayout(context) {
    private var unregisterBack: (() -> Unit)? = null
    private val outsideTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = isOutside(e)
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!isOutside(e)) return false
            dismiss()
            return true
        }
    })

    private fun isOutside(event: MotionEvent) =
        event.x < 0 || event.y < 0 || event.x >= width || event.y >= height

    init { isFocusableInTouchMode = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = findOnBackInvokedDispatcher() ?: return
            val callback = OnBackInvokedCallback { dismiss() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            unregisterBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }

    override fun onDetachedFromWindow() {
        unregisterBack?.invoke()
        unregisterBack = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismiss()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        outsideTap.onTouchEvent(event) || super.onTouchEvent(event)
}

// Intercept before the Compose child consumes touch events; ComposeView itself is final.
private class DragOverlayView(context: Context) : FrameLayout(context) {
    var onDragTouch: ((MotionEvent) -> Boolean)? = null
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        onDragTouch?.invoke(event) ?: super.dispatchTouchEvent(event)
}

/** ComposeView in a Service needs view-tree owners, but it never owns the source app's Activity. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    override val lifecycle = LifecycleRegistry(this)
    override val viewModelStore = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    fun start() {
        controller.performAttach()
        controller.performRestore(null)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }
    fun stop() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { result ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (result.isSuccessful) continuation.resume(result.result)
            else continuation.resumeWithException(result.exception ?: IllegalStateException("OCR failed"))
        }
    }

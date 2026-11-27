package app.peekturn.reader.video

import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.ui.PeekturnIcons
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class PictureLevel { Brightness, Volume }

/** Use the side where the swipe began, even if the finger later crosses the centre. */
internal suspend fun PointerInputScope.detectPictureLevelSwipes(
    leftInset: Int,
    rightInset: Int,
    onStart: (PictureLevel) -> Float,
    onChange: (PictureLevel, Float) -> Unit,
    onEnd: () -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    if (down.position.x < leftInset || down.position.x >= size.width - rightInset) return@awaitEachGesture
    val level = if (down.position.x < size.width / 2f) PictureLevel.Brightness else PictureLevel.Volume
    var initial: Float? = null
    try {
        while (true) {
            val change = awaitPointerEvent().changes.singleOrNull() ?: break
            if (change.id != down.id || !change.pressed || change.isConsumed) break
            val movement = change.position - down.position
            if (initial == null) {
                if (maxOf(abs(movement.x), abs(movement.y)) <= viewConfiguration.touchSlop) continue
                // Cancel the picture's tap on a horizontal swipe, without adjusting either level.
                if (abs(movement.x) >= abs(movement.y)) {
                    change.consume()
                    break
                }
                initial = onStart(level)
            }
            change.consume()
            val fraction = (initial - movement.y / (size.height * 0.65f)).coerceIn(0f, 1f)
            onChange(level, fraction)
        }
    } finally {
        if (initial != null) onEnd()
    }
}

/** The activity window owns brightness; media volume belongs to the current system audio route. */
@Composable
internal fun FullscreenPictureGestures(window: Window, onToggleTools: () -> Unit, modifier: Modifier = Modifier) {
    val audio = remember(window) { window.context.getSystemService(AudioManager::class.java) }
    val label = stringResource(R.string.video_ui_toggle_controls)
    val interaction = remember { MutableInteractionSource() }
    val sys = WindowInsets.systemGestures
    val left = sys.getLeft(LocalDensity.current, LocalLayoutDirection.current)
    val right = sys.getRight(LocalDensity.current, LocalLayoutDirection.current)
    var feedback by remember { mutableStateOf<PictureLevel?>(null) }
    var percent by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging, feedback) {
        if (!dragging && feedback != null) {
            delay(800)
            feedback = null
        }
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, onClickLabel = label, onClick = onToggleTools)
        .semantics { contentDescription = label }
        .pointerInput(window, audio, left, right) {
            val stream = AudioManager.STREAM_MUSIC
            var minVolume = 0
            var range = 0
            var volume = 0
            detectPictureLevelSwipes(left, right, onStart = { level ->
                dragging = true
                feedback = level
                if (level == PictureLevel.Brightness) {
                    window.attributes.screenBrightness.takeIf { it >= 0f }
                        ?: (Settings.System.getInt(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                            .coerceIn(0f, 1f)
                } else {
                    minVolume = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
                    range = audio.getStreamMaxVolume(stream) - minVolume
                    volume = audio.getStreamVolume(stream)
                    if (range > 0) (volume - minVolume).toFloat() / range else 1f
                }
            }, onChange = { level, value ->
                val applied = if (level == PictureLevel.Brightness) {
                    window.attributes = window.attributes.apply { screenBrightness = value }
                    value
                } else {
                    val index = minVolume + (range * value).roundToInt()
                    if (index != volume && !audio.isVolumeFixed) {
                        audio.setStreamVolume(stream, index, 0)
                        volume = audio.getStreamVolume(stream)
                    }
                    if (range > 0) (volume - minVolume).toFloat() / range else 1f
                }
                percent = (applied * 100).roundToInt()
            }, onEnd = { dragging = false })
        }
    ) {
        feedback?.let { level ->
            val brightness = level == PictureLevel.Brightness
            val side = if (brightness) Alignment.CenterStart else Alignment.CenterEnd
            Column(
                Modifier.align(side).padding(horizontal = 18.dp).offset(y = (-48).dp)
                    .background(Color.Black.copy(alpha = .74f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 7.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(if (brightness) PeekturnIcons.Appearance else PeekturnIcons.Speaker,
                    stringResource(if (brightness) R.string.video_ui_brightness else R.string.video_ui_media_volume),
                    Modifier.size(17.dp), tint = Color.White)
                Box(Modifier.width(4.dp).height(44.dp).background(Color.White.copy(alpha = .34f), RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(percent / 100f).background(Color.White, RoundedCornerShape(2.dp)))
                }
                Text("$percent%", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

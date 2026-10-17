package app.margin.reader.learning

import android.graphics.RectF
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.margin.reader.data.OfflineDictionary
import app.margin.reader.model.*
import app.margin.reader.speech.PronunciationPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

/** Focus ink-blue surface, warm text and quiet native press feedback; no scrim or entrance motion. */
@Composable
fun LearningPopup(
    context: LookupContext,
    anchor: RectF?,
    settings: AppSettings,
    client: ExplanationClient,
    dictionary: OfflineDictionary,
    speech: PronunciationPlayer,
    initialDictionary: Boolean = false,
    onDismiss: () -> Unit,
    onAdjust: () -> Unit
) {
    var dictionaryTab by remember(context) { mutableStateOf(initialDictionary) }
    var retry by remember(context) { mutableIntStateOf(0) }
    var answer by remember(context) { mutableStateOf(Explanation()) }
    var complete by remember(context) { mutableStateOf(false) }
    var loading by remember(context) { mutableStateOf(false) }
    var error by remember(context) { mutableStateOf<String?>(null) }
    var entry by remember(context) { mutableStateOf<DictionaryEntry?>(null) }
    val latestSettings by rememberUpdatedState(settings)
    val playback by speech.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val androidContext = LocalContext.current
    val activity = remember(androidContext) {
        generateSequence(androidContext) { current ->
            (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
        }.filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.stop() }
    }
    LaunchedEffect(context) {
        if (latestSettings.autoPronounce) speech.speak(context.selection, latestSettings.accent)
    }
    LaunchedEffect(context, dictionaryTab, retry) {
        loading = true; error = null
        try {
            if (dictionaryTab) {
                entry = null
                entry = dictionary.lookup(context.selection)
            } else {
                answer = Explanation(); complete = false
                client.explain(latestSettings.model, context).collect { event ->
                    when (event) {
                        is ExplanationEvent.Content -> answer = event.answer
                        is ExplanationEvent.Complete -> { answer = event.answer; complete = true; loading = false }
                        is ExplanationEvent.Failure -> { error = event.message; loading = false }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = if (dictionaryTab) "The offline dictionary could not be opened. Try again." else "The explanation could not be loaded. Try again." }
        finally { loading = false }
    }
    val density = LocalDensity.current
    val inset = with(density) { 12.dp.roundToPx() }
    // 72dp term + two 48dp action rows + padding/dividers, leaving a useful body viewport.
    val minimumHeight = with(density) { 260.dp.roundToPx() }
    val bounds = anchor?.let { PopupSelectionBounds(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt()) }
    val position = remember(bounds, inset, minimumHeight) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val geometry = learningPopupGeometry(windowSize.width, windowSize.height, popupContentSize.width, popupContentSize.height, bounds, inset, minimumHeight)
                return IntOffset(geometry.x, geometry.y)
            }
        }
    }
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val geometry = with(density) {
        val windowWidth = windowSize.width.takeIf { it > 0 } ?: configuration.screenWidthDp.dp.roundToPx()
        val windowHeight = windowSize.height.takeIf { it > 0 } ?: configuration.screenHeightDp.dp.roundToPx()
        learningPopupGeometry(windowWidth, windowHeight, 420.dp.roundToPx(), 520.dp.roundToPx(),
            bounds, inset, minimumHeight)
    }
    val width = with(density) { geometry.width.toDp() }
    val height = with(density) { geometry.height.toDp() }
    fun close() { speech.stop(); onDismiss() }
    Popup(popupPositionProvider = position, onDismissRequest = { close() }, properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        Surface(modifier = Modifier.width(width).heightIn(max = height).pointerInput(activity) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) activity?.onUserInteraction()
                }
            }
        }, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 4.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.selection, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).heightIn(max = 72.dp).verticalScroll(rememberScrollState()))
                    Text(latestSettings.accent, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    SpeakButton(context.selection, latestSettings.accent, speech, playback.text, playback.playing)
                }
                Row {
                    TextButton(onClick = { dictionaryTab = false }, enabled = dictionaryTab) { Text("Explain") }
                    TextButton(onClick = { dictionaryTab = true }, enabled = !dictionaryTab) { Text("Dictionary") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(if (dictionaryTab) "Looking up offline…" else "Explaining…", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (dictionaryTab) {
                        entry?.let { value ->
                            Text(value.word, style = MaterialTheme.typography.titleMedium)
                            if (value.phonetic.isNotBlank()) Text(value.phonetic)
                            if (value.partOfSpeech.isNotBlank()) Text(value.partOfSpeech, color = MaterialTheme.colorScheme.primary)
                            Text(value.meaning)
                        }
                        if (!loading && entry == null && error == null) Text("No offline entry for this selection. Try a single word or use Explain.")
                    } else {
                        if (!complete && answer != Explanation()) Text("Incomplete answer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        if (answer.term.isNotBlank()) Text(answer.term, style = MaterialTheme.typography.titleMedium)
                        if (answer.partOfSpeech.isNotBlank()) Text(answer.partOfSpeech, color = MaterialTheme.colorScheme.primary)
                        if (answer.usIpa.isNotBlank()) Text("US ${answer.usIpa}")
                        if (answer.ukIpa.isNotBlank()) Text("UK ${answer.ukIpa}")
                        if (answer.meaning.isNotBlank()) Text(answer.meaning)
                        if (answer.context.isNotBlank()) {
                            Text("In this passage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(answer.context)
                        }
                        answer.examples.forEach { example ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(example, modifier = Modifier.weight(1f))
                                SpeakButton(example, latestSettings.accent, speech, playback.text, playback.playing)
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    playback.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = speech::openSystemSettings) { Text("Speech settings") }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { speech.stop(); onAdjust() }) { Text("Adjust") }
                    TextButton(onClick = { retry++ }, enabled = !loading) { Text("Retry") }
                    TextButton(onClick = { close() }) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun SpeakButton(text: String, accent: String, speech: PronunciationPlayer, playingText: String, playing: Boolean) {
    val active = playing && playingText == text
    IconButton(onClick = { if (active) speech.stop() else speech.speak(text, accent) }) {
        Icon(if (active) Icons.Default.Stop else Icons.Default.VolumeUp, contentDescription = if (active) "Stop pronunciation" else "Speak $accent")
    }
}

package app.peekturn.reader.learning

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.peekturn.reader.R
import app.peekturn.reader.data.OfflineDictionary
import app.peekturn.reader.model.*
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.speech.SpeechReport
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Bottom-docked native ink-and-paper answer; content and lookup ownership are unchanged. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearningPopup(
    context: LookupContext,
    settings: AppSettings,
    session: LearningSession,
    dictionary: OfflineDictionary,
    speech: PronunciationPlayer,
    initialDictionary: Boolean = false,
    // Resolved from the Activity's actual configuration at request time; "follow system" therefore
    // works without a second stored language, and an explicit app language already won there.
    language: ExplanationLanguage = ExplanationLanguage.forLocale(LocalConfiguration.current.locales[0]),
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onAdjust: () -> Unit,
    /** The thrown failure behind the visible dictionary error; the caller records it safely. */
    onDictionaryFailure: (Throwable) -> Unit = {}
) {
    var dictionaryTab by remember(context) { mutableStateOf(initialDictionary) }
    var dictionaryRetry by remember(context) { mutableIntStateOf(0) }
    var entry by remember(context) { mutableStateOf<DictionaryEntry?>(null) }
    var dictionaryLoading by remember(context) { mutableStateOf(false) }
    var dictionaryError by remember(context) { mutableStateOf<String?>(null) }
    // Once Explain has been opened, changing tabs never interrupts its request.
    var explanationRequested by remember(context) { mutableStateOf(!initialDictionary) }
    val explanation by session.state.collectAsState()
    val answer = explanation.answer
    val latestSettings by rememberUpdatedState(settings)
    val playback by speech.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val androidContext = LocalContext.current
    val activity = remember(androidContext) {
        generateSequence(androidContext) { current ->
            (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
        }.filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(session, context) {
        onDispose { session.dismiss(context) }
    }
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.stop() }
    }
    LaunchedEffect(context) {
        if (latestSettings.autoPronounce) {
            speech.speakSelection(context.selection, latestSettings.accent, latestSettings.sentenceSpeechProvider)
        }
    }
    LaunchedEffect(context, explanationRequested, settings.activeProfileId, settings.model, language) {
        if (explanationRequested) session.open(context, settings.activeProfileId, settings.model, language)
    }
    // Resolved in composition: reading a resource from inside the coroutine would need a Context
    // and would not follow a language or configuration change.
    val dictionaryErrorMessage = stringResource(R.string.reader_lookup_dictionary_error)
    // Failures carry an app-owned category. Both interface languages localize the same distinction
    // instead of showing or translating provider text.
    val explanationFailure = explanation.failure
    val explanationErrorMessage = if (explanationFailure != null) stringResource(explanationFailureMessage(explanationFailure)) else null
    LaunchedEffect(context, dictionaryTab, dictionaryRetry) {
        if (!dictionaryTab || entry != null && dictionaryRetry == 0) return@LaunchedEffect
        dictionaryLoading = true
        dictionaryError = null
        try {
            entry = dictionary.lookup(context.selection)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            dictionaryError = dictionaryErrorMessage
            // Best-effort diagnostics: a failing sink must not replace the visible error.
            try {
                onDictionaryFailure(e)
            } catch (_: Throwable) {
                // Diagnostics must not become a second failure.
            }
        } finally { dictionaryLoading = false }
    }
    val density = LocalDensity.current
    val inset = with(density) { 12.dp.roundToPx() }
    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility
        .union(WindowInsets.displayCutout).getBottom(density)
    val position = remember(inset, bottomInset) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val geometry = learningPopupGeometry(windowSize.width, windowSize.height,
                    popupContentSize.width, popupContentSize.height, inset, bottomInset)
                return IntOffset(geometry.x, geometry.y)
            }
        }
    }
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val geometry = with(density) {
        val windowWidth = windowSize.width.takeIf { it > 0 } ?: configuration.screenWidthDp.dp.roundToPx()
        val windowHeight = windowSize.height.takeIf { it > 0 } ?: configuration.screenHeightDp.dp.roundToPx()
        learningPopupGeometry(windowWidth, windowHeight, 440.dp.roundToPx(), (windowHeight * .82f).roundToInt(),
            inset, bottomInset)
    }
    val width = with(density) { geometry.width.toDp() }
    val height = with(density) { geometry.height.toDp() }
    fun close() { speech.stop(); onClose() }
    // An outside touch or Back is reported separately from the Close button so the reader can tell
    // which dismissal happened; both still close the popup and clear the selection.
    Popup(popupPositionProvider = position, onDismissRequest = { speech.stop(); onCancel() },
        properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        Surface(modifier = Modifier.width(width).heightIn(max = height).pointerInput(activity) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) activity?.onUserInteraction()
                }
            }
        }, shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 0.dp) {
            val accent = latestSettings.accent
            val sentenceProvider = latestSettings.sentenceSpeechProvider
            val phonetic = if (dictionaryTab) entry?.phonetic.orEmpty() else if (accent == "UK") answer.ukIpa else answer.usIpa
            val partOfSpeech = if (dictionaryTab) entry?.partOfSpeech.orEmpty() else answer.partOfSpeech
            val loading = if (dictionaryTab) dictionaryLoading else explanation.loading
            val error = if (dictionaryTab) dictionaryError else explanationErrorMessage
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(context.selection, modifier = Modifier.weight(1f).heightIn(max = height * .2f)
                        .verticalScroll(rememberScrollState()), fontSize = 20.sp, lineHeight = 25.sp,
                        fontFamily = FontFamily.Serif)
                    SpeakButton(context.selection, speech, playback.text, playback.playing) {
                        speech.speakSelection(context.selection, accent, sentenceProvider)
                    }
                    IconButton(onClick = { close() }, modifier = Modifier.size(48.dp)) {
                        Icon(popupClose, contentDescription = stringResource(R.string.reader_lookup_close_description), modifier = Modifier.size(20.dp))
                    }
                }
                val details = listOf(phonetic.takeIf { it.isNotBlank() }, partOfSpeech.takeIf { it.isNotBlank() })
                    .filterNotNull().joinToString(" · ")
                if (details.isNotEmpty()) Text(details, modifier = Modifier.padding(horizontal = 14.dp),
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().selectableGroup()) {
                    LookupTab(stringResource(R.string.reader_lookup_tab_explain), !dictionaryTab, Modifier.weight(1f)) {
                        explanationRequested = true; dictionaryTab = false
                    }
                    LookupTab(stringResource(R.string.reader_lookup_tab_dictionary), dictionaryTab, Modifier.weight(1f)) { dictionaryTab = true }
                }
                HorizontalDivider()
                key(context, dictionaryTab) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val meaning = if (dictionaryTab) entry?.meaning.orEmpty() else answer.meaning
                        if (meaning.isNotBlank()) Text(meaning, fontFamily = FontFamily.Serif,
                            fontSize = 17.sp, lineHeight = 23.sp)
                        if (!dictionaryTab) {
                            if (answer.context.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_context), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Text(answer.context, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                            if (answer.examples.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_examples), fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.primary)
                                answer.examples.forEach { example ->
                                    // Both examples share one trailing icon column, and each speaker
                                    // stays on its own first line: a wrapped example can no longer
                                    // push its glyph into the middle of the text block.
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            example.displayLines(language).forEachIndexed { index, line ->
                                                Text(line,
                                                    fontSize = if (index == 0) 14.sp else 13.sp,
                                                    lineHeight = if (index == 0) 20.sp else 18.sp,
                                                    color = if (index == 0) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        // Speech always reads the English sentence, never its translation.
                                        SpeakButton(example.spokenText, speech, playback.text, playback.playing, firstLine = true) {
                                            speech.speakExample(example.spokenText, accent, sentenceProvider)
                                        }
                                    }
                                }
                            }
                        }
                        if (loading) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(if (dictionaryTab) R.string.reader_lookup_loading_dictionary else R.string.reader_lookup_loading_explanation), fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (dictionaryTab && !loading && entry == null && error == null) {
                            Text(stringResource(R.string.reader_lookup_empty_dictionary), fontSize = 15.sp)
                        }
                        error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
                            TextButton(onClick = {
                                if (dictionaryTab) dictionaryRetry++
                                else session.retry(context, latestSettings.activeProfileId, latestSettings.model, language)
                            }, enabled = !loading) { Text(stringResource(R.string.reader_lookup_retry)) }
                        }
                        playback.notice?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        playback.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
                            playback.diagnostic?.let { report -> SpeechReport(report) }
                            TextButton(onClick = speech::dismissFailure) { Text(stringResource(R.string.reader_lookup_dismiss_failure)) }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { speech.stop(); onAdjust() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reader_lookup_adjust), fontSize = 13.sp, maxLines = 1)
                    }
                    TextButton(onClick = { close() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.reader_lookup_close), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Maps the app-owned failure category to localized, actionable copy. Providers can return text in
 * any language, so no provider message is placed into either interface.
 */
internal fun explanationFailureMessage(category: ExplanationFailureCategory): Int = when (category) {
    ExplanationFailureCategory.Configuration -> R.string.reader_lookup_explanation_failure_configuration
    ExplanationFailureCategory.Authentication -> R.string.reader_lookup_explanation_failure_authentication
    ExplanationFailureCategory.Quota -> R.string.reader_lookup_explanation_failure_quota
    ExplanationFailureCategory.Connectivity -> R.string.reader_lookup_explanation_failure_connectivity
    ExplanationFailureCategory.MalformedResponse -> R.string.reader_lookup_explanation_failure_malformed
    ExplanationFailureCategory.IncompleteResponse -> R.string.reader_lookup_explanation_failure_incomplete
    ExplanationFailureCategory.Refusal -> R.string.reader_lookup_explanation_failure_refusal
    ExplanationFailureCategory.ProviderRejected -> R.string.reader_lookup_explanation_failure_rejected
    ExplanationFailureCategory.Unknown -> R.string.reader_lookup_explanation_failure_unknown
}

@Composable
private fun LookupTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .heightIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Box(Modifier.weight(1f, fill = false).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface))
    }
}

@Composable
private fun SpeakButton(
    text: String, speech: PronunciationPlayer, playingText: String, playing: Boolean,
    firstLine: Boolean = false, onSpeak: () -> Unit
) {
    val active = playing && playingText == text
    IconButton(onClick = { if (active) speech.stop() else onSpeak() }, modifier = Modifier.size(48.dp)) {
        Box(Modifier.fillMaxSize(),
            contentAlignment = if (firstLine) Alignment.TopCenter else Alignment.Center) {
            Icon(if (active) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = stringResource(if (active) R.string.reader_lookup_stop_pronouncing else R.string.reader_lookup_pronounce),
                modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Material's Close is the same filled cross in every style, so the popup draws its own hairline
 * cross on the rail's 24dp grid instead of importing a glyph that reads heavier than the rest.
 */
private val popupClose: ImageVector = ImageVector.Builder(
    name = "PeekturnPopupClose", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).path(
    fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    pathBuilder = {
        moveTo(6.4f, 6.4f); lineTo(17.6f, 17.6f)
        moveTo(17.6f, 6.4f); lineTo(6.4f, 17.6f)
    }
).build()

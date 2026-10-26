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
import androidx.compose.ui.text.font.FontWeight
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
import app.peekturn.reader.speech.SpeechState
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * Where the answer layer sits. [Bottom] is the reader's docked sheet above the text edge; [Center]
 * is a screen-centered overlay for video, independent of the video and caption containers.
 */
enum class LearningPopupPlacement { Bottom, Center }

/** Text determines height. Compact rows plus the divider gap keep 48dp speech hit targets apart. */
@Composable
internal fun ExplanationExampleRow(
    example: ExplanationExample,
    language: ExplanationLanguage,
    playing: Boolean,
    onSpeech: () -> Unit
) {
    val lines = example.displayLines(language)
    val translated = lines.size > 1
    val actionSize = if (translated) 48.dp else 44.dp
    var singleLine by remember(example.text, language) { mutableStateOf(false) }
    val centerShortLine = !translated && singleLine
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (centerShortLine) Alignment.CenterVertically else Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEachIndexed { index, line ->
                Text(line, fontSize = if (index == 0) 14.sp else 13.sp,
                    lineHeight = if (index == 0) 20.sp else 18.sp,
                    onTextLayout = { if (index == 0) singleLine = it.lineCount == 1 },
                    color = if (index == 0) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Keep the icon on the first text line, rather than centering it in a tall translation.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides actionSize) {
            IconButton(onClick = onSpeech, modifier = Modifier.width(48.dp).height(actionSize)) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (centerShortLine) Alignment.Center else Alignment.TopCenter) {
                    Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = stringResource(if (playing) R.string.reader_lookup_stop_pronouncing else R.string.reader_lookup_pronounce),
                        modifier = Modifier.size(20.dp),
                        tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Two compact lines after examples; a partial stream must not claim that a relation is absent. */
@Composable
internal fun ExplanationRelations(answer: Explanation, complete: Boolean) {
    if (answer.synonyms.isBlank() && answer.antonyms.isBlank() && !complete) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (answer.synonyms.isNotBlank() || complete) Text(
            stringResource(R.string.reader_lookup_synonyms,
                answer.synonyms.ifBlank { stringResource(R.string.reader_lookup_no_relation) }),
            fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (answer.antonyms.isNotBlank() || complete) Text(
            stringResource(R.string.reader_lookup_antonyms,
                answer.antonyms.ifBlank { stringResource(R.string.reader_lookup_no_relation) }),
            fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Native ink-and-paper answer; content and lookup ownership are unchanged. The caller decides the
 * placement and whether adjusting the source selection is a meaningful action at all.
 */
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
    /** Null hides the adjust action: video has no source selection to re-open. */
    onAdjust: (() -> Unit)? = null,
    /** Null hides replay: only a source with a real sentence start can offer it. */
    onReplay: (() -> Unit)? = null,
    /**
     * The one explicit action that resumes playback the answer paused. Null keeps the ordinary
     * close action, so a reader dismissal never changes playback state.
     */
    onResume: (() -> Unit)? = null,
    /** The thrown failure behind the visible dictionary error; the caller records it safely. */
    onDictionaryFailure: (Throwable) -> Unit = {},
    placement: LearningPopupPlacement = LearningPopupPlacement.Bottom,
    /**
     * Reports whether the visible tab currently shows real reading content: a validated answer with
     * meaning, context or examples, or a real dictionary entry. Loading, empty and failed tabs report
     * false, as does disposal. Null keeps every existing caller unchanged.
     */
    onReadableContent: ((Boolean) -> Unit)? = null
) {
    var dictionaryTab by remember(context) { mutableStateOf(initialDictionary) }
    var dictionaryRetry by remember(context) { mutableIntStateOf(0) }
    var entry by remember(context) { mutableStateOf<DictionaryEntry?>(null) }
    var dictionaryLoading by remember(context) { mutableStateOf(false) }
    var dictionaryError by remember(context) { mutableStateOf<String?>(null) }
    // Once Explain has been opened, changing tabs never interrupts its request.
    var explanationRequested by remember(context) { mutableStateOf(!initialDictionary) }
    // The session is shared, so its state can still describe the previous selection until this
    // context actually opens its request. Only then may the answer count as this popup's content.
    var explanationOwned by remember(context) { mutableStateOf(false) }
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
        if (explanationRequested) {
            session.open(context, settings.activeProfileId, settings.model, language)
            explanationOwned = true
        }
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
    // Playback learning time may only accrue while the visible tab really shows content. The
    // dictionary resets per selection, and an explanation counts only after this context opened its
    // own request, so a previous selection's answer can never report readable here.
    val latestReadableContent = rememberUpdatedState(onReadableContent)
    val dictionaryReadable = entry != null && !dictionaryLoading && dictionaryError == null
    val explanationReadable = explanationOwned && !explanation.loading && explanation.failure == null &&
        (answer.meaning.isNotBlank() || answer.context.isNotBlank() || answer.examples.any { it.text.isNotBlank() })
    val readableContent = if (dictionaryTab) dictionaryReadable else explanationReadable
    LaunchedEffect(readableContent, onReadableContent != null) {
        latestReadableContent.value?.invoke(readableContent)
    }
    DisposableEffect(Unit) {
        // Leaving the answer layer ends explanation time even when no recomposition follows.
        onDispose { latestReadableContent.value?.invoke(false) }
    }
    val density = LocalDensity.current
    val inset = with(density) { 12.dp.roundToPx() }
    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility
        .union(WindowInsets.displayCutout).getBottom(density)
    val position = remember(inset, bottomInset, placement) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val geometry = if (placement == LearningPopupPlacement.Center) {
                    centeredLearningPopupGeometry(windowSize.width, windowSize.height,
                        popupContentSize.width, popupContentSize.height, inset, bottomInset)
                } else {
                    learningPopupGeometry(windowSize.width, windowSize.height,
                        popupContentSize.width, popupContentSize.height, inset, bottomInset)
                }
                return IntOffset(geometry.x, geometry.y)
            }
        }
    }
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val geometry = with(density) {
        val windowWidth = windowSize.width.takeIf { it > 0 } ?: configuration.screenWidthDp.dp.roundToPx()
        val windowHeight = windowSize.height.takeIf { it > 0 } ?: configuration.screenHeightDp.dp.roundToPx()
        if (placement == LearningPopupPlacement.Center) {
            // A screen-owned layer: fixed maximum width and available height, never the video's
            // remaining width, so the picture and caption geometry cannot change under the answer.
            centeredLearningPopupGeometry(windowWidth, windowHeight, 480.dp.roundToPx(), windowHeight,
                inset, bottomInset)
        } else {
            learningPopupGeometry(windowWidth, windowHeight, 440.dp.roundToPx(), (windowHeight * .82f).roundToInt(),
                inset, bottomInset)
        }
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
        }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 0.dp) {
            val accent = latestSettings.accent
            val sentenceProvider = latestSettings.sentenceSpeechProvider
            val phonetic = if (dictionaryTab) entry?.phonetic.orEmpty() else if (accent == "UK") answer.ukIpa else answer.usIpa
            val partOfSpeech = if (dictionaryTab) entry?.partOfSpeech.orEmpty() else answer.partOfSpeech
            val loading = if (dictionaryTab) dictionaryLoading else explanation.loading
            val error = if (dictionaryTab) dictionaryError else explanationErrorMessage
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(context.selection, modifier = Modifier.weight(1f).heightIn(max = height * .2f)
                        .verticalScroll(rememberScrollState()), fontSize = 21.sp, lineHeight = 26.sp,
                        fontFamily = FontFamily.Serif)
                    SpeakButton(context.selection, speech, playback.text, playback.playing) {
                        speech.speakSelection(context.selection, accent, sentenceProvider)
                    }
                    IconButton(onClick = { close() }, modifier = Modifier.size(48.dp)) {
                        Icon(popupClose, contentDescription = stringResource(R.string.reader_lookup_close_description),
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val details = listOf(phonetic.takeIf { it.isNotBlank() }, partOfSpeech.takeIf { it.isNotBlank() })
                    .filterNotNull().joinToString(" · ")
                if (details.isNotEmpty()) Text(details, modifier = Modifier.padding(horizontal = 16.dp),
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
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val meaning = if (dictionaryTab) entry?.meaning.orEmpty() else answer.meaning
                        if (meaning.isNotBlank()) Text(meaning, fontFamily = FontFamily.Serif,
                            fontSize = 17.sp, lineHeight = 26.sp)
                        if (!dictionaryTab) {
                            if (answer.context.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_context),
                                    fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp, color = MaterialTheme.colorScheme.primary)
                                Text(answer.context, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                            if (answer.examples.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_examples),
                                    fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.2.sp, color = MaterialTheme.colorScheme.primary)
                                answer.examples.forEachIndexed { exampleIndex, example ->
                                    val active = playback.playing && playback.text == example.spokenText
                                    ExplanationExampleRow(example, language, active) {
                                        if (active) speech.stop()
                                        else speech.speakExample(example.spokenText, accent, sentenceProvider)
                                    }
                                    if (exampleIndex < answer.examples.lastIndex) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                }
                                ExplanationRelations(answer, explanation.complete)
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
                        LookupSpeechFailure(playback, speech::dismissFailure)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val replay = onReplay
                    val adjust = onAdjust
                    when {
                        replay != null -> TextButton(onClick = { speech.stop(); replay() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.learning_popup_replay_sentence), fontSize = 13.sp, maxLines = 1)
                        }
                        adjust != null -> TextButton(onClick = { speech.stop(); adjust() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.reader_lookup_adjust), fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    // X and outside/Back stay cancellations: only this explicit action resumes.
                    val resume = onResume
                    if (resume != null) {
                        TextButton(onClick = { speech.stop(); resume() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.learning_popup_continue_watching), fontSize = 13.sp,
                                maxLines = 1, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        TextButton(onClick = { close() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.reader_lookup_close), fontSize = 13.sp, maxLines = 1)
                        }
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
    ExplanationFailureCategory.Balance -> R.string.reader_lookup_explanation_failure_balance
    ExplanationFailureCategory.Quota -> R.string.reader_lookup_explanation_failure_quota
    ExplanationFailureCategory.InvalidRequest -> R.string.reader_lookup_explanation_failure_invalid_request
    ExplanationFailureCategory.Connectivity -> R.string.reader_lookup_explanation_failure_connectivity
    ExplanationFailureCategory.Protocol -> R.string.reader_lookup_explanation_failure_protocol
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
                fontWeight = if (selected) FontWeight.SemiBold else null,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth(.56f).height(2.dp).background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(2.dp)))
        }
    }
}

/** Learning content stays quiet on success; route/engine evidence belongs to failures or Settings. */
@Composable
internal fun LookupSpeechFailure(playback: SpeechState, onDismiss: () -> Unit) {
    val error = playback.error ?: return
    Column {
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 20.sp)
        playback.diagnostic?.let { SpeechReport(it) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_lookup_dismiss_failure)) }
    }
}

@Composable
private fun SpeakButton(
    text: String, speech: PronunciationPlayer, playingText: String, playing: Boolean,
    onSpeak: () -> Unit
) {
    val active = playing && playingText == text
    IconButton(onClick = { if (active) speech.stop() else onSpeak() }, modifier = Modifier.size(48.dp)) {
        Box(Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center) {
            Icon(if (active) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = stringResource(if (active) R.string.reader_lookup_stop_pronouncing else R.string.reader_lookup_pronounce),
                modifier = Modifier.size(20.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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

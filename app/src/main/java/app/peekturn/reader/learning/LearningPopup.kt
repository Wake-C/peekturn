package app.peekturn.reader.learning

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import app.peekturn.reader.reader.rememberReaderFontFamily
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
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
import app.peekturn.reader.ui.PeekturnIconButton
import app.peekturn.reader.ui.PeekturnIcons
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
    tight: Boolean = false,
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
                Text(line, fontSize = if (tight) { if (index == 0) 13.sp else 12.sp } else { if (index == 0) 14.sp else 13.sp },
                    lineHeight = if (tight) { if (index == 0) 18.sp else 16.sp } else { if (index == 0) 20.sp else 18.sp },
                    onTextLayout = { if (index == 0) singleLine = it.lineCount == 1 },
                    color = if (index == 0) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Keep the icon on the first text line, rather than centering it in a tall translation.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides actionSize) {
            IconButton(onClick = onSpeech, modifier = Modifier.width(48.dp).height(actionSize)) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (centerShortLine) Alignment.Center else Alignment.TopCenter) {
                    Icon(if (playing) PeekturnIcons.Stop else PeekturnIcons.Speaker,
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
internal fun ExplanationRelations(answer: Explanation, complete: Boolean, tight: Boolean = false) {
    if (answer.synonyms.isBlank() && answer.antonyms.isBlank() && !complete) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (answer.synonyms.isNotBlank() || complete) Text(
            stringResource(R.string.reader_lookup_synonyms,
                answer.synonyms.ifBlank { stringResource(R.string.reader_lookup_no_relation) }),
            fontSize = if (tight) 12.sp else 13.sp, lineHeight = if (tight) 17.sp else 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (answer.antonyms.isNotBlank() || complete) Text(
            stringResource(R.string.reader_lookup_antonyms,
                answer.antonyms.ifBlank { stringResource(R.string.reader_lookup_no_relation) }),
            fontSize = if (tight) 12.sp else 13.sp, lineHeight = if (tight) 17.sp else 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    /** The thrown failure behind the visible dictionary error; the caller records it safely. */
    onDictionaryFailure: (Throwable) -> Unit = {},
    placement: LearningPopupPlacement = LearningPopupPlacement.Bottom,
    /**
     * Reports whether the visible tab currently shows real reading content: a validated answer with
     * meaning, context or examples, or a real dictionary entry. Loading, empty and failed tabs report
     * false, as does disposal. Null keeps every existing caller unchanged.
     */
    onReadableContent: ((Boolean) -> Unit)? = null,
    /** The cross-app WindowManager host owns the window; this renders the same answer in place. */
    externalOverlay: Boolean = false,
    /** Only the cross-app card offers a direct route to model configuration. */
    onOpenSettings: (() -> Unit)? = null,
    initiallyExpanded: Boolean = false
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
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val centered = placement == LearningPopupPlacement.Center || landscape
    var expanded by rememberSaveable(context.selection, context.sectionKey) { mutableStateOf(initiallyExpanded) }
    var showOriginal by rememberSaveable(context) { mutableStateOf(false) }
    val serif = rememberReaderFontFamily("source-serif-4")
    val colors = MaterialTheme.colorScheme
    val phonetic = if (dictionaryTab) entry?.phonetic.orEmpty() else if (settings.accent == "UK") answer.ukIpa else answer.usIpa
    val partOfSpeech = if (dictionaryTab) entry?.partOfSpeech.orEmpty() else answer.partOfSpeech
    val details = listOf(phonetic, partOfSpeech).filter(String::isNotBlank).joinToString("   ·   ")
    val loading = if (dictionaryTab) dictionaryLoading else explanation.loading
    val error = if (dictionaryTab) dictionaryError else explanationErrorMessage
    val density = LocalDensity.current
    var dragged by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { dragged += it }
    val expandLabel = stringResource(if (expanded) R.string.design_collapse else R.string.design_expand)
    val headerGesture = if (externalOverlay) Modifier else Modifier.draggable(
        state = drag, orientation = Orientation.Vertical,
        onDragStarted = { dragged = 0f },
        onDragStopped = { velocity ->
            val limit = with(density) { 22.dp.toPx() }
            when {
                dragged < -limit || velocity < -650f -> expanded = true
                dragged > limit || velocity > 650f -> if (expanded) expanded = false else { speech.stop(); onCancel() }
            }
        }
    )
    val body: @Composable (Modifier) -> Unit = { frame ->
        Surface(modifier = frame.testTag("learning-popup").pointerInput(activity) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) activity?.onUserInteraction()
                }
            }
        }, shape = RoundedCornerShape(if (externalOverlay) 14.dp else 20.dp),
            color = colors.surface, shadowElevation = if (externalOverlay) 1.dp else 6.dp) {
            Column {
                Column(Modifier.fillMaxWidth().then(headerGesture)) {
                    if (!externalOverlay && !centered) {
                        Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp, 3.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = if (centered || externalOverlay) 8.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(context.selection, Modifier.heightIn(max = if (centered) 68.dp else 100.dp)
                                .verticalScroll(rememberScrollState()), fontFamily = serif,
                                fontSize = if (externalOverlay) 26.sp else 32.sp, lineHeight = if (externalOverlay) 31.sp else 37.sp,
                                color = colors.onSurface)
                            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        SpeakButton(context.selection, speech, playback.text, playback.playing) {
                            speech.speakSelection(context.selection, settings.accent, settings.sentenceSpeechProvider)
                        }
                        if (!externalOverlay) IconButton(onClick = { expanded = !expanded }) {
                            Icon(if (expanded) PeekturnIcons.ChevronDown else PeekturnIcons.ChevronUp,
                                expandLabel, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { speech.stop(); onClose() }) {
                            Icon(PeekturnIcons.Close, stringResource(R.string.reader_lookup_close_description),
                                Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    LookupTab(stringResource(R.string.reader_lookup_tab_explain), !dictionaryTab, Modifier) {
                        explanationRequested = true; dictionaryTab = false
                    }
                    LookupTab(stringResource(R.string.reader_lookup_tab_dictionary), dictionaryTab, Modifier) { dictionaryTab = true }
                }
                HorizontalDivider(color = colors.outlineVariant)
                key(context, dictionaryTab) {
                    Column(Modifier.weight(1f, fill = !externalOverlay).fillMaxWidth()
                        .testTag("learning-popup-scroll").verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = if (externalOverlay) 12.dp else 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (externalOverlay) 12.dp else 15.dp)) {
                        val meaning = if (dictionaryTab) entry?.meaning.orEmpty() else answer.meaning
                        if (meaning.isNotBlank()) Text(meaning, color = colors.onSurface,
                            fontSize = if (dictionaryTab) 16.sp else 20.sp,
                            lineHeight = if (dictionaryTab) 24.sp else 28.sp,
                            fontWeight = if (dictionaryTab) FontWeight.Normal else FontWeight.Medium)
                        if (!dictionaryTab && answer.examples.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_examples),
                                    style = MaterialTheme.typography.titleSmall)
                                answer.examples.forEachIndexed { index, example ->
                                    val active = playback.playing && playback.text == example.spokenText
                                    ExplanationExampleRow(example, language, active, tight = externalOverlay) {
                                        if (active) speech.stop()
                                        else speech.speakExample(example.spokenText, settings.accent, settings.sentenceSpeechProvider)
                                    }
                                    if (index < answer.examples.lastIndex) HorizontalDivider(color = colors.outlineVariant)
                                }
                            }
                        }
                        if (!dictionaryTab && (answer.synonyms.isNotBlank() || answer.antonyms.isNotBlank())) {
                            HorizontalDivider(color = colors.outlineVariant)
                            ExplanationRelations(answer, explanation.complete, tight = externalOverlay)
                        }
                        if (!dictionaryTab && answer.context.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(stringResource(R.string.reader_lookup_section_context),
                                    style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                                Text(answer.context, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp))
                            }
                        }
                        if (!dictionaryTab && (context.before.isNotBlank() || context.after.isNotBlank())) {
                            Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 13.dp)) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { showOriginal = !showOriginal }, modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.design_source))
                                            Icon(if (showOriginal) PeekturnIcons.ChevronUp else PeekturnIcons.ChevronDown,
                                                null, Modifier.size(16.dp))
                                        }
                                        if (onReplay != null) IconButton(onClick = { speech.stop(); onReplay() }) {
                                            Icon(PeekturnIcons.Replay, stringResource(R.string.learning_popup_replay_sentence),
                                                Modifier.size(19.dp), tint = colors.primary)
                                        }
                                        if (onAdjust != null) TextButton(onClick = { speech.stop(); onAdjust() },
                                            contentPadding = PaddingValues(horizontal = 6.dp)) {
                                            Text(stringResource(R.string.design_adjust), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    val before = remember(context) { adjacentWords(context.before, before = true) }
                                    val after = remember(context) { adjacentWords(context.after, before = false) }
                                    val quote = buildAnnotatedString {
                                        if (before.isNotEmpty()) { append(before); append(" ") }
                                        val start = length
                                        append(context.selection)
                                        addStyle(SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold), start, length)
                                        if (after.isNotEmpty()) {
                                            if (after.first() !in ".,!?;:") append(" ")
                                            append(after)
                                        }
                                    }
                                    if (showOriginal) Text(quote, fontFamily = serif, fontSize = 17.sp, lineHeight = 24.sp)
                                }
                            }
                        } else if (!dictionaryTab && onReplay != null) {
                            TextButton(onClick = { speech.stop(); onReplay() }) { Text(stringResource(R.string.learning_popup_replay_sentence)) }
                        }
                        if (loading) Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(if (dictionaryTab) R.string.reader_lookup_loading_dictionary else R.string.reader_lookup_loading_explanation),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (dictionaryTab && !loading && entry == null && error == null) Text(
                            stringResource(R.string.reader_lookup_empty_dictionary), style = MaterialTheme.typography.bodyMedium)
                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    if (dictionaryTab) dictionaryRetry++
                                    else session.retry(context, latestSettings.activeProfileId, latestSettings.model, language)
                                }, enabled = !loading) { Text(stringResource(R.string.reader_lookup_retry)) }
                                if (!dictionaryTab && explanationFailure == ExplanationFailureCategory.Configuration &&
                                    externalOverlay && onOpenSettings != null) {
                                    TextButton(onClick = { speech.stop(); onOpenSettings() }) { Text(stringResource(R.string.floating_lookup_open_settings)) }
                                }
                            }
                        }
                        LookupSpeechFailure(playback, speech::dismissFailure)
                        if (onAdjust != null && (dictionaryTab || (context.before.isBlank() && context.after.isBlank()))) {
                            TextButton(onClick = { speech.stop(); onAdjust() }) { Text(stringResource(R.string.design_adjust)) }
                        }
                    }
                }
            }
        }
    }
    if (externalOverlay) {
        body(Modifier.fillMaxWidth().heightIn(max = 440.dp))
    } else Popup(alignment = Alignment.Center, onDismissRequest = { speech.stop(); onCancel() },
        properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = .22f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    speech.stop(); onCancel()
                }.clearAndSetSemantics { })
            val align = if (centered) Alignment.Center else Alignment.BottomCenter
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = if (centered) 14.dp else 8.dp, vertical = 6.dp),
                contentAlignment = align) {
                val tall = if (expanded) .94f else if (centered) .82f else .59f
                body(Modifier.widthIn(max = if (centered) 640.dp else 520.dp).fillMaxWidth().height(maxHeight * tall))
            }
        }
    }
}

/** Use only adjacent words supplied by the caller, with no inferred or generated source text. */
private fun adjacentWords(text: String, before: Boolean): String {
    val cleaned = text.trim().replace(Regex("\\s+"), " ")
    if (cleaned.isEmpty()) return ""
    val words = cleaned.split(' ')
    if (words.size <= 24) return cleaned
    return if (before) "…" + words.takeLast(24).joinToString(" ")
        else words.take(24).joinToString(" ") + "…"
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
    Column(modifier.width(IntrinsicSize.Max).widthIn(min = 54.dp)
        .selectable(selected = selected, role = Role.Tab, onClick = onClick).heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Box(Modifier.weight(1f, fill = false).heightIn(min = 46.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(
            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)))
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
            Icon(if (active) PeekturnIcons.Stop else PeekturnIcons.Speaker,
                contentDescription = stringResource(if (active) R.string.reader_lookup_stop_pronouncing else R.string.reader_lookup_pronounce),
                modifier = Modifier.size(20.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package app.peekturn.reader.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.peekturn.reader.R
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.LlmProfile
import app.peekturn.reader.model.ModelConfig
import app.peekturn.reader.model.SentenceSpeechProvider
import app.peekturn.reader.speech.AUDIO_SOURCE_NOTICE
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.speech.SpeechReport
import app.peekturn.reader.speech.SpeechState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: ((AppSettings) -> AppSettings) -> Unit,
    onTestConnection: suspend (ModelConfig) -> Result<String>,
    speech: PronunciationPlayer,
    onExplanationCacheBytes: suspend () -> Long,
    onClearExplanationCache: suspend () -> Unit,
    onNarrationCacheBytes: suspend () -> Long,
    onClearNarrationCache: suspend () -> Unit,
    onDeveloperLogExport: suspend () -> String,
    onDeveloperLogClear: suspend () -> Unit,
    developerLogUpdates: kotlinx.coroutines.flow.StateFlow<Long>
) {
    var editingProfile by remember { mutableStateOf<LlmProfile?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<LlmProfile?>(null) }
    var pronunciationOpen by rememberSaveable { mutableStateOf(false) }
    var explanationCacheOpen by rememberSaveable { mutableStateOf(false) }
    var narrationCacheOpen by rememberSaveable { mutableStateOf(false) }
    var developerLogsOpen by rememberSaveable { mutableStateOf(false) }
    val unnamedProfile = stringResource(R.string.settings_unnamed_profile)
    val playback by speech.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .widthIn(max = 640.dp).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_interface_section), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup {
            AppearancePreference(settings.appTheme) { value -> onSave { latest -> latest.copy(appTheme = value) } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LanguagePreference(settings.uiLanguage) { value -> onSave { latest -> latest.copy(uiLanguage = value) } }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_ai_profiles), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                addingProfile = true
                editingProfile = LlmProfile(UUID.randomUUID().toString(), ModelConfig(name = ""))
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add))
            }
        }
        if (settings.profiles.isNotEmpty()) {
            SettingsGroup(Modifier.selectableGroup()) {
                settings.profiles.forEachIndexed { index, profile ->
                    ProfilePreference(
                        title = profile.config.name.ifBlank { unnamedProfile },
                        subtitle = if (profile.config.apiKey.isBlank()) stringResource(R.string.settings_not_configured) else profile.config.model,
                        selected = profile.id == settings.activeProfileId,
                        onSelect = {
                            onSave { latest ->
                                if (latest.profiles.any { it.id == profile.id }) latest.copy(activeProfileId = profile.id) else latest
                            }
                        },
                        onEdit = { addingProfile = false; editingProfile = profile },
                        onDelete = { deletingProfile = profile }
                    )
                    if (index < settings.profiles.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsDisclosure(
                stringResource(R.string.settings_pronunciation),
                stringResource(if (settings.accent == "US") R.string.settings_us_english else R.string.settings_uk_english),
                icon = Icons.Outlined.Headphones
            ) { pronunciationOpen = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsDisclosure(
                stringResource(R.string.settings_explanation_cache),
                stringResource(R.string.settings_explanation_cache_summary),
                icon = Icons.Outlined.Storage
            ) { explanationCacheOpen = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsDisclosure(
                stringResource(R.string.settings_narration_cache),
                stringResource(R.string.settings_narration_cache_summary),
                icon = Icons.Outlined.GraphicEq
            ) { narrationCacheOpen = true }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsDisclosure(
                stringResource(R.string.developer_logs_title),
                stringResource(R.string.developer_logs_summary),
                icon = Icons.Outlined.BugReport
            ) { developerLogsOpen = true }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.settings_reading_location), Modifier.padding(horizontal = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pronunciationOpen) {
        Dialog(onDismissRequest = { pronunciationOpen = false }) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PronunciationSection(settings, playback, speech, onSave)
                    TextButton(onClick = { pronunciationOpen = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.shell_close))
                    }
                }
            }
        }
    }
    if (explanationCacheOpen) {
        ExplanationCacheDialog(
            onLoadBytes = onExplanationCacheBytes,
            onClear = onClearExplanationCache,
            onDismiss = { explanationCacheOpen = false }
        )
    }
    if (narrationCacheOpen) {
        NarrationCacheDialog(
            onLoadBytes = onNarrationCacheBytes,
            onClear = onClearNarrationCache,
            onDismiss = { narrationCacheOpen = false }
        )
    }
    if (developerLogsOpen) {
        DeveloperLogDialog(
            onExport = onDeveloperLogExport,
            onClear = onDeveloperLogClear,
            updates = developerLogUpdates,
            onDismiss = { developerLogsOpen = false }
        )
    }
    editingProfile?.let { profile ->
        ModelConfigurationDialog(initial = profile.config, onDismiss = { editingProfile = null },
            onSave = { config ->
                val isNew = addingProfile
                onSave { latest ->
                    if (isNew) latest.copy(profiles = latest.profiles + profile.copy(config = config), activeProfileId = profile.id)
                    else latest.copy(profiles = latest.profiles.map { if (it.id == profile.id) it.copy(config = config) else it })
                }
                editingProfile = null
            }, onTestConnection = onTestConnection)
    }
    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.settings_delete_profile_title, profile.config.name.ifBlank { unnamedProfile })) },
            confirmButton = {
                TextButton(onClick = {
                    onSave { latest -> latest.copy(profiles = latest.profiles.filterNot { it.id == profile.id }) }
                    deletingProfile = null
                }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text(stringResource(R.string.settings_cancel)) } }
        )
    }
}

/**
 * A soft paper block that carries related settings rows on one shared rounded surface, with
 * hairline dividers between rows instead of the page background showing through.
 */
@Composable
private fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}


@Composable
private fun ExplanationCacheDialog(
    onLoadBytes: suspend () -> Long,
    onClear: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { bytes = runCatching { onLoadBytes() }.getOrNull() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_explanation_cache), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_explanation_cache_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        R.string.settings_explanation_cache_usage,
                        bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                            ?: stringResource(R.string.settings_explanation_cache_calculating)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (cleared) {
                    Text(stringResource(R.string.settings_explanation_cache_cleared),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = {
                    clearing = true
                    cleared = false
                    scope.launch {
                        try {
                            onClear()
                            bytes = 0L
                            cleared = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed delete leaves the existing entries visible; keep the button usable.
                        } finally {
                            clearing = false
                        }
                    }
                }, enabled = !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (clearing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_explanation_cache_clear))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.shell_close))
                }
            }
        }
    }
}

@Composable
private fun NarrationCacheDialog(
    onLoadBytes: suspend () -> Long,
    onClear: suspend () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { bytes = runCatching { onLoadBytes() }.getOrNull() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_narration_cache), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.settings_narration_cache_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(
                        R.string.settings_narration_cache_usage,
                        bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
                            ?: stringResource(R.string.settings_narration_cache_calculating)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (cleared) {
                    Text(stringResource(R.string.settings_narration_cache_cleared),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = {
                    clearing = true
                    cleared = false
                    scope.launch {
                        try {
                            onClear()
                            bytes = 0L
                            cleared = true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed clear leaves the existing entries visible; keep the button usable.
                        } finally {
                            clearing = false
                        }
                    }
                }, enabled = !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (clearing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_narration_cache_clear))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.shell_close))
                }
            }
        }
    }
}

/**
 * The copied report is exactly what [onExport] returned for this opening: refreshed on demand,
 * never a stale placeholder. The text scrolls and stays selectable while Copy and Close remain
 * pinned below it, so a narrow screen or large font cannot hide the action.
 */
@Composable
private fun DeveloperLogDialog(
    onExport: suspend () -> String,
    onClear: suspend () -> Unit,
    updates: kotlinx.coroutines.flow.StateFlow<Long>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.developer_logs_clip_label)
    val clearButtonLabel = stringResource(R.string.developer_logs_clear)
    val clearSuccessLabel = stringResource(R.string.developer_logs_cleared)
    val clearErrorLabel = stringResource(R.string.developer_logs_clear_error)
    var report by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var copyFailed by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var clearFailed by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, updates) {
        updates.collect {
            // StateFlow conflates writes during this delay. Continuous narration must not cancel
            // every refresh, flash the initial spinner, or keep the copy action disabled.
            if (report != null) delay(500L)
            loading = report == null
            try {
                val exported = onExport()
                report = exported
                failed = exported.isBlank()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.developer_logs_title), style = MaterialTheme.typography.titleLarge)
                Surface(Modifier.weight(1f).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        when {
                            loading -> Column(Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.developer_logs_loading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            failed -> Text(stringResource(R.string.developer_logs_error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error)
                            else -> SelectionContainer {
                                Text(report.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (copied) {
                    Text(stringResource(R.string.developer_logs_copied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (copyFailed) {
                    Text(stringResource(R.string.developer_logs_copy_error),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                if (cleared) {
                    Text(clearSuccessLabel, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (clearFailed) {
                    Text(clearErrorLabel, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        scope.launch {
                            clearing = true
                            cleared = false
                            clearFailed = false
                            try {
                                onClear()
                                report = onExport()
                                cleared = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                clearFailed = true
                            } finally {
                                clearing = false
                            }
                        }
                    }, enabled = !clearing, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(clearButtonLabel)
                    }
                    if (failed) {
                        TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.developer_logs_retry))
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.shell_close))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            copying = true
                            copied = false
                            copyFailed = false
                            try {
                                val exported = onExport()
                                check(exported.isNotBlank())
                                val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                                clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, exported))
                                report = exported
                                copied = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                copyFailed = true
                            } finally {
                                copying = false
                            }
                        }
                    }, enabled = !loading && !copying && !failed && !report.isNullOrBlank(),
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.developer_logs_copy))
                    }
                }
            }
        }
    }
}

private const val PREVIEW_WORD = "hello"
private const val PREVIEW_SENTENCE = "A little further, one page at a time."

/** The one shared source for phrases, examples, previews and narration; a word keeps its own route. */
private val sentenceProviderChoices = listOf(
    Triple(SentenceSpeechProvider.MICROSOFT, R.string.settings_source_microsoft, R.string.settings_source_microsoft_detail),
    Triple(SentenceSpeechProvider.BAIDU, R.string.settings_source_baidu, R.string.settings_source_baidu_detail),
    Triple(SentenceSpeechProvider.ANDROID, R.string.settings_source_android, R.string.settings_source_android_detail)
)

/**
 * Concise controls first (toggle, accent, word/sentence previews); the source explanation and the
 * system speech entry point stay one tap away. Failures are never hidden inside the expansion.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PronunciationSection(
    settings: AppSettings,
    playback: SpeechState,
    speech: PronunciationPlayer,
    onSave: ((AppSettings) -> AppSettings) -> Unit
) {
    var details by rememberSaveable { mutableStateOf(false) }
    val wordPreview = playback.playing && playback.text == PREVIEW_WORD
    val sentencePreview = playback.playing && playback.text == PREVIEW_SENTENCE
    val previewMinWidth = 120.dp * LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_pronunciation), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("US", "UK").forEach { accent ->
                FilterChip(selected = settings.accent == accent, onClick = {
                    speech.stop()
                    onSave { latest -> latest.copy(accent = accent) }
                }, label = { Text(stringResource(if (accent == "US") R.string.settings_us_english else R.string.settings_uk_english)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        Text(stringResource(R.string.settings_sentence_source), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            sentenceProviderChoices.forEach { (provider, label, detail) ->
                SentenceProviderChoice(
                    label = stringResource(label),
                    detail = stringResource(detail),
                    selected = settings.sentenceSpeechProvider == provider
                ) {
                    speech.stop()
                    onSave { latest -> latest.copy(sentenceSpeechProvider = provider) }
                }
            }
        }
        if (settings.sentenceSpeechProvider == SentenceSpeechProvider.ANDROID) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_source_android_system_voice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { speech.openSystemSettings() },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.settings_system_speech_settings))
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewAction(stringResource(R.string.settings_word), wordPreview, Modifier.widthIn(min = previewMinWidth).weight(1f)) {
                if (wordPreview) speech.stop() else speech.speakWord(PREVIEW_WORD, settings.accent)
            }
            PreviewAction(stringResource(R.string.settings_sentence), sentencePreview, Modifier.widthIn(min = previewMinWidth).weight(1f)) {
                if (sentencePreview) speech.stop()
                else speech.speakExample(PREVIEW_SENTENCE, settings.accent, settings.sentenceSpeechProvider)
            }
        }
        playback.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        playback.notice?.let { message -> Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        playback.diagnostic?.let { report -> SpeechReport(report) }
        playback.credit?.let { AudioAttribution(it) }
        TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(if (details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (details) R.string.settings_hide_audio_details else R.string.settings_audio_details))
        }
        if (details) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(AUDIO_SOURCE_NOTICE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { speech.openSystemSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (playback.error != null) R.string.settings_install_voice else R.string.settings_system_speech_settings))
                }
            }
        }
    }
}

@Composable
private fun SentenceProviderChoice(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .heightIn(min = 52.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PreviewAction(label: String, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (playing) stringResource(R.string.settings_stop) else label, textAlign = TextAlign.Center, maxLines = 2)
    }
}


@Composable
private fun ModelConfigurationDialog(
    initial: ModelConfig,
    onDismiss: () -> Unit,
    onSave: (ModelConfig) -> Unit,
    onTestConnection: suspend (ModelConfig) -> Result<String>
) {
    // Never save credentials in instance-state bundles or display them in provider errors.
    var draft by remember { mutableStateOf(initial) }
    var temperatureDraft by remember { mutableStateOf(initial.temperature.toString()) }
    var testing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val temperature = temperatureDraft.trim().replace(',', '.').toDoubleOrNull()?.takeIf { ModelConfig.validTemperature(it) }
    val canSave = temperature != null && draft.name.isNotBlank() && draft.endpoint.isNotBlank() && draft.model.isNotBlank()
    val ready = canSave && draft.apiKey.isNotBlank()
    fun update(config: ModelConfig) { draft = config; resultMessage = null }
    fun updateTemperature(text: String) { temperatureDraft = text; resultMessage = null }
    val failedMessage = stringResource(R.string.settings_connection_test_failed)
    val incompleteMessage = stringResource(R.string.settings_connection_test_incomplete)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).imePadding().heightIn(max = 640.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_ai_profile), style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, label = { Text(stringResource(R.string.settings_profile_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing)
                OutlinedTextField(draft.endpoint, { update(draft.copy(endpoint = it)) }, label = { Text(stringResource(R.string.settings_base_url)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(draft.model, { update(draft.copy(model = it)) }, label = { Text(stringResource(R.string.settings_model_identifier)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing)
                OutlinedTextField(draft.apiKey, { update(draft.copy(apiKey = it)) }, label = { Text(stringResource(R.string.settings_api_key)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(temperatureDraft, { updateTemperature(it) }, label = { Text(stringResource(R.string.settings_temperature)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    isError = temperature == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text(stringResource(R.string.settings_temperature_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (temperature == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_test_charge_detail),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    val chosenTemperature = temperature ?: return@OutlinedButton
                    testing = true
                    resultMessage = null
                    val config = draft.copy(temperature = chosenTemperature, name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim())
                    scope.launch {
                        try {
                            val result = onTestConnection(config)
                            testFailed = result.isFailure
                            val message = result.fold({ it }, { it.message ?: failedMessage })
                            resultMessage = message.replace(config.apiKey, "[redacted]").take(1000)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            testFailed = true
                            resultMessage = incompleteMessage
                        } finally { testing = false }
                    }
                }, enabled = ready && !testing, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (testing) R.string.settings_testing_connection else R.string.settings_test_connection))
                }
                resultMessage?.let { Text(it, color = if (testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Button(onClick = {
                    val chosenTemperature = temperature ?: return@Button
                    onSave(draft.copy(temperature = chosenTemperature, name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim()))
                },
                    enabled = canSave && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_save_profile)) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_cancel)) }
            }
        }
    }
}

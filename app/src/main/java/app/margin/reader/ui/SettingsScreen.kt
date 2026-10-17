package app.margin.reader.ui

import android.app.LocaleManager
import android.os.Build
import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.margin.reader.R
import app.margin.reader.model.AppSettings
import app.margin.reader.model.LlmProfile
import app.margin.reader.model.ModelConfig
import app.margin.reader.speech.AUDIO_SOURCE_NOTICE
import app.margin.reader.speech.PronunciationPlayer
import app.margin.reader.speech.SpeechReport
import app.margin.reader.speech.SpeechState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: ((AppSettings) -> AppSettings) -> Unit,
    onTestConnection: suspend (ModelConfig) -> Result<String>,
    speech: PronunciationPlayer
) {
    var editingProfile by remember { mutableStateOf<LlmProfile?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<LlmProfile?>(null) }
    val unnamedProfile = stringResource(R.string.settings_unnamed_profile)
    val playback by speech.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleLarge)
        ThemeGroup(settings.appTheme) { value -> onSave { latest -> latest.copy(appTheme = value) } }
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleLarge)
        LanguageGroup(settings.uiLanguage) { value -> onSave { latest -> latest.copy(uiLanguage = value) } }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_ai_profiles), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = {
                addingProfile = true
                editingProfile = LlmProfile(UUID.randomUUID().toString(), ModelConfig(name = ""))
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add))
            }
        }
        Column(Modifier.selectableGroup()) {
            settings.profiles.forEach { profile ->
                val active = profile.id == settings.activeProfileId
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected = active, role = Role.RadioButton, onClick = {
                            onSave { latest ->
                                if (latest.profiles.any { it.id == profile.id }) latest.copy(activeProfileId = profile.id) else latest
                            }
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = active, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.config.name.ifBlank { unnamedProfile }, style = MaterialTheme.typography.titleMedium)
                            Text(if (profile.config.apiKey.isBlank()) stringResource(R.string.settings_not_configured) else profile.config.model,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { addingProfile = false; editingProfile = profile }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.settings_edit_profile, profile.config.name))
                    }
                    IconButton(onClick = { deletingProfile = profile }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.settings_delete_profile, profile.config.name))
                    }
                }
            }
        }
        HorizontalDivider()
        PreferenceSwitch(stringResource(R.string.settings_quick_explain), stringResource(R.string.settings_quick_explain_detail),
            settings.quickExplain) { enabled -> onSave { latest -> latest.copy(quickExplain = enabled) } }
        HorizontalDivider()
        PronunciationSection(settings, playback, speech, onSave)
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
 * Five themes sit in a 3 + 2 grid: the second row uses the same fixed tile width as the first and
 * is centred, so every tile has one width and one height rather than two half-width tiles stretching
 * wider than the row above. Past 1.8x text the columns are too narrow for their labels on a phone,
 * so the same tiles stack at full width instead of breaking words to fit.
 */
@Composable
private fun ThemeGroup(selected: String, onSelect: (String) -> Unit) {
    val stacked = LocalDensity.current.fontScale >= 1.8f
    val tiles = listOf(
        "dark" to stringResource(R.string.settings_theme_dark),
        "light" to stringResource(R.string.settings_theme_light),
        "warm" to stringResource(R.string.settings_theme_warm),
        "oled" to stringResource(R.string.settings_theme_oled),
        "sage" to stringResource(R.string.settings_theme_sage)
    )
    if (stacked) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.forEach { (value, label) -> Tile(value, label, selected, Modifier.fillMaxWidth(), onSelect) }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val tileWidth = (maxWidth - gap * 2) / 3
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tiles.take(3).forEach { (value, label) -> Tile(value, label, selected, Modifier.width(tileWidth), onSelect) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                    tiles.drop(3).forEach { (value, label) -> Tile(value, label, selected, Modifier.width(tileWidth), onSelect) }
                }
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, selected: String, modifier: Modifier, onSelect: (String) -> Unit) {
    val preview = marginColorScheme(value)
    ThemeChoice(label, selected == value, preview.background, preview.onBackground, preview.primary, modifier) {
        onSelect(value)
    }
}

/**
 * Language uses the accent selector's chip idiom: one choice per chip, the stored choice selected.
 * "Follow system" spells out the language it currently resolves to (Chinese only for a zh system,
 * otherwise the English fallback), so the option is never an unexplained promise.
 */
@Composable
private fun LanguageGroup(selected: String, onSelect: (String) -> Unit) {
    // The system locale, not the app's: a per-app language overrides both Resources.getSystem() and
    // the activity configuration, so the device's own locale list is read instead. "Follow system"
    // must name what it would actually resolve to.
    val context = LocalContext.current
    val systemLanguage = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)?.language
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locales[0]?.language
        }
    }
    val systemName = stringResource(if (systemLanguage == "zh") R.string.settings_language_zh else R.string.settings_language_en)
    val options = listOf(
        "system" to stringResource(R.string.settings_language_system, systemName),
        "en" to stringResource(R.string.settings_language_en),
        "zh" to stringResource(R.string.settings_language_zh)
    )
    // The full "Follow system (…)" label needs the whole row once the type grows; below that the
    // three chips stay one compact row.
    val stacked = LocalDensity.current.fontScale >= 1.5f
    if (stacked) {
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) -> LanguageChoice(label, selected == value, Modifier.fillMaxWidth()) { onSelect(value) } }
        }
    } else {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                // The system chip carries the resolved language in its label, so it gets the extra
                // column width; the two explicit languages need one short word each.
                val weight = if (value == "system") 1.4f else 0.8f
                LanguageChoice(label, selected == value, Modifier.weight(weight)) { onSelect(value) }
            }
        }
    }
}

@Composable
private fun LanguageChoice(label: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    FilterChip(selected = selected, onClick = onSelect, label = {
        // Two lines on every chip keep the row one height even when only "Follow system" wraps.
        Text(label, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, style = MaterialTheme.typography.labelLarge)
    }, modifier = modifier.heightIn(min = 48.dp))
}

@Composable
private fun ThemeChoice(
    label: String, selected: Boolean, paper: Color, ink: Color, accent: Color,
    modifier: Modifier = Modifier, onSelect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline)
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp).background(paper, MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Aa", color = ink, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleMedium)
                    // The accent is part of the scheme too, so the preview shows all three roles.
                    Box(Modifier.width(16.dp).height(2.dp).background(accent))
                }
            }
            // Two label lines are reserved so all five tiles keep one height at every text scale, even
            // when only the longest name ("Classic White") needs the second line.
            Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2, minLines = 2)
        }
    }
}

private const val PREVIEW_WORD = "hello"
private const val PREVIEW_SENTENCE = "A little further, one page at a time."

/**
 * Concise controls first (toggle, accent, word/sentence previews); the source explanation and the
 * system speech entry point stay one tap away. Failures are never hidden inside the expansion.
 */
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_pronunciation), style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch(stringResource(R.string.settings_auto_pronounce), stringResource(R.string.settings_auto_pronounce_detail),
            settings.autoPronounce) { enabled -> onSave { latest -> latest.copy(autoPronounce = enabled) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("US", "UK").forEach { accent ->
                FilterChip(selected = settings.accent == accent, onClick = {
                    speech.stop()
                    onSave { latest -> latest.copy(accent = accent) }
                }, label = { Text(stringResource(if (accent == "US") R.string.settings_us_english else R.string.settings_uk_english)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewAction(stringResource(R.string.settings_word), wordPreview, Modifier.weight(1f)) {
                if (wordPreview) speech.stop() else speech.speakWord(PREVIEW_WORD, settings.accent)
            }
            PreviewAction(stringResource(R.string.settings_sentence), sentencePreview, Modifier.weight(1f)) {
                if (sentencePreview) speech.stop() else speech.speakExample(PREVIEW_SENTENCE, settings.accent)
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
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(Modifier.semantics { contentDescription = title }))
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
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

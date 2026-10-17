package app.margin.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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
import app.margin.reader.model.AppSettings
import app.margin.reader.model.LlmProfile
import app.margin.reader.model.ModelConfig
import app.margin.reader.speech.PronunciationPlayer
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
    val playback by speech.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(speech, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.stop() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Focus Dark", "light" to "Classic White", "warm" to "Warm Paper").forEach { (value, label) ->
                val preview = marginColorScheme(value)
                ThemeChoice(label, settings.appTheme == value, preview.background, preview.onBackground) {
                    onSave { latest -> latest.copy(appTheme = value) }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI profiles", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = {
                addingProfile = true
                editingProfile = LlmProfile(UUID.randomUUID().toString(), ModelConfig(name = ""))
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
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
                            Text(profile.config.name.ifBlank { "Unnamed profile" }, style = MaterialTheme.typography.titleMedium)
                            Text(if (profile.config.apiKey.isBlank()) "Not configured" else profile.config.model,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { addingProfile = false; editingProfile = profile }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit ${profile.config.name}")
                    }
                    IconButton(onClick = { deletingProfile = profile }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete ${profile.config.name}")
                    }
                }
            }
        }
        HorizontalDivider()
        PreferenceSwitch("Quick Explain", "Explain a new selection immediately. Off keeps Explain as an explicit action.",
            settings.quickExplain) { enabled -> onSave { latest -> latest.copy(quickExplain = enabled) } }
        HorizontalDivider()
        Text("Pronunciation", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Auto-pronounce", "Speak the selected word or phrase once when a lookup opens.",
            settings.autoPronounce) { enabled -> onSave { latest -> latest.copy(autoPronounce = enabled) } }
        HorizontalDivider()
        Text("Preferred accent", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("US", "UK").forEach { accent ->
                FilterChip(selected = settings.accent == accent, onClick = {
                    speech.stop()
                    onSave { latest -> latest.copy(accent = accent) }
                }, label = { Text(if (accent == "US") "US English" else "UK English") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("Words use online recordings when available. Phrases and examples use your phone’s English voice; another English accent may be used.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Only the word, not its sentence or book, is sent to Wikimedia Commons.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val wordPreview = playback.playing && playback.text == "hello"
        OutlinedButton(onClick = {
            if (wordPreview) speech.stop() else speech.speakWord("hello", settings.accent)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (wordPreview) "Stop word audio" else "Preview ${settings.accent} word") }
        val examplePreview = playback.playing && playback.text == "A little further, one page at a time."
        OutlinedButton(onClick = {
            if (examplePreview) speech.stop() else speech.speakExample("A little further, one page at a time.", settings.accent)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (examplePreview) "Stop sentence" else "Preview English sentence") }
        playback.notice?.let { message -> Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall) }
        playback.credit?.let { RecordingAttribution(it) }
        playback.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { speech.openSystemSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (playback.error != null) "Install or choose a system voice" else "System speech settings")
        }
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
            title = { Text("Delete ${profile.config.name.ifBlank { "profile" }}?") },
            confirmButton = {
                TextButton(onClick = {
                    onSave { latest -> latest.copy(profiles = latest.profiles.filterNot { it.id == profile.id }) }
                    deletingProfile = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, paper: Color, ink: Color, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline)
    ) {
        Row(Modifier.heightIn(min = 64.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(paper, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Text("Aa", color = ink, fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleLarge)
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            RadioButton(selected = selected, onClick = null)
        }
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).imePadding().heightIn(max = 640.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("AI profile", style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing)
                OutlinedTextField(draft.endpoint, { update(draft.copy(endpoint = it)) }, label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(draft.model, { update(draft.copy(model = it)) }, label = { Text("Model identifier") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing)
                OutlinedTextField(draft.apiKey, { update(draft.copy(apiKey = it)) }, label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                OutlinedTextField(temperatureDraft, { updateTemperature(it) }, label = { Text("Temperature") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !testing,
                    isError = temperature == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Temperature sets how random the answers are, not how fast they arrive. Allowed values are 0 to 2; lower is more predictable. Provider support varies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (temperature == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Connection testing sends a short real request and may incur provider charges.",
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
                            val message = result.fold({ it }, { it.message ?: "The connection test failed." })
                            resultMessage = message.replace(config.apiKey, "[redacted]").take(1000)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            testFailed = true
                            resultMessage = "The connection test could not complete. Check your configuration and connection."
                        } finally { testing = false }
                    }
                }, enabled = ready && !testing, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testing) "Testing connection" else "Test connection")
                }
                resultMessage?.let { Text(it, color = if (testFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Button(onClick = {
                    val chosenTemperature = temperature ?: return@Button
                    onSave(draft.copy(temperature = chosenTemperature, name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim()))
                },
                    enabled = canSave && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save profile") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

package app.margin.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import app.margin.reader.model.ModelConfig
import app.margin.reader.speech.PronunciationPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: ((AppSettings) -> AppSettings) -> Unit,
    onTestConnection: suspend (ModelConfig) -> Result<String>,
    speech: PronunciationPlayer
) {
    var editingModel by remember { mutableStateOf(false) }
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
        Text("A few considered choices. Then back to the book.", color = FocusColors.Muted)
        Text("Understanding", style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Active AI model", style = MaterialTheme.typography.titleMedium)
            Text(settings.model.name.ifBlank { "Unnamed model" }, color = FocusColors.Accent)
            Text(settings.model.model.ifBlank { "No model configured" }, color = FocusColors.Muted)
            Text(if (settings.model.apiKey.isBlank()) "API key not configured. Offline reading and dictionary remain available."
                else "API key saved securely on this device.", style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
            OutlinedButton(onClick = { editingModel = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Configure model") }
        }
        HorizontalDivider()
        PreferenceSwitch("Quick Explain", "Explain a new selection immediately. Off keeps Explain as an explicit action.",
            settings.quickExplain) { enabled -> onSave { latest -> latest.copy(quickExplain = enabled) } }
        HorizontalDivider()
        PreferenceSwitch("Reasoning", "Request thinking from your configured model. Provider support varies.",
            settings.model.thinking) { enabled -> onSave { latest -> latest.copy(model = latest.model.copy(thinking = enabled)) } }
        HorizontalDivider()
        Text("Pronunciation", style = MaterialTheme.typography.titleLarge)
        PreferenceSwitch("Auto-pronounce", "Speak the selected word or phrase once when a lookup opens.",
            settings.autoPronounce) { enabled -> onSave { latest -> latest.copy(autoPronounce = enabled) } }
        HorizontalDivider()
        Text("English accent", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("US", "UK").forEach { accent ->
                FilterChip(selected = settings.accent == accent, onClick = {
                    speech.stop()
                    onSave { latest -> latest.copy(accent = accent) }
                }, label = { Text(if (accent == "US") "US English" else "UK English") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("Uses your Android speech engine. An installed offline voice is preferred; the other accent is never substituted.",
            style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
        OutlinedButton(onClick = {
            if (playback.playing) speech.stop() else speech.speak("A little further, one page at a time.", settings.accent)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (playback.playing) "Stop preview" else "Preview ${settings.accent} voice") }
        playback.error?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = { speech.openSystemSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (playback.error != null) "Install or choose a system voice" else "System speech settings")
        }
        HorizontalDivider()
        Text("Reading appearance", style = MaterialTheme.typography.titleLarge)
        Text("Font, spacing, margins, paper, and page or scroll layout are available inside each book.", color = FocusColors.Muted)
    }
    if (editingModel) {
        ModelConfigurationDialog(initial = settings.model, onDismiss = { editingModel = false },
            onSave = { model -> onSave { latest -> latest.copy(model = model) }; editingModel = false }, onTestConnection = onTestConnection)
    }
}

@Composable
private fun PreferenceSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
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
    var testing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val canSave = draft.name.isNotBlank() && draft.endpoint.isNotBlank() && draft.model.isNotBlank()
    val ready = canSave && draft.apiKey.isNotBlank()
    fun update(config: ModelConfig) { draft = config; resultMessage = null }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).imePadding().heightIn(max = 640.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Model configuration", style = MaterialTheme.typography.headlineMedium)
                Text("One active OpenAI-compatible model. Changes apply only when you save.", color = FocusColors.Muted)
                OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, label = { Text("Display name") },
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
                PreferenceSwitch("Reasoning", "Request thinking when supported by the provider.", draft.thinking, enabled = !testing) {
                    update(draft.copy(thinking = it))
                }
                Text("Connection testing sends a short real request and may incur provider charges.",
                    style = MaterialTheme.typography.bodySmall, color = FocusColors.Muted)
                OutlinedButton(onClick = {
                    testing = true
                    resultMessage = null
                    val config = draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim())
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
                resultMessage?.let { Text(it, color = if (testFailed) MaterialTheme.colorScheme.error else FocusColors.Accent) }
                Button(onClick = { onSave(draft.copy(name = draft.name.trim(), endpoint = draft.endpoint.trim(), model = draft.model.trim())) },
                    enabled = canSave && !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save model") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

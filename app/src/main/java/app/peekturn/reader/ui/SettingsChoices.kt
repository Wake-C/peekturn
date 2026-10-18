package app.peekturn.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R

private val themeChoices = listOf(
    "dark" to R.string.settings_theme_dark,
    "light" to R.string.settings_theme_light,
    "warm" to R.string.settings_theme_warm,
    "oled" to R.string.settings_theme_oled,
    "sage" to R.string.settings_theme_sage
)

/** A setting gets the whole text column, never a narrow chip competing with two other languages. */
@Composable
internal fun SettingsDisclosure(title: String, summary: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 68.dp)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AppearancePreference(selected: String, onSelect: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    SettingsDisclosure(stringResource(R.string.settings_appearance),
        stringResource(themeChoices.firstOrNull { it.first == selected }?.second ?: R.string.settings_theme_dark)) {
        choosing = true
    }
    if (choosing) {
        AlertDialog(onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_appearance)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    themeChoices.forEach { (value, label) ->
                        val preview = peekturnColorScheme(value)
                        Row(Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton,
                            onClick = { onSelect(value); choosing = false }).heightIn(min = 64.dp)
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(42.dp), shape = MaterialTheme.shapes.small,
                                color = preview.background, border = BorderStroke(1.dp, preview.outlineVariant)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Aa", color = preview.onBackground, fontFamily = FontFamily.Serif,
                                        style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected == value, onClick = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
internal fun LanguagePreference(selected: String, onSelect: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val choices = listOf(
        "system" to R.string.settings_language_follow,
        "en" to R.string.settings_language_en,
        "zh" to R.string.settings_language_zh
    )
    SettingsDisclosure(stringResource(R.string.settings_language),
        stringResource(choices.firstOrNull { it.first == selected }?.second ?: R.string.settings_language_follow)) {
        choosing = true
    }
    if (choosing) {
        AlertDialog(onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    choices.forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().selectable(selected == value, role = Role.RadioButton,
                            onClick = { choosing = false; onSelect(value) }).heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            RadioButton(selected == value, onClick = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
internal fun ProfilePreference(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 68.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.settings_edit_profile, title))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.settings_edit_profile, title)) },
                    onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.settings_delete)) },
                    onClick = { menu = false; onDelete() })
            }
        }
    }
}

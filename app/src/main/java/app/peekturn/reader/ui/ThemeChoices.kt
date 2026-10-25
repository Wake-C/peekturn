package app.peekturn.reader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R

private val appearanceThemes = listOf(
    "light" to R.string.settings_theme_light,
    "warm" to R.string.settings_theme_warm,
    "sage" to R.string.settings_theme_sage,
    "dark" to R.string.settings_theme_dark,
    "oled" to R.string.settings_theme_oled
)

/** One direct, accessible theme picker shared by reading and Settings. Palette values stay owned by Theme.kt. */
@Composable
internal fun ThemeChoices(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        // Keep every existing theme visible; large type or a narrow phone uses two rows, not a hidden menu.
        val columns = if (maxWidth >= (56.dp * fontScale * 5 + 32.dp)) 5 else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            appearanceThemes.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, labelRes) ->
                        val label = stringResource(labelRes)
                        val preview = peekturnColorScheme(id)
                        val active = selected == id
                        Column(
                            Modifier.weight(1f)
                                .selectable(active, role = Role.RadioButton, onClick = { onSelect(id) })
                                .heightIn(min = 80.dp).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(Modifier.size(48.dp)) {
                                Surface(
                                    Modifier.fillMaxSize(), shape = CircleShape,
                                    color = preview.background,
                                    border = BorderStroke(if (active) 2.dp else 1.dp,
                                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("Aa", color = preview.onBackground, fontFamily = FontFamily.Serif,
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (active) Surface(
                                    Modifier.size(18.dp).align(Alignment.BottomEnd), shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.padding(2.dp))
                                }
                            }
                            Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

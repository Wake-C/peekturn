@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.margin.reader.reader

import android.graphics.Typeface
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.margin.reader.R
import app.margin.reader.model.AppSettings
import app.margin.reader.model.ReaderPreferences
import app.margin.reader.ui.SettingsDisclosure
import org.readium.r2.shared.publication.Link

/**
 * A compact sheet: bundled fonts first, then size and spacing controls. Content scrolls inside a
 * bounded sheet, labels wrap instead of shrinking, and every control keeps a 48dp touch target.
 * The draft is only typography; behaviour lives in the options sheet.
 */
@Composable internal fun ReaderTypographySheet(
    settings: AppSettings,
    onApply: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(settings.reading) { mutableStateOf(settings.reading) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = readerFontFor(draft.fontFamily)
    var choosingFont by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(stringResource(R.string.reader_typography_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(14.dp))
            SettingsDisclosure(stringResource(R.string.reader_typography_font), readerFontLabel(selected)) {
                choosingFont = true
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_size),
                value = stringResource(R.string.reader_typography_size_value, draft.fontSize.toInt()),
                valueNow = draft.fontSize,
                range = 100f..180f
            ) { draft = draft.copy(fontSize = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_letter_spacing),
                value = stringResource(R.string.reader_typography_letter_spacing_value, draft.letterSpacing),
                valueNow = draft.letterSpacing,
                range = 0f..0.4f
            ) { draft = draft.copy(letterSpacing = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_line_height),
                value = stringResource(R.string.reader_typography_line_height_value, draft.lineHeight),
                valueNow = draft.lineHeight,
                range = 1f..2.4f
            ) { draft = draft.copy(lineHeight = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_paragraph_spacing),
                value = stringResource(R.string.reader_typography_paragraph_spacing_value, draft.paragraphSpacing),
                valueNow = draft.paragraphSpacing,
                range = 0f..2f
            ) { draft = draft.copy(paragraphSpacing = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_side_margins),
                value = stringResource(R.string.reader_typography_side_margins_value, draft.pageMargins),
                valueNow = draft.pageMargins,
                range = 0.3f..2.5f
            ) { draft = draft.copy(pageMargins = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_top_distance),
                value = stringResource(R.string.reader_typography_distance_value, draft.topPaddingDp.toInt()),
                valueNow = draft.topPaddingDp,
                range = 16f..120f
            ) { draft = draft.copy(topPaddingDp = it) }
            PreferenceSlider(
                title = stringResource(R.string.reader_typography_bottom_distance),
                value = stringResource(R.string.reader_typography_distance_value, draft.bottomPaddingDp.toInt()),
                valueNow = draft.bottomPaddingDp,
                range = 24f..140f
            ) { draft = draft.copy(bottomPaddingDp = it) }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_typography_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onApply(draft) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_typography_apply))
                }
            }
        }
    }
    if (choosingFont) {
        AlertDialog(
            onDismissRequest = { choosingFont = false },
            title = { Text(stringResource(R.string.reader_typography_font)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup()) {
                    ReaderFonts.all.forEach { font ->
                        FontChoice(font, selected.id == font.id, Modifier.fillMaxWidth()) {
                            draft = draft.copy(fontFamily = font.id)
                            choosingFont = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingFont = false }) {
                    Text(stringResource(R.string.reader_typography_cancel))
                }
            }
        )
    }
}

@Composable private fun FontChoice(
    font: ReaderFont,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val assets = LocalContext.current.assets
    val previewFamily = remember(assets, font.id) {
        if (font.faces.isEmpty()) {
            FontFamily.Default
        } else {
            val face = font.faces.first()
            val builder = Typeface.Builder(assets, face.path).setWeight(400)
            if (face.weights.first != face.weights.last) {
                builder.setFontVariationSettings("'wght' 400")
            }
            FontFamily(builder.build())
        }
    }
    Row(modifier.heightIn(min = 64.dp)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Aa", fontFamily = previewFamily, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.width(42.dp))
        Spacer(Modifier.width(12.dp))
        Text(readerFontLabel(font), fontFamily = previewFamily, style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable private fun readerFontLabel(font: ReaderFont): String =
    if (font.id == "system") stringResource(R.string.reader_typography_system_font) else font.familyName

@Composable private fun PreferenceSlider(
    title: String,
    value: String,
    valueNow: Double,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Double) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = valueNow.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        )
    }
}

/**
 * The contents panel. Its header owns the whole-book search entry, drawn as binoculars because
 * the query is a look through the entire publication rather than a filter of this chapter list.
 * Rows use the publication's own TOC titles and hierarchy, and the current chapter is the reader's
 * resolved label, not an invented page number.
 */
@Composable internal fun ReaderContentsSheet(
    chapters: List<Pair<Int, Link>>,
    currentChapter: String,
    onOpen: (Link) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.reader_contents_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onSearch, modifier = Modifier.size(48.dp)) {
                    Icon(
                        toolbarBinoculars,
                        contentDescription = stringResource(R.string.reader_contents_search)
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.reader_close)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (chapters.isEmpty()) {
                Text(
                    stringResource(R.string.reader_contents_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    itemsIndexed(chapters) { index, (depth, link) ->
                        val title = link.title ?: stringResource(R.string.reader_chapter_untitled)
                        val current = title.equals(currentChapter.trim(), ignoreCase = true)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = title,
                                    onClick = { onOpen(link) }
                                )
                                .padding(
                                    start = (20 + depth.coerceAtMost(5) * 12).dp,
                                    end = 20.dp,
                                    top = 14.dp,
                                    bottom = 14.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (index < chapters.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Reading behaviour toggles. Each switch changes only its own field via the caller's transform. */
@Composable internal fun ReaderOptionsSheet(
    options: ReaderOptions,
    onChange: (ReaderOption, Boolean) -> Unit,
    cacheBytes: Long,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(stringResource(R.string.reader_options_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OptionSwitch(
                title = stringResource(R.string.reader_options_immersive),
                detail = stringResource(R.string.reader_options_immersive_detail),
                checked = options.immersive
            ) { onChange(ReaderOption.IMMERSIVE, it) }
            OptionSwitch(
                title = stringResource(R.string.reader_options_scroll),
                detail = stringResource(R.string.reader_options_scroll_detail),
                checked = options.scroll
            ) { onChange(ReaderOption.SCROLL, it) }
            OptionSwitch(
                title = stringResource(R.string.reader_options_quick_explain),
                detail = stringResource(R.string.reader_options_quick_explain_detail),
                checked = options.quickExplain
            ) { onChange(ReaderOption.QUICK_EXPLAIN, it) }
            OptionSwitch(
                title = stringResource(R.string.reader_options_auto_pronounce),
                detail = stringResource(R.string.reader_options_auto_pronounce_detail),
                checked = options.autoPronounce
            ) { onChange(ReaderOption.AUTO_PRONOUNCE, it) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        stringResource(R.string.reader_options_narration_cache),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        stringResource(
                            R.string.reader_options_narration_cache_size,
                            Formatter.formatShortFileSize(LocalContext.current, cacheBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = onClearCache,
                    enabled = cacheBytes > 0,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.reader_options_narration_clear))
                }
            }
            Text(
                stringResource(R.string.reader_options_narration_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_options_done))
                }
            }
        }
    }
}

@Composable private fun OptionSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

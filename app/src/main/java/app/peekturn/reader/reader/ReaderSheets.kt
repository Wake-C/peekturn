@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.peekturn.reader.reader

import android.graphics.Typeface
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.peekturn.reader.R
import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.ui.SettingsDisclosure
import app.peekturn.reader.ui.ThemeChoices
import org.readium.r2.shared.publication.Link

/** A's sheets lift on a 24dp top radius over the reading surface. */
private val readerSheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * Theme previews and a few named type/layout presets, with less-used controls folded away.
 * Only Apply commits the draft. The fixed footer stays reachable while the body scrolls;
 * opening or cancelling never rounds an existing custom preference to a preset.
 */
@Composable internal fun ReaderTypographySheet(
    settings: AppSettings,
    onApply: (ReaderPreferences, String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(settings.reading) { mutableStateOf(settings.reading) }
    var draftTheme by remember(settings.appTheme) { mutableStateOf(settings.appTheme) }
    var more by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selected = readerFontFor(draft.fontFamily)
    var choosingFont by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = readerSheetShape,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(stringResource(R.string.reader_typography_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.reader_typography_theme), style = MaterialTheme.typography.titleSmall)
                ThemeChoices(draftTheme, { draftTheme = it }, Modifier.padding(top = 8.dp, bottom = 12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsDisclosure(stringResource(R.string.reader_typography_font), readerFontLabel(selected)) {
                    choosingFont = true
                }
                TypographyPresetRow(stringResource(R.string.reader_typography_size), ReaderTypographyPreset.FONT_SIZE, draft) { draft = it }
                TypographyPresetRow(stringResource(R.string.reader_typography_line_height), ReaderTypographyPreset.LINE_HEIGHT, draft) { draft = it }
                TypographyPresetRow(stringResource(R.string.reader_typography_side_margins), ReaderTypographyPreset.SIDE_MARGINS, draft) { draft = it }
                TextButton(onClick = { more = !more }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (more) R.string.reader_typography_less else R.string.reader_typography_more))
                }
                if (more) {
                    TypographyPresetRow(stringResource(R.string.reader_typography_paragraph_spacing), ReaderTypographyPreset.PARAGRAPH_SPACING, draft) { draft = it }
                    TypographyPresetRow(stringResource(R.string.reader_typography_letter_spacing), ReaderTypographyPreset.LETTER_SPACING, draft) { draft = it }
                    TypographyPresetRow(stringResource(R.string.reader_typography_vertical_padding), ReaderTypographyPreset.VERTICAL_PADDING, draft) { draft = it }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_typography_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(draft, draftTheme) }, modifier = Modifier.heightIn(min = 48.dp)) {
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
                    ReaderFonts.all.forEachIndexed { index, font ->
                        FontChoice(font, selected.id == font.id, Modifier.fillMaxWidth()) {
                            draft = draft.copy(fontFamily = font.id)
                            choosingFont = false
                        }
                        if (index < ReaderFonts.all.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

@Composable internal fun TypographyPresetRow(
    title: String,
    preset: ReaderTypographyPreset,
    preferences: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit
) {
    val selected = preset.selected(preferences)
    val labels = if (preset == ReaderTypographyPreset.FONT_SIZE) listOf(
        R.string.reader_size_small, R.string.reader_size_medium, R.string.reader_size_large, R.string.reader_size_extra_large
    ) else listOf(R.string.reader_preset_small, R.string.reader_preset_medium, R.string.reader_preset_large)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (selected == null) Text(stringResource(R.string.reader_typography_custom),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().semantics { contentDescription = title },
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Surface(
                    selected = active,
                    onClick = { onChange(preset.apply(preferences, index)) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.weight(1f).fillMaxHeight().semantics { role = Role.RadioButton }
                ) {
                    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(label), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
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
        shape = readerSheetShape,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
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
                        contentDescription = stringResource(R.string.reader_contents_search),
                        modifier = Modifier.size(21.dp)
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        toolbarClose,
                        contentDescription = stringResource(R.string.reader_close),
                        modifier = Modifier.size(20.dp)
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
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (current) FontWeight.Medium else FontWeight.Normal
                                ),
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
        shape = readerSheetShape,
        containerColor = MaterialTheme.colorScheme.surface
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
            OptionSwitch(
                title = stringResource(R.string.reader_options_volume_keys),
                detail = stringResource(R.string.reader_options_volume_keys_detail),
                checked = options.volumeKeysNarration,
                divider = false
            ) { onChange(ReaderOption.VOLUME_KEYS_NARRATION, it) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
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
            }
            Spacer(Modifier.height(8.dp))
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
    divider: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
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
        if (divider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

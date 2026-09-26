package com.ravango.feature.editor.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgChipRow
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.toFontFamily
import com.ravango.core.model.ContentDirection
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.PrompterFont
import com.ravango.core.model.PrompterTextAlign
import com.ravango.core.model.TextStyleSpec
import com.ravango.engine.editor.ops.EditOps
import com.ravango.engine.editor.ops.withTransform
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorActions
import com.ravango.feature.editor.R
import com.ravango.feature.editor.Selection
import com.ravango.feature.editor.ui.PanelSection
import com.ravango.feature.editor.ui.SwatchColors
import com.ravango.feature.editor.ui.SwitchRow
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.percent
import com.ravango.feature.editor.ui.toArgbLong
import com.ravango.feature.editor.ui.toComposeColor
import kotlin.math.roundToInt

/** Brand names of fonts (shown in their own typeface). Unknown future fonts fall back to their enum name. */
fun PrompterFont.displayName(): String = when (name) {
    "RAVAGH" -> "Ravagh"
    "VAZIRMATN" -> "Vazirmatn"
    "SAHEL" -> "Sahel"
    "SAMIM" -> "Samim"
    "SYSTEM_SANS" -> "Sans"
    "SYSTEM_SERIF" -> "Serif"
    "SYSTEM_MONO" -> "Mono"
    else -> name.lowercase().replaceFirstChar { it.uppercase() }
}

fun OverlayAnimation.label(): Int = when (this) {
    OverlayAnimation.NONE -> R.string.editor_anim_none
    OverlayAnimation.FADE -> R.string.editor_anim_fade
    OverlayAnimation.POP -> R.string.editor_anim_pop
    OverlayAnimation.SLIDE_UP -> R.string.editor_anim_slide_up
    OverlayAnimation.SLIDE_DOWN -> R.string.editor_anim_slide_down
    OverlayAnimation.TYPEWRITER -> R.string.editor_anim_typewriter
    OverlayAnimation.BOUNCE -> R.string.editor_anim_bounce
    OverlayAnimation.ZOOM -> R.string.editor_anim_zoom
}

@Composable
internal fun FontPicker(selected: PrompterFont, onSelect: (PrompterFont) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(PrompterFont.entries.toList(), key = { it.name }) { f ->
            val active = f == selected
            Box(
                Modifier
                    .pressable { onSelect(f) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    f.displayName(),
                    style = TextStyle(fontFamily = f.toFontFamily(), fontSize = 15.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
                    color = if (active) accent() else Color.White,
                )
            }
        }
    }
}

@Composable
internal fun AnimationPickers(item: OverlayItem, vm: EditorActions) {
    PanelSection(stringResource(R.string.editor_anim_in)) {
        RgChipRow(OverlayAnimation.entries.toList(), item.animationIn, { vm.setOverlayAnimations(item.id, it, item.animationOut) }, { stringResource(it.label()) }, glass = true, contentPadding = PaddingValues(0.dp))
    }
    PanelSection(stringResource(R.string.editor_anim_out)) {
        RgChipRow(OverlayAnimation.entries.filter { it != OverlayAnimation.TYPEWRITER }, item.animationOut, { vm.setOverlayAnimations(item.id, item.animationIn, it) }, { stringResource(it.label()) }, glass = true, contentPadding = PaddingValues(0.dp))
    }
}

@Composable
internal fun ItemActions(item: OverlayItem, vm: EditorActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(
            com.ravango.feature.editor.ui.timeRange(item.startUs, item.endUs),
            style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f),
        )
        RgIconButton(Icons.Rounded.ContentCopy, stringResource(R.string.editor_duplicate), vm::duplicateSelection, size = 36.dp, iconSize = 18.dp, glass = true)
        RgIconButton(Icons.Rounded.Delete, stringResource(R.string.editor_delete), vm::deleteSelection, size = 36.dp, iconSize = 18.dp, glass = true)
    }
    ValueSlider(stringResource(R.string.editor_opacity), item.transform.opacity, { v -> vm.updateOverlay(item.id, "opacity") { it.withTransform(it.transform.copy(opacity = v)) } }, 0f..1f, percent(item.transform.opacity), onFinished = vm::endGesture)
    ValueSlider(stringResource(R.string.editor_size), item.transform.scale, { v -> vm.updateOverlay(item.id, "scale") { it.withTransform(it.transform.copy(scale = v)) } }, 0.2f..4f, percent(item.transform.scale), onFinished = vm::endGesture)
}

@Composable
fun TextPanel(state: EditorUiState, vm: EditorActions) {
    val selected = (state.selection as? Selection.Overlay)?.let { EditOps.findOverlay(state.document, it.id) } as? OverlayItem.Text
    if (selected == null) {
        var draft by rememberSaveable { mutableStateOf("") }
        var style by remember { mutableStateOf(TextStyleSpec()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            RgTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = stringResource(R.string.editor_text_placeholder), singleLine = false, maxLines = 3)
            Spacer(Modifier.width(Spacing.sm))
            RgIconButton(Icons.Rounded.Add, stringResource(R.string.editor_add_text), {
                vm.addText(draft, style)
                draft = ""
            }, selected = true, enabled = draft.isNotBlank())
        }
        TextStyleEditor(style) { s, _ -> style = s }
        return
    }
    RgTextField(
        selected.text,
        { t -> vm.updateOverlay(selected.id, "text") { (it as OverlayItem.Text).copy(text = t) } },
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        singleLine = false, maxLines = 4,
    )
    ItemActions(selected, vm)
    TextStyleEditor(selected.style, vm::endGesture) { s, key -> vm.updateOverlay(selected.id, key) { (it as OverlayItem.Text).copy(style = s) } }
    AnimationPickers(selected, vm)
}

/** Font, size, color, outline, background, shadow, alignment and direction. [onChange] gets a gesture key for sliders. */
@Composable
internal fun TextStyleEditor(style: TextStyleSpec, onGestureEnd: () -> Unit = {}, onChange: (TextStyleSpec, String?) -> Unit) {
    PanelSection(stringResource(R.string.editor_font)) {}
    FontPicker(style.font) { onChange(style.copy(font = it), null) }
    ValueSlider(stringResource(R.string.editor_text_size), style.sizeSp, { onChange(style.copy(sizeSp = it), "size") }, 12f..96f, localized("${style.sizeSp.roundToInt()}"), onFinished = onGestureEnd)
    SwitchRow(stringResource(R.string.editor_bold), style.weight >= 600, { onChange(style.copy(weight = if (it) 700 else 400), null) })
    PanelSection(stringResource(R.string.editor_color)) {
        ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, style.color.toComposeColor(), { onChange(style.copy(color = it.toArgbLong()), null) })
    }
    SwitchRow(stringResource(R.string.editor_outline), style.outlineColor != null && style.outlineWidth > 0f, { on ->
        onChange(if (on) style.copy(outlineColor = style.outlineColor ?: 0xFF000000, outlineWidth = 0.5f) else style.copy(outlineWidth = 0f), null)
    })
    if (style.outlineColor != null && style.outlineWidth > 0f) {
        ValueSlider(stringResource(R.string.editor_outline_width), style.outlineWidth, { onChange(style.copy(outlineWidth = it), "outline") }, 0.1f..1f, percent(style.outlineWidth), onFinished = onGestureEnd)
        PanelSection(null) {
            ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, (style.outlineColor ?: 0xFF000000).toComposeColor(), { onChange(style.copy(outlineColor = it.toArgbLong()), null) })
        }
    }
    PanelSection(stringResource(R.string.editor_text_background)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RgChip(stringResource(R.string.editor_none), style.backgroundColor == null, { onChange(style.copy(backgroundColor = null), null) }, glass = true)
            Spacer(Modifier.width(Spacing.sm))
            ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, style.backgroundColor?.toComposeColor(), { onChange(style.copy(backgroundColor = (it.toArgbLong() and 0x00FFFFFF) or 0xCC000000), null) }, swatchSize = 28.dp)
        }
    }
    SwitchRow(stringResource(R.string.editor_shadow), style.shadow, { onChange(style.copy(shadow = it), null) })
    PanelSection(stringResource(R.string.editor_alignment)) {
        RgSegmentedControl(PrompterTextAlign.entries.toList(), style.align, { onChange(style.copy(align = it), null) }, {
            stringResource(
                when (it) {
                    PrompterTextAlign.START -> R.string.editor_align_start
                    PrompterTextAlign.CENTER -> R.string.editor_align_center
                    PrompterTextAlign.END -> R.string.editor_align_end
                    PrompterTextAlign.JUSTIFY -> R.string.editor_align_justify
                },
            )
        }, glass = true)
    }
    PanelSection(stringResource(R.string.editor_direction)) {
        RgSegmentedControl(ContentDirection.entries.toList(), style.direction, { onChange(style.copy(direction = it), null) }, {
            stringResource(
                when (it) {
                    ContentDirection.AUTO -> R.string.editor_dir_auto
                    ContentDirection.RTL -> R.string.editor_dir_rtl
                    ContentDirection.LTR -> R.string.editor_dir_ltr
                },
            )
        }, glass = true)
    }
    Spacer(Modifier.height(Spacing.sm))
}

private val Emojis = listOf(
    "😀", "😂", "🥹", "😍", "🤩", "😎", "🤔", "😮", "😢", "😡", "👍", "👏", "🙌", "🙏", "💪", "👀",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🔥", "✨", "⭐", "🌟", "💯", "✅", "❌", "⚡", "🎉", "🎬",
    "🎤", "🎧", "📌", "📣", "💡", "🚀", "🌸", "🌈", "☕", "🍀", "🎁", "📱", "💬", "👉", "👈", "⬆️",
)

@Composable
fun StickersPanel(state: EditorUiState, vm: EditorActions) {
    val selected = (state.selection as? Selection.Overlay)?.let { EditOps.findOverlay(state.document, it.id) } as? OverlayItem.Sticker
    if (selected != null) {
        ItemActions(selected, vm)
        AnimationPickers(selected, vm)
        PanelSection(stringResource(R.string.editor_add_another)) {}
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(48.dp),
        modifier = Modifier.fillMaxWidth().height(200.dp).padding(horizontal = Spacing.md),
    ) {
        items(Emojis) { e ->
            Box(Modifier.height(48.dp).pressable { vm.addSticker(e) }, contentAlignment = Alignment.Center) {
                Text(e, fontSize = 28.sp)
            }
        }
    }
    if (selected == null) {
        Text(stringResource(R.string.editor_sticker_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = Spacing.lg))
    }
}

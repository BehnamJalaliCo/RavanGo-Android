package com.ravango.feature.editor.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CanvasBackground
import com.ravango.core.model.ColorAdjustments
import com.ravango.core.model.ContentFit
import com.ravango.core.model.FilterPreset
import com.ravango.core.model.ProFeature
import com.ravango.core.model.VideoClip
import com.ravango.engine.editor.effects.GradeParams
import com.ravango.feature.editor.EditorUiState
import com.ravango.feature.editor.EditorActions
import com.ravango.feature.editor.R
import com.ravango.feature.editor.ui.PanelSection
import com.ravango.feature.editor.ui.SwatchColors
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.percent
import com.ravango.feature.editor.ui.toArgbLong
import com.ravango.feature.editor.ui.toComposeColor
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------- canvas

private val AspectPresets = listOf(
    AspectRatioSpec.Portrait9x16, AspectRatioSpec.Landscape16x9, AspectRatioSpec.Square1x1, AspectRatioSpec.Portrait4x5,
    AspectRatioSpec.Portrait3x4, AspectRatioSpec.Landscape4x3, AspectRatioSpec.Cinema21x9,
)

private val GradientPresets = listOf(
    0xFF8B7CF6 to 0xFFFF93AF, 0xFF7DB8FF to 0xFF6FD9C0, 0xFFFFAE7A to 0xFFFF4D6D, 0xFF15131F to 0xFF45405F, 0xFFFFE7A3 to 0xFFFFB3C7,
)

private enum class BgMode { SOLID, GRADIENT, BLUR }

@Composable
fun CanvasPanel(state: EditorUiState, vm: EditorActions) {
    val canvas = state.document.canvas
    PanelSection(stringResource(R.string.editor_canvas_ratio)) {}
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(AspectPresets, key = { it.label }) { a ->
            AspectChip(a, selected = canvas.aspectRatio == a) { vm.setAspect(a) }
        }
    }
    val mode = when (canvas.background) {
        is CanvasBackground.Solid -> BgMode.SOLID
        is CanvasBackground.Gradient -> BgMode.GRADIENT
        is CanvasBackground.Blur -> BgMode.BLUR
    }
    PanelSection(stringResource(R.string.editor_canvas_background)) {
        RgSegmentedControl(
            BgMode.entries.toList(), mode,
            {
                when (it) {
                    BgMode.SOLID -> vm.setBackground(CanvasBackground.Solid(0xFF000000))
                    BgMode.GRADIENT -> vm.setBackground(CanvasBackground.Gradient(GradientPresets[0].first, GradientPresets[0].second))
                    BgMode.BLUR -> vm.setBackground(CanvasBackground.Blur())
                }
            },
            {
                stringResource(
                    when (it) {
                        BgMode.SOLID -> R.string.editor_bg_color
                        BgMode.GRADIENT -> R.string.editor_bg_gradient
                        BgMode.BLUR -> R.string.editor_bg_blur
                    },
                )
            },
            glass = true,
        )
        Spacer(Modifier.height(Spacing.sm))
        when (val bg = canvas.background) {
            is CanvasBackground.Solid -> ColorSwatchRow(SwatchColors.map { it.toComposeColor() }, bg.color.toComposeColor(), { vm.setBackground(CanvasBackground.Solid(it.toArgbLong())) })
            is CanvasBackground.Gradient -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    GradientPresets.forEach { (a, b) ->
                        val selected = bg.start == a && bg.end == b
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(a.toComposeColor(), b.toComposeColor())))
                                .border(if (selected) 2.dp else 0.dp, Color.White, CircleShape)
                                .pressable { vm.setBackground(CanvasBackground.Gradient(a, b, bg.angleDegrees)) },
                        )
                    }
                }
            }
            is CanvasBackground.Blur -> Unit
        }
    }
    (canvas.background as? CanvasBackground.Gradient)?.let { g ->
        ValueSlider(stringResource(R.string.editor_gradient_angle), g.angleDegrees, { vm.setBackground(g.copy(angleDegrees = it), true) }, 0f..360f, localized("${g.angleDegrees.roundToInt()}") + "°", onFinished = vm::endGesture)
    }
    (canvas.background as? CanvasBackground.Blur)?.let { b ->
        ValueSlider(stringResource(R.string.editor_blur_amount), b.radius, { vm.setBackground(CanvasBackground.Blur(it), true) }, 0.05f..1f, percent(b.radius), onFinished = vm::endGesture)
    }
    PanelSection(stringResource(R.string.editor_canvas_fit)) {
        RgSegmentedControl(ContentFit.entries.toList(), canvas.fit, vm::setFit, { stringResource(if (it == ContentFit.FIT) R.string.editor_fit else R.string.editor_fill) }, glass = true)
    }
}

@Composable
private fun AspectChip(a: AspectRatioSpec, selected: Boolean, onClick: () -> Unit) {
    val box = 30.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(onClick = onClick).padding(4.dp)) {
        Box(Modifier.size(box), contentAlignment = Alignment.Center) {
            val r = a.ratio
            val w = if (r >= 1f) box else box * r
            val h = if (r >= 1f) box / r else box
            Box(Modifier.size(w, h).border(2.dp, if (selected) accent() else Color.White.copy(alpha = 0.6f), RoundedCornerShape(4.dp)))
        }
        Text(localized(a.label), style = MaterialTheme.typography.labelSmall, color = if (selected) accent() else Color.White)
    }
}

// ---------------------------------------------------------------------------------------------- filters

fun FilterPreset.label(): Int = when (this) {
    FilterPreset.NONE -> R.string.editor_filter_none
    FilterPreset.VIVID -> R.string.editor_filter_vivid
    FilterPreset.WARM -> R.string.editor_filter_warm
    FilterPreset.COOL -> R.string.editor_filter_cool
    FilterPreset.CINEMA -> R.string.editor_filter_cinema
    FilterPreset.TEAL_ORANGE -> R.string.editor_filter_teal_orange
    FilterPreset.FILM -> R.string.editor_filter_film
    FilterPreset.FADE -> R.string.editor_filter_fade
    FilterPreset.MONO -> R.string.editor_filter_mono
    FilterPreset.NOIR -> R.string.editor_filter_noir
    FilterPreset.PASTEL -> R.string.editor_filter_pastel
    FilterPreset.SUNSET -> R.string.editor_filter_sunset
}

/**
 * Approximates a grade with a 4×5 color matrix for the chip thumbnails (exposure, brightness, contrast, fade, saturation,
 * temperature, tint and split toning). The real render uses the GL shader.
 */
internal fun previewMatrix(p: GradeParams): ColorMatrix {
    val sat = (1f + p.saturation + p.vibrance * 0.5f).coerceAtLeast(0f)
    val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
    val e = 2f.pow(p.exposure * 2f) * (1f + p.contrast) * (1f - p.fade * 0.22f)
    val offset = (0.5f * (1f - (1f + p.contrast)) + p.brightness * 0.25f + p.fade * 0.14f) * 255f
    val tr = (p.temperature * 0.10f + p.tint * 0.03f + (p.shadowTint.first + p.highlightTint.first) * 0.5f) * 255f
    val tg = (-p.tint * 0.08f + (p.shadowTint.second + p.highlightTint.second) * 0.5f) * 255f
    val tb = (-p.temperature * 0.10f + p.tint * 0.03f + (p.shadowTint.third + p.highlightTint.third) * 0.5f) * 255f
    fun row(r: Float, g: Float, b: Float, add: Float) = floatArrayOf(r * e, g * e, b * e, 0f, offset + add)
    return ColorMatrix(
        row((1 - sat) * lr + sat, (1 - sat) * lg, (1 - sat) * lb, tr) +
            row((1 - sat) * lr, (1 - sat) * lg + sat, (1 - sat) * lb, tg) +
            row((1 - sat) * lr, (1 - sat) * lg, (1 - sat) * lb + sat, tb) +
            floatArrayOf(0f, 0f, 0f, 1f, 0f),
    )
}

@Composable
fun FiltersPanel(state: EditorUiState, vm: EditorActions) {
    val clip = state.selectedClip() ?: return SelectClipHint(vm)
    val density = LocalDensity.current
    val heightPx = with(density) { 64.dp.roundToPx() }
    val frameUs = (clip.trimStartUs + clip.trimEndUs) / 2
    val thumb by produceState<Bitmap?>(vm.thumbnails.cached(clip.source.uri, frameUs, heightPx), clip.source.uri, frameUs) {
        value = vm.thumbnails.frame(clip.source.uri, clip.source.kind, frameUs, heightPx)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(FilterPreset.entries.toList(), key = { it.name }) { f ->
            val matrix = remember(f, clip.adjustments) { previewMatrix(GradeParams.resolve(f, 1f, clip.adjustments)) }
            val selected = clip.filter == f
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable { vm.setFilter(f) }) {
                Box(
                    Modifier.size(58.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2640))
                        .border(if (selected) 2.dp else 0.dp, accent(), RoundedCornerShape(12.dp)),
                ) {
                    thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(matrix), modifier = Modifier.fillMaxSize()) }
                }
                Text(stringResource(f.label()), style = MaterialTheme.typography.labelSmall, color = if (selected) accent() else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (clip.filter != FilterPreset.NONE) {
        ValueSlider(stringResource(R.string.editor_intensity), clip.filterIntensity, { vm.setFilterIntensity(it, true) }, 0f..1f, percent(clip.filterIntensity), onFinished = vm::endGesture)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.End) {
        RgTextButton(stringResource(R.string.editor_apply_all), onClick = vm::applyFilterToAll)
    }
}

// ---------------------------------------------------------------------------------------------- adjust

private enum class AdjustParam(val label: Int, val bipolar: Boolean, val advanced: Boolean) {
    EXPOSURE(R.string.editor_adj_exposure, true, false),
    BRIGHTNESS(R.string.editor_adj_brightness, true, false),
    CONTRAST(R.string.editor_adj_contrast, true, false),
    SATURATION(R.string.editor_adj_saturation, true, false),
    TEMPERATURE(R.string.editor_adj_temperature, true, false),
    TINT(R.string.editor_adj_tint, true, false),
    HIGHLIGHTS(R.string.editor_adj_highlights, true, true),
    SHADOWS(R.string.editor_adj_shadows, true, true),
    VIBRANCE(R.string.editor_adj_vibrance, true, true),
    SHARPEN(R.string.editor_adj_sharpen, false, true),
    BLUR(R.string.editor_adj_blur, false, true),
    VIGNETTE(R.string.editor_adj_vignette, false, true),
    GRAIN(R.string.editor_adj_grain, false, true),
    ;

    fun get(a: ColorAdjustments): Float = when (this) {
        EXPOSURE -> a.exposure; BRIGHTNESS -> a.brightness; CONTRAST -> a.contrast; SATURATION -> a.saturation
        TEMPERATURE -> a.temperature; TINT -> a.tint; HIGHLIGHTS -> a.highlights; SHADOWS -> a.shadows
        VIBRANCE -> a.vibrance; SHARPEN -> a.sharpen; BLUR -> a.blur; VIGNETTE -> a.vignette; GRAIN -> a.grain
    }

    fun set(a: ColorAdjustments, v: Float): ColorAdjustments = when (this) {
        EXPOSURE -> a.copy(exposure = v); BRIGHTNESS -> a.copy(brightness = v); CONTRAST -> a.copy(contrast = v)
        SATURATION -> a.copy(saturation = v); TEMPERATURE -> a.copy(temperature = v); TINT -> a.copy(tint = v)
        HIGHLIGHTS -> a.copy(highlights = v); SHADOWS -> a.copy(shadows = v); VIBRANCE -> a.copy(vibrance = v)
        SHARPEN -> a.copy(sharpen = v); BLUR -> a.copy(blur = v); VIGNETTE -> a.copy(vignette = v); GRAIN -> a.copy(grain = v)
    }
}

@Composable
fun AdjustPanel(state: EditorUiState, vm: EditorActions) {
    val clip: VideoClip = state.selectedClip() ?: return SelectClipHint(vm)
    var param by rememberSaveable { mutableStateOf(AdjustParam.EXPOSURE) }
    val pro = state.has(ProFeature.EDITOR_ADVANCED_COLOR)
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(AdjustParam.entries.toList(), key = { it.name }) { p ->
            val changed = p.get(clip.adjustments) != 0f
            RgChip(
                text = stringResource(p.label),
                selected = param == p,
                onClick = { param = p },
                glass = true,
                trailing = when {
                    p.advanced && !pro -> ({ ProBadge() })
                    changed -> ({ Box(Modifier.size(6.dp).background(accent(), CircleShape)) })
                    else -> null
                },
            )
        }
    }
    val value = param.get(clip.adjustments)
    ValueSlider(
        stringResource(param.label), value,
        { vm.setAdjustments(param.set(clip.adjustments, it), param.name, param.advanced) },
        if (param.bipolar) -1f..1f else 0f..1f,
        localized("${(value * 100).roundToInt()}"),
        bipolar = param.bipolar,
        pro = param.advanced && !pro,
        onFinished = vm::endGesture,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md), horizontalArrangement = Arrangement.SpaceBetween) {
        RgTextButton(stringResource(R.string.editor_reset), onClick = vm::resetAdjustments, enabled = !clip.adjustments.isNeutral)
        RgTextButton(stringResource(R.string.editor_apply_all), onClick = vm::applyFilterToAll)
    }
}

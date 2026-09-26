package com.ravango.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pastel slider with a pill track, gradient fill, haptic detents and RTL support.
 *
 * @param bipolar fill grows from the center (for "less ↔ more" adjustments).
 * @param steps number of haptic detents (0 = continuous with a tick every 10%).
 */
@Composable
fun RgSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    bipolar: Boolean = false,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    trackColor: Color = RgTheme.colors.surfaceMuted,
    contentDescription: String? = null,
) {
    val colors = RgTheme.colors
    val haptics = rememberHaptics()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentValue by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    fun fractionOf(v: Float) = ((v - valueRange.start) / span).coerceIn(0f, 1f)
    val detents = if (steps > 0) steps + 1 else 10
    var lastDetent by remember { mutableFloatStateOf(-1f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                setProgress { target -> onChange(target.coerceIn(valueRange)); true }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val thumbRadiusPx = with(LocalDensity.current) { 12.dp.toPx() }
        fun valueAt(x: Float): Float {
            var f = ((x - thumbRadiusPx) / (widthPx - 2 * thumbRadiusPx)).coerceIn(0f, 1f)
            if (rtl) f = 1f - f
            if (steps > 0) f = (f * (steps + 1)).roundToInt() / (steps + 1).toFloat()
            return valueRange.start + f * span
        }
        fun emit(x: Float) {
            val v = valueAt(x)
            val detent = (fractionOf(v) * detents).roundToInt().toFloat()
            if (detent != lastDetent) {
                if (lastDetent >= 0) haptics.perform(HapticEvent.TICK)
                lastDetent = detent
            }
            onChange(v)
        }
        val gesture = if (!enabled) Modifier else Modifier
            .pointerInput(widthPx, rtl) {
                detectTapGestures { emit(it.x); onValueChangeFinished?.invoke() }
            }
            .pointerInput(widthPx, rtl) {
                detectDragGestures(
                    onDragStart = { emit(it.x) },
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                ) { change, _ -> change.consume(); emit(change.position.x) }
            }
        Canvas(Modifier.fillMaxWidth().fillMaxHeight().then(gesture)) {
            val trackH = 8.dp.toPx()
            val cy = size.height / 2
            val left = thumbRadiusPx
            val right = size.width - thumbRadiusPx
            val usable = right - left
            drawRoundRect(trackColor, Offset(left, cy - trackH / 2), Size(usable, trackH), CornerRadius(trackH / 2))
            var f = fractionOf(currentValue)
            if (rtl) f = 1f - f
            val thumbX = left + usable * f
            val (from, to) = if (bipolar) {
                val center = left + usable / 2
                minOf(center, thumbX) to maxOf(center, thumbX)
            } else if (rtl) {
                thumbX to right
            } else {
                left to thumbX
            }
            if (to - from > 0.5f) {
                drawRoundRect(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(com.ravango.core.designsystem.theme.Palette.Lavender400, com.ravango.core.designsystem.theme.Palette.Rose400),
                        startX = from,
                        endX = to,
                    ),
                    topLeft = Offset(from, cy - trackH / 2),
                    size = Size(to - from, trackH),
                    cornerRadius = CornerRadius(trackH / 2),
                    alpha = if (enabled) 1f else 0.4f,
                )
            }
            if (bipolar) {
                drawCircle(colors.outlineStrong, radius = 2.dp.toPx(), center = Offset(left + usable / 2, cy))
            }
            drawCircle(Color.Black.copy(alpha = 0.12f), radius = thumbRadiusPx + 1.5.dp.toPx(), center = Offset(thumbX, cy + 1.dp.toPx()))
            drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(thumbX, cy))
            drawCircle(colors.accent, radius = thumbRadiusPx * 0.38f, center = Offset(thumbX, cy))
        }
    }
}

/** Slider row with label, formatted value and optional reset-on-double-tap. */
@Composable
fun RgLabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    valueText: String = value.roundToInt().toString(),
    bipolar: Boolean = false,
    steps: Int = 0,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = RgTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            trailing?.invoke()
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = RgTheme.colors.accent)
        }
        RgSlider(value, onValueChange, valueRange = valueRange, bipolar = bipolar, steps = steps, enabled = enabled, onValueChangeFinished = onValueChangeFinished, contentDescription = label)
    }
}

/** Animated pastel switch. */
@Composable
fun RgSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = RgTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else colors.outlineStrong, Motion.quick(), label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, Motion.bouncy(), label = "thumb")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(track.copy(alpha = if (enabled) 1f else 0.4f))
            .pressable(enabled = enabled, haptic = if (checked) HapticEvent.TOGGLE_OFF else HapticEvent.TOGGLE_ON) { onCheckedChange(!checked) }
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(((if (rtl) -offset else offset).toPx()).roundToInt(), 0) }
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** Segmented control with a sliding pill indicator. */
@Composable
fun <T> RgSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
) {
    val colors = RgTheme.colors
    val haptics = rememberHaptics()
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (glass) Color.Black.copy(alpha = 0.35f) else colors.surfaceMuted)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(if (isSelected) (if (glass) Color.White else colors.surface) else Color.Transparent, Motion.quick(), label = "seg")
            val fg = when {
                isSelected && glass -> Color.Black
                isSelected -> colors.textPrimary
                glass -> Color.White.copy(alpha = 0.85f)
                else -> colors.textSecondary
            }
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(bg)
                    .pressable(haptic = null) { if (!isSelected) { haptics.perform(HapticEvent.SNAP); onSelect(option) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

/** Selectable chip. */
@Composable
fun RgChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glass: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RgTheme.colors
    val bg by animateColorAsState(
        when {
            selected -> colors.accent
            glass -> Color.Black.copy(alpha = 0.35f)
            else -> colors.surface
        },
        Motion.quick(), label = "chip",
    )
    val fg = when {
        selected -> colors.onAccent
        glass -> Color.White
        else -> colors.textPrimary
    }
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .then(if (!selected && !glass) Modifier.border(1.dp, colors.outline, RoundedCornerShape(Radius.pill)) else Modifier)
            .pressable(haptic = HapticEvent.SNAP, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        trailing?.let { Spacer(Modifier.width(6.dp)); it() }
    }
}

@Composable
fun <T> RgChipRow(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.gutter),
    glass: Boolean = false,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { item -> RgChip(label(item), item == selected, { onSelect(item) }, glass = glass) }
    }
}

/** Color swatches for text, makeup and background colors. */
@Composable
fun ColorSwatchRow(
    colors: List<Color>,
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
    swatchSize: androidx.compose.ui.unit.Dp = 34.dp,
) {
    val theme = RgTheme.colors
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSel = selected != null && abs(c.value.toLong() - selected.value.toLong()) == 0L
            Box(
                Modifier
                    .size(swatchSize)
                    .clip(CircleShape)
                    .background(c)
                    .border(BorderStroke(if (isSel) 3.dp else 1.dp, if (isSel) theme.accent else theme.outline), CircleShape)
                    .pressable(haptic = HapticEvent.SNAP) { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSel) Icon(Icons.Rounded.Check, null, tint = if (c.luminance() > 0.6f) Color.Black else Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Text field in the RavanGo style. */
@Composable
fun RgTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    val colors = RgTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = colors.textTertiary) } },
        leadingIcon = leadingIcon?.let { { Icon(it, null, tint = colors.textSecondary) } },
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        isError = isError,
        enabled = enabled,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(Radius.md),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.outline,
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            cursorColor = colors.accent,
        ),
    )
}

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.ravango.core.designsystem.theme.ButtonText
import com.ravango.core.designsystem.theme.Dimens
import com.ravango.core.designsystem.theme.TabularNumbers
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
    trackColor: Color = if (RgTheme.colors.isDark) RgTheme.colors.surfaceRaised else RgTheme.colors.outline,
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
            .height(Dimens.controlMedium)
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
            val trackH = 6.dp.toPx()
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
            // Soft, diffuse drop shadow (radial falloff) instead of a hard ring.
            val shadowCenter = Offset(thumbX, cy + 1.5.dp.toPx())
            val shadowR = thumbRadiusPx + 4.dp.toPx()
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to colors.shadowTint.copy(alpha = if (colors.isDark) 0.45f else 0.22f),
                    (thumbRadiusPx / shadowR) to colors.shadowTint.copy(alpha = if (colors.isDark) 0.30f else 0.14f),
                    1f to Color.Transparent,
                    center = shadowCenter,
                    radius = shadowR,
                ),
                radius = shadowR,
                center = shadowCenter,
            )
            drawCircle(Color.White, radius = thumbRadiusPx, center = Offset(thumbX, cy))
            drawCircle(colors.accent.copy(alpha = if (enabled) 1f else 0.4f), radius = thumbRadiusPx * 0.36f, center = Offset(thumbX, cy))
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
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) RgTheme.colors.textPrimary else RgTheme.colors.textTertiary, modifier = Modifier.weight(1f))
            trailing?.invoke()
            Text(valueText, style = MaterialTheme.typography.labelLarge.merge(TabularNumbers), color = if (enabled) RgTheme.colors.accent else RgTheme.colors.textTertiary)
        }
        RgSlider(value, onValueChange, valueRange = valueRange, bipolar = bipolar, steps = steps, enabled = enabled, onValueChangeFinished = onValueChangeFinished, contentDescription = label)
    }
}

/**
 * Animated pastel switch (50x30, 24dp thumb). The thumb travels toward the *end* edge when checked, so in RTL it moves
 * left. [offset] is already direction-aware, so no manual mirroring is applied (doing so pushed the thumb out of the
 * track in RTL).
 */
@Composable
fun RgSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = RgTheme.colors
    val track by animateColorAsState(if (checked) colors.accent else (if (colors.isDark) colors.surfaceRaised else colors.outlineStrong), Motion.quick(), label = "track")
    val travel = 20.dp
    val offset by animateDpAsState(if (checked) travel else 0.dp, Motion.bouncy(), label = "thumb")
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        modifier
            .size(width = 50.dp, height = 30.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(track)
            .pressable(
                shape = shape,
                enabled = enabled,
                role = Role.Switch,
                haptic = if (checked) HapticEvent.TOGGLE_OFF else HapticEvent.TOGGLE_ON,
            ) { onCheckedChange(!checked) }
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .size(24.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.25f))
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
    Layout(
        modifier = modifier
            .height(Dimens.controlMedium)
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (glass) Color.Black.copy(alpha = 0.35f) else colors.surfaceMuted)
            .then(if (!glass && colors.isDark) Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(Radius.pill)) else Modifier)
            .padding(4.dp),
        content = {
            options.forEach { option ->
                val isSelected = option == selected
                val bg by animateColorAsState(if (isSelected) (if (glass) Color.White else colors.surfaceRaised) else Color.Transparent, Motion.quick(), label = "seg")
                val fg = when {
                    isSelected && glass -> Color.Black
                    isSelected -> colors.textPrimary
                    glass -> Color.White.copy(alpha = 0.85f)
                    else -> colors.textSecondary
                }
                val segShape = RoundedCornerShape(Radius.pill)
                Box(
                    Modifier
                        .then(if (isSelected && !glass) Modifier.softShadow(if (colors.isDark) 0.dp else 2.dp, segShape) else Modifier)
                        .clip(segShape)
                        .background(bg)
                        .pressable(shape = segShape, haptic = null, role = Role.Tab) { if (!isSelected) { haptics.perform(HapticEvent.SNAP); onSelect(option) } }
                        .semantics { semanticsSelected = isSelected }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label(option), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    ) { measurables, constraints ->
        // Wraps its content by default; when given a fixed width (fillMaxWidth / width) the segments share it equally
        // (iOS-style), falling back to content-proportional widths when a label is longer than an equal share.
        val gap = 2.dp.roundToPx()
        val n = measurables.size
        val h = constraints.maxHeight
        val natural = measurables.map { it.maxIntrinsicWidth(h) }
        val gaps = gap * (n - 1).coerceAtLeast(0)
        val naturalTotal = natural.sum() + gaps
        val target = when {
            constraints.hasFixedWidth -> constraints.maxWidth
            naturalTotal < constraints.minWidth -> constraints.minWidth
            else -> naturalTotal.coerceAtMost(constraints.maxWidth)
        }
        val avail = (target - gaps).coerceAtLeast(0)
        val widths: List<Int> = if (n == 0) {
            emptyList()
        } else if (target == naturalTotal) {
            natural
        } else if (natural.all { it <= avail / n }) {
            List(n) { i -> avail / n + if (i < avail % n) 1 else 0 }
        } else if (avail >= natural.sum()) {
            val extra = avail - natural.sum()
            natural.mapIndexed { i, w -> w + extra / n + if (i < extra % n) 1 else 0 }
        } else {
            // Not enough room: shrink proportionally (labels ellipsize).
            natural.map { (it.toLong() * avail / natural.sum().coerceAtLeast(1)).toInt() }
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), h)) }
        val width = (placeables.sumOf { it.width } + gaps).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, h) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, 0)
                x += p.width + gap
            }
        }
    }
}

/** Selectable chip (32dp: the same height as [RgButtonSize.SMALL], so chips and small buttons share rows). */
@Composable
fun RgChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    glass: Boolean = false,
    enabled: Boolean = true,
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
    val shape = RoundedCornerShape(Radius.pill)
    val stroke = when {
        selected -> Color.Transparent
        glass -> Color.White.copy(alpha = 0.16f)
        else -> colors.outline
    }
    Row(
        modifier
            .height(Dimens.controlSmall)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .pressable(shape = shape, enabled = enabled, haptic = HapticEvent.SNAP, onClick = onClick)
            .semantics { semanticsSelected = selected }
            .padding(start = if (icon != null) 10.dp else 14.dp, end = if (trailing != null) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(Dimens.iconSmall))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = ButtonText.small, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        verticalAlignment = Alignment.CenterVertically,
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
            // Selected: accent ring with a small gap (the swatch shrinks inside it); reads clearly on any color.
            val inset by animateDpAsState(if (isSel) 4.dp else 0.dp, Motion.snappy(), label = "swatch")
            Box(
                Modifier
                    .size(swatchSize)
                    .border(BorderStroke(2.dp, if (isSel) theme.accent else Color.Transparent), CircleShape)
                    .pressable(shape = CircleShape, haptic = HapticEvent.SNAP) { onSelect(c) }
                    .semantics { semanticsSelected = isSel },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(inset)
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(c)
                        .border(1.dp, if (c.luminance() > 0.85f || c.alpha < 0.3f) theme.outlineStrong else Color.Black.copy(alpha = 0.06f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSel) Icon(Icons.Rounded.Check, null, tint = if (c.luminance() > 0.6f) Color.Black else Color.White, modifier = Modifier.size(14.dp))
                }
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
        // The container is painted here (below the floating-label gap) instead of by Material: M3 paints the container
        // behind the floating label too, which showed as a box sticking out above the field on gradient backgrounds.
        modifier = modifier.fillMaxWidth().drawBehind {
            val top = if (label != null) 8.dp.toPx() else 0f
            drawRoundRect(
                color = if (enabled) colors.surface else colors.surfaceMuted,
                topLeft = Offset(0f, top),
                size = Size(size.width, size.height - top),
                cornerRadius = CornerRadius(Radius.md.toPx()),
            )
        },
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = colors.textTertiary) } },
        leadingIcon = leadingIcon?.let { { Icon(it, null, modifier = Modifier.size(Dimens.iconMedium)) } },
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
            unfocusedBorderColor = colors.outlineStrong.copy(alpha = if (colors.isDark) 1f else 0.7f),
            disabledBorderColor = colors.outline,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textSecondary,
            focusedLeadingIconColor = colors.accent,
            unfocusedLeadingIconColor = colors.textSecondary,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedSupportingTextColor = colors.textSecondary,
            unfocusedSupportingTextColor = colors.textSecondary,
            errorBorderColor = colors.danger,
            errorLabelColor = colors.danger,
            errorSupportingTextColor = colors.danger,
            errorCursorColor = colors.danger,
            cursorColor = colors.accent,
            selectionColors = TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        ),
    )
}

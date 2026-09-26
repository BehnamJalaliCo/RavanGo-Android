package com.ravango.feature.editor.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import java.util.Locale
import kotlin.math.roundToInt

/** Vertical icon + label action used in tool panels. */
@Composable
fun ToolAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    pro: Boolean = false,
    busy: Boolean = false,
) {
    val colors = RgTheme.colors
    Column(
        modifier
            .widthIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(Radius.md))
            .pressable(enabled = enabled && !busy, haptic = HapticEvent.TAP, onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The PRO tag sits on the circle's top-end corner, inside the item's own width (never clipped).
        Box(Modifier.width(64.dp).height(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                else Icon(icon, null, tint = if (selected) colors.onAccent else Color.White, modifier = Modifier.size(22.dp))
            }
            if (pro) ProBadge(Modifier.align(Alignment.TopEnd))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Horizontally scrolling row of [ToolAction]s. */
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}

/** Titled panel section. */
@Composable
fun PanelSection(title: String?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            Spacer(Modifier.height(Spacing.xs))
        }
        content()
    }
}

/** Slider with label and formatted value (digits localized). */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    enabled: Boolean = true,
    pro: Boolean = false,
    onFinished: (() -> Unit)? = null,
) {
    RgLabeledSlider(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = Spacing.lg),
        valueRange = range,
        valueText = valueText,
        bipolar = bipolar,
        enabled = enabled,
        trailing = if (pro) ({ ProBadge(); Spacer(Modifier.width(Spacing.sm)) }) else null,
        onValueChangeFinished = onFinished,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, pro: Boolean = false, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
        if (pro) {
            ProBadge()
            Spacer(Modifier.width(Spacing.sm))
        }
        RgSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Small informational card inside panels (service requirements, hints). */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = RgTheme.colors.warning, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .clip(RoundedCornerShape(Radius.md))
            .background(tint.copy(alpha = 0.14f))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

val PanelPadding = PaddingValues(horizontal = Spacing.lg)

/** Seconds with one decimal, digits localized: "1.5s" / "۱٫۵ ث". */
fun formatSeconds(us: Long, suffix: String): String {
    val v = us / 1_000_000.0
    val text = if (v >= 10) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)
    return localized(text) + suffix
}

/** Localizes digits (and the decimal separator in Persian). */
fun localized(text: String): String {
    val digits = text.localizeDigits()
    return if (Locale.getDefault().language == "fa") digits.replace('.', '٫') else digits
}

fun timecode(us: Long, tenths: Boolean = true): String = formatDuration(us, showTenths = tenths)

fun percent(v: Float): String = localized("${(v * 100).roundToInt()}") + "%"

/** Palette offered for text, backgrounds and subtitles. */
val SwatchColors: List<Long> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD166, 0xFFFF93AF, 0xFFA394FB, 0xFF7DB8FF, 0xFF6FD9C0, 0xFFFF6B6B, 0xFFFFAE7A, 0xFF2F2B44,
)

fun Long.toComposeColor(): Color = Color((this and 0xFFFFFFFFL).toInt())
fun Color.toArgbLong(): Long = (android.graphics.Color.argb((alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt()).toLong() and 0xFFFFFFFFL)


/** "start – end" time range, kept left-to-right inside RTL text. */
fun timeRange(startUs: Long, endUs: Long, separator: String = " – "): String = "\u2066" + timecode(startUs) + separator + timecode(endUs) + "\u2069"

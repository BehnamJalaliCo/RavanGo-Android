package com.ravango.core.designsystem.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.TabularNumbers

/**
 * A number whose changed digits roll (odometer style): up when the value grows, down when it shrinks. [format] turns
 * the value into the displayed digits (use `localizeDigits()` for Persian numerals); it must return digits and
 * separators only — words would be split into glyphs. Digits always read left-to-right, also in RTL layouts.
 * Uses tabular figures so the width never jitters. With reduce motion the value simply changes.
 */
@Composable
fun RgAnimatedCounter(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = RgTheme.colors.textPrimary,
    format: (Long) -> String = { it.toString() },
) {
    val text = format(value)
    val reduceMotion = RgTheme.reduceMotion
    var previous by remember { mutableLongStateOf(value) }
    val up = value >= previous
    LaunchedEffect(value) { previous = value }
    val merged = style.merge(TabularNumbers)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
            // Keyed from the end so the ones digit stays the ones digit when the number gains a digit.
            val length = text.length
            text.forEachIndexed { i, ch ->
                androidx.compose.runtime.key(length - i) {
                    AnimatedContent(
                        targetState = ch,
                        transitionSpec = { rgVerticalTicker(reduceMotion, up) },
                        label = "digit",
                    ) { c -> Text(c.toString(), style = merged, color = color, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun RgAnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = RgTheme.colors.textPrimary,
    format: (Int) -> String = { it.toString() },
) = RgAnimatedCounter(value.toLong(), modifier, style, color) { format(it.toInt()) }

/**
 * Counts from 0 up to [target] the first time it is shown (and eases to new targets afterwards). Read the returned
 * state where it is displayed. Jumps straight to the value with reduce motion.
 */
@Composable
fun rememberCountUp(target: Long, durationMs: Int = 900): State<Long> {
    val reduceMotion = RgTheme.reduceMotion
    val animatable = remember { Animatable(if (reduceMotion) target.toFloat() else 0f) }
    LaunchedEffect(target, reduceMotion) {
        if (reduceMotion) animatable.snapTo(target.toFloat()) else animatable.animateTo(target.toFloat(), tween(durationMs, easing = Motion.DecelerateEasing))
    }
    return remember { derivedStateOf { animatable.value.toLong() } }
}

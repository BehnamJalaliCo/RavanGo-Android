package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.RgSlider
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.camera.EffectsGating
import com.ravango.feature.camera.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** Pure decisions of the preview filter swipe (unit-tested). */
internal object FilterSwipeMath {
    /** Fraction of the preview width after which a released swipe commits. */
    const val COMMIT_FRACTION = 0.28f
    /** Release velocity (preview widths per second) that commits even a short swipe. */
    const val FLING_VELOCITY = 1.1f

    /**
     * The filter a swipe with signed screen [progress] (negative = finger moving left) reveals. The next filter comes
     * from the reading direction's end: swiping left in LTR, right in RTL.
     */
    fun target(list: List<LiveFilter>, current: LiveFilter, progress: Float, rtl: Boolean): LiveFilter {
        if (progress == 0f) return current
        val forward = (progress < 0f) != rtl
        return EffectsGating.neighbor(list, current, if (forward) 1 else -1)
    }

    fun shouldCommit(progress: Float, velocity: Float): Boolean =
        abs(progress) >= COMMIT_FRACTION || (abs(velocity) >= FLING_VELOCITY && sign(velocity) == sign(progress) && abs(progress) > 0.04f)
}

/**
 * Drives the Snapchat-style filter swipe: while the finger drags, the engine shows a live split between the current
 * filter and the incoming one; on release the split animates to the edge (commit) or back (cancel). Engine updates
 * are plain volatile writes, so the animation never recomposes the studio; only the name banner is state.
 */
@Stable
internal class FilterSwipeController(
    private val scope: CoroutineScope,
    private val filters: () -> List<LiveFilter>,
    private val current: () -> LiveFilter,
    private val rotation: () -> Int,
    private val preview: (FilterSwipe?) -> Unit,
    private val commit: (LiveFilter) -> Unit,
    private val onTick: () -> Unit,
) {
    var rtl: Boolean = false
    private val progress = Animatable(0f)
    private var dragProgress = 0f
    private var target: LiveFilter? = null
    private var job: Job? = null

    /** The filter whose name is fading in, and a counter that restarts the fade for repeated names. */
    var banner by mutableStateOf<LiveFilter?>(null)
        private set
    var bannerKey by mutableIntStateOf(0)
        private set

    val active: Boolean get() = target != null

    fun onDrag(deltaFraction: Float) {
        if (job?.isActive == true) {
            // Grabbing the split while it settles continues from where it is.
            job?.cancel()
            dragProgress = progress.value
        }
        val p = (dragProgress + deltaFraction).coerceIn(-1f, 1f)
        dragProgress = p
        val t = FilterSwipeMath.target(filters(), current(), p, rtl)
        if (p != 0f && t != target) { target = t; onTick() }
        target?.let { preview(FilterSwipe(it, p, rotation())) }
    }

    fun onRelease(velocityFraction: Float) {
        val t = target ?: run { reset(); return }
        val p = dragProgress
        job?.cancel()
        job = scope.launch {
            progress.snapTo(p)
            if (FilterSwipeMath.shouldCommit(p, velocityFraction)) {
                val end = if (p < 0f) -1f else 1f
                progress.animateTo(end, tween(170)) { preview(FilterSwipe(t, value, rotation())) }
                commit(t)
                showBanner(t)
            } else {
                progress.animateTo(0f, tween(160)) { preview(FilterSwipe(t, value, rotation())) }
            }
            reset()
        }
    }

    fun showBanner(filter: LiveFilter) {
        banner = filter
        bannerKey++
    }

    private fun reset() {
        target = null
        dragProgress = 0f
        preview(null)
    }
}

@Composable
internal fun rememberFilterSwipeController(
    filters: () -> List<LiveFilter>,
    current: () -> LiveFilter,
    rotation: () -> Int,
    preview: (FilterSwipe?) -> Unit,
    commit: (LiveFilter) -> Unit,
): FilterSwipeController {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val f by rememberUpdatedState(filters)
    val c by rememberUpdatedState(current)
    val r by rememberUpdatedState(rotation)
    val p by rememberUpdatedState(preview)
    val k by rememberUpdatedState(commit)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return remember(scope) {
        FilterSwipeController(scope, { f() }, { c() }, { r() }, { p(it) }, { k(it) }, { haptics.perform(HapticEvent.TICK) })
    }.also { it.rtl = rtl }
}

/** The filter name fading in at the centre of the preview after a swipe (like Snapchat). */
@Composable
internal fun FilterNameBanner(filter: LiveFilter?, key: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (key == 0 || filter == null) return@LaunchedEffect
        visible = true
        delay(1_100)
        visible = false
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible && filter != null, enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.9f), exit = fadeOut(tween(420))) {
            Text(
                filter?.label().orEmpty(),
                style = MaterialTheme.typography.headlineMedium.overPreview().copy(fontSize = 30.sp, letterSpacing = 0.5.sp),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/**
 * Small glass pill naming the active filter: tap opens Filters & background, long-press reveals the strength
 * slider (the preview's own long-press stays AE/AF lock).
 */
@Composable
internal fun FilterPill(filter: LiveFilter, intensity: Int, onClick: () -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(StudioGlass.Fill)
            .border(1.dp, StudioGlass.Stroke, RoundedCornerShape(50))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.camera_effects),
                onLongClickLabel = stringResource(R.string.camera_filter_hold_hint),
                onLongClick = { haptics.perform(HapticEvent.LONG_PRESS); onLongPress() },
                onClick = onClick,
            )
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterSwatch(filter, Modifier.size(24.dp).clip(CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(filter.label(), style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
        if (intensity < 100) {
            Spacer(Modifier.width(4.dp))
            Text("$intensity%".localizeDigits(), style = MaterialTheme.typography.labelMedium, color = StudioGlass.Muted)
        }
    }
}

/** Floating strength slider for the active filter; hides itself after a few idle seconds. */
@Composable
internal fun FilterIntensityCard(filter: LiveFilter, value: Int, onChange: (Int) -> Unit, onIdle: () -> Unit, modifier: Modifier = Modifier) {
    var lastTouch by remember { mutableIntStateOf(0) }
    val idle by rememberUpdatedState(onIdle)
    LaunchedEffect(lastTouch) {
        delay(3_000)
        idle()
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(StudioGlass.FillStrong)
            .border(1.dp, StudioGlass.Stroke, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterSwatch(filter, Modifier.size(20.dp).clip(CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.camera_filter_intensity), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.weight(1f))
            Text("$value%".localizeDigits(), style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        RgSlider(
            value = value.toFloat(),
            onValueChange = { lastTouch++; onChange(it.toInt()) },
            valueRange = 0f..100f,
            trackColor = Color.White.copy(alpha = 0.18f),
            contentDescription = stringResource(R.string.camera_filter_intensity),
        )
    }
}

package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.Entitlements
import com.ravango.engine.beauty.effects.Lens
import com.ravango.feature.camera.EffectsGating
import com.ravango.feature.camera.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lenses in carousel order; index 0 is "no lens". */
internal val CarouselLenses: List<Lens?> = listOf<Lens?>(null) + Lens.entries

private val ItemSize = 56.dp
private val ItemSpacing = 12.dp

/**
 * Snapchat-style lens carousel around the shutter: round previews scroll under a fixed shutter ring; the item in
 * the ring is the live lens (applied as it snaps in, with a haptic tick) and grows to fill the ring. Tapping a side
 * item scrolls it into the ring; tapping the ring records ([shutter] is drawn on top). Scale/alpha are computed in
 * the draw phase from the list's layout info, so scrolling never recomposes the items.
 */
@Composable
internal fun LensCarousel(
    applied: Lens?,
    entitlements: Entitlements,
    atlas: ImageBitmap?,
    onFocus: (Lens?) -> Unit,
    modifier: Modifier = Modifier,
    shutter: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
    val startIndex = remember { CarouselLenses.indexOf(applied).coerceAtLeast(0) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val scope = rememberCoroutineScope()
    val focus by rememberUpdatedState(onFocus)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Center)
    val centered by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: startIndex
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { centered }.distinctUntilChanged().drop(1).collect { index ->
            haptics.perform(HapticEvent.TICK)
            focus(CarouselLenses.getOrNull(index))
        }
    }
    val itemPx = with(LocalDensity.current) { (ItemSize + ItemSpacing).toPx() }
    BoxWithConstraints(modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        val side = (maxWidth - ItemSize) / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(ItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) {
            itemsIndexed(CarouselLenses, key = { _, lens -> lens?.id ?: "none" }) { index, lens ->
                val locked = lens != null && !EffectsGating.lensAllowed(lens, entitlements)
                val label = lens.label()
                Box(
                    Modifier
                        .size(ItemSize)
                        .graphicsLayer {
                            val info = state.layoutInfo
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val d = if (item == null) itemPx * 3 else abs(item.offset + item.size / 2f - mid)
                            val t = (1f - d / itemPx).coerceIn(0f, 1f)
                            val s = 0.86f + 0.28f * t
                            scaleX = s; scaleY = s
                            alpha = 0.72f + 0.28f * t
                        }
                        .shadow(6.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                            selected = index == centered
                        }
                        .pressable(haptic = HapticEvent.SNAP) {
                            scope.launch { state.animateScrollToItem(index) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    LensGlyph(lens, atlas, Modifier.size(ItemSize))
                    if (locked) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
        shutter()
    }
}

/**
 * Lens name toast above the carousel: the focused lens name fades in with a hint (show your face / open your mouth)
 * or a Pro note for locked lenses.
 */
@Composable
internal fun LensNameToast(lens: Lens?, locked: Boolean, needsFace: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = Triple(lens, locked, needsFace),
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }) togetherWith fadeOut(tween(120)) },
        label = "lensToast",
        modifier = modifier,
    ) { (l, isLocked, faceMissing) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    l.label(),
                    style = MaterialTheme.typography.titleMedium.overPreview(),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (isLocked) {
                    Spacer(Modifier.width(8.dp))
                    ProBadge(text = stringResource(R.string.camera_pro_badge))
                }
            }
            val hint = when {
                isLocked -> stringResource(R.string.camera_lens_hint_pro)
                l == null -> null
                faceMissing -> stringResource(R.string.camera_lens_hint_face)
                l.hasTrigger -> stringResource(R.string.camera_lens_hint_mouth)
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium.overPreview(),
                    color = if (isLocked) Palette.Butter300 else Color.White.copy(alpha = 0.92f),
                )
            }
        }
    }
}

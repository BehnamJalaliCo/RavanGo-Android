package com.ravango.feature.beauty

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgSlider
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.beauty.looks.LookAvatar
import com.ravango.feature.beauty.looks.LookDef
import com.ravango.feature.beauty.looks.LookTag
import com.ravango.feature.beauty.looks.tileGestures

private val OnGlass = Color.White
private val OnGlassMuted = Color.White.copy(alpha = 0.65f)
private val Idle = Color.White.copy(alpha = 0.08f)
private val FavouritePink = Color(0xFFFF6F9A)

/** The looks shown for [filter] (saved looks first in "All"). */
internal fun BeautyUiState.filteredLooks(filter: LookFilter = lookFilter): List<LookDef> = when (filter) {
    LookFilter.ALL -> looks.looks
    LookFilter.FULL -> looks.looks.filter { !it.isCustom && LookTag.BEAUTY !in it.tags }
    LookFilter.LIGHT -> looks.looks.filter { LookTag.BEAUTY in it.tags }
    LookFilter.FAVOURITES -> looks.looks.filter { looks.isFavourite(it.key) }
    LookFilter.MINE -> looks.custom
}

/**
 * Looks tab of the Beauty panel (first tab): filter chips, a carousel of illustrated look thumbnails (tap to apply,
 * tap again to remove, long-press to favourite), then the active look's strength slider with Clear, Customise
 * (opens the Makeup tab) and Save as my look.
 */
@Composable
internal fun LooksTab(ui: BeautyUiState, actions: BeautyPanelActions) {
    val names = rememberLookNamer()
    val active = ui.activeLook
    val list = ui.filteredLooks()
    val faceMissing = ui.status.tracking && !ui.status.faceDetected
    Column(Modifier.fillMaxWidth()) {
        LookFilterRow(ui, actions.onLookFilter)
        Spacer(Modifier.height(Spacing.md))
        Box(Modifier.fillMaxWidth().heightIn(min = 104.dp), contentAlignment = Alignment.CenterStart) {
            if (list.isEmpty()) {
                Text(
                    stringResource(if (ui.lookFilter == LookFilter.MINE) R.string.beauty_look_empty_mine else R.string.beauty_look_empty_favourites),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnGlassMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item(key = "none") {
                        NoneTile(selected = active == null && !ui.hasMakeup, onClick = actions.onClearMakeup)
                    }
                    items(list, key = { it.id }) { look ->
                        val favourite = ui.looks.isFavourite(look.key)
                        LookTile(
                            look = look,
                            name = names(look),
                            selected = look.id == active?.id,
                            locked = ui.isLookLocked(look),
                            favourite = favourite,
                            onClick = { actions.onApplyLook(look) },
                            onLongPress = { actions.onToggleFavourite(look) },
                            longPressLabel = stringResource(if (favourite) R.string.beauty_look_favourite_remove else R.string.beauty_look_favourite_add),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        AnimatedContent(
            targetState = active,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
            contentKey = { it?.id },
            label = "lookControls",
        ) { look ->
            if (look == null) {
                Text(
                    stringResource(if (faceMissing) R.string.beauty_face_not_detected else R.string.beauty_look_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (faceMissing) RgTheme.colors.warning else OnGlassMuted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            } else {
                ActiveLookControls(ui, look, names(look), actions)
            }
        }
    }
}

@Composable
private fun LookFilterRow(ui: BeautyUiState, onFilter: (LookFilter) -> Unit) {
    val filters = buildList {
        add(LookFilter.ALL)
        add(LookFilter.FULL)
        add(LookFilter.LIGHT)
        add(LookFilter.FAVOURITES)
        if (ui.looks.custom.isNotEmpty() || ui.lookFilter == LookFilter.MINE) add(LookFilter.MINE)
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(filters, key = { it.name }) { f ->
            val selected = f == ui.lookFilter
            val bg by animateColorAsState(if (selected) Color.White.copy(alpha = 0.92f) else Idle, Motion.quick(), label = "lookFilter")
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(bg)
                    .border(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.12f), RoundedCornerShape(Radius.pill))
                    .pressable(haptic = HapticEvent.SNAP) { onFilter(f) }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (f == LookFilter.FAVOURITES) {
                    Icon(Icons.Rounded.Favorite, null, tint = if (selected) FavouritePink else OnGlassMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    stringResource(
                        when (f) {
                            LookFilter.ALL -> R.string.beauty_look_filter_all
                            LookFilter.FULL -> R.string.beauty_look_filter_full
                            LookFilter.LIGHT -> R.string.beauty_look_filter_light
                            LookFilter.FAVOURITES -> R.string.beauty_look_filter_favourites
                            LookFilter.MINE -> R.string.beauty_look_filter_mine
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Color.Black else OnGlassMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A look thumbnail tile: the illustrated avatar in a ring, Pro badge, favourite heart and the name. */
@Composable
internal fun LookTile(
    look: LookDef,
    name: String,
    selected: Boolean,
    locked: Boolean,
    favourite: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    longPressLabel: String? = null,
    size: Dp = 64.dp,
) {
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, Motion.snappy(), label = "lookScale")
    val ring by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.18f), Motion.quick(), label = "lookRing")
    Column(
        Modifier
            .width(size + 14.dp)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .tileGestures(onTap = onClick, onLongPress = onLongPress, longPressLabel = longPressLabel)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .drawBehind {
                        val w = if (selected) 2.5.dp.toPx() else 1.5.dp.toPx()
                        drawCircle(ring, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
                    }
                    .padding(if (selected) 4.dp else 3.dp)
                    .clip(CircleShape),
            ) {
                LookAvatar(look, Modifier.fillMaxSize().graphicsLayer { alpha = if (locked) 0.82f else 1f })
            }
            if (locked) {
                ProBadge(Modifier.align(Alignment.BottomCenter).offset(y = 7.dp), text = stringResource(R.string.beauty_pro))
            }
            if (favourite) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xE6121019))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Favorite, null, tint = FavouritePink, modifier = Modifier.size(12.dp))
                }
            }
        }
        Spacer(Modifier.height(if (locked) 10.dp else 6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) OnGlass else OnGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = size + 14.dp),
        )
    }
}

@Composable
private fun NoneTile(selected: Boolean, onClick: () -> Unit) {
    val ring by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.18f), Motion.quick(), label = "noneRing")
    val label = stringResource(R.string.beauty_look_none)
    Column(
        Modifier
            .width(78.dp)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .tileGestures(onTap = onClick, onLongPress = null)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .border(if (selected) 2.5.dp else 1.5.dp, ring, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Idle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Block, null, tint = OnGlass, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) OnGlass else OnGlassMuted, maxLines = 1)
    }
}

@Composable
private fun ActiveLookControls(ui: BeautyUiState, look: LookDef, name: String, actions: BeautyPanelActions) {
    val locale = currentLocale()
    val customised = ui.looks.customised
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = OnGlass, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (customised) R.string.beauty_look_hint_customised else R.string.beauty_look_intensity),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (customised) Palette.Butter300 else OnGlassMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                ui.looks.intensity.toString().localizeDigits(locale),
                style = MaterialTheme.typography.titleMedium,
                color = OnGlass,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
            LookPill(icon = Icons.Rounded.Close, text = stringResource(R.string.beauty_look_clear), onClick = actions.onClearMakeup)
        }
        RgSlider(
            value = ui.looks.intensity.toFloat(),
            onValueChange = { v -> actions.onLookIntensity(v.toInt()) },
            valueRange = 0f..100f,
            trackColor = Color.White.copy(alpha = 0.16f),
            contentDescription = stringResource(R.string.beauty_look_intensity),
        )
        Spacer(Modifier.height(Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            LookPill(icon = Icons.Rounded.Tune, text = stringResource(R.string.beauty_look_customise), onClick = actions.onCustomiseLook)
            LookPill(icon = Icons.Rounded.SaveAlt, text = stringResource(R.string.beauty_look_save), onClick = actions.onSaveLook, accent = customised)
            if (look.isCustom) {
                LookPill(icon = Icons.Rounded.DeleteOutline, text = stringResource(R.string.beauty_look_delete), onClick = { actions.onDeleteLook(look) }, iconOnly = true)
            }
        }
    }
}

@Composable
private fun LookPill(icon: ImageVector, text: String, onClick: () -> Unit, accent: Boolean = false, iconOnly: Boolean = false) {
    val bg = if (accent) RgTheme.colors.accent else Color.White.copy(alpha = 0.1f)
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (accent) 0f else 0.14f), RoundedCornerShape(Radius.pill))
            .semantics { if (iconOnly) contentDescription = text }
            .pressable(haptic = HapticEvent.TAP, onClick = onClick)
            .padding(horizontal = if (iconOnly) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = OnGlass, modifier = Modifier.size(16.dp))
        if (!iconOnly) {
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = OnGlass, maxLines = 1)
        }
    }
}

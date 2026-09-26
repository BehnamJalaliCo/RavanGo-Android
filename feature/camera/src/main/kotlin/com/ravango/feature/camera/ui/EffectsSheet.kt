@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.model.Entitlements
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.camera.EffectsGating
import com.ravango.feature.camera.R

internal enum class EffectsTab { FILTERS, BACKGROUND }

/** Solid background colours offered for replacement (ARGB). */
internal val BackgroundColors: List<Long> = listOf(0xFF15131F, 0xFFF7F5FD, 0xFFA394FB, 0xFF6FD9C0, 0xFFFF93AF, 0xFFFFD166, 0xFF7DB8FF)

/** Vertical gradients (top, bottom) offered for replacement. */
internal val BackgroundGradients: List<Pair<Long, Long>> = listOf(
    0xFFA394FB to 0xFFFF93AF,
    0xFF7DB8FF to 0xFF6FD9C0,
    0xFFFFC9A3 to 0xFFFF7A9E,
    0xFF2F2B44 to 0xFF7160E8,
    0xFFFFE7A3 to 0xFFFF8A5B,
)

@Composable
internal fun EffectsSheet(
    tab: EffectsTab,
    effects: EffectsState,
    status: EffectsStatus,
    entitlements: Entitlements,
    hasBackgroundImage: Boolean,
    onTab: (EffectsTab) -> Unit,
    onFilter: (LiveFilter) -> Unit,
    onIntensity: (Int) -> Unit,
    onBackground: (BackgroundEffect) -> Unit,
    onPickPhoto: () -> Unit,
    onRequirePro: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, dark = true) {
        EffectsSheetContent(tab, effects, status, entitlements, hasBackgroundImage, onTab, onFilter, onIntensity, onBackground, onPickPhoto, onRequirePro, onClearAll)
    }
}

/** Stateless body of the Filters & background sheet (also rendered by screenshot tests). */
@Composable
internal fun EffectsSheetContent(
    tab: EffectsTab,
    effects: EffectsState,
    status: EffectsStatus,
    entitlements: Entitlements,
    hasBackgroundImage: Boolean,
    onTab: (EffectsTab) -> Unit,
    onFilter: (LiveFilter) -> Unit,
    onIntensity: (Int) -> Unit,
    onBackground: (BackgroundEffect) -> Unit,
    onPickPhoto: () -> Unit,
    onRequirePro: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RgSegmentedControl(
                options = EffectsTab.entries,
                selected = tab,
                onSelect = onTab,
                label = { stringResource(if (it == EffectsTab.FILTERS) R.string.camera_effects_tab_filters else R.string.camera_effects_tab_background) },
                glass = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RgTextButton(
                stringResource(R.string.camera_effects_off),
                onClearAll,
                color = Color.White.copy(alpha = 0.8f),
                enabled = !effects.isNeutral,
            )
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) }, label = "effectsTab") { t ->
            when (t) {
                EffectsTab.FILTERS -> FiltersTab(effects, entitlements, onFilter, onIntensity, onRequirePro)
                EffectsTab.BACKGROUND -> BackgroundTab(effects.background, status, entitlements, hasBackgroundImage, onBackground, onPickPhoto, onRequirePro)
            }
        }
    }
}

@Composable
private fun FiltersTab(
    effects: EffectsState,
    entitlements: Entitlements,
    onFilter: (LiveFilter) -> Unit,
    onIntensity: (Int) -> Unit,
    onRequirePro: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LiveFilter.entries, key = { it.id }) { filter ->
                val locked = !EffectsGating.filterAllowed(filter, entitlements)
                OptionChip(
                    label = filter.label(),
                    selected = effects.filter == filter,
                    locked = locked,
                    onClick = { if (locked) onRequirePro() else onFilter(filter) },
                ) { FilterSwatch(filter, Modifier.size(OptionSize)) }
            }
        }
        AnimatedVisibility(effects.filter != LiveFilter.NONE, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            RgLabeledSlider(
                label = stringResource(R.string.camera_filter_intensity),
                value = effects.filterIntensity.toFloat(),
                onValueChange = { onIntensity(it.toInt()) },
                valueText = "${effects.filterIntensity}%".localizeDigits(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        Hint(Icons.Rounded.Swipe, stringResource(R.string.camera_filter_swipe_hint))
    }
}

@Composable
private fun BackgroundTab(
    background: BackgroundEffect,
    status: EffectsStatus,
    entitlements: Entitlements,
    hasImage: Boolean,
    onBackground: (BackgroundEffect) -> Unit,
    onPickPhoto: () -> Unit,
    onRequirePro: () -> Unit,
) {
    val photoLocked = !EffectsGating.backgroundAllowed(BackgroundEffect.Image, entitlements)
    Column(Modifier.fillMaxWidth()) {
        if (status.backgroundUnavailable) {
            Hint(Icons.Rounded.Info, stringResource(R.string.camera_bg_unavailable), warning = true)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OptionChip(stringResource(R.string.camera_bg_none), background == BackgroundEffect.None, false, { onBackground(BackgroundEffect.None) }) {
                IconDisc(Icons.Rounded.Block, listOf(Color(0xFF3A3550), Color(0xFF221F30)))
            }
            OptionChip(stringResource(R.string.camera_bg_blur), background is BackgroundEffect.Blur, false, {
                if (background !is BackgroundEffect.Blur) onBackground(BackgroundEffect.Blur())
            }) {
                IconDisc(Icons.Rounded.BlurOn, listOf(Palette.Sky300, Palette.Lavender400))
            }
            OptionChip(
                stringResource(if (hasImage) R.string.camera_bg_photo else R.string.camera_bg_photo_pick),
                background is BackgroundEffect.Image,
                photoLocked,
                {
                    when {
                        photoLocked -> onRequirePro()
                        hasImage && background !is BackgroundEffect.Image -> onBackground(BackgroundEffect.Image)
                        else -> onPickPhoto()
                    }
                },
            ) {
                IconDisc(Icons.Rounded.AddPhotoAlternate, listOf(Palette.Peach300, Palette.Rose400))
            }
        }
        AnimatedVisibility(background is BackgroundEffect.Blur, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val strength = (background as? BackgroundEffect.Blur)?.strength ?: 60
            RgLabeledSlider(
                label = stringResource(R.string.camera_bg_blur_strength),
                value = strength.toFloat(),
                onValueChange = { onBackground(BackgroundEffect.Blur(it.toInt())) },
                valueText = "$strength%".localizeDigits(),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        if (background is BackgroundEffect.Image && hasImage && !photoLocked) {
            RgTextButton(stringResource(R.string.camera_bg_photo_change), onPickPhoto, modifier = Modifier.padding(start = 8.dp, top = 8.dp), color = RgTheme.colors.pastelLavender)
        }
        SectionLabel(stringResource(R.string.camera_bg_colors))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackgroundColors.forEach { argb ->
                val selected = background is BackgroundEffect.Color && background.argb == argb
                Swatch(Brush.linearGradient(listOf(Color(argb), Color(argb))), selected) { onBackground(BackgroundEffect.Color(argb)) }
            }
        }
        SectionLabel(stringResource(R.string.camera_bg_gradients))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackgroundGradients.forEach { (top, bottom) ->
                val selected = background is BackgroundEffect.Gradient && background.top == top && background.bottom == bottom
                Swatch(Brush.verticalGradient(listOf(Color(top), Color(bottom))), selected) { onBackground(BackgroundEffect.Gradient(top, bottom)) }
            }
        }
        val line = when {
            status.backgroundWarmingUp -> stringResource(R.string.camera_bg_warming)
            else -> stringResource(R.string.camera_bg_hint)
        }
        Hint(Icons.Rounded.Info, line)
    }
}

private val OptionSize: Dp = 56.dp

/** A round option with a label underneath; selected = accent ring, locked = PRO badge. */
@Composable
private fun OptionChip(label: String, selected: Boolean, locked: Boolean, onClick: () -> Unit, art: @Composable () -> Unit) {
    val accent = RgTheme.colors.accent
    val ring by animateColorAsState(if (selected) accent else Color.White.copy(alpha = 0.16f), Motion.quick(), label = "optionRing")
    Column(
        Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .semantics { role = Role.Button; this.selected = selected; contentDescription = label }
            .pressable(haptic = HapticEvent.SNAP, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(OptionSize + 8.dp)
                    .border(2.5.dp, ring, CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) { art() }
            if (locked) ProBadge(Modifier.align(Alignment.BottomCenter).offset(y = 6.dp), text = stringResource(R.string.camera_pro_badge))
        }
        Spacer(Modifier.height(if (locked) 12.dp else 8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else StudioGlass.Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 88.dp),
        )
    }
}

@Composable
private fun IconDisc(icon: ImageVector, colors: List<Color>) {
    Box(Modifier.size(OptionSize).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Swatch(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    val accent = RgTheme.colors.accent
    Box(
        Modifier
            .size(44.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.3f), CircleShape)
            .padding(if (selected) 4.dp else 1.dp)
            .clip(CircleShape)
            .background(brush)
            .pressable(haptic = HapticEvent.SNAP, onClick = onClick),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = StudioGlass.Muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(icon: ImageVector, text: String, warning: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (warning) RgTheme.colors.warning else StudioGlass.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (warning) RgTheme.colors.warning else StudioGlass.Muted)
    }
}

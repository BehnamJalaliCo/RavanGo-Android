package com.ravango.feature.beauty

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.FaceRetouchingOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgSlider
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.BeautyPreset
import com.ravango.core.model.MakeupFeature
import com.ravango.core.model.ProFeature
import com.ravango.engine.beauty.BeautyQuality
import com.ravango.engine.beauty.BeautyStatus
import com.ravango.engine.beauty.BeautySuspendReason
import com.ravango.feature.beauty.looks.LookDef
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CONTRACT — beauty & makeup control panel embedded in Camera Studio (dark glass bottom panel).
 * Sliders for every beauty/makeup option (0–100), color pickers for makeup, presets, before/after.
 * Pro-gated options call [onRequirePro] instead of applying.
 */
@Composable
fun BeautyPanel(
    modifier: Modifier = Modifier,
    onOpenPresets: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
) {
    BeautyPanelImpl(modifier, onOpenPresets, onRequirePro)
}

private val PanelTint = Color(0xD9121019)
private val OnGlass = Color.White
private val OnGlassMuted = Color.White.copy(alpha = 0.65f)
private val ChipIdle = Color.White.copy(alpha = 0.08f)

/** Every intent of the beauty panel (defaults are no-ops for previews and screenshot tests). */
@Immutable
internal class BeautyPanelActions(
    val onToggle: (Boolean) -> Unit = {},
    val onCompare: (Boolean) -> Unit = {},
    val onResetAll: () -> Unit = {},
    val onOpenPresets: () -> Unit = {},
    val onSelectTab: (BeautyTab) -> Unit = {},
    val onSelectItem: (BeautyItem) -> Unit = {},
    /** Returns false when the item is locked (the panel then asks for Pro). */
    val onValue: (BeautyItem, Int) -> Boolean = { _, _ -> true },
    val onReset: (BeautyItem) -> Unit = {},
    val onMakeupColor: (MakeupFeature, Long) -> Unit = { _, _ -> },
    val onEyeColor: (Long) -> Unit = {},
    val onUnlock: (BeautyItem) -> Unit = {},
    val onApplyLook: (LookDef) -> Unit = {},
    val onClearMakeup: () -> Unit = {},
    val onLookIntensity: (Int) -> Unit = {},
    val onToggleFavourite: (LookDef) -> Unit = {},
    val onLookFilter: (LookFilter) -> Unit = {},
    val onCustomiseLook: () -> Unit = {},
    val onSaveLook: () -> Unit = {},
    val onDeleteLook: (LookDef) -> Unit = {},
    val onApplyPreset: (BeautyPreset) -> Unit = {},
    val onSavePreset: () -> Unit = {},
)

@Composable
internal fun BeautyPanelImpl(modifier: Modifier, onOpenPresets: () -> Unit, onRequirePro: (ProFeature) -> Unit) {
    val viewModel: BeautyViewModel = hiltViewModel()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val requirePro by rememberUpdatedState(onRequirePro)
    val openPresets by rememberUpdatedState(onOpenPresets)
    var toast by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showSaveLookDialog by rememberSaveable { mutableStateOf(false) }

    val savedText = stringResource(R.string.beauty_preset_saved)
    val failedText = stringResource(R.string.beauty_error_save)
    val appliedFormat = stringResource(R.string.beauty_preset_applied)
    val lookSavedText = stringResource(R.string.beauty_look_saved)
    val presetNames = rememberPresetNamer()
    val lookNames = rememberLookNamer()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is BeautyEvent.RequirePro -> requirePro(event.feature)
                is BeautyEvent.PresetSaved -> toast = savedText
                is BeautyEvent.PresetApplied -> toast = appliedFormat.format(presetNames(event.preset))
                BeautyEvent.SaveFailed -> toast = failedText
                is BeautyEvent.LookApplied -> toast = appliedFormat.format(lookNames(event.look))
                BeautyEvent.LookSaved -> toast = lookSavedText
                BeautyEvent.PresetDeleted, BeautyEvent.PresetRenamed -> Unit
            }
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_200)
            toast = null
        }
    }

    val canSaveMore by rememberUpdatedState(ui.canSaveMorePresets)
    val actions = remember(viewModel) {
        BeautyPanelActions(
            onToggle = viewModel::setEnabled,
            onCompare = viewModel::setComparing,
            onResetAll = viewModel::resetAll,
            onOpenPresets = { openPresets() },
            onSelectTab = viewModel::selectTab,
            onSelectItem = viewModel::select,
            onValue = { item, v -> viewModel.setValue(item, v).also { ok -> if (!ok) viewModel.requirePro(item) } },
            onReset = viewModel::reset,
            onMakeupColor = viewModel::setMakeupColor,
            onEyeColor = viewModel::setEyeColor,
            onUnlock = viewModel::requirePro,
            onApplyLook = viewModel::applyLook,
            onClearMakeup = viewModel::clearMakeup,
            onLookIntensity = viewModel::setLookIntensity,
            onToggleFavourite = viewModel::toggleFavourite,
            onLookFilter = viewModel::setLookFilter,
            onCustomiseLook = viewModel::customiseLook,
            onSaveLook = { showSaveLookDialog = true },
            onDeleteLook = viewModel::deleteLook,
            onApplyPreset = viewModel::applyPreset,
            onSavePreset = { if (canSaveMore) showSaveDialog = true else requirePro(ProFeature.UNLIMITED_PRESETS) },
        )
    }
    BeautyPanelContent(ui = ui, toast = toast, actions = actions, modifier = modifier)

    if (showSaveLookDialog) {
        PresetNameDialog(
            title = stringResource(R.string.beauty_look_save_title),
            initial = ui.activeLook?.let { lookNames(it) }.orEmpty(),
            onConfirm = { name ->
                showSaveLookDialog = false
                viewModel.saveLook(name)
            },
            onDismiss = { showSaveLookDialog = false },
        )
    }
    if (showSaveDialog) {
        PresetNameDialog(
            title = stringResource(R.string.beauty_save_preset),
            initial = "",
            onConfirm = { name ->
                showSaveDialog = false
                viewModel.saveCurrentAsPreset(name)
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

/**
 * Stateless beauty panel (dark glass bottom panel): header (on/off, status, reset, presets, compare), tab row and
 * the active tab. Rendered by the camera sheet and by screenshot tests.
 */
@Composable
internal fun BeautyPanelContent(ui: BeautyUiState, toast: String?, actions: BeautyPanelActions, modifier: Modifier = Modifier) {
    val presetNames = rememberPresetNamer()
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.lg),
        tint = PanelTint,
    ) {
        Column(Modifier.fillMaxWidth()) {
            PanelHeader(
                ui = ui,
                onToggle = actions.onToggle,
                onCompare = actions.onCompare,
                onResetAll = actions.onResetAll,
                onOpenPresets = actions.onOpenPresets,
            )
            AnimatedVisibility(visible = toast != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    toast.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnGlass,
                    modifier = Modifier
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(RgTheme.colors.accent.copy(alpha = 0.35f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            TabRow(selected = ui.tab, onSelect = actions.onSelectTab)
            Spacer(Modifier.height(Spacing.lg))
            val dim by animateFloatAsState(if (ui.state.enabled) 1f else 0.55f, Motion.quick(), label = "enabled")
            AnimatedContent(
                targetState = ui.tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "tab",
                modifier = Modifier.alpha(dim),
            ) { tab ->
                when (tab) {
                    BeautyTab.LOOKS -> LooksTab(ui = ui, actions = actions)
                    BeautyTab.PRESETS -> PresetsTab(
                        ui = ui,
                        nameOf = presetNames,
                        onApply = actions.onApplyPreset,
                        onSave = actions.onSavePreset,
                        onManage = actions.onOpenPresets,
                    )
                    else -> FeatureTab(ui = ui, tab = tab, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(
    ui: BeautyUiState,
    onToggle: (Boolean) -> Unit,
    onCompare: (Boolean) -> Unit,
    onResetAll: () -> Unit,
    onOpenPresets: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RgSwitch(checked = ui.state.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            stringResource(R.string.beauty_title),
            style = MaterialTheme.typography.titleMedium,
            color = OnGlass,
        )
        Spacer(Modifier.width(Spacing.sm))
        Box(Modifier.weight(1f)) {
            QualityIndicator(ui.status, ui.state.enabled)
        }
        RgIconButton(
            icon = Icons.Rounded.RestartAlt,
            contentDescription = stringResource(R.string.beauty_reset_all),
            onClick = onResetAll,
            glass = true,
            size = 44.dp,
            iconSize = 20.dp,
        )
        Spacer(Modifier.width(Spacing.sm))
        RgIconButton(
            icon = Icons.Rounded.Tune,
            contentDescription = stringResource(R.string.beauty_manage_presets),
            onClick = onOpenPresets,
            glass = true,
            size = 44.dp,
            iconSize = 20.dp,
        )
        Spacer(Modifier.width(Spacing.sm))
        CompareButton(comparing = ui.comparing, enabled = ui.state.enabled, onCompare = onCompare)
    }
}

/** Press-and-hold "before/after": shows the original preview while held (recording is unaffected). */
@Composable
private fun CompareButton(comparing: Boolean, enabled: Boolean, onCompare: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val currentOnCompare by rememberUpdatedState(onCompare)
    val bg by animateColorAsState(if (comparing) RgTheme.colors.accent else Color.Black.copy(alpha = 0.32f), Motion.quick(), label = "cmp")
    val label = stringResource(R.string.beauty_compare)
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptics.perform(HapticEvent.TICK)
                        currentOnCompare(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            currentOnCompare(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Compare, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

/**
 * Status line: quality/thermal notices take priority; otherwise it shows multi-face awareness ("Face tracked",
 * "2 faces") while tracking runs.
 */
@Composable
private fun QualityIndicator(status: BeautyStatus, enabled: Boolean) {
    val locale = currentLocale()
    val quality = when {
        !enabled -> null
        status.suspendedReason == BeautySuspendReason.GPU_ERROR -> stringResource(R.string.beauty_gpu_error)
        status.suspendedReason == BeautySuspendReason.THERMAL -> stringResource(R.string.beauty_quality_thermal)
        status.quality == BeautyQuality.BALANCED -> stringResource(R.string.beauty_quality_balanced)
        status.quality == BeautyQuality.LIGHT -> stringResource(R.string.beauty_quality_light)
        status.quality == BeautyQuality.MINIMAL -> stringResource(R.string.beauty_quality_minimal)
        else -> null
    }
    val faces = when {
        !enabled || !status.tracking -> null
        status.faceCount >= 2 -> stringResource(R.string.beauty_faces_tracked, status.faceCount.toString().localizeDigits(locale))
        status.faceCount == 1 -> stringResource(R.string.beauty_face_tracked)
        else -> stringResource(R.string.beauty_looking_for_face)
    }
    val text = quality ?: faces
    val tracked = quality == null && status.faceCount > 0
    val tint by animateColorAsState(if (tracked) Palette.Mint400 else OnGlassMuted, Motion.quick(), label = "track")
    AnimatedVisibility(visible = text != null, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = when {
                quality != null -> Icons.Rounded.Speed
                status.faceCount >= 2 -> Icons.Rounded.People
                else -> Icons.Rounded.Face
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = text.orEmpty(),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(160)) },
                label = "status",
            ) { t ->
                Text(
                    t,
                    style = MaterialTheme.typography.labelSmall,
                    color = OnGlassMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TabRow(selected: BeautyTab, onSelect: (BeautyTab) -> Unit) {
    val listState = rememberLazyListState()
    // Keep the selected tab in view (e.g. when the panel reopens on a tab at the far end).
    LaunchedEffect(selected) { listState.animateScrollToItem(selected.ordinal.coerceAtLeast(0).let { (it - 1).coerceAtLeast(0) }) }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        items(BeautyTab.entries, key = { it.name }) { tab ->
            val isSelected = tab == selected
            val bg by animateColorAsState(if (isSelected) Color.White else Color.Transparent, Motion.quick(), label = "tab")
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(bg)
                    .pressable(haptic = HapticEvent.SNAP) { onSelect(tab) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color.Black else OnGlassMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FeatureTab(ui: BeautyUiState, tab: BeautyTab, actions: BeautyPanelActions) {
    val items = remember(tab) { BeautyCatalog.items(tab) }
    val selected = ui.selected?.takeIf { it in items } ?: items.firstOrNull() ?: return
    val faceMissing = ui.status.tracking && !ui.status.faceDetected
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items.forEach { item ->
                key(item.key) {
                    FeatureChip(
                        item = item,
                        selected = item == selected,
                        value = ui.valueOf(item),
                        shade = shadeOf(ui, item),
                        locked = ui.isLocked(item),
                        faceMissing = faceMissing && item.requiresFace,
                        onClick = { actions.onSelectItem(item) },
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "detail",
        ) { item ->
            FeatureDetail(
                item = item,
                value = ui.valueOf(item),
                color = shadeOf(ui, item),
                locked = ui.isLocked(item),
                faceMissing = faceMissing && item.requiresFace,
                trackingUnavailable = item.requiresFace && ui.status.suspendedReason == BeautySuspendReason.TRACKING_UNAVAILABLE,
                onValue = { v -> actions.onValue(item, v) },
                onReset = { actions.onReset(item) },
                onColor = { c ->
                    when (item) {
                        is BeautyItem.Makeup -> actions.onMakeupColor(item.feature, c)
                        BeautyItem.EyeColor -> actions.onEyeColor(c)
                        is BeautyItem.Beauty -> Unit
                    }
                },
                onUnlock = { actions.onUnlock(item) },
            )
        }
    }
}

/** The colour an item paints with (makeup layer or iris), or null for plain beauty sliders. */
private fun shadeOf(ui: BeautyUiState, item: BeautyItem): Long? = when (item) {
    is BeautyItem.Makeup -> ui.state.layer(item.feature).color
    BeautyItem.EyeColor -> ui.eyeColor.color
    is BeautyItem.Beauty -> null
}

/**
 * Round preview chip (Snapchat-style carousel): the circle shows the item's shade when it paints a colour, and a
 * ring around it shows the current amount (from the top, clockwise; counter-clockwise below neutral for bipolar
 * sliders).
 */
@Composable
private fun FeatureChip(
    item: BeautyItem,
    selected: Boolean,
    value: Int,
    shade: Long?,
    locked: Boolean,
    faceMissing: Boolean,
    onClick: () -> Unit,
) {
    val accent = RgTheme.colors.accent
    val mint = RgTheme.colors.pastelMint
    val neutral = BeautyCatalog.neutral(item)
    val active = value != neutral
    val fraction by animateFloatAsState(
        if (item.bipolar) (value - 50) / 50f else value / 100f,
        Motion.quick(),
        label = "ring",
    )
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, Motion.quick(), label = "chipScale")
    // The amount ring fills in the reading direction (clockwise in LTR, counter-clockwise in RTL).
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val fill = when {
        shade != null && active -> Color(shade)
        selected -> accent.copy(alpha = 0.9f)
        else -> ChipIdle
    }
    val bg by animateColorAsState(fill, Motion.quick(), label = "chip")
    val iconTint = if (shade != null && active && Color(shade).luminance() > 0.6f) Color.Black.copy(alpha = 0.75f) else OnGlass
    Column(
        Modifier
            .widthIn(min = 68.dp)
            .alpha(if (faceMissing) 0.5f else 1f)
            .clip(RoundedCornerShape(Radius.md))
            .pressable(haptic = HapticEvent.SNAP, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(54.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .drawBehind {
                        val stroke = 2.5.dp.toPx()
                        val inset = stroke / 2
                        drawCircle(
                            Color.White.copy(alpha = if (selected) 0.35f else 0.14f),
                            radius = size.minDimension / 2 - inset,
                            style = Stroke(stroke),
                        )
                        if (fraction != 0f) {
                            drawArc(
                                color = if (selected) Color.White else mint,
                                startAngle = -90f,
                                sweepAngle = 360f * fraction * direction,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(stroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(BeautyCatalog.icon(item), null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            if (locked) {
                ProBadge(Modifier.align(Alignment.BottomCenter).offset(y = 8.dp), text = stringResource(R.string.beauty_pro))
            }
        }
        Spacer(Modifier.height(if (locked) 10.dp else 6.dp))
        Text(
            stringResource(BeautyCatalog.label(item)),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) OnGlass else OnGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 84.dp),
        )
    }
}

@Composable
private fun FeatureDetail(
    item: BeautyItem,
    value: Int,
    color: Long?,
    locked: Boolean,
    faceMissing: Boolean,
    trackingUnavailable: Boolean,
    onValue: (Int) -> Unit,
    onReset: () -> Unit,
    onColor: (Long) -> Unit,
    onUnlock: () -> Unit,
) {
    val haptics = rememberHaptics()
    val locale = currentLocale()
    val neutral = BeautyCatalog.neutral(item)
    val sweetSpot = BeautyCatalog.recommended(item)
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(BeautyCatalog.label(item)), style = MaterialTheme.typography.titleSmall, color = OnGlass)
                val warning = when {
                    trackingUnavailable -> stringResource(R.string.beauty_tracking_unavailable)
                    faceMissing -> stringResource(R.string.beauty_face_not_detected)
                    else -> null
                }
                val hint = BeautyCatalog.hint(item)?.let { stringResource(it) }
                AnimatedVisibility(visible = warning != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FaceRetouchingOff, null, tint = RgTheme.colors.warning, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(warning.orEmpty(), style = MaterialTheme.typography.labelSmall, color = RgTheme.colors.warning)
                    }
                }
                if (warning == null && hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = OnGlassMuted, maxLines = 2)
                }
            }
            Text(
                valueLabel(value, item.bipolar, locale),
                style = MaterialTheme.typography.titleMedium,
                color = OnGlass,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
            RgIconButton(
                icon = Icons.Rounded.RestartAlt,
                contentDescription = stringResource(R.string.beauty_reset),
                onClick = onReset,
                glass = true,
                size = 40.dp,
                iconSize = 18.dp,
                enabled = !locked && value != neutral,
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Box(contentAlignment = Alignment.Center) {
            RgSlider(
                value = value.toFloat(),
                onValueChange = { raw ->
                    // Magnetic detents at the neutral point and the item's sweet spot (RgSlider also ticks every 10 %).
                    val v = snapDetent(raw.roundToInt(), neutral, sweetSpot)
                    if (v != raw.roundToInt() && v != value) haptics.perform(HapticEvent.SNAP)
                    if (v != value) onValue(v)
                },
                valueRange = 0f..100f,
                bipolar = item.bipolar,
                enabled = !locked,
                trackColor = Color.White.copy(alpha = 0.16f),
                contentDescription = stringResource(BeautyCatalog.label(item)),
                modifier = Modifier.alpha(if (locked) 0.4f else 1f),
            )
            if (locked) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(Radius.pill))
                        .pressable(haptic = HapticEvent.TAP, onClick = onUnlock)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = OnGlass, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.beauty_unlock_pro), style = MaterialTheme.typography.labelLarge, color = OnGlass)
                    Spacer(Modifier.width(6.dp))
                    ProBadge(text = stringResource(R.string.beauty_pro))
                }
            }
        }
        if (item.bipolar) {
            val (start, end) = BeautyCatalog.bipolarEnds(item)
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(start), style = MaterialTheme.typography.labelSmall, color = OnGlassMuted, modifier = Modifier.weight(1f))
                Text(stringResource(end), style = MaterialTheme.typography.labelSmall, color = OnGlassMuted)
            }
        }
        if (color != null) {
            Spacer(Modifier.height(Spacing.sm))
            val shades = remember(item) {
                when (item) {
                    is BeautyItem.Makeup -> BeautyCatalog.shades(item.feature)
                    BeautyItem.EyeColor -> BeautyCatalog.eyeColorShades
                    is BeautyItem.Beauty -> emptyList()
                }
            }
            ShadeRow(shades = shades, selected = color, onSelect = onColor)
        }
    }
}

/** Snaps [raw] to [neutral] or [sweetSpot] when within ±2 (magnetic slider detents); otherwise clamps to 0..100. */
internal fun snapDetent(raw: Int, neutral: Int, sweetSpot: Int): Int {
    if (abs(raw - neutral) <= 2) return neutral
    if (abs(raw - sweetSpot) <= 2) return sweetSpot
    return raw.coerceIn(0, 100)
}

@Composable
private fun ShadeRow(shades: List<Long>, selected: Long, onSelect: (Long) -> Unit) {
    val colors = remember(shades) { shades.map { Color(it) } }
    // Show the user's current colour even when it is not one of the curated shades.
    val all = if (shades.contains(selected)) colors else listOf(Color(selected)) + colors
    ColorSwatchRow(
        colors = all,
        selected = Color(selected),
        onSelect = { c -> onSelect(argbOf(c)) },
        swatchSize = 30.dp,
    )
}

@Composable
private fun PresetsTab(
    ui: BeautyUiState,
    nameOf: (BeautyPreset) -> String,
    onApply: (BeautyPreset) -> Unit,
    onSave: () -> Unit,
    onManage: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetPill(
                text = stringResource(R.string.beauty_save_preset),
                selected = false,
                locked = !ui.canSaveMorePresets,
                icon = { Icon(Icons.Rounded.Add, null, tint = OnGlass, modifier = Modifier.size(16.dp)) },
                onClick = onSave,
            )
            (ui.presets.builtIn + ui.presets.mine).forEach { preset ->
                PresetPill(
                    text = nameOf(preset),
                    selected = preset.id == ui.activePresetId,
                    locked = ui.isPresetLocked(preset),
                    onClick = { onApply(preset) },
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.beauty_manage_presets),
            style = MaterialTheme.typography.labelLarge,
            color = RgTheme.colors.pastelLavender,
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .clip(RoundedCornerShape(Radius.pill))
                .pressable(onClick = onManage)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun PresetPill(
    text: String,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    val accent = RgTheme.colors.accent
    val bg by animateColorAsState(if (selected) accent else ChipIdle, Motion.quick(), label = "preset")
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.14f), RoundedCornerShape(Radius.pill))
            .pressable(haptic = HapticEvent.SNAP, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = OnGlass, maxLines = 1)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            ProBadge(text = stringResource(R.string.beauty_pro))
        }
    }
}

// ------------------------------------------------------------------------------------------------ shared helpers

@Composable
internal fun currentLocale(): Locale {
    val config = LocalConfiguration.current
    return if (config.locales.isEmpty) Locale.getDefault() else config.locales[0]
}

/** "35" for regular sliders; "+12" / "−8" / "0" around the center for bipolar ones. Digits are localized. */
internal fun valueLabel(value: Int, bipolar: Boolean, locale: Locale): String {
    val text = if (!bipolar) {
        value.toString()
    } else {
        val d = value - 50
        when {
            d > 0 -> "+$d"
            d < 0 -> "−${-d}"
            else -> "0"
        }
    }
    return text.localizeDigits(locale)
}

internal fun argbOf(c: Color): Long {
    val r = (c.red * 255f).roundToInt()
    val g = (c.green * 255f).roundToInt()
    val b = (c.blue * 255f).roundToInt()
    return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

/** Resolves display names: built-in presets use localized strings, user presets their own name. */
@Composable
internal fun rememberPresetNamer(): (BeautyPreset) -> String {
    val names = mapOf(
        "preset_beauty_natural" to stringResource(R.string.beauty_preset_natural),
        "preset_beauty_glow" to stringResource(R.string.beauty_preset_glow),
        "preset_beauty_studio" to stringResource(R.string.beauty_preset_studio),
        "preset_beauty_business" to stringResource(R.string.beauty_preset_business),
        "preset_beauty_glam" to stringResource(R.string.beauty_preset_glam),
    )
    return remember(names) { { preset: BeautyPreset -> if (preset.builtIn) names[preset.name] ?: preset.name else preset.name } }
}

/** Localized names of looks: built-ins use their string resource, saved looks their own name. */
@Composable
internal fun rememberLookNamer(): (LookDef) -> String {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val config = LocalConfiguration.current
    return remember(resources, config) { { look: LookDef -> look.customName ?: resources.getString(look.nameRes) } }
}

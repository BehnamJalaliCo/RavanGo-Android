package com.ravango.feature.camera.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgSlider
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.Entitlements
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.feature.beauty.looks.LookAvatar
import com.ravango.feature.beauty.looks.LookDef
import com.ravango.feature.camera.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lenses in carousel order; index 0 is "no lens". */
internal val CarouselLenses: List<Lens?> = listOf<Lens?>(null) + Lens.entries

private val ItemSize = 56.dp
private val ItemSpacing = 12.dp
private val FavouritePink = Color(0xFFFF6F9A)

/** How long an item must stay under the ring before it counts as "recently used". */
private const val RECENT_AFTER_MS = 1_500L

/**
 * Snapchat-style lens tray around the shutter: category tabs under a carousel of round previews that scroll under a
 * fixed shutter ring. The item in the ring is live — a complete makeup look, a fun lens or a live filter — applied
 * as it snaps in (with a haptic tick) and grown to fill the ring. Tapping a side item scrolls it into the ring,
 * long-pressing it toggles a favourite, tapping the ring records ([shutter] is drawn on top; a locked Pro item in
 * the ring opens the paywall instead). Above the carousel: the item's name, a favourite heart, hints and — for
 * looks and filters — a strength slider (looks also get "Clear").
 *
 * Layers stack (one look + one lens + one filter); see [TrayCategory] for what snapping and "None" change.
 * Scale/alpha are computed in the draw phase from the list's layout info, so scrolling never recomposes items.
 */
@Composable
internal fun LensCarousel(
    applied: Lens?,
    entitlements: Entitlements,
    atlas: ImageBitmap?,
    onFocus: (Lens?) -> Unit,
    modifier: Modifier = Modifier,
    tray: LensTrayState = LensTrayState.LensesOnly,
    trayActions: LensTrayActions = LensTrayActions(),
    needsFace: Boolean = false,
    initialCategory: TrayCategory? = null,
    shutter: @Composable () -> Unit,
) {
    val categories = LensTrayLogic.categories(tray)
    var category by rememberSaveable { mutableStateOf(initialCategory ?: LensTrayLogic.defaultCategory(tray)) }
    if (category !in categories) category = categories.first()
    // Recents / favourites are snapshotted when the tab opens, so the list never shifts under the finger.
    val looksList = tray.looks?.looks
    val items = remember(category, looksList, tray.looks == null) { LensTrayLogic.items(category, tray) }
    val startIndex = remember(category, items) { LensTrayLogic.appliedIndex(items, applied, tray) }
    var focused by remember(category) { mutableStateOf(items.getOrNull(startIndex) ?: TrayItem.None) }

    val actions by rememberUpdatedState(trayActions)
    val focusLens by rememberUpdatedState(onFocus)
    val appliedLens by rememberUpdatedState(applied)
    val entitled by rememberUpdatedState(entitlements)
    val currentCategory by rememberUpdatedState(category)

    fun apply(item: TrayItem) {
        focused = item
        val locked = LensTrayLogic.locked(item, entitled)
        when (item) {
            TrayItem.None -> {
                if (LensTrayLogic.noneClearsLook(currentCategory)) actions.onClearLook()
                if (LensTrayLogic.noneClearsLens(currentCategory)) focusLens(null) else focusLens(appliedLens)
            }
            is TrayItem.Look -> {
                if (locked) actions.onClearLook() else actions.onLook(item.look)
                // Keeps the studio's "focused lens" in sync (the applied lens stays).
                focusLens(appliedLens)
            }
            is TrayItem.Fx -> focusLens(item.lens)
            is TrayItem.Filter -> {
                if (!locked) actions.onFilter(item.filter)
                focusLens(appliedLens)
            }
        }
    }

    // Recently used: an item that stays in the ring for a moment.
    LaunchedEffect(focused) {
        val item = focused
        if (item == TrayItem.None || LensTrayLogic.locked(item, entitled)) return@LaunchedEffect
        if (item is TrayItem.Filter && item.filter == LiveFilter.NONE) return@LaunchedEffect
        delay(RECENT_AFTER_MS)
        actions.onRecent(item.key)
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TrayHeader(
            item = focused,
            category = category,
            empty = items.size <= 1 && (category == TrayCategory.RECENTS || category == TrayCategory.FAVORITES),
            tray = tray,
            locked = LensTrayLogic.locked(focused, entitlements),
            needsFace = needsFace,
            actions = trayActions,
        )
        Spacer(Modifier.height(4.dp))
        key(category) {
            CarouselRow(
                items = items,
                startIndex = startIndex,
                entitlements = entitlements,
                atlas = atlas,
                favourites = tray.looks?.favourites.orEmpty(),
                focusedLocked = LensTrayLogic.locked(focused, entitlements),
                onFocus = ::apply,
                onLongPress = { item -> if (item != TrayItem.None && tray.looks != null) trayActions.onToggleFavourite(item.key) },
                onLockedShutter = { trayActions.onRequirePro(LensTrayLogic.requiredFeature(focused)) },
                shutter = shutter,
            )
        }
        if (categories.size > 1) {
            Spacer(Modifier.height(2.dp))
            CategoryTabs(categories, category, onSelect = { category = it })
        }
    }
}

@Composable
private fun CarouselRow(
    items: List<TrayItem>,
    startIndex: Int,
    entitlements: Entitlements,
    atlas: ImageBitmap?,
    favourites: List<String>,
    focusedLocked: Boolean,
    onFocus: (TrayItem) -> Unit,
    onLongPress: (TrayItem) -> Unit,
    onLockedShutter: () -> Unit,
    shutter: @Composable () -> Unit,
) {
    val haptics = rememberHaptics()
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
            items.getOrNull(index)?.let(focus)
        }
    }
    val itemPx = with(LocalDensity.current) { (ItemSize + ItemSpacing).toPx() }
    val favouriteLabel = stringResource(R.string.camera_tray_favorite_add)
    BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        val side = (maxWidth - ItemSize) / 2
        LazyRow(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(ItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                val locked = LensTrayLogic.locked(item, entitlements)
                val label = item.label()
                Box(
                    Modifier
                        .size(ItemSize)
                        .graphicsLayer {
                            val info = state.layoutInfo
                            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val d = if (visible == null) itemPx * 3 else abs(visible.offset + visible.size / 2f - mid)
                            val t = (1f - d / itemPx).coerceIn(0f, 1f)
                            val s = 0.86f + 0.28f * t
                            scaleX = s; scaleY = s
                            alpha = 0.72f + 0.28f * t
                        }
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                            selected = index == centered
                            if (item != TrayItem.None) onLongClick(label = favouriteLabel) { onLongPress(item); true }
                        }
                        .pointerInput(item) {
                            detectTapGestures(
                                onTap = {
                                    haptics.perform(HapticEvent.SNAP)
                                    scope.launch { state.animateScrollToItem(index) }
                                },
                                onLongPress = {
                                    if (item != TrayItem.None) {
                                        haptics.perform(HapticEvent.LONG_PRESS)
                                        onLongPress(item)
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(ItemSize)
                            .shadow(6.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                    ) {
                        TrayGlyph(item, atlas, Modifier.fillMaxSize())
                    }
                    if (locked) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 2.dp)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp))
                        }
                    }
                    if (item.key in favourites) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(0xE6121019)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Favorite, null, tint = FavouritePink, modifier = Modifier.size(10.dp))
                        }
                    }
                }
            }
        }
        shutter()
        if (focusedLocked) {
            // A locked Pro item in the ring: the shutter offers the upgrade instead of recording.
            Box(
                Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onLockedShutter() }) },
            )
        }
    }
}

@Composable
private fun TrayGlyph(item: TrayItem, atlas: ImageBitmap?, modifier: Modifier) {
    when (item) {
        TrayItem.None -> LensGlyph(null, atlas, modifier)
        is TrayItem.Look -> LookAvatar(item.look, modifier)
        is TrayItem.Fx -> LensGlyph(item.lens, atlas, modifier)
        is TrayItem.Filter -> FilterSwatch(item.filter, modifier)
    }
}

@Composable
private fun TrayItem.label(): String = when (this) {
    TrayItem.None -> stringResource(R.string.camera_tray_none)
    is TrayItem.Look -> lookName(look)
    is TrayItem.Fx -> lens.label()
    is TrayItem.Filter -> filter.label()
}

@Composable
private fun lookName(look: LookDef): String = look.customName ?: LocalContext.current.resources.getString(look.nameRes)

/** Name + favourite heart + hint over the carousel; the strength slider for looks and filters. */
@Composable
private fun TrayHeader(
    item: TrayItem,
    category: TrayCategory,
    empty: Boolean,
    tray: LensTrayState,
    locked: Boolean,
    needsFace: Boolean,
    actions: LensTrayActions,
) {
    val activeLook = tray.looks?.active
    AnimatedContent(
        targetState = item.key to empty,
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith fadeOut(tween(120)) },
        label = "trayHeader",
        modifier = Modifier.fillMaxWidth(),
    ) { (_, isEmpty) ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (isEmpty) {
                Text(
                    stringResource(if (category == TrayCategory.RECENTS) R.string.camera_tray_empty_recents else R.string.camera_tray_empty_favorites),
                    style = MaterialTheme.typography.labelLarge.overPreview(),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.label(),
                    style = MaterialTheme.typography.titleMedium.overPreview(),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                if (locked) {
                    Spacer(Modifier.width(8.dp))
                    // Tappable, like Snapchat's "Get Lens+" pill.
                    Box(Modifier.clip(RoundedCornerShape(50)).pressable(haptic = HapticEvent.TAP) { actions.onRequirePro(LensTrayLogic.requiredFeature(item)) }) {
                        ProBadge(text = stringResource(R.string.camera_pro_badge))
                    }
                }
                if (item != TrayItem.None && tray.looks != null) {
                    Spacer(Modifier.width(6.dp))
                    FavouriteToggle(tray.looks.isFavourite(item.key)) { actions.onToggleFavourite(item.key) }
                }
            }
            val hint = when {
                locked && item is TrayItem.Look -> stringResource(R.string.camera_tray_hint_look_pro)
                locked -> stringResource(R.string.camera_lens_hint_pro)
                item is TrayItem.Fx && needsFace -> stringResource(R.string.camera_lens_hint_face)
                item is TrayItem.Fx && item.lens.hasTrigger -> stringResource(R.string.camera_lens_hint_mouth)
                item is TrayItem.Fx && activeLook != null -> stringResource(R.string.camera_tray_look_active, lookName(activeLook))
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium.overPreview(),
                    color = if (locked) Palette.Butter300 else Color.White.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                )
            }
            val lookApplied = item is TrayItem.Look && !locked && activeLook?.id == item.look.id
            val filterApplied = item is TrayItem.Filter && !locked && item.filter != LiveFilter.NONE && tray.filter == item.filter
            AnimatedVisibility(lookApplied || filterApplied, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                StrengthRow(
                    value = if (lookApplied) tray.looks?.intensity ?: 100 else tray.filterIntensity,
                    label = stringResource(if (lookApplied) R.string.camera_tray_look_strength else R.string.camera_filter_intensity),
                    onChange = if (lookApplied) actions.onLookIntensity else actions.onFilterIntensity,
                    onClear = if (lookApplied) actions.onClearLook else null,
                )
            }
        }
    }
}

@Composable
private fun FavouriteToggle(favourite: Boolean, onToggle: () -> Unit) {
    val tint by animateColorAsState(if (favourite) FavouritePink else Color.White, Motion.quick(), label = "fav")
    val label = stringResource(if (favourite) R.string.camera_tray_favorite_remove else R.string.camera_tray_favorite_add)
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(StudioGlass.Fill)
            .semantics { contentDescription = label }
            .pressable(haptic = HapticEvent.TOGGLE_ON, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (favourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Compact glass strength slider (0–100) with an optional Clear chip. */
@Composable
private fun StrengthRow(value: Int, label: String, onChange: (Int) -> Unit, onClear: (() -> Unit)?) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(StudioGlass.FillStrong)
            .border(1.dp, StudioGlass.Stroke, RoundedCornerShape(24.dp))
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClear != null) {
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .pressable(haptic = HapticEvent.TAP, onClick = onClear)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.camera_tray_clear), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            RgSlider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 0f..100f,
                trackColor = Color.White.copy(alpha = 0.18f),
                contentDescription = label,
            )
        }
        Text(
            value.toString().localizeDigits(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
    }
}

/** Text tabs under the carousel (Snapchat-style): the selected one is white with a dot. */
@Composable
private fun CategoryTabs(categories: List<TrayCategory>, selected: TrayCategory, onSelect: (TrayCategory) -> Unit) {
    val state = rememberLazyListState()
    // Keep the selected tab centred (instantly on open, animated afterwards).
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        val i = categories.indexOf(selected)
        if (i < 0) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (state.layoutInfo.visibleItemsInfo.none { it.index == i }) state.scrollToItem(i)
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == i } ?: return@LaunchedEffect
        val delta = (item.offset + item.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2).toFloat()
        if (first) state.scrollBy(delta) else state.animateScrollBy(delta)
        first = false
    }
    val description = stringResource(R.string.camera_tray_categories)
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        itemsIndexed(categories, key = { _, c -> c.name }) { _, c ->
            val isSelected = c == selected
            val color by animateColorAsState(if (isSelected) Color.White else Color.White.copy(alpha = 0.7f), Motion.quick(), label = "tab")
            Column(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .semantics { this.selected = isSelected }
                    .pressable(haptic = HapticEvent.SNAP) { onSelect(c) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(c.label),
                    style = MaterialTheme.typography.labelLarge.overPreview(),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color.White else Color.Transparent),
                )
            }
        }
    }
}

/**
 * Lens name toast above the carousel: the focused lens name fades in with a hint (show your face / open your mouth)
 * or a Pro note for locked lenses. (Outside the tray: the applied lens.)
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

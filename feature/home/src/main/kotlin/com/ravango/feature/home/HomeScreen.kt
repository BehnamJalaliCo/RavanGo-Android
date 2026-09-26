package com.ravango.feature.home

import com.ravango.core.designsystem.theme.BalancedLines
import com.ravango.core.designsystem.component.RgSpinner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.Subject
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ravango.core.common.format.formatDuration
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.GradientBackground
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.SectionHeader
import com.ravango.core.designsystem.component.ShimmerBox
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.component.softShadow
import com.ravango.core.designsystem.theme.ButtonText
import com.ravango.core.designsystem.theme.Dimens
import com.ravango.core.designsystem.theme.Elevation
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.TabularNumbers
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.Plan
import com.ravango.core.model.ProjectStatus
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.model.Script
import com.ravango.core.model.service.SyncPhase
import com.ravango.core.navigation.AccountRoute
import com.ravango.core.navigation.AiStudioRoute
import com.ravango.core.navigation.CameraRoute
import com.ravango.core.navigation.CloudRoute
import com.ravango.core.navigation.EditorRoute
import com.ravango.core.navigation.PaywallRoute
import com.ravango.core.navigation.ProjectsRoute
import com.ravango.core.navigation.ScriptEditorRoute
import com.ravango.core.navigation.ScriptsRoute
import com.ravango.core.navigation.TeleprompterRoute
import com.ravango.core.navigation.TemplatesRoute
import com.ravango.core.ui.messageRes
import com.ravango.feature.home.common.AspectFramePreview
import com.ravango.feature.home.common.ProjectThumbnail
import com.ravango.feature.home.common.TemplateSheet
import com.ravango.feature.home.common.aspectLabel
import com.ravango.feature.home.common.currentLocale
import com.ravango.feature.home.common.formatRelativeTime
import com.ravango.feature.home.common.formatSuggestedDuration
import com.ravango.feature.home.common.templateName
import java.util.Calendar
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.designsystem.component.RavanGoLogo
import com.ravango.core.designsystem.component.RavanGoLogoStyle
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.SkeletonCard
import com.ravango.core.designsystem.component.SkeletonLine
import com.ravango.core.designsystem.motion.RgEnter
import com.ravango.core.designsystem.motion.RgExit
import com.ravango.core.designsystem.motion.SharedKeys
import com.ravango.core.designsystem.motion.rememberEntranceActive
import com.ravango.core.designsystem.motion.rgSharedBounds
import com.ravango.core.designsystem.motion.staggeredEntrance
import com.ravango.core.designsystem.motion.staggeredSlideIn
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.RgTone

private const val MAX_IMPORT_ITEMS = 30

@Composable
fun HomeDestination(
    onNavigate: (Any) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val context = LocalContext.current
    val fallbackTitle = stringResource(R.string.home_imported_title)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_ITEMS)) { uris ->
        viewModel.importMedia(uris, fallbackTitle)
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is HomeEvent.Navigate -> onNavigate(event.route)
                is HomeEvent.Error -> snackbar.showSnackbar(context.getString(event.kind.messageRes()))
            }
        }
    }

    // Refresh relative times and the greeting whenever the screen comes back.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        now = System.currentTimeMillis()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            state = state,
            now = now,
            onNavigate = onNavigate,
            onTeleprompter = {
                // With no scripts there is nothing to pick: go straight to writing one.
                onNavigate(if (state.scriptCount == 0) ScriptEditorRoute() else ScriptsRoute(pickForPrompter = true))
            },
            onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onTemplate = viewModel::selectTemplate,
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        ImportingOverlay(state.importing)
    }

    state.selectedTemplate?.let { template ->
        TemplateSheet(
            template = template,
            busyMode = state.templateBusy,
            captionsIncluded = state.captionsIncluded,
            onStart = viewModel::startTemplate,
            onDismiss = { viewModel.selectTemplate(null) },
        )
    }
}

/** Stateless home screen (rendered by screenshot tests with sample data). [now] drives the greeting and relative times. */
@Composable
internal fun HomeContent(
    state: HomeUiState,
    now: Long,
    onNavigate: (Any) -> Unit,
    onTeleprompter: () -> Unit,
    onImport: () -> Unit,
    onTemplate: (ProjectTemplate) -> Unit,
) {
    val entrance = rememberEntranceActive(ready = !state.loading)
    val listState = rememberLazyListState()
    // The floating record button only appears once the hero (the screen's primary action) has scrolled away,
    // so there is never more than one "record" call to action on screen.
    val heroGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }

    GradientBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            // Entrance order is fixed per section (computed here, not inside item content, so recomposition is stable).
            var section = 0
            item(key = "header") {
                HomeHeader(state, now, onNavigate, Modifier.staggeredEntrance(0, entrance))
            }
            val heroIndex = ++section
            item(key = "hero") {
                HeroCard(
                    onRecord = { onNavigate(CameraRoute()) },
                    onAudioOnly = { onNavigate(CameraRoute(audioOnly = true)) },
                    modifier = Modifier.padding(top = Spacing.xl).staggeredEntrance(heroIndex, entrance),
                )
            }
            val actionsIndex = ++section
            item(key = "actions") {
                QuickActions(onNavigate, onTeleprompter, onImport, entrance, actionsIndex, Modifier.padding(top = Spacing.xxl))
            }
            state.continueItem?.let { item ->
                val index = ++section
                item(key = "continue") {
                    ContinueCard(item, now, { onNavigate(EditorRoute(item.item.project.id)) }, Modifier.padding(top = Spacing.xxl).staggeredEntrance(index, entrance))
                }
            }
            if (state.isNewUser) {
                val index = ++section
                item(key = "welcome") {
                    WelcomeCard(
                        onWrite = { onNavigate(ScriptEditorRoute()) },
                        onRecord = { onNavigate(CameraRoute()) },
                        modifier = Modifier.padding(top = Spacing.xxl).staggeredEntrance(index, entrance),
                    )
                }
            }
            if (state.loading) {
                item(key = "loading") { LoadingProjects(Modifier.padding(top = Spacing.xxl)) }
            }
            if (state.recentProjects.isNotEmpty()) {
                val index = ++section
                item(key = "projects") {
                    RecentProjects(state.recentProjects, now, onNavigate, entrance, Modifier.padding(top = Spacing.xl).staggeredEntrance(index, entrance))
                }
            }
            if (state.recentScripts.isNotEmpty()) {
                val index = ++section
                item(key = "scripts") {
                    RecentScripts(state.recentScripts, state.wordsPerMinute, onNavigate, Modifier.padding(top = Spacing.xl).staggeredEntrance(index, entrance))
                }
            }
            val templatesIndex = ++section
            item(key = "templates") {
                TemplatesTeaser(state.templates, onTemplate, { onNavigate(TemplatesRoute) }, Modifier.padding(top = Spacing.xl).staggeredEntrance(templatesIndex, entrance))
            }
        }
        AnimatedVisibility(
            visible = heroGone,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Spacing.lg),
            enter = RgEnter.pop(),
            exit = RgExit.pop(),
        ) {
            RecordFab(onClick = { onNavigate(CameraRoute()) })
        }
    }
}

// region Header

@Composable
private fun HomeHeader(state: HomeUiState, now: Long, onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
    val greeting = stringResource(
        when (dayPartFor(hour)) {
            DayPart.MORNING -> R.string.home_greeting_morning
            DayPart.AFTERNOON -> R.string.home_greeting_afternoon
            DayPart.EVENING -> R.string.home_greeting_evening
            DayPart.NIGHT -> R.string.home_greeting_night
        },
    )
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.md)) {
        // Brand bar: the official lockup on the start side, plan + account on the end side (all 40dp tall).
        Row(Modifier.fillMaxWidth().height(Dimens.controlMedium), verticalAlignment = Alignment.CenterVertically) {
            RavanGoLogo(style = RavanGoLogoStyle.HORIZONTAL, height = 30.dp, contentDescription = stringResource(R.string.home_app_name))
            Spacer(Modifier.weight(1f))
            if (state.isPaid) {
                PlanBadge(stringResource(if (state.plan == Plan.LIFETIME) R.string.home_plan_lifetime else R.string.home_plan_pro))
            } else {
                GoProChip { onNavigate(PaywallRoute(source = "home")) }
            }
            Spacer(Modifier.width(Spacing.sm))
            Avatar(state.avatarUrl, state.userName, state.syncPhase, onClick = { onNavigate(AccountRoute) })
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            state.userName?.let { stringResource(R.string.home_greeting_with_name, greeting, it) } ?: greeting,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Spacing.xxs))
        Text(stringResource(R.string.home_headline), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
    }
}

/** Upsell chip: gold like every Pro surface, dark ink label. */
@Composable
private fun GoProChip(onClick: () -> Unit) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        Modifier
            .height(Dimens.controlSmall)
            .softShadow(6.dp, shape, Palette.Gold400)
            .clip(shape)
            .background(colors.proGradient)
            .pressable(shape = shape, haptic = HapticEvent.TAP, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = colors.onPro, modifier = Modifier.size(Dimens.iconSmall))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.home_go_pro), style = ButtonText.small, color = colors.onPro, maxLines = 1)
    }
}

@Composable
private fun PlanBadge(text: String) {
    val colors = RgTheme.colors
    Row(
        Modifier.height(Dimens.controlSmall).clip(RoundedCornerShape(Radius.pill)).background(colors.proGradient).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WorkspacePremium, null, tint = colors.onPro, modifier = Modifier.size(Dimens.iconSmall))
        Spacer(Modifier.width(6.dp))
        Text(text, style = ButtonText.small, color = colors.onPro, maxLines = 1)
    }
}

@Composable
private fun Avatar(url: String?, name: String?, syncPhase: SyncPhase, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val description = stringResource(R.string.home_account)
    Box(Modifier.size(Dimens.controlMedium)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(colors.brandGradientSoft)
                .border(1.5.dp, colors.surface, CircleShape)
                .semantics { contentDescription = description }
                .pressable(shape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val initial = name?.firstOrNull()?.uppercase()
            if (initial != null) {
                Text(initial, style = MaterialTheme.typography.titleMedium, color = colors.onAccentSoft)
            } else {
                Icon(Icons.Rounded.Person, null, tint = colors.onAccentSoft, modifier = Modifier.size(Dimens.iconMedium + 2.dp))
            }
            if (!url.isNullOrBlank()) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            }
        }
        if (syncPhase != SyncPhase.DISABLED) SyncDot(syncPhase, Modifier.align(Alignment.BottomEnd))
    }
}

/** Sync status as a small dot on the avatar (pulses while syncing). */
@Composable
private fun SyncDot(phase: SyncPhase, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val (color, label) = when (phase) {
        SyncPhase.SYNCING -> colors.accent to R.string.home_sync_syncing
        SyncPhase.IDLE -> colors.success to R.string.home_sync_idle
        SyncPhase.OFFLINE -> colors.warning to R.string.home_sync_offline
        SyncPhase.ERROR -> colors.danger to R.string.home_sync_error
        SyncPhase.DISABLED -> colors.textTertiary to R.string.home_sync_idle
    }
    val description = stringResource(label)
    val pulsing = phase == SyncPhase.SYNCING && !RgTheme.reduceMotion
    val pulse = if (pulsing) {
        val t = rememberInfiniteTransition(label = "sync")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart), label = "p")
    } else {
        null
    }
    val ring = colors.background
    Box(
        modifier
            .size(14.dp)
            .semantics { contentDescription = description }
            .drawBehind {
                val p = pulse?.value
                if (p != null) drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius = size.minDimension / 2 * (0.6f + p * 0.6f))
                drawCircle(ring, radius = 6.dp.toPx())
                drawCircle(color, radius = 4.dp.toPx())
            },
    )
}

// endregion

// region Hero

/**
 * The screen's primary call to action. Brand-blue gradient echoing the logo: a large soft play triangle sits behind
 * the content, a slow sheen passes over it, and the record button carries the only white fill on the card.
 */
@Composable
private fun HeroCard(onRecord: () -> Unit, onAudioOnly: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.xxl)
    val shimmer = if (RgTheme.reduceMotion) {
        null
    } else {
        val t = rememberInfiniteTransition(label = "hero")
        t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(4_200, delayMillis = 1_400, easing = LinearEasing), RepeatMode.Restart), label = "shine")
    }
    Box(
        modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .pressable(shape = shape, haptic = HapticEvent.CONFIRM, pressScale = Motion.PressScaleLarge, onClick = onRecord)
            .softShadow(Elevation.high, shape, colors.accentGlow)
            .clip(shape)
            .background(colors.ctaGradient)
            .drawWithCache {
                val w = size.width
                val h = size.height
                // Two play triangles (the logo's counter) as a soft watermark on the side opposite the text. The
                // symbol itself always points right, as media controls do in every locale.
                val rtl = layoutDirection == LayoutDirection.Rtl
                fun play(cxFraction: Float, cy: Float, r: Float) = Path().apply {
                    val cx = w * (if (rtl) 1f - cxFraction else cxFraction)
                    moveTo(cx - r * 0.5f, cy - r * 0.62f)
                    lineTo(cx + r * 0.58f, cy)
                    lineTo(cx - r * 0.5f, cy + r * 0.62f)
                    close()
                }
                val big = play(0.86f, h * 0.36f, h * 0.5f)
                val small = play(0.64f, h * 0.14f, h * 0.14f)
                val sheenWidth = w * 0.5f
                val sheen = Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent), start = Offset.Zero, end = Offset(sheenWidth, h))
                val glow = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(if (rtl) w * 0.9f else w * 0.1f, 0f), radius = w * 0.7f)
                onDrawWithContent {
                    drawRect(glow)
                    drawPath(big, Color.White.copy(alpha = 0.12f))
                    drawPath(small, Color.White.copy(alpha = 0.16f))
                    drawContent()
                    val x = shimmer?.value ?: return@onDrawWithContent
                    translate(left = w * x - sheenWidth / 2) { drawRect(sheen, size = Size(sheenWidth, h)) }
                }
            },
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.xl)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(Radius.md)).background(Color.White.copy(alpha = 0.18f)).border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(Radius.md)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Videocam, null, tint = Color.White, modifier = Modifier.size(Dimens.icon)) }
            Spacer(Modifier.height(Spacing.lg))
            Text(stringResource(R.string.home_new_recording), style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                stringResource(R.string.home_new_recording_body),
                style = MaterialTheme.typography.bodyMedium.merge(BalancedLines),
                color = Color.White.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(Spacing.xl))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val pill = RoundedCornerShape(Radius.pill)
                Row(
                    Modifier
                        .height(Dimens.controlMedium)
                        .clip(pill)
                        .background(Color.White)
                        .pressable(shape = pill, haptic = HapticEvent.CONFIRM, onClick = onRecord)
                        .padding(start = 14.dp, end = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors.record))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.home_start_recording), style = ButtonText.medium, color = Palette.Blue700, maxLines = 1)
                }
                Row(
                    Modifier
                        .height(Dimens.controlMedium)
                        .clip(pill)
                        .background(Color.White.copy(alpha = 0.16f))
                        .border(1.dp, Color.White.copy(alpha = 0.36f), pill)
                        .pressable(shape = pill, onClick = onAudioOnly)
                        .padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(Dimens.iconMedium - 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_audio_only), style = ButtonText.medium, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

// endregion

// region Quick actions

private data class QuickAction(val label: String, val icon: ImageVector, val tone: RgTone, val onClick: () -> Unit)

@Composable
private fun QuickActions(
    onNavigate: (Any) -> Unit,
    onTeleprompter: () -> Unit,
    onImport: () -> Unit,
    entrance: Boolean,
    firstIndex: Int,
    modifier: Modifier = Modifier,
) {
    val tones = RgTheme.colors.tones
    val actions = listOf(
        QuickAction(stringResource(R.string.home_action_teleprompter), Icons.AutoMirrored.Rounded.Subject, tones.sky, onTeleprompter),
        QuickAction(stringResource(R.string.home_action_scripts), Icons.Rounded.EditNote, tones.periwinkle) { onNavigate(ScriptsRoute()) },
        QuickAction(stringResource(R.string.home_action_ai), Icons.Rounded.AutoAwesome, tones.lilac) { onNavigate(AiStudioRoute()) },
        QuickAction(stringResource(R.string.home_action_editor), Icons.Rounded.MovieCreation, tones.blush, onImport),
        QuickAction(stringResource(R.string.home_action_projects), Icons.Rounded.Folder, tones.mint) { onNavigate(ProjectsRoute()) },
        QuickAction(stringResource(R.string.home_action_drafts), Icons.Rounded.Description, tones.butter) { onNavigate(ProjectsRoute(tab = 1)) },
        QuickAction(stringResource(R.string.home_action_templates), Icons.Rounded.ViewQuilt, tones.peach) { onNavigate(TemplatesRoute) },
        QuickAction(stringResource(R.string.home_action_cloud), Icons.Rounded.Cloud, tones.sky) { onNavigate(CloudRoute) },
    )
    Column(modifier.padding(horizontal = Spacing.gutter - Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        actions.chunked(4).forEachIndexed { row, items ->
            Row {
                items.forEachIndexed { col, action ->
                    // Tiles cascade in reading order (the row index leads, so the second row follows the first).
                    QuickTile(action, Modifier.weight(1f).staggeredEntrance(firstIndex + row * 2 + col, entrance, rise = 16.dp))
                }
            }
        }
    }
}

/** App-launcher style tool: a pastel squircle with a toned icon and a label below — no card, lots of air. */
@Composable
private fun QuickTile(action: QuickAction, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.md))
            .pressable(shape = RoundedCornerShape(Radius.md), onClick = action.onClick)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(shape)
                .background(action.tone.container)
                .border(1.dp, Color.White.copy(alpha = if (RgTheme.colors.isDark) 0.05f else 0.6f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, null, tint = action.tone.content, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(Spacing.sm))
        // Shrinks slightly instead of truncating on narrow phones ("هوش مصنوعی" at 360dp).
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            color = RgTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// endregion

// region Sections

@Composable
private fun ContinueCard(item: ContinueItem, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.xl)
    GlassSurface(
        modifier.padding(horizontal = Spacing.gutter).fillMaxWidth().clip(shape).pressable(shape = shape, pressScale = Motion.PressScaleLarge, onClick = onClick),
        shape = shape,
        contentPadding = PaddingValues(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectThumbnail(
                item.item.thumbnail,
                accentSeed = item.item.project.id,
                audioOnly = item.item.audioOnly,
                modifier = Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(Radius.md)),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_continue_label), style = MaterialTheme.typography.labelMedium, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.item.project.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.home_continue_edited, formatRelativeTime(item.editedAt, now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Box(Modifier.size(Dimens.controlMedium).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.home_continue_action), tint = colors.onAccentSoft)
            }
        }
    }
}

@Composable
private fun WelcomeCard(onWrite: () -> Unit, onRecord: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    RgCard(
        modifier.padding(horizontal = Spacing.gutter).fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xl),
        color = colors.surface.copy(alpha = 0.9f),
        contentPadding = PaddingValues(Spacing.xl),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.md)).background(colors.tones.periwinkle.container), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.EditNote, null, tint = colors.tones.periwinkle.content, modifier = Modifier.size(Dimens.icon))
        }
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(stringResource(R.string.home_welcome_body), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.lg))
        // The hero above is the screen's primary action; here writing is secondary and recording a quiet link.
        RgSecondaryButton(stringResource(R.string.home_welcome_write), onWrite, icon = Icons.Rounded.EditNote, size = RgButtonSize.MEDIUM, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.xs))
        RgTextButton(stringResource(R.string.home_welcome_record), onRecord, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun LoadingProjects(modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonLine(Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.md), widthFraction = 0.35f, height = 16.dp)
        Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            repeat(3) { SkeletonCard(Modifier.width(PROJECT_CARD_WIDTH), thumbnailAspect = PROJECT_CARD_ASPECT) }
        }
    }
}

private val PROJECT_CARD_WIDTH = 152.dp
private const val PROJECT_CARD_ASPECT = 0.78f

@Composable
private fun RecentProjects(items: List<HomeProject>, now: Long, onNavigate: (Any) -> Unit, entrance: Boolean, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier) {
        SectionHeader(stringResource(R.string.home_recent_projects), action = stringResource(R.string.home_see_all), onAction = { onNavigate(ProjectsRoute()) })
        Spacer(Modifier.height(Spacing.xs))
        LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            itemsIndexed(items, key = { _, it -> it.project.id }) { index, item ->
                ProjectCard(item, now, Modifier.staggeredSlideIn(index, entrance, rtl)) { onNavigate(EditorRoute(item.project.id)) }
            }
        }
    }
}

@Composable
private fun ProjectCard(item: HomeProject, now: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    val project = item.project
    val shape = RoundedCornerShape(Radius.lg)
    Column(modifier.width(PROJECT_CARD_WIDTH).pressable(onClick = onClick)) {
        // The thumbnail morphs into the editor's preview (container transform).
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(PROJECT_CARD_ASPECT)
                .rgSharedBounds(SharedKeys.project(project.id), shape)
                .clip(shape)
                .border(1.dp, colors.outline, shape),
        ) {
            ProjectThumbnail(item.thumbnail, accentSeed = project.id, audioOnly = item.audioOnly, modifier = Modifier.fillMaxSize())
            // Bottom scrim keeps the corner pills legible on bright thumbnails.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f))))
            DarkPill(statusText(project.status), Modifier.align(Alignment.TopStart).padding(Spacing.sm), dot = statusColor(project.status))
            if (project.durationUs > 0) {
                DarkPill(formatDuration(project.durationUs, locale), Modifier.align(Alignment.BottomEnd).padding(Spacing.sm))
            }
            DarkPill(aspectLabel(project.aspectRatio, locale), Modifier.align(Alignment.BottomStart).padding(Spacing.sm))
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(project.title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.xs))
        Text(
            formatRelativeTime(project.updatedAt, now),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
    }
}

@Composable
private fun statusText(status: ProjectStatus): String = stringResource(
    when (status) {
        ProjectStatus.RECORDED -> R.string.home_status_recorded
        ProjectStatus.EDITING -> R.string.home_status_editing
        ProjectStatus.EXPORTED -> R.string.home_status_exported
    },
)

/** Status dot colors tuned to read on the dark frosted pill. */
@Composable
private fun statusColor(status: ProjectStatus): Color = when (status) {
    ProjectStatus.RECORDED -> Palette.Butter400
    ProjectStatus.EDITING -> Palette.Blue300
    ProjectStatus.EXPORTED -> Palette.Mint400
}

/** Frosted metadata pill over thumbnails (fixed 22dp so pills in opposite corners share a baseline). */
@Composable
private fun DarkPill(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(Radius.pill)).background(Palette.Ink.copy(alpha = 0.5f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = Color.White, maxLines = 1)
    }
}

@Composable
private fun RecentScripts(scripts: List<Script>, wpm: Int, onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    Column(modifier) {
        SectionHeader(stringResource(R.string.home_recent_scripts), action = stringResource(R.string.home_see_all), onAction = { onNavigate(ScriptsRoute()) })
        Spacer(Modifier.height(Spacing.xs))
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            scripts.forEach { script ->
                val words = script.wordCount
                val seconds = readTimeSeconds(words, wpm)
                val minutes = (seconds + 59) / 60
                val wordsText = pluralStringResource(R.plurals.home_words, words, words.toString().localizeDigits(locale))
                val readText = if (seconds < 60) stringResource(R.string.home_read_under_minute) else pluralStringResource(R.plurals.home_read_minutes, minutes, minutes.toString().localizeDigits(locale))
                val shape = RoundedCornerShape(Radius.lg)
                RgCard(
                    // The card morphs into the script editor.
                    Modifier.fillMaxWidth().rgSharedBounds(SharedKeys.script(script.id), shape),
                    shape = shape,
                    onClick = { onNavigate(ScriptEditorRoute(scriptId = script.id)) },
                    contentPadding = PaddingValues(start = Spacing.md, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tone = colors.tones.periwinkle
                        Box(Modifier.size(Dimens.listIcon).clip(RoundedCornerShape(Radius.sm)).background(tone.container), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Description, null, tint = tone.content, modifier = Modifier.size(Dimens.iconMedium + 2.dp))
                        }
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                script.title.ifBlank { stringResource(R.string.home_untitled_script) },
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(wordsText + RgTheme.metaSeparator + readText, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(Spacing.sm))
                        // Quiet secondary action: the card itself opens the script.
                        RgIconButton(
                            Icons.Rounded.PlayArrow,
                            stringResource(R.string.home_play_prompter),
                            { onNavigate(TeleprompterRoute(script.id)) },
                            container = colors.accentSoft,
                            tint = colors.onAccentSoft,
                            size = Dimens.controlMedium,
                            iconSize = Dimens.iconMedium + 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplatesTeaser(templates: List<ProjectTemplate>, onTemplate: (ProjectTemplate) -> Unit, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.home_templates), action = stringResource(R.string.home_see_all), onAction = onSeeAll)
        Spacer(Modifier.height(Spacing.xs))
        LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            itemsIndexed(templates, key = { _, t -> t.id }) { _, template ->
                TemplateCard(template) { onTemplate(template) }
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val accent = Color(template.accentArgb)
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        Modifier
            .width(148.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .pressable(shape = shape, onClick = onClick),
    ) {
        Box(
            Modifier
                .padding(Spacing.xs)
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = if (colors.isDark) 0.45f else 0.55f), accent.copy(alpha = 0.12f)))),
        ) {
            AspectFramePreview(template.aspectRatio, accent, template.autoCaptions, Modifier.fillMaxSize().padding(Spacing.md))
        }
        Column(Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.xs, bottom = Spacing.md)) {
            Text(templateName(template), style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSuggestedDuration(template.suggestedDurationSec), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
    }
}

// endregion

// region Floating record button & overlays

@Composable
private fun RecordFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val description = stringResource(R.string.home_record)
    val pulse = if (RgTheme.reduceMotion) {
        null
    } else {
        val t = rememberInfiniteTransition(label = "fab")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    }
    Box(
        modifier
            .size(80.dp)
            .drawBehind {
                val p = pulse?.value ?: return@drawBehind
                drawCircle(colors.record.copy(alpha = (1f - p) * 0.3f), radius = size.minDimension / 2 * (0.78f + 0.22f * p))
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .shadow(16.dp, CircleShape, ambientColor = colors.record.copy(alpha = 0.5f), spotColor = colors.record.copy(alpha = 0.5f))
                .clip(CircleShape)
                .background(colors.recordGradient)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                .semantics { contentDescription = description }
                .pressable(shape = CircleShape, haptic = HapticEvent.RECORD_START, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Videocam, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ImportingOverlay(visible: Boolean) {
    AnimatedVisibility(visible, enter = RgEnter.fade(), exit = RgExit.fade()) {
        // Swallows touches so nothing underneath is triggered while media is prepared.
        Box(Modifier.fillMaxSize().background(RgTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
            GlassSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RgSpinner(Modifier.size(22.dp), color = RgTheme.colors.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.home_importing), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                }
            }
        }
    }
}

// endregion

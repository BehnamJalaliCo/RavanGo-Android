package com.ravango.feature.home

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
import com.ravango.feature.home.common.rememberEntranceActive
import com.ravango.feature.home.common.staggeredEntrance
import com.ravango.feature.home.common.templateName
import java.util.Calendar

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

    GradientBackground {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
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
                    modifier = Modifier.staggeredEntrance(heroIndex, entrance),
                )
            }
            val actionsIndex = ++section
            item(key = "actions") {
                QuickActions(state, onNavigate, onTeleprompter, onImport, Modifier.staggeredEntrance(actionsIndex, entrance))
            }
            if (state.loading) {
                item(key = "loading") {
                    Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        repeat(3) { ShimmerBox(Modifier.size(width = 148.dp, height = 188.dp), RoundedCornerShape(Radius.lg)) }
                    }
                }
            }
            state.continueItem?.let { item ->
                val index = ++section
                item(key = "continue") {
                    ContinueCard(item, now, { onNavigate(EditorRoute(item.item.project.id)) }, Modifier.staggeredEntrance(index, entrance))
                }
            }
            if (state.isNewUser) {
                val index = ++section
                item(key = "welcome") {
                    WelcomeCard(
                        onWrite = { onNavigate(ScriptEditorRoute()) },
                        onRecord = { onNavigate(CameraRoute()) },
                        modifier = Modifier.staggeredEntrance(index, entrance),
                    )
                }
            }
            if (state.recentProjects.isNotEmpty()) {
                val index = ++section
                item(key = "projects") {
                    RecentProjects(state.recentProjects, now, onNavigate, Modifier.staggeredEntrance(index, entrance))
                }
            }
            if (state.recentScripts.isNotEmpty()) {
                val index = ++section
                item(key = "scripts") {
                    RecentScripts(state.recentScripts, state.wordsPerMinute, onNavigate, Modifier.staggeredEntrance(index, entrance))
                }
            }
            val templatesIndex = ++section
            item(key = "templates") {
                TemplatesTeaser(state.templates, onTemplate, { onNavigate(TemplatesRoute) }, Modifier.staggeredEntrance(templatesIndex, entrance))
            }
        }
        RecordFab(
            onClick = { onNavigate(CameraRoute()) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Spacing.lg),
        )
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
    val name = state.userName ?: stringResource(R.string.home_app_name)
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(greeting, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (state.syncPhase != SyncPhase.DISABLED) {
                    Spacer(Modifier.width(Spacing.sm))
                    SyncDot(state.syncPhase)
                }
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        if (state.isPaid) {
            ProBadge(text = stringResource(if (state.plan == Plan.LIFETIME) R.string.home_plan_lifetime else R.string.home_plan_pro))
        } else {
            GoProChip { onNavigate(PaywallRoute(source = "home")) }
        }
        Spacer(Modifier.width(Spacing.sm))
        Avatar(state.avatarUrl, state.userName, onClick = { onNavigate(AccountRoute) })
    }
}

@Composable
private fun GoProChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        Modifier
            .height(Dimens.controlSmall)
            .softShadow(6.dp, shape, Palette.Rose500)
            .clip(shape)
            .background(RgTheme.colors.proGradient)
            .pressable(shape = shape, haptic = HapticEvent.TAP, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(Dimens.iconSmall))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.home_go_pro), style = ButtonText.small, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Avatar(url: String?, name: String?, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val description = stringResource(R.string.home_account)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.brandGradientSoft)
            .border(1.5.dp, colors.surface, CircleShape)
            .semantics { contentDescription = description }
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name?.firstOrNull()?.uppercase()
        if (initial != null) {
            Text(initial, style = MaterialTheme.typography.titleMedium, color = colors.accent)
        } else {
            Icon(Icons.Rounded.Person, null, tint = colors.accent)
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        }
    }
}

@Composable
private fun SyncDot(phase: SyncPhase) {
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
    Box(
        Modifier
            .size(18.dp)
            .semantics { contentDescription = description }
            .drawBehind {
                val p = pulse?.value
                if (p != null) drawCircle(color.copy(alpha = (1f - p) * 0.45f), radius = size.minDimension / 2 * (0.5f + p * 0.5f))
                drawCircle(color, radius = 4.dp.toPx())
            },
    )
}

// endregion

// region Hero

@Composable
private fun HeroCard(onRecord: () -> Unit, onAudioOnly: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val shape = RoundedCornerShape(Radius.xxl)
    val shimmer = if (RgTheme.reduceMotion) {
        null
    } else {
        val t = rememberInfiniteTransition(label = "hero")
        t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(3_400, easing = LinearEasing), RepeatMode.Restart), label = "shine")
    }
    Box(
        modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .heightIn(min = 184.dp)
            .softShadow(Elevation.high, shape, colors.accent)
            .clip(shape)
            .background(colors.ctaGradient)
            .drawWithContent {
                drawContent()
                val x = shimmer?.value ?: return@drawWithContent
                val cx = size.width * x
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        start = Offset(cx - size.width * 0.25f, 0f),
                        end = Offset(cx + size.width * 0.25f, size.height),
                    ),
                )
            }
            .pressable(haptic = HapticEvent.CONFIRM, onClick = onRecord),
    ) {
        // Decorative soft circles.
        Box(
            Modifier.matchParentSize().drawBehind {
                drawCircle(Color.White.copy(alpha = 0.12f), size.height * 0.9f, Offset(size.width * 1.02f, -size.height * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.08f), size.height * 0.5f, Offset(size.width * 0.1f, size.height * 1.1f))
            },
        )
        Row(Modifier.padding(Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_new_recording), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(Spacing.xs))
                Text(stringResource(R.string.home_new_recording_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
                Spacer(Modifier.height(Spacing.lg))
                val pill = RoundedCornerShape(Radius.pill)
                Row(
                    Modifier
                        .height(Dimens.controlSmall)
                        .clip(pill)
                        .background(Color.White.copy(alpha = 0.2f))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), pill)
                        .pressable(shape = pill, onClick = onAudioOnly)
                        .padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(Dimens.iconSmall))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.home_audio_only), style = ButtonText.small, color = Color.White, maxLines = 1)
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f))
                    .border(1.5.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Videocam, null, tint = colors.accent, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

// endregion

// region Quick actions

private data class QuickAction(val label: String, val icon: ImageVector, val bubble: Color, val tint: Color, val onClick: () -> Unit)

@Composable
private fun QuickActions(state: HomeUiState, onNavigate: (Any) -> Unit, onTeleprompter: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val actions = listOf(
        QuickAction(stringResource(R.string.home_action_teleprompter), Icons.AutoMirrored.Rounded.Subject, colors.pastelLavender, colors.accent, onTeleprompter),
        QuickAction(stringResource(R.string.home_action_scripts), Icons.Rounded.EditNote, colors.pastelSky, Color(0xFF4C8DDB), { onNavigate(ScriptsRoute()) }),
        QuickAction(stringResource(R.string.home_action_ai), Icons.Rounded.AutoAwesome, colors.pastelRose, Color(0xFFE0557A), { onNavigate(AiStudioRoute()) }),
        QuickAction(stringResource(R.string.home_action_editor), Icons.Rounded.MovieCreation, colors.pastelPeach, Color(0xFFE07B3C), onImport),
        QuickAction(stringResource(R.string.home_action_projects), Icons.Rounded.Folder, colors.pastelMint, colors.success, { onNavigate(ProjectsRoute()) }),
        QuickAction(stringResource(R.string.home_action_drafts), Icons.Rounded.Description, colors.pastelButter, Color(0xFFC99A12), { onNavigate(ProjectsRoute(tab = 1)) }),
        QuickAction(stringResource(R.string.home_action_templates), Icons.Rounded.ViewQuilt, colors.pastelLavender, colors.accent, { onNavigate(TemplatesRoute) }),
        QuickAction(stringResource(R.string.home_action_cloud), Icons.Rounded.Cloud, colors.pastelSky, Color(0xFF4C8DDB), { onNavigate(CloudRoute) }),
    )
    Column(modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        actions.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                row.forEach { action -> QuickTile(action, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickTile(action: QuickAction, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier.clip(RoundedCornerShape(Radius.lg)).pressable(onClick = action.onClick),
        shape = RoundedCornerShape(Radius.lg),
        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.md),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(action.bubble), contentAlignment = Alignment.Center) {
                Icon(action.icon, null, tint = action.tint, modifier = Modifier.size(24.dp))
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
}

// endregion

// region Sections

@Composable
private fun ContinueCard(item: ContinueItem, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    GlassSurface(
        modifier.padding(horizontal = Spacing.gutter).fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).pressable(onClick = onClick),
        contentPadding = PaddingValues(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectThumbnail(
                item.item.thumbnail,
                accentSeed = item.item.project.id,
                audioOnly = item.item.audioOnly,
                modifier = Modifier.size(width = 60.dp, height = 76.dp).clip(RoundedCornerShape(Radius.sm)),
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_continue_label), style = MaterialTheme.typography.labelMedium, color = colors.accent)
                Text(item.item.project.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.home_continue_edited, formatRelativeTime(item.editedAt, now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.home_continue_action), tint = colors.onAccent)
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
        color = colors.surface.copy(alpha = 0.85f),
        contentPadding = PaddingValues(Spacing.xl),
    ) {
        Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(stringResource(R.string.home_welcome_body), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
        Spacer(Modifier.height(Spacing.lg))
        RgPrimaryButton(stringResource(R.string.home_welcome_write), onWrite, icon = Icons.Rounded.EditNote, size = RgButtonSize.MEDIUM, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.sm))
        RgSecondaryButton(stringResource(R.string.home_welcome_record), onRecord, icon = Icons.Rounded.Videocam, size = RgButtonSize.MEDIUM, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RecentProjects(items: List<HomeProject>, now: Long, onNavigate: (Any) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeader(stringResource(R.string.home_recent_projects), action = stringResource(R.string.home_see_all), onAction = { onNavigate(ProjectsRoute()) })
        LazyRow(contentPadding = PaddingValues(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            itemsIndexed(items, key = { _, it -> it.project.id }) { _, item ->
                ProjectCard(item, now) { onNavigate(EditorRoute(item.project.id)) }
            }
        }
    }
}

@Composable
private fun ProjectCard(item: HomeProject, now: Long, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val locale = currentLocale()
    val project = item.project
    Column(Modifier.width(148.dp).pressable(onClick = onClick)) {
        Box(Modifier.size(width = 148.dp, height = 188.dp).clip(RoundedCornerShape(Radius.lg)).border(1.dp, colors.outline, RoundedCornerShape(Radius.lg))) {
            ProjectThumbnail(item.thumbnail, accentSeed = project.id, audioOnly = item.audioOnly, modifier = Modifier.fillMaxSize())
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

@Composable
private fun statusColor(status: ProjectStatus): Color = when (status) {
    ProjectStatus.RECORDED -> Palette.Butter400
    ProjectStatus.EDITING -> Palette.Lavender300
    ProjectStatus.EXPORTED -> Palette.Mint400
}

/** Frosted metadata pill over thumbnails (fixed 22dp so pills in opposite corners share a baseline). */
@Composable
private fun DarkPill(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.height(22.dp).clip(RoundedCornerShape(Radius.pill)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp),
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
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            scripts.forEach { script ->
                val words = script.wordCount
                val seconds = readTimeSeconds(words, wpm)
                val minutes = (seconds + 59) / 60
                val wordsText = pluralStringResource(R.plurals.home_words, words, words.toString().localizeDigits(locale))
                val readText = if (seconds < 60) stringResource(R.string.home_read_under_minute) else pluralStringResource(R.plurals.home_read_minutes, minutes, minutes.toString().localizeDigits(locale))
                RgCard(Modifier.fillMaxWidth(), onClick = { onNavigate(ScriptEditorRoute(scriptId = script.id)) }, contentPadding = PaddingValues(Spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(Dimens.listIcon).clip(RoundedCornerShape(Radius.sm)).background(colors.pastelLavender), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Description, null, tint = colors.accent, modifier = Modifier.size(Dimens.iconMedium + 2.dp))
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
                            Text("$wordsText · $readText", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        RgIconButton(
                            Icons.Rounded.PlayArrow,
                            stringResource(R.string.home_play_prompter),
                            { onNavigate(TeleprompterRoute(script.id)) },
                            container = colors.accent,
                            tint = colors.onAccent,
                            size = 40.dp,
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
            .width(150.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .pressable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(118.dp).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.6f), accent.copy(alpha = 0.15f))))) {
            AspectFramePreview(template.aspectRatio, accent, template.autoCaptions, Modifier.fillMaxSize().padding(Spacing.md))
        }
        Column(Modifier.padding(Spacing.md)) {
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
            .size(84.dp)
            .drawBehind {
                val p = pulse?.value ?: return@drawBehind
                drawCircle(colors.record.copy(alpha = (1f - p) * 0.35f), radius = size.minDimension / 2 * (0.78f + 0.22f * p))
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .shadow(16.dp, CircleShape, ambientColor = colors.record.copy(alpha = 0.5f), spotColor = colors.record.copy(alpha = 0.5f))
                .clip(CircleShape)
                .background(colors.recordGradient)
                .border(3.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                .semantics { contentDescription = description }
                .pressable(haptic = HapticEvent.RECORD_START, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Videocam, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ImportingOverlay(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        // Swallows touches so nothing underneath is triggered while media is prepared.
        Box(Modifier.fillMaxSize().background(RgTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
            GlassSurface {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = RgTheme.colors.accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.home_importing), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                }
            }
        }
    }
}

// endregion

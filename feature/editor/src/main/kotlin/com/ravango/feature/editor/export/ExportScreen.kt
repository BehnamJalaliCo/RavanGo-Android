package com.ravango.feature.editor.export

import android.content.ActivityNotFoundException
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ravango.core.designsystem.theme.Radius
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatBytes
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.StudioTheme
import com.ravango.core.model.ProFeature
import com.ravango.core.model.VideoCodec
import com.ravango.core.ui.AppPermission
import com.ravango.core.ui.KeepScreenOn
import com.ravango.core.ui.PermissionStatus
import com.ravango.core.ui.rememberPermissionRequester
import com.ravango.engine.editor.export.ExportMath
import com.ravango.engine.editor.export.ExportState
import com.ravango.engine.editor.export.PrepareStep
import com.ravango.feature.editor.R
import com.ravango.feature.editor.messageRes
import com.ravango.feature.editor.ui.InfoCard
import com.ravango.feature.editor.ui.SwitchRow
import com.ravango.feature.editor.ui.ValueSlider
import com.ravango.feature.editor.ui.localized
import com.ravango.feature.editor.ui.timecode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ExportScreen(
    onBack: () -> Unit,
    onBackToEditor: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    vm: ExportViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ExportEvent.RequirePro -> onRequirePro(e.feature)
                is ExportEvent.Launch -> try {
                    context.startActivity(e.intent)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
    }
    KeepScreenOn(state.export.isActive)
    StudioTheme { ExportContent(state, vm, onBack, onBackToEditor) }
}

/** Stateless export screen: settings form, progress, success and failure. */
@Composable
internal fun ExportContent(state: ExportUiState, vm: ExportActions, onBack: () -> Unit, onBackToEditor: () -> Unit) {
    RgScreen(title = stringResource(R.string.editor_export_title), subtitle = state.title.takeIf { it.isNotBlank() }, onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState()
                state.notFound -> EmptyState(Icons.Rounded.Movie, stringResource(R.string.editor_not_found_title), stringResource(R.string.editor_not_found_message), actionText = stringResource(R.string.editor_back), onAction = onBack)
                else -> AnimatedContent(state.export, contentKey = { it::class }, label = "export") { export ->
                    when (export) {
                        is ExportState.Preparing, is ExportState.Running -> ExportProgress(export, vm::cancel)
                        is ExportState.Succeeded -> ExportDone(export, vm, onBackToEditor)
                        is ExportState.Failed -> ExportFailed(stringResource(export.error.messageRes()), onRetry = { vm.reset(); vm.startExport() }, onEdit = vm::reset)
                        is ExportState.Cancelled, ExportState.Idle -> ExportForm(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportForm(state: ExportUiState, vm: ExportActions) {
    val s = state.settings
    val notifications = rememberPermissionRequester(AppPermission.NOTIFICATIONS)
    val (w, h) = state.outputSize
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Summary: what will be produced, at a glance.
        val estimate = stringResource(
            R.string.editor_export_estimate,
            localized("$w×$h"),
            localized("${(state.videoBitrate / 100_000) / 10f}"),
            formatBytes(state.estimatedBytes),
            timecode(state.durationUs, tenths = false),
        )
        GlassSurface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = estimate }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(RgTheme.colors.accent.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Movie, null, tint = RgTheme.colors.accent)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("\u2066" + localized("$w × $h") + "\u2069", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(
                            (if (s.codec == VideoCodec.H264) "H.264" else "HEVC") + RgTheme.metaSeparator + localized("${s.frameRate}") + " fps",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SummaryStat(Icons.Rounded.Schedule, timecode(state.durationUs, tenths = false), Modifier.weight(1f))
                    SummaryStat(Icons.Rounded.SdStorage, "≈ " + formatBytes(state.estimatedBytes), Modifier.weight(1f), isolateLtr = false)
                    SummaryStat(Icons.Rounded.Speed, localized("${(state.videoBitrate / 100_000) / 10f}") + " Mbps", Modifier.weight(1f))
                }
            }
        }
        FormCard {
            Section(stringResource(R.string.editor_export_resolution)) {
                ChipOptions(ExportMath.RESOLUTIONS, s.resolutionShortSide, state::resolutionLocked, { resolutionLabel(it) }, vm::setResolution)
            }
            Section(stringResource(R.string.editor_export_fps)) {
                ChipOptions(ExportMath.FRAME_RATES, s.frameRate, state::fpsLocked, { localized(it.toString()) }, vm::setFrameRate)
            }
            Section(stringResource(R.string.editor_export_codec), scroll = false) {
                RgSegmentedControl(
                    VideoCodec.entries.toList(), s.codec,
                    { if (it == VideoCodec.H264 || state.hevcSupported) vm.setCodec(it) },
                    { if (it == VideoCodec.H264) "H.264" else "HEVC (H.265)" },
                    glass = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.hevcSupported) Text(stringResource(R.string.editor_export_hevc_unsupported), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = Spacing.xs))
                else if (s.codec == VideoCodec.HEVC) Text(stringResource(R.string.editor_export_hevc_note), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = Spacing.xs))
            }
            SwitchRow(stringResource(R.string.editor_export_auto_bitrate), s.videoBitrateBps == null, vm::setAutoBitrate)
            val customBps = s.videoBitrateBps
            if (customBps != null) {
                val mbps = customBps / 1_000_000f
                ValueSlider(stringResource(R.string.editor_export_bitrate), mbps, { vm.setBitrate((it * 1_000_000).roundToInt()) }, 1f..80f, localized("${mbps.roundToInt()}") + " Mbps")
            }
        }
        FormCard {
            SwitchRow(stringResource(R.string.editor_export_include_audio), s.includeAudio, vm::setIncludeAudio)
            if (state.hasCues) SwitchRow(stringResource(R.string.editor_export_srt), s.exportSrt, vm::setExportSrt)
        }
        if (state.watermark) {
            InfoCard(stringResource(R.string.editor_export_watermark_note), modifier = Modifier.bleed(Spacing.lg)) {
                RgTextButton(stringResource(R.string.editor_remove), onClick = vm::removeWatermark)
            }
        }
        if (state.otherExportRunning) InfoCard(stringResource(R.string.editor_export_other_running), modifier = Modifier.bleed(Spacing.lg))
        if (notifications.status != PermissionStatus.GRANTED && AppPermission.NOTIFICATIONS.manifest.isNotEmpty()) {
            InfoCard(stringResource(com.ravango.core.ui.R.string.permission_notifications_message), modifier = Modifier.bleed(Spacing.lg), icon = Icons.Rounded.Notifications, tint = RgTheme.colors.accent) {
                RgTextButton(stringResource(com.ravango.core.ui.R.string.permission_allow), onClick = notifications::request)
            }
        }
        RgPrimaryButton(
            stringResource(R.string.editor_export_start),
            onClick = vm::startExport,
            icon = Icons.Rounded.Upload,
            enabled = !state.otherExportRunning && state.durationUs > 0,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun SummaryStat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, isolateLtr: Boolean = true) {
    Row(
        modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(Radius.md)).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.xs))
        // Times and bitrates read left-to-right; sizes follow the reading direction ("≈ ۲۰ مگابایت").
        Text(if (isolateLtr) "\u2066" + text + "\u2069" else text, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
    }
}

/** Grouped settings on a subtle card; rows inside use the panel components' own 16dp inset. */
@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(Radius.lg))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(Radius.lg))
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

private fun resolutionLabel(shortSide: Int): String = localized(
    when (shortSide) {
        2160 -> "4K"
        1440 -> "2K"
        else -> "${shortSide}p"
    },
)

@Composable
private fun <T> ChipOptions(options: List<T>, selected: T, locked: (T) -> Boolean, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.forEach { o ->
            RgChip(label(o), selected = o == selected, onClick = { onSelect(o) }, glass = true, trailing = if (locked(o)) ({ ProBadge() }) else null)
        }
    }
}

@Composable
private fun Section(title: String, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = Spacing.lg))
        Spacer(Modifier.height(Spacing.sm))
        if (scroll) {
            // Chips scroll edge to edge inside the card.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg)) { Column { content() } }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), content = content)
        }
    }
}

@Composable
private fun ExportProgress(export: ExportState, onCancel: () -> Unit) {
    val (label, progress) = when (export) {
        is ExportState.Preparing -> stringResource(if (export.step == PrepareStep.REVERSING) R.string.editor_export_preparing_reverse else R.string.editor_export_preparing) to export.progress
        is ExportState.Running -> stringResource(R.string.editor_export_running) to export.progress
        else -> "" to 0f
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxSize().padding(Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(140.dp), strokeWidth = 8.dp, color = RgTheme.colors.accent, trackColor = Color.White.copy(alpha = 0.1f))
            Text(localized("${(progress * 100).roundToInt()}") + "%", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (export is ExportState.Running && progress > 0.03f) {
            val elapsed = (now - export.startedAtMs).coerceAtLeast(0)
            val remainingMs = (elapsed / progress * (1f - progress)).toLong()
            Text(stringResource(R.string.editor_export_remaining, timecode(remainingMs * 1000, tenths = false)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(Spacing.md))
        RgProgressBar(progress, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(R.string.editor_export_background_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xl))
        RgOutlineButton(stringResource(R.string.editor_cancel), onCancel, contentColor = Color.White)
    }
}

@Composable
private fun ExportDone(done: ExportState.Succeeded, vm: ExportActions, onBackToEditor: () -> Unit) {
    val shareTitle = stringResource(R.string.editor_share_title)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Spacer(Modifier.height(Spacing.xl))
        Box(Modifier.size(96.dp).background(RgTheme.colors.success.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CheckCircle, null, tint = RgTheme.colors.success, modifier = Modifier.size(56.dp))
        }
        Text(stringResource(R.string.editor_export_done), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text(
            localized("${done.width}×${done.height}") + RgTheme.metaSeparator + formatBytes(done.sizeBytes) + RgTheme.metaSeparator + timecode(done.durationUs, tenths = false),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f),
        )
        if (done.galleryUri != null) Text(stringResource(R.string.editor_export_saved_gallery), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        if (done.codecFallback) InfoCard(stringResource(R.string.editor_export_codec_fallback))
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                RgPrimaryButton(stringResource(R.string.editor_share), { vm.share(shareTitle) }, icon = Icons.Rounded.Share, modifier = Modifier.fillMaxWidth())
                RgSecondaryButton(stringResource(R.string.editor_open_gallery), vm::openInGallery, icon = Icons.Rounded.PhotoLibrary, modifier = Modifier.fillMaxWidth())
                if (done.srtFile != null) RgSecondaryButton(stringResource(R.string.editor_share_srt), { vm.shareSrt(shareTitle) }, icon = Icons.Rounded.Subtitles, modifier = Modifier.fillMaxWidth())
                RgOutlineButton(stringResource(R.string.editor_back_to_editor), { vm.reset(); onBackToEditor() }, icon = Icons.AutoMirrored.Rounded.ArrowBack, modifier = Modifier.fillMaxWidth(), contentColor = Color.White, size = RgButtonSize.LARGE)
            }
        }
    }
}

@Composable
private fun ExportFailed(message: String, onRetry: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = RgTheme.colors.danger, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(R.string.editor_export_failed), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Spacer(Modifier.height(Spacing.xs))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xl))
        RgPrimaryButton(stringResource(R.string.editor_retry), onRetry, size = RgButtonSize.MEDIUM)
        RgTextButton(stringResource(R.string.editor_change_settings), onClick = onEdit)
    }
}

/** Lets a child with its own horizontal inset ([h] per side) line up with this column's edges. */
private fun Modifier.bleed(h: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = h.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

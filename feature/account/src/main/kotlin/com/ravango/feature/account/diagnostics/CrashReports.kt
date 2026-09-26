package com.ravango.feature.account.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.diagnostics.CrashKind
import com.ravango.core.common.diagnostics.CrashReport
import com.ravango.core.common.diagnostics.CrashReporter
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.common.log.RgLog
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.feature.account.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Crash reports stored on this device (see [CrashReporter]); nothing is uploaded. */
@HiltViewModel
class CrashReportsViewModel @Inject constructor(private val reporter: CrashReporter) : ViewModel() {
    val reports: StateFlow<List<CrashReport>> = reporter.reports
    val pending: StateFlow<List<CrashReport>> = reporter.pending

    init {
        viewModelScope.launch { runCatching { reporter.refresh() } }
    }

    fun acknowledge() = viewModelScope.launch { runCatching { reporter.acknowledge() } }
    fun deleteAll() = viewModelScope.launch { runCatching { reporter.deleteAll() } }
}

/** Text that is safe to hand to other apps (share sheet / clipboard have size limits). */
internal fun CrashReport.shareText(): String =
    if (body.length <= SHARE_LIMIT) body else body.take(SHARE_LIMIT) + "\n… (truncated)\n"

private const val SHARE_LIMIT = 60_000
private const val VIEW_LIMIT = 40_000

internal fun shareReport(context: Context, report: CrashReport) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "RavanGo crash report")
            .putExtra(Intent.EXTRA_TEXT, report.shareText())
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.account_crash_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { RgLog.w("CrashReports", "share failed", it) }
}

internal fun copyReport(context: Context, report: CrashReport) {
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("RavanGo crash report", report.shareText()))
        Toast.makeText(context, R.string.account_crash_copied, Toast.LENGTH_SHORT).show()
    }.onFailure { RgLog.w("CrashReports", "copy failed", it) }
}

@Composable
private fun kindLabel(kind: CrashKind): String = stringResource(
    when (kind) {
        CrashKind.JAVA -> R.string.account_crash_kind_java
        CrashKind.NATIVE -> R.string.account_crash_kind_native
        CrashKind.ANR -> R.string.account_crash_kind_anr
    },
)

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis)).localizeDigits()

/**
 * Shown once at launch when RavanGo closed unexpectedly since the last acknowledged report: reassures the user and
 * offers to view or share the report. Dismissing acknowledges it (Settings → About → Crash reports keeps it).
 */
@Composable
fun CrashReportPrompt(viewModel: CrashReportsViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<CrashReport?>(null) }
    val context = LocalContext.current
    val latest = pending.firstOrNull()
    viewing?.let { report ->
        CrashReportViewer(report, onDismiss = { viewing = null })
        return
    }
    if (latest == null) return
    Dialog(onDismissRequest = { viewModel.acknowledge() }) {
        CrashReportPromptCard(
            report = latest,
            earlier = pending.size - 1,
            onView = { viewing = latest },
            onShare = {
                shareReport(context, latest)
                viewModel.acknowledge()
            },
            onDismiss = { viewModel.acknowledge() },
        )
    }
}

/** Stateless body of [CrashReportPrompt] (also used by screenshot tests). */
@Composable
internal fun CrashReportPromptCard(report: CrashReport, earlier: Int, onView: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.xl))
            .background(RgTheme.colors.backgroundElevated)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(Radius.md)).background(RgTheme.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.BugReport, null, tint = RgTheme.colors.accent, modifier = Modifier.size(26.dp))
        }
        Text(stringResource(R.string.account_crash_prompt_title), style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary)
        Text(stringResource(R.string.account_crash_prompt_message), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(RgTheme.colors.surfaceMuted)
                .padding(Spacing.sm),
        ) {
            Text(
                kindLabel(report.kind) + " · " + formatTime(report.timeMillis),
                style = MaterialTheme.typography.labelMedium,
                color = RgTheme.colors.textSecondary,
            )
            Text(
                report.summary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                color = RgTheme.colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (earlier > 0) {
            Text(
                pluralStringResource(R.plurals.account_crash_more, earlier, earlier.toString().localizeDigits()),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textTertiary,
            )
        }
        RgPrimaryButton(
            stringResource(R.string.account_crash_share),
            onClick = onShare,
            icon = Icons.Rounded.Share,
            size = RgButtonSize.MEDIUM,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RgTextButton(stringResource(R.string.account_crash_view), onClick = onView)
            RgTextButton(stringResource(R.string.account_crash_dismiss), onClick = onDismiss, color = RgTheme.colors.textSecondary)
        }
    }
}

/** Full report with Share and Copy. */
@Composable
fun CrashReportViewer(report: CrashReport, onDismiss: () -> Unit) {
    val context = LocalContext.current
    RgBottomSheet(onDismiss = onDismiss, title = kindLabel(report.kind) + " · " + formatTime(report.timeMillis)) {
        CrashReportViewerContent(report, onShare = { shareReport(context, report) }, onCopy = { copyReport(context, report) })
    }
}

/** Stateless body of [CrashReportViewer]. */
@Composable
internal fun CrashReportViewerContent(report: CrashReport, onShare: () -> Unit, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(R.string.account_crash_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = RgTheme.colors.textSecondary,
        )
        // The report is English/ASCII: lay the scroll box out LTR so lines start at the visible edge in Persian too.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(RgTheme.colors.surfaceMuted)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.sm),
        ) {
            SelectionContainer {
                Text(
                    if (report.body.length > VIEW_LIMIT) report.body.take(VIEW_LIMIT) + "\n…" else report.body,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, textDirection = TextDirection.Ltr),
                    color = RgTheme.colors.textPrimary,
                    softWrap = false,
                )
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RgPrimaryButton(
                stringResource(R.string.account_crash_share),
                onClick = onShare,
                icon = Icons.Rounded.Share,
                size = RgButtonSize.MEDIUM,
                modifier = Modifier.weight(1f),
            )
            RgSecondaryButton(
                stringResource(R.string.account_crash_copy),
                onClick = onCopy,
                icon = Icons.Rounded.ContentCopy,
                size = RgButtonSize.MEDIUM,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Settings → About → Crash reports: every report stored on this device. */
@Composable
fun CrashReportsSheet(onDismiss: () -> Unit, viewModel: CrashReportsViewModel = hiltViewModel()) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = reports.firstOrNull { it.id == openId }
    if (open != null) {
        CrashReportViewer(open, onDismiss = { openId = null })
        return
    }
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.account_crash_reports)) {
        if (reports.isEmpty()) {
            EmptyState(Icons.Rounded.BugReport, stringResource(R.string.account_crash_empty_title), stringResource(R.string.account_crash_empty_message))
            return@RgBottomSheet
        }
        Text(
            stringResource(R.string.account_crash_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = RgTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(reports, key = { it.id }) { report ->
                RgListItem(
                    report.summary,
                    subtitle = kindLabel(report.kind) + " · " + formatTime(report.timeMillis),
                    icon = Icons.Rounded.BugReport,
                    onClick = { openId = report.id },
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md), horizontalArrangement = Arrangement.End) {
            RgTextButton(stringResource(R.string.account_crash_delete_all), onClick = { viewModel.deleteAll() }, color = RgTheme.colors.danger)
        }
    }
}

/** Subtitle for the About entry. */
@Composable
internal fun crashReportsSubtitle(count: Int): String =
    if (count == 0) stringResource(R.string.account_crash_reports_none)
    else pluralStringResource(R.plurals.account_crash_reports_count, count, count.toString().localizeDigits())

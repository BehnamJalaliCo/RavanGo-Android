package com.ravango.feature.account.privacy

import com.ravango.feature.account.common.iconTone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.component.RgConfirmDialog
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.ui.openUrl
import com.ravango.feature.account.R
import com.ravango.feature.account.account.ConfirmWithWipeDialog
import com.ravango.feature.account.common.LegalSection
import com.ravango.feature.account.common.SwitchRow
import com.ravango.feature.account.common.messageRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Policy sections shown in-app (title, body) — localized, so the text matches the app language. */
private val policySections = listOf(
    R.string.account_privacy_intro_title to R.string.account_privacy_intro_body,
    R.string.account_privacy_collect_title to R.string.account_privacy_collect_body,
    R.string.account_privacy_device_title to R.string.account_privacy_device_body,
    R.string.account_privacy_cloud_title to R.string.account_privacy_cloud_body,
    R.string.account_privacy_ai_title to R.string.account_privacy_ai_body,
    R.string.account_privacy_payments_title to R.string.account_privacy_payments_body,
    R.string.account_privacy_ads_title to R.string.account_privacy_ads_body,
    R.string.account_privacy_rights_title to R.string.account_privacy_rights_body,
    R.string.account_privacy_security_title to R.string.account_privacy_security_body,
    R.string.account_privacy_children_title to R.string.account_privacy_children_body,
    R.string.account_privacy_changes_title to R.string.account_privacy_changes_body,
)

@Composable
fun PrivacyScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = rememberSnackbarHostState()
    var confirmLocal by rememberSaveable { mutableStateOf(false) }
    var confirmAccount by rememberSaveable { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }

    val exported = stringResource(R.string.account_export_done)
    val exportFailed = stringResource(R.string.account_export_failed)
    val localDeleted = stringResource(R.string.account_local_deleted)
    val accountDeleted = stringResource(R.string.account_deleted)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                PrivacyEvent.Exported -> exported
                PrivacyEvent.ExportFailed -> exportFailed
                PrivacyEvent.LocalDataDeleted -> localDeleted
                PrivacyEvent.AccountDeleted -> {
                    confirmAccount = false
                    accountDeleted
                }
                is PrivacyEvent.Error -> context.getString(event.error.messageRes())
            }
            snackbar.showSnackbar(text)
        }
    }

    PrivacyContent(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onAnalytics = viewModel::setAnalytics,
        onCrashReports = viewModel::setCrashReports,
        onExport = {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            exporter.launch("ravango-export-$date.json")
        },
        onDeleteLocal = { confirmLocal = true },
        onDeleteAccount = { confirmAccount = true },
        onOpenOnline = { context.openUrl(state.privacyUrl) },
    )

    if (confirmLocal) {
        RgConfirmDialog(
            title = stringResource(R.string.account_delete_local_title),
            message = stringResource(R.string.account_delete_local_message),
            confirmText = stringResource(R.string.account_delete_local_confirm),
            dismissText = stringResource(com.ravango.core.ui.R.string.action_cancel),
            onConfirm = {
                confirmLocal = false
                viewModel.deleteLocalData()
            },
            onDismiss = { confirmLocal = false },
            destructive = true,
        )
    }
    if (confirmAccount) {
        ConfirmWithWipeDialog(
            title = stringResource(R.string.account_delete_title),
            message = stringResource(R.string.account_delete_message),
            confirmText = stringResource(R.string.account_delete_confirm),
            requiredWord = stringResource(R.string.account_delete_word),
            busy = state.busy,
            onConfirm = { wipe -> viewModel.deleteAccount(wipe) },
            onDismiss = { if (!state.busy) confirmAccount = false },
        )
    }
}

/** Stateless privacy screen: consent switches, data controls and the in-app policy. */
@Composable
internal fun PrivacyContent(
    state: PrivacyUiState,
    snackbar: SnackbarHostState?,
    onBack: () -> Unit,
    onAnalytics: (Boolean) -> Unit,
    onCrashReports: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenOnline: () -> Unit,
) {
    RgScreen(title = stringResource(R.string.account_privacy), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RgGroup(title = stringResource(R.string.account_privacy_choices)) {
                SwitchRow(
                    stringResource(R.string.account_analytics_consent),
                    state.analyticsConsent,
                    onAnalytics,
                    subtitle = stringResource(R.string.account_analytics_consent_sub),
                    icon = Icons.Rounded.Insights,
                )
                SwitchRow(
                    stringResource(R.string.account_crash_consent),
                    state.crashReportsConsent,
                    onCrashReports,
                    subtitle = stringResource(R.string.account_crash_consent_sub),
                    icon = Icons.Rounded.BugReport,
                )
            }
            RgGroup(title = stringResource(R.string.account_privacy_your_data)) {
                RgListItem(
                    stringResource(R.string.account_export_data),
                    subtitle = stringResource(R.string.account_export_data_sub),
                    icon = Icons.Rounded.Download, iconTint = iconTone(Icons.Rounded.Download).content, iconBackground = iconTone(Icons.Rounded.Download).container,
                    onClick = if (state.busy) null else onExport,
                )
                RgListItem(
                    stringResource(R.string.account_delete_local),
                    subtitle = stringResource(R.string.account_delete_local_sub),
                    icon = Icons.Rounded.DeleteSweep,
                    iconTint = RgTheme.colors.danger,
                    iconBackground = RgTheme.colors.danger.copy(alpha = 0.12f),
                    onClick = if (state.busy) null else onDeleteLocal,
                )
                if (state.signedIn) {
                    RgListItem(
                        stringResource(R.string.account_delete),
                        subtitle = stringResource(R.string.account_delete_subtitle),
                        icon = Icons.Rounded.DeleteForever,
                        iconTint = RgTheme.colors.danger,
                        iconBackground = RgTheme.colors.danger.copy(alpha = 0.12f),
                        onClick = onDeleteAccount,
                    )
                }
                if (state.privacyUrl.isNotBlank()) {
                    RgListItem(
                        stringResource(R.string.account_privacy_online),
                        subtitle = state.privacyUrl,
                        icon = Icons.AutoMirrored.Rounded.OpenInNew, iconTint = iconTone(Icons.AutoMirrored.Rounded.OpenInNew).content, iconBackground = iconTone(Icons.AutoMirrored.Rounded.OpenInNew).container,
                        onClick = onOpenOnline,
                    )
                }
            }
            policySections.forEach { (title, body) -> LegalSection(stringResource(title), stringResource(body)) }
        }
    }
}

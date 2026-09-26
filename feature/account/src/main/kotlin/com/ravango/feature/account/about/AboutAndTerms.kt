package com.ravango.feature.account.about

import com.ravango.feature.account.common.iconTone
import com.ravango.core.designsystem.component.RavanGoLogo
import com.ravango.core.designsystem.component.RavanGoLogoStyle
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.rememberHaptics
import kotlinx.coroutines.launch
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.AppConfig
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.ui.openUrl
import com.ravango.feature.account.R
import com.ravango.feature.account.common.LegalSection
import com.ravango.feature.account.diagnostics.CrashReportsSheet
import com.ravango.feature.account.diagnostics.CrashReportsViewModel
import com.ravango.feature.account.diagnostics.crashReportsSubtitle
import androidx.compose.material.icons.rounded.BugReport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes build configuration (version, links, support email) to the static screens. */
@HiltViewModel
class AppInfoViewModel @Inject constructor(
    val config: AppConfig,
    private val tester: com.ravango.platform.billing.TesterAccess,
) : ViewModel() {
    val testerActive = tester.active
    val testerAvailable: Boolean get() = tester.available

    private val _unlockResult = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val unlockResult: kotlinx.coroutines.flow.StateFlow<Boolean?> = _unlockResult

    fun unlock(code: String) = viewModelScope.launch { _unlockResult.value = tester.unlock(code) }
    fun disableTester() = viewModelScope.launch { tester.disable() }
    fun clearResult() { _unlockResult.value = null }
}

private val termsSections = listOf(
    R.string.account_terms_accept_title to R.string.account_terms_accept_body,
    R.string.account_terms_account_title to R.string.account_terms_account_body,
    R.string.account_terms_content_title to R.string.account_terms_content_body,
    R.string.account_terms_ai_title to R.string.account_terms_ai_body,
    R.string.account_terms_subscription_title to R.string.account_terms_subscription_body,
    R.string.account_terms_credits_title to R.string.account_terms_credits_body,
    R.string.account_terms_acceptable_title to R.string.account_terms_acceptable_body,
    R.string.account_terms_liability_title to R.string.account_terms_liability_body,
    R.string.account_terms_changes_title to R.string.account_terms_changes_body,
)

@Composable
fun TermsScreen(onBack: () -> Unit, viewModel: AppInfoViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val url = viewModel.config.termsUrl
    TermsContent(url = url, onBack = onBack, onOpenOnline = { context.openUrl(url) })
}

/** Stateless terms of use. */
@Composable
internal fun TermsContent(url: String, onBack: () -> Unit, onOpenOnline: () -> Unit) {
    RgScreen(title = stringResource(R.string.account_terms), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            termsSections.forEach { (title, body) -> LegalSection(stringResource(title), stringResource(body)) }
            if (url.isNotBlank()) {
                RgGroup {
                    RgListItem(stringResource(R.string.account_terms_online), subtitle = url, icon = Icons.AutoMirrored.Rounded.OpenInNew, iconTint = iconTone(Icons.AutoMirrored.Rounded.OpenInNew).content, iconBackground = iconTone(Icons.AutoMirrored.Rounded.OpenInNew).container, onClick = onOpenOnline)
                }
            }
        }
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    viewModel: AppInfoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val config = viewModel.config
    val haptics = rememberHaptics()
    var versionTaps by remember { mutableIntStateOf(0) }
    var showUnlock by remember { mutableStateOf(false) }
    val testerActive by viewModel.testerActive.collectAsStateWithLifecycle()
    val unlockResult by viewModel.unlockResult.collectAsStateWithLifecycle()
    val crashViewModel: CrashReportsViewModel = hiltViewModel()
    val crashReports by crashViewModel.reports.collectAsStateWithLifecycle()
    var showCrashReports by remember { mutableStateOf(false) }
    if (showCrashReports) CrashReportsSheet(onDismiss = { showCrashReports = false }, viewModel = crashViewModel)
    if (showUnlock) {
        TesterUnlockDialog(
            failed = unlockResult == false,
            onSubmit = viewModel::unlock,
            onDismiss = { showUnlock = false; viewModel.clearResult() },
        )
    }
    LaunchedEffect(unlockResult) {
        if (unlockResult == true) {
            haptics.perform(HapticEvent.CONFIRM)
            showUnlock = false
            viewModel.clearResult()
        }
    }
    AboutContent(
        versionName = config.versionName,
        versionCode = config.versionCode.toString(),
        supportEmail = config.supportEmail,
        testerActive = testerActive,
        onBack = onBack,
        onVersionTap = {
            // Hidden entry for owners/testers: tap the version 7 times.
            versionTaps++
            if (versionTaps >= 7 && viewModel.testerAvailable) {
                versionTaps = 0
                haptics.perform(HapticEvent.CONFIRM)
                showUnlock = true
            }
        },
        onLicenses = onLicenses,
        onPrivacy = onPrivacy,
        onTerms = onTerms,
        onContactSupport = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${config.supportEmail}"))
                        .putExtra(Intent.EXTRA_SUBJECT, "RavanGo ${config.versionName}")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        onDisableTester = viewModel::disableTester,
        crashReportCount = crashReports.size,
        onCrashReports = { showCrashReports = true },
    )
}

/** Stateless about screen. */
@Composable
internal fun AboutContent(
    versionName: String,
    versionCode: String,
    supportEmail: String,
    testerActive: Boolean,
    onBack: () -> Unit,
    onVersionTap: () -> Unit,
    onLicenses: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    onContactSupport: () -> Unit,
    onDisableTester: () -> Unit,
    crashReportCount: Int = 0,
    onCrashReports: () -> Unit = {},
) {
    RgScreen(title = stringResource(R.string.account_about), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    RavanGoLogo(
                        style = RavanGoLogoStyle.STACKED,
                        height = 64.dp,
                        contentDescription = stringResource(R.string.account_app_name),
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.account_version, "$versionName ($versionCode)".localizeDigits()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RgTheme.colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable(interactionSource = null, indication = null, onClick = onVersionTap)
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(R.string.account_about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RgTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            AnimatedVisibility(testerActive) {
                RgGroup {
                    RgListItem(
                        stringResource(R.string.account_tester_active),
                        subtitle = stringResource(R.string.account_tester_active_sub),
                        icon = Icons.Rounded.Verified,
                        iconTint = Color.White,
                        iconBackground = RgTheme.colors.accent,
                        trailing = { RgTextButton(stringResource(R.string.account_tester_disable), onDisableTester, color = RgTheme.colors.danger) },
                    )
                }
            }
            RgGroup {
                if (supportEmail.isNotBlank()) {
                    RgListItem(
                        stringResource(R.string.account_contact_support),
                        subtitle = supportEmail,
                        icon = Icons.Rounded.Mail, iconTint = iconTone(Icons.Rounded.Mail).content, iconBackground = iconTone(Icons.Rounded.Mail).container,
                        onClick = onContactSupport,
                    )
                }
                RgListItem(stringResource(R.string.account_privacy), icon = Icons.Rounded.PrivacyTip, iconTint = iconTone(Icons.Rounded.PrivacyTip).content, iconBackground = iconTone(Icons.Rounded.PrivacyTip).container, onClick = onPrivacy)
                RgListItem(stringResource(R.string.account_terms), icon = Icons.Rounded.Gavel, iconTint = iconTone(Icons.Rounded.Gavel).content, iconBackground = iconTone(Icons.Rounded.Gavel).container, onClick = onTerms)
                RgListItem(
                    stringResource(R.string.account_licenses),
                    subtitle = stringResource(R.string.account_licenses_sub),
                    icon = Icons.Rounded.Description, iconTint = iconTone(Icons.Rounded.Description).content, iconBackground = iconTone(Icons.Rounded.Description).container,
                    onClick = onLicenses,
                )
                RgListItem(
                    stringResource(R.string.account_crash_reports),
                    subtitle = crashReportsSubtitle(crashReportCount),
                    icon = Icons.Rounded.BugReport, iconTint = iconTone(Icons.Rounded.BugReport).content, iconBackground = iconTone(Icons.Rounded.BugReport).container,
                    onClick = onCrashReports,
                )
            }
            RgGroup(title = stringResource(R.string.account_fonts_title)) {
                RgListItem(stringResource(R.string.account_font_ravagh), subtitle = stringResource(R.string.account_font_ravagh_sub), icon = Icons.Rounded.Verified, iconTint = iconTone(Icons.Rounded.Verified).content, iconBackground = iconTone(Icons.Rounded.Verified).container)
                RgListItem(stringResource(R.string.account_font_ofl), subtitle = stringResource(R.string.account_font_ofl_sub), icon = Icons.Rounded.FontDownload, iconTint = iconTone(Icons.Rounded.FontDownload).content, iconBackground = iconTone(Icons.Rounded.FontDownload).container)
            }
            LegalSection(stringResource(R.string.account_about_privacy_title), stringResource(R.string.account_about_privacy_body))
            Text(
                stringResource(R.string.account_made_with_love),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
            )
        }
    }
}

@Composable
private fun TesterUnlockDialog(failed: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RgTheme.colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(stringResource(R.string.account_tester_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.account_tester_message), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
                RgTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = stringResource(R.string.account_tester_code_hint),
                    isError = failed,
                    supportingText = if (failed) stringResource(R.string.account_tester_wrong_code) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onSubmit(code) }),
                )
            }
        },
        confirmButton = { RgTextButton(stringResource(R.string.account_tester_unlock), { onSubmit(code) }, enabled = code.isNotBlank()) },
        dismissButton = { RgTextButton(stringResource(com.ravango.core.ui.R.string.action_cancel), onDismiss, color = RgTheme.colors.textSecondary) },
    )
}

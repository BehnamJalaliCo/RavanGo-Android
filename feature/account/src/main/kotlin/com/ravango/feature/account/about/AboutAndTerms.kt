package com.ravango.feature.account.about

import android.content.Intent
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
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes build configuration (version, links, support email) to the static screens. */
@HiltViewModel
class AppInfoViewModel @Inject constructor(val config: AppConfig) : ViewModel()

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
    RgScreen(title = stringResource(R.string.account_terms), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            termsSections.forEach { (title, body) -> LegalSection(stringResource(title), stringResource(body)) }
            if (url.isNotBlank()) {
                RgGroup {
                    RgListItem(stringResource(R.string.account_terms_online), subtitle = url, icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(url) })
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
    RgScreen(title = stringResource(R.string.account_about), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(Radius.lg)).background(RgTheme.colors.brandGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.account_app_initial), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(stringResource(R.string.account_app_name), style = MaterialTheme.typography.headlineSmall, color = RgTheme.colors.textPrimary)
                    Text(
                        stringResource(R.string.account_version, "${config.versionName} (${config.versionCode})".localizeDigits()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RgTheme.colors.textSecondary,
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
            RgGroup {
                if (config.supportEmail.isNotBlank()) {
                    RgListItem(
                        stringResource(R.string.account_contact_support),
                        subtitle = config.supportEmail,
                        icon = Icons.Rounded.Mail,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${config.supportEmail}"))
                                        .putExtra(Intent.EXTRA_SUBJECT, "RavanGo ${config.versionName}")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
                RgListItem(stringResource(R.string.account_privacy), icon = Icons.Rounded.PrivacyTip, onClick = onPrivacy)
                RgListItem(stringResource(R.string.account_terms), icon = Icons.Rounded.Gavel, onClick = onTerms)
                RgListItem(
                    stringResource(R.string.account_licenses),
                    subtitle = stringResource(R.string.account_licenses_sub),
                    icon = Icons.Rounded.Description,
                    onClick = onLicenses,
                )
            }
            RgGroup(title = stringResource(R.string.account_fonts_title)) {
                RgListItem(stringResource(R.string.account_font_ravagh), subtitle = stringResource(R.string.account_font_ravagh_sub), icon = Icons.Rounded.Verified)
                RgListItem(stringResource(R.string.account_font_ofl), subtitle = stringResource(R.string.account_font_ofl_sub), icon = Icons.Rounded.FontDownload)
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

package com.ravango.feature.account.account

import com.ravango.feature.account.common.iconTone
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.LoadingState
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AuthProviderType
import com.ravango.core.model.Plan
import com.ravango.core.model.UserAccount
import com.ravango.core.model.service.AuthState
import com.ravango.core.ui.openUrl
import com.ravango.feature.account.R
import com.ravango.feature.account.common.InfoBanner
import com.ravango.feature.account.common.InitialsAvatar
import com.ravango.feature.account.common.initialsOf
import com.ravango.feature.account.common.messageRes
import com.ravango.feature.account.common.relativeTime

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onPaywall: () -> Unit,
    onCloud: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    onAbout: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = rememberSnackbarHostState()
    var editProfile by rememberSaveable { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val savedText = stringResource(R.string.account_profile_saved)
    val signedOutText = stringResource(R.string.account_signed_out)
    val deletedText = stringResource(R.string.account_deleted)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AccountEvent.Error -> snackbar.showSnackbar(context.getString(event.error.messageRes()))
                AccountEvent.ProfileSaved -> {
                    editProfile = false
                    snackbar.showSnackbar(savedText)
                }
                AccountEvent.SignedOut -> snackbar.showSnackbar(signedOutText)
                AccountEvent.AccountDeleted -> {
                    confirmDelete = false
                    snackbar.showSnackbar(deletedText)
                }
            }
        }
    }

    AccountContent(
        state = state,
        snackbar = snackbar,
        onBack = onBack,
        onSignIn = onSignIn,
        onPaywall = onPaywall,
        onCloud = onCloud,
        onSettings = onSettings,
        onPrivacy = onPrivacy,
        onTerms = onTerms,
        onAbout = onAbout,
        onSubscription = {
            val url = viewModel.manageSubscriptionUrl()
            if (state.entitlements.plan == Plan.PRO && url != null) context.openUrl(url) else onPaywall()
        },
        onContactSupport = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${state.supportEmail}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        onEditProfile = { editProfile = true },
        onSignOut = { confirmSignOut = true },
        onDelete = { confirmDelete = true },
    )

    val user = (state.auth as? AuthState.SignedIn)?.user
    if (editProfile && user != null) {
        EditProfileSheet(user, busy = state.busy, onDismiss = { editProfile = false }, onSave = viewModel::updateDisplayName)
    }
    if (confirmSignOut) {
        ConfirmWithWipeDialog(
            title = stringResource(R.string.account_sign_out_title),
            message = stringResource(R.string.account_sign_out_message),
            confirmText = stringResource(R.string.account_sign_out),
            requiredWord = null,
            onConfirm = { wipe ->
                confirmSignOut = false
                viewModel.signOut(wipe)
            },
            onDismiss = { confirmSignOut = false },
        )
    }
    if (confirmDelete) {
        ConfirmWithWipeDialog(
            title = stringResource(R.string.account_delete_title),
            message = stringResource(R.string.account_delete_message),
            confirmText = stringResource(R.string.account_delete_confirm),
            requiredWord = stringResource(R.string.account_delete_word),
            busy = state.busy,
            onConfirm = { wipe -> viewModel.deleteAccount(wipe) },
            onDismiss = { if (!state.busy) confirmDelete = false },
        )
    }
}

/** Stateless account hub (profile or guest header, usage, account/app groups). */
@Composable
internal fun AccountContent(
    state: AccountUiState,
    snackbar: SnackbarHostState?,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onPaywall: () -> Unit,
    onCloud: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit,
    onAbout: () -> Unit,
    onSubscription: () -> Unit,
    onContactSupport: () -> Unit,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    RgScreen(title = stringResource(R.string.account_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        if (state.auth == AuthState.Unknown) {
            LoadingState(Modifier.padding(padding))
            return@RgScreen
        }
        val user = (state.auth as? AuthState.SignedIn)?.user
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            AnimatedContent(user, label = "header", contentKey = { it?.id }) { u ->
                if (u != null) ProfileHeader(u, state, onEdit = onEditProfile) else GuestHeader(state, onSignIn)
            }
            UsageCard(state, onPaywall = onPaywall, onCloud = onCloud)

            RgGroup(title = stringResource(R.string.account_section_account)) {
                if (user != null) {
                    RgListItem(stringResource(R.string.account_profile_edit), icon = Icons.Rounded.Edit, iconTint = iconTone(Icons.Rounded.Edit).content, iconBackground = iconTone(Icons.Rounded.Edit).container, onClick = onEditProfile)
                }
                val planName = planLabel(state.entitlements.plan)
                val planSubtitle = if (state.entitlements.isTrial) stringResource(R.string.account_plan_trial, planName) else planName
                if (state.entitlements.plan == Plan.FREE) {
                    RgListItem(
                        title = stringResource(R.string.account_subscription),
                        subtitle = planSubtitle,
                        icon = Icons.Rounded.WorkspacePremium, iconTint = iconTone(Icons.Rounded.WorkspacePremium).content, iconBackground = iconTone(Icons.Rounded.WorkspacePremium).container,
                        onClick = onSubscription,
                        trailing = { ProBadge(text = stringResource(R.string.account_upgrade_badge)) },
                    )
                } else {
                    // Paid: keep the default chevron (the row opens subscription management).
                    RgListItem(stringResource(R.string.account_subscription), subtitle = planSubtitle, icon = Icons.Rounded.WorkspacePremium, iconTint = iconTone(Icons.Rounded.WorkspacePremium).content, iconBackground = iconTone(Icons.Rounded.WorkspacePremium).container, onClick = onSubscription)
                }
                RgListItem(
                    title = stringResource(R.string.account_cloud),
                    subtitle = cloudSubtitle(state),
                    icon = Icons.Rounded.Cloud, iconTint = iconTone(Icons.Rounded.Cloud).content, iconBackground = iconTone(Icons.Rounded.Cloud).container,
                    onClick = onCloud,
                )
            }
            RgGroup(title = stringResource(R.string.account_section_app)) {
                RgListItem(stringResource(R.string.account_settings), icon = Icons.Rounded.Settings, iconTint = iconTone(Icons.Rounded.Settings).content, iconBackground = iconTone(Icons.Rounded.Settings).container, onClick = onSettings)
                RgListItem(stringResource(R.string.account_privacy), icon = Icons.Rounded.PrivacyTip, iconTint = iconTone(Icons.Rounded.PrivacyTip).content, iconBackground = iconTone(Icons.Rounded.PrivacyTip).container, onClick = onPrivacy)
                RgListItem(stringResource(R.string.account_terms), icon = Icons.Rounded.Description, iconTint = iconTone(Icons.Rounded.Description).content, iconBackground = iconTone(Icons.Rounded.Description).container, onClick = onTerms)
                RgListItem(stringResource(R.string.account_about), icon = Icons.Rounded.Info, iconTint = iconTone(Icons.Rounded.Info).content, iconBackground = iconTone(Icons.Rounded.Info).container, onClick = onAbout)
                if (state.supportEmail.isNotBlank()) {
                    RgListItem(
                        stringResource(R.string.account_contact_support),
                        subtitle = state.supportEmail,
                        icon = Icons.Rounded.Mail, iconTint = iconTone(Icons.Rounded.Mail).content, iconBackground = iconTone(Icons.Rounded.Mail).container,
                        onClick = onContactSupport,
                    )
                }
            }
            if (user != null) {
                RgGroup {
                    RgListItem(
                        stringResource(R.string.account_sign_out),
                        icon = Icons.AutoMirrored.Rounded.Logout, iconTint = iconTone(Icons.AutoMirrored.Rounded.Logout).content, iconBackground = iconTone(Icons.AutoMirrored.Rounded.Logout).container,
                        onClick = onSignOut,
                    )
                    RgListItem(
                        stringResource(R.string.account_delete),
                        subtitle = stringResource(R.string.account_delete_subtitle),
                        icon = Icons.Rounded.DeleteForever,
                        iconTint = RgTheme.colors.danger,
                        iconBackground = RgTheme.colors.danger.copy(alpha = 0.12f),
                        onClick = onDelete,
                    )
                }
            }
        }
    }

}

@Composable
internal fun planLabel(plan: Plan): String = stringResource(
    when (plan) {
        Plan.FREE -> R.string.account_plan_free
        Plan.PRO -> R.string.account_plan_pro
        Plan.LIFETIME -> R.string.account_plan_lifetime
    },
)

@Composable
private fun cloudSubtitle(state: AccountUiState): String = when {
    !state.cloud.configured -> stringResource(R.string.account_cloud_not_configured)
    !state.cloud.signedIn -> stringResource(R.string.account_cloud_sign_in_hint)
    !state.cloud.syncEnabled -> stringResource(R.string.account_cloud_off)
    state.cloud.lastSyncedAt != null -> stringResource(R.string.account_cloud_last_sync, relativeTime(state.cloud.lastSyncedAt!!))
    else -> stringResource(R.string.account_cloud_on)
}

@Composable
private fun ProfileHeader(user: UserAccount, state: AccountUiState, onEdit: () -> Unit) {
    val name = user.displayName?.takeIf { it.isNotBlank() }
    val contact = user.email ?: user.phone
    GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(initialsOf(name ?: contact), seed = user.id)
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name ?: stringResource(R.string.account_no_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = RgTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contact != null) {
                    // Emails and phone numbers are always left-to-right.
                    Text("⁦$contact⁩", style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    PlanBadge(state.entitlements.plan)
                    RgTag(providerLabel(user.provider), color = RgTheme.colors.surfaceMuted, contentColor = RgTheme.colors.textSecondary)
                }
            }
            RgTextButton(stringResource(R.string.account_edit), onEdit)
        }
    }
}

@Composable
private fun PlanBadge(plan: Plan) {
    if (plan == Plan.FREE) {
        RgTag(planLabel(plan), color = RgTheme.colors.accentSoft, contentColor = RgTheme.colors.accent)
    } else {
        ProBadge(Modifier.height(24.dp), text = planLabel(plan))
    }
}

@Composable
private fun providerLabel(provider: AuthProviderType): String = stringResource(
    when (provider) {
        AuthProviderType.EMAIL_OTP -> R.string.account_provider_email_code
        AuthProviderType.EMAIL_PASSWORD -> R.string.account_provider_email_password
        AuthProviderType.PHONE_OTP -> R.string.account_provider_phone
        AuthProviderType.GOOGLE -> R.string.account_provider_google
    },
)

@Composable
private fun GuestHeader(state: AccountUiState, onSignIn: () -> Unit) {
    GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar("", seed = "guest", size = 56.dp)
                Spacer(Modifier.width(Spacing.md))
                Column {
                    Text(stringResource(R.string.account_guest_title), style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
                    Text(stringResource(R.string.account_guest_subtitle), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
                }
            }
            if (state.cloudConfigured) {
                RgPrimaryButton(stringResource(R.string.account_sign_in), onSignIn, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Person)
            } else {
                InfoBanner(stringResource(R.string.account_cloud_not_configured_long))
            }
        }
    }
}

@Composable
private fun UsageCard(state: AccountUiState, onPaywall: () -> Unit, onCloud: () -> Unit) {
    val e = state.entitlements
    GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth(), contentPadding = PaddingValues(Spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            UsageRow(
                icon = Icons.Rounded.AutoAwesome,
                title = stringResource(R.string.account_ai_credits),
                value = stringResource(R.string.account_ai_credits_value, formatNumber(e.aiCreditsRemaining), formatNumber(e.aiCreditsPerMonth)),
                progress = if (e.aiCreditsPerMonth > 0) (e.aiCreditsRemaining.coerceAtMost(e.aiCreditsPerMonth).toFloat() / e.aiCreditsPerMonth) else 0f,
                onClick = onPaywall,
            )
            if (e.cloudQuotaBytes > 0) {
                UsageRow(
                    icon = Icons.Rounded.Cloud,
                    title = stringResource(R.string.account_cloud_storage),
                    value = stringResource(R.string.account_storage_value, formatBytes(state.cloud.backedUpBytes), formatBytes(e.cloudQuotaBytes)),
                    progress = (state.cloud.backedUpBytes.toFloat() / e.cloudQuotaBytes).coerceIn(0f, 1f),
                    onClick = onCloud,
                )
            }
            Text(
                stringResource(R.string.account_local_media, formatBytes(state.localMediaBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun UsageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, progress: Float, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().then(Modifier.padding(vertical = 2.dp)),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(icon, null, tint = RgTheme.colors.accent, modifier = Modifier.padding(end = Spacing.sm))
            Text(title, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            RgTextButton(value, onClick, color = RgTheme.colors.textSecondary)
        }
        RgProgressBar(progress)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(user: UserAccount, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(user.displayName.orEmpty()) }
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.account_profile_edit)) {
        Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RgTextField(
                value = name,
                onValueChange = { if (it.length <= 60) name = it },
                label = stringResource(R.string.account_display_name),
                leadingIcon = Icons.Rounded.Person,
            )
            RgPrimaryButton(
                stringResource(R.string.account_save),
                onClick = { onSave(name) },
                modifier = Modifier.fillMaxWidth(),
                loading = busy,
                enabled = name.isNotBlank(),
            )
        }
    }
}

/** Confirmation with an "also erase this device" option; destructive actions require typing a word. */
@Composable
internal fun ConfirmWithWipeDialog(
    title: String,
    message: String,
    confirmText: String,
    requiredWord: String?,
    onConfirm: (wipeLocal: Boolean) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    var wipe by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val colors = RgTheme.colors
    val canConfirm = !busy && (requiredWord == null || typed.trim().equals(requiredWord, ignoreCase = true))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.backgroundElevated,
        shape = RoundedCornerShape(Radius.xl),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wipe, onCheckedChange = { wipe = it }, colors = CheckboxDefaults.colors(checkedColor = colors.danger))
                    Text(stringResource(R.string.account_wipe_local), style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                }
                if (requiredWord != null) {
                    RgTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = stringResource(R.string.account_delete_type_prompt, requiredWord),
                        isError = typed.isNotEmpty() && !canConfirm && !busy,
                    )
                }
            }
        },
        confirmButton = { RgTextButton(confirmText, { onConfirm(wipe) }, color = colors.danger, enabled = canConfirm) },
        dismissButton = { RgTextButton(stringResource(com.ravango.core.ui.R.string.action_cancel), onDismiss, color = colors.textSecondary, enabled = !busy) },
    )
}

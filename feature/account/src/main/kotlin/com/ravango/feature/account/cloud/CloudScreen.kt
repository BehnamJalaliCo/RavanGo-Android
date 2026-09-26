package com.ravango.feature.account.cloud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ProFeature
import com.ravango.core.model.service.SyncPhase
import com.ravango.feature.account.R
import com.ravango.feature.account.common.InfoBanner
import com.ravango.feature.account.common.SwitchRow
import com.ravango.feature.account.common.messageRes
import com.ravango.feature.account.common.relativeTime
import com.ravango.platform.cloud.CloudStatus
import com.ravango.platform.cloud.CloudSyncController
import com.ravango.platform.cloud.CloudToggleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CloudEvent {
    data object SignInRequired : CloudEvent
    data class RequirePro(val feature: ProFeature) : CloudEvent
}

@HiltViewModel
class CloudViewModel @Inject constructor(private val controller: CloudSyncController) : ViewModel() {
    val state: StateFlow<CloudStatus> = controller.status

    private val _events = Channel<CloudEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setSyncEnabled(enabled: Boolean) = viewModelScope.launch { handle(controller.setSyncEnabled(enabled)) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { controller.setWifiOnly(enabled) }
    fun setBackupMedia(enabled: Boolean) = viewModelScope.launch { handle(controller.setBackupMedia(enabled)) }
    fun syncNow() = controller.syncNow()

    private suspend fun handle(result: CloudToggleResult) {
        when (result) {
            CloudToggleResult.SIGN_IN_REQUIRED -> _events.send(CloudEvent.SignInRequired)
            CloudToggleResult.REQUIRES_PRO -> _events.send(CloudEvent.RequirePro(ProFeature.CLOUD_MEDIA_BACKUP))
            CloudToggleResult.OK, CloudToggleResult.NOT_CONFIGURED -> Unit
        }
    }
}

@Composable
fun CloudScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    viewModel: CloudViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CloudEvent.SignInRequired -> onSignIn()
                is CloudEvent.RequirePro -> onRequirePro(event.feature)
            }
        }
    }

    RgScreen(title = stringResource(R.string.account_cloud), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (!s.configured) {
                InfoBanner(stringResource(R.string.account_cloud_not_configured_long), Modifier.padding(horizontal = Spacing.gutter))
                Text(
                    stringResource(R.string.account_cloud_local_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RgTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = Spacing.gutter),
                )
                return@Column
            }
            StatusCard(s, onSyncNow = viewModel::syncNow, onSignIn = onSignIn)

            RgGroup(title = stringResource(R.string.account_cloud_sync_section)) {
                SwitchRow(
                    stringResource(R.string.account_cloud_sync_toggle),
                    s.syncEnabled && s.signedIn,
                    { viewModel.setSyncEnabled(it) },
                    subtitle = stringResource(if (s.signedIn) R.string.account_cloud_sync_sub else R.string.account_cloud_sync_requires_sign_in),
                    icon = Icons.Rounded.CloudSync,
                )
                SwitchRow(
                    stringResource(R.string.account_cloud_wifi_only),
                    s.wifiOnly,
                    { viewModel.setWifiOnly(it) },
                    subtitle = stringResource(R.string.account_cloud_wifi_only_sub),
                    icon = Icons.Rounded.Wifi,
                    enabled = s.syncEnabled,
                )
                RgListItem(
                    title = stringResource(R.string.account_cloud_what_syncs),
                    subtitle = stringResource(if (s.canSyncProjects) R.string.account_cloud_syncs_pro else R.string.account_cloud_syncs_free),
                    icon = Icons.Rounded.Cloud,
                    onClick = if (s.canSyncProjects) null else ({ onRequirePro(ProFeature.CLOUD_PROJECT_SYNC) }),
                    trailing = if (s.canSyncProjects) null else ({ ProBadge() }),
                )
            }

            RgGroup(title = stringResource(R.string.account_cloud_media_section)) {
                SwitchRow(
                    stringResource(R.string.account_cloud_backup_media),
                    s.backupMedia && s.canBackupMedia,
                    { viewModel.setBackupMedia(it) },
                    subtitle = stringResource(R.string.account_cloud_backup_media_sub),
                    icon = Icons.Rounded.PermMedia,
                    enabled = s.syncEnabled || !s.canBackupMedia,
                    trailingBadge = if (!s.canBackupMedia) ({ ProBadge() }) else null,
                )
                if (s.quotaBytes > 0) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            stringResource(R.string.account_storage_value, formatBytes(s.backedUpBytes), formatBytes(s.quotaBytes)),
                            style = MaterialTheme.typography.labelLarge,
                            color = RgTheme.colors.textPrimary,
                        )
                        RgProgressBar((s.backedUpBytes.toFloat() / s.quotaBytes).coerceIn(0f, 1f))
                    }
                }
                AnimatedVisibility(s.media.running) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            stringResource(R.string.account_cloud_uploading, formatNumber(s.media.filesDone), formatNumber(s.media.filesTotal), formatBytes(s.media.bytesDone), formatBytes(s.media.bytesTotal)),
                            style = MaterialTheme.typography.bodySmall,
                            color = RgTheme.colors.textSecondary,
                        )
                        RgProgressBar(if (s.media.bytesTotal > 0) s.media.bytesDone.toFloat() / s.media.bytesTotal else 0f)
                    }
                }
                s.mediaIssue?.let { InfoBanner(stringResource(it.messageRes()), Modifier.padding(Spacing.sm), tint = RgTheme.colors.warning) }
            }
        }
    }
}

@Composable
private fun StatusCard(s: CloudStatus, onSyncNow: () -> Unit, onSignIn: () -> Unit) {
    GlassSurface(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (s.phase) {
                    SyncPhase.DISABLED -> Icons.Rounded.CloudOff to RgTheme.colors.textTertiary
                    SyncPhase.IDLE -> Icons.Rounded.CloudDone to RgTheme.colors.success
                    SyncPhase.SYNCING -> Icons.Rounded.Sync to RgTheme.colors.accent
                    SyncPhase.OFFLINE -> Icons.Rounded.CloudOff to RgTheme.colors.warning
                    SyncPhase.ERROR -> Icons.Rounded.ErrorOutline to RgTheme.colors.danger
                }
                val rotation = if (s.phase == SyncPhase.SYNCING && !RgTheme.reduceMotion) {
                    val t = rememberInfiniteTransition(label = "sync")
                    val r by t.animateFloat(360f, 0f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "rot")
                    r
                } else {
                    0f
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp).rotate(rotation))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(phaseTitle(s), style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
                    Text(
                        s.lastSyncedAt?.let { stringResource(R.string.account_cloud_last_sync, relativeTime(it)) } ?: stringResource(R.string.account_cloud_never_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = RgTheme.colors.textSecondary,
                    )
                    if (s.pendingChanges > 0 && s.syncEnabled) {
                        Text(
                            stringResource(R.string.account_cloud_pending, formatNumber(s.pendingChanges)),
                            style = MaterialTheme.typography.bodySmall,
                            color = RgTheme.colors.textSecondary,
                        )
                    }
                }
            }
            if (s.issue != null && s.phase == SyncPhase.ERROR) InfoBanner(stringResource(s.issue!!.messageRes()), tint = RgTheme.colors.danger)
            if (s.waitingForWifi) InfoBanner(stringResource(R.string.account_cloud_waiting_wifi), icon = Icons.Rounded.Wifi)
            when {
                !s.signedIn -> RgPrimaryButton(stringResource(R.string.account_sign_in), onSignIn, Modifier.fillMaxWidth(), size = RgButtonSize.MEDIUM)
                s.syncEnabled -> RgSecondaryButton(
                    stringResource(R.string.account_cloud_sync_now),
                    onSyncNow,
                    Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.Sync,
                    enabled = s.phase != SyncPhase.SYNCING && s.online,
                    size = RgButtonSize.MEDIUM,
                )
            }
        }
    }
}

@Composable
private fun phaseTitle(s: CloudStatus): String = stringResource(
    when {
        !s.signedIn -> R.string.account_cloud_sign_in_hint
        !s.syncEnabled -> R.string.account_cloud_off
        s.phase == SyncPhase.SYNCING -> R.string.account_cloud_syncing
        s.phase == SyncPhase.OFFLINE -> R.string.account_cloud_offline
        s.phase == SyncPhase.ERROR -> R.string.account_cloud_error
        else -> R.string.account_cloud_up_to_date
    },
)

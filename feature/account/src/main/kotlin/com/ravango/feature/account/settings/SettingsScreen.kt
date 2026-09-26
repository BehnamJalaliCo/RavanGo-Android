package com.ravango.feature.account.settings

import com.ravango.feature.account.common.iconTone
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatBytes
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AiProviderId
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SpeechProviderId
import com.ravango.core.model.ThemeMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.ui.AppPermission
import com.ravango.core.ui.PermissionStatus
import com.ravango.core.ui.rememberPermissionRequester
import com.ravango.feature.account.R
import com.ravango.feature.account.common.InfoBanner
import com.ravango.feature.account.common.SwitchRow

/** Everything the settings screen can change; implemented by [SettingsViewModel] via [asActions]. */
internal interface SettingsActions {
    fun setLanguage(language: AppLanguage)
    fun setTheme(mode: ThemeMode)
    fun setHaptics(enabled: Boolean)
    fun setReduceMotion(enabled: Boolean)
    fun setSaveToGallery(enabled: Boolean)
    fun setKeepScreenOn(enabled: Boolean)
    fun setExportQuality(quality: ExportQuality)
    fun setHevc(enabled: Boolean)
    fun clearCache()
    fun setTextProvider(provider: AiProviderId)
    fun setSpeechProvider(provider: SpeechProviderId)
    fun setConsent(enabled: Boolean)
    fun setCustomEndpoint(baseUrl: String, model: String)
    fun saveKey(provider: AiProviderId, key: String)
    fun removeKey(provider: AiProviderId)
    fun saveSpeechKey(key: String)
    fun removeSpeechKey()
}

private fun SettingsViewModel.asActions(): SettingsActions = object : SettingsActions {
    override fun setLanguage(language: AppLanguage) { this@asActions.setLanguage(language) }
    override fun setTheme(mode: ThemeMode) { this@asActions.setTheme(mode) }
    override fun setHaptics(enabled: Boolean) { this@asActions.setHaptics(enabled) }
    override fun setReduceMotion(enabled: Boolean) { this@asActions.setReduceMotion(enabled) }
    override fun setSaveToGallery(enabled: Boolean) { this@asActions.setSaveToGallery(enabled) }
    override fun setKeepScreenOn(enabled: Boolean) { this@asActions.setKeepScreenOn(enabled) }
    override fun setExportQuality(quality: ExportQuality) { this@asActions.setExportQuality(quality) }
    override fun setHevc(enabled: Boolean) { this@asActions.setHevc(enabled) }
    override fun clearCache() { this@asActions.clearCache() }
    override fun setTextProvider(provider: AiProviderId) { this@asActions.setTextProvider(provider) }
    override fun setSpeechProvider(provider: SpeechProviderId) { this@asActions.setSpeechProvider(provider) }
    override fun setConsent(enabled: Boolean) { this@asActions.setConsent(enabled) }
    override fun setCustomEndpoint(baseUrl: String, model: String) { this@asActions.setCustomEndpoint(baseUrl, model) }
    override fun saveKey(provider: AiProviderId, key: String) { this@asActions.saveKey(provider, key) }
    override fun removeKey(provider: AiProviderId) { this@asActions.removeKey(provider) }
    override fun saveSpeechKey(key: String) { this@asActions.saveSpeechKey(key) }
    override fun removeSpeechKey() { this@asActions.removeSpeechKey() }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = rememberSnackbarHostState()
    val keySaved = stringResource(R.string.account_key_saved)
    val keyRemoved = stringResource(R.string.account_key_removed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.RequirePro -> onRequirePro(event.feature)
                is SettingsEvent.CacheCleared -> snackbar.showSnackbar(context.getString(R.string.account_cache_cleared, formatBytes(event.bytes)))
                SettingsEvent.KeySaved -> snackbar.showSnackbar(keySaved)
                SettingsEvent.KeyRemoved -> snackbar.showSnackbar(keyRemoved)
            }
        }
    }
    val actions = remember(viewModel) { viewModel.asActions() }
    SettingsContent(state, snackbar, onBack, actions)
}

/** Stateless settings screen. */
@Composable
internal fun SettingsContent(state: SettingsUiState, snackbar: SnackbarHostState?, onBack: () -> Unit, actions: SettingsActions) {
    val viewModel = actions
    val prefs = state.prefs

    RgScreen(title = stringResource(R.string.account_settings), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Appearance & language
            RgGroup(title = stringResource(R.string.account_settings_appearance)) {
                LabeledControl(Icons.Rounded.Translate, stringResource(R.string.account_language)) {
                    RgSegmentedControl(
                        options = listOf(AppLanguage.PERSIAN, AppLanguage.ENGLISH, AppLanguage.SYSTEM),
                        selected = prefs.language,
                        onSelect = viewModel::setLanguage,
                        label = { lang ->
                            when (lang) {
                                AppLanguage.PERSIAN -> "فارسی"
                                AppLanguage.ENGLISH -> "English"
                                AppLanguage.SYSTEM -> stringResource(R.string.account_follow_system)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LabeledControl(Icons.Rounded.LightMode, stringResource(R.string.account_theme)) {
                    RgSegmentedControl(
                        options = ThemeMode.entries,
                        selected = prefs.themeMode,
                        onSelect = viewModel::setTheme,
                        label = { mode ->
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.account_follow_system
                                    ThemeMode.LIGHT -> R.string.account_theme_light
                                    ThemeMode.DARK -> R.string.account_theme_dark
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(stringResource(R.string.account_haptics), prefs.hapticsEnabled, viewModel::setHaptics, icon = Icons.Rounded.Vibration)
                SwitchRow(
                    stringResource(R.string.account_reduce_motion),
                    prefs.reduceMotion,
                    viewModel::setReduceMotion,
                    subtitle = stringResource(R.string.account_reduce_motion_sub),
                    icon = Icons.Rounded.Animation,
                )
            }

            // Recording & export
            RgGroup(title = stringResource(R.string.account_settings_recording)) {
                SwitchRow(
                    stringResource(R.string.account_save_to_gallery),
                    prefs.saveToGallery,
                    viewModel::setSaveToGallery,
                    subtitle = stringResource(R.string.account_save_to_gallery_sub),
                    icon = Icons.Rounded.PhotoLibrary,
                )
                SwitchRow(
                    stringResource(R.string.account_keep_screen_on),
                    prefs.keepScreenOnWhilePrompting,
                    viewModel::setKeepScreenOn,
                    icon = Icons.Rounded.ScreenLockPortrait,
                )
                LabeledControl(Icons.Rounded.VideoSettings, stringResource(R.string.account_default_export)) {
                    val current = ExportQuality.of(prefs.defaultExport)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ExportQuality.entries.take(3).forEach { q -> QualityChip(q, q == current, q.feature?.let(state.entitlements::has) ?: true, viewModel::setExportQuality) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ExportQuality.entries.drop(3).forEach { q -> QualityChip(q, q == current, q.feature?.let(state.entitlements::has) ?: true, viewModel::setExportQuality) }
                    }
                }
                SwitchRow(
                    stringResource(R.string.account_hevc),
                    prefs.defaultExport.codec == VideoCodec.HEVC,
                    viewModel::setHevc,
                    subtitle = stringResource(R.string.account_hevc_sub),
                    icon = Icons.Rounded.Memory,
                    trailingBadge = if (!state.entitlements.has(ProFeature.RECORD_HEVC)) ({ ProBadge() }) else null,
                )
            }

            AiSection(state, viewModel)

            // Notifications
            RgGroup(title = stringResource(R.string.account_settings_notifications)) {
                if (Build.VERSION.SDK_INT >= 33) {
                    val requester = rememberPermissionRequester(AppPermission.NOTIFICATIONS)
                    RgListItem(
                        title = stringResource(R.string.account_notifications),
                        subtitle = stringResource(
                            when (requester.status) {
                                PermissionStatus.GRANTED -> R.string.account_notifications_on
                                PermissionStatus.DENIED -> R.string.account_notifications_off
                                PermissionStatus.PERMANENTLY_DENIED -> R.string.account_notifications_blocked
                            },
                        ),
                        icon = Icons.Rounded.Notifications, iconTint = iconTone(Icons.Rounded.Notifications).content, iconBackground = iconTone(Icons.Rounded.Notifications).container,
                        onClick = if (requester.allGranted) null else requester::request,
                        trailing = if (requester.allGranted) null else ({ RgTextButton(stringResource(R.string.account_allow), requester::request) }),
                    )
                } else {
                    RgListItem(stringResource(R.string.account_notifications), subtitle = stringResource(R.string.account_notifications_on), icon = Icons.Rounded.Notifications, iconTint = iconTone(Icons.Rounded.Notifications).content, iconBackground = iconTone(Icons.Rounded.Notifications).container)
                }
            }

            // Storage
            RgGroup(title = stringResource(R.string.account_settings_storage)) {
                RgListItem(
                    stringResource(R.string.account_media_storage),
                    subtitle = stringResource(R.string.account_media_storage_sub, formatBytes(state.mediaBytes)),
                    icon = Icons.Rounded.Storage, iconTint = iconTone(Icons.Rounded.Storage).content, iconBackground = iconTone(Icons.Rounded.Storage).container,
                )
                RgListItem(
                    stringResource(R.string.account_cache),
                    subtitle = stringResource(R.string.account_cache_sub, formatBytes(state.cacheBytes)),
                    icon = Icons.Rounded.CleaningServices, iconTint = iconTone(Icons.Rounded.CleaningServices).content, iconBackground = iconTone(Icons.Rounded.CleaningServices).container,
                    trailing = { RgTextButton(stringResource(R.string.account_clear), viewModel::clearCache, enabled = !state.clearingCache && state.cacheBytes > 0) },
                )
            }
        }
    }
}

@Composable
private fun QualityChip(quality: ExportQuality, selected: Boolean, unlocked: Boolean, onSelect: (ExportQuality) -> Unit) {
    val label = when (quality) {
        ExportQuality.HD_720 -> "720p"
        ExportQuality.FHD_1080 -> "1080p"
        ExportQuality.FHD_1080_60 -> "1080p60"
        ExportQuality.UHD_4K -> "4K"
        ExportQuality.UHD_4K_60 -> "4K60"
    }
    RgChip(label, selected, { onSelect(quality) }, trailing = if (!unlocked) ({ ProBadge() }) else null)
}

@Composable
private fun LabeledControl(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        RgListItem(title, icon = icon, iconTint = iconTone(icon).content, iconBackground = iconTone(icon).container)
        Column(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) { content() }
    }
}

@Composable
private fun AiSection(state: SettingsUiState, vm: SettingsActions) {
    val ai = state.prefs.ai
    RgGroup(title = stringResource(R.string.account_settings_ai)) {
        LabeledControl(Icons.Rounded.AutoAwesome, stringResource(R.string.account_ai_provider)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AiProviderId.entries.forEach { p ->
                    RgChip(aiProviderLabel(p), ai.textProvider == p, { vm.setTextProvider(p) })
                }
            }
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (ai.textProvider) {
                    AiProviderId.RAVANGO_GATEWAY -> InfoBanner(
                        stringResource(if (state.aiGatewayConfigured) R.string.account_ai_gateway_ready else R.string.account_ai_gateway_missing),
                        tint = if (state.aiGatewayConfigured) RgTheme.colors.success else RgTheme.colors.warning,
                    )
                    AiProviderId.ANTHROPIC_DIRECT -> KeyEditor(
                        label = stringResource(R.string.account_anthropic_key),
                        hasKey = state.keys.anthropic,
                        onSave = { vm.saveKey(AiProviderId.ANTHROPIC_DIRECT, it) },
                        onRemove = { vm.removeKey(AiProviderId.ANTHROPIC_DIRECT) },
                    )
                    AiProviderId.OPENAI_COMPATIBLE -> {
                        EndpointEditor(ai.customBaseUrl.orEmpty(), ai.customModel.orEmpty(), vm::setCustomEndpoint)
                        KeyEditor(
                            label = stringResource(R.string.account_openai_key),
                            hasKey = state.keys.openAi,
                            onSave = { vm.saveKey(AiProviderId.OPENAI_COMPATIBLE, it) },
                            onRemove = { vm.removeKey(AiProviderId.OPENAI_COMPATIBLE) },
                        )
                    }
                }
            }
        }
        LabeledControl(Icons.Rounded.Key, stringResource(R.string.account_speech_provider)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SpeechProviderId.entries.forEach { p ->
                    RgChip(speechProviderLabel(p), ai.speechProvider == p, { vm.setSpeechProvider(p) })
                }
            }
            Column(Modifier.padding(top = Spacing.md)) {
                when (ai.speechProvider) {
                    SpeechProviderId.RAVANGO_GATEWAY -> if (!state.aiGatewayConfigured) {
                        InfoBanner(stringResource(R.string.account_ai_gateway_missing), tint = RgTheme.colors.warning)
                    }
                    SpeechProviderId.OPENAI_WHISPER -> KeyEditor(
                        label = stringResource(R.string.account_whisper_key),
                        hasKey = state.keys.speech,
                        onSave = vm::saveSpeechKey,
                        onRemove = vm::removeSpeechKey,
                    )
                    SpeechProviderId.ANDROID_ON_DEVICE -> InfoBanner(stringResource(R.string.account_speech_on_device_note))
                }
            }
        }
        SwitchRow(
            stringResource(R.string.account_ai_consent),
            ai.cloudProcessingConsent,
            vm::setConsent,
            subtitle = stringResource(R.string.account_ai_consent_sub),
        )
    }
}

@Composable
private fun aiProviderLabel(p: AiProviderId) = stringResource(
    when (p) {
        AiProviderId.RAVANGO_GATEWAY -> R.string.account_ai_provider_gateway
        AiProviderId.ANTHROPIC_DIRECT -> R.string.account_ai_provider_anthropic
        AiProviderId.OPENAI_COMPATIBLE -> R.string.account_ai_provider_openai
    },
)

@Composable
private fun speechProviderLabel(p: SpeechProviderId) = stringResource(
    when (p) {
        SpeechProviderId.RAVANGO_GATEWAY -> R.string.account_ai_provider_gateway
        SpeechProviderId.OPENAI_WHISPER -> R.string.account_speech_whisper
        SpeechProviderId.ANDROID_ON_DEVICE -> R.string.account_speech_on_device
    },
)

/** Keys are write-only in the UI: once saved only a masked placeholder is shown. */
@Composable
private fun KeyEditor(label: String, hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var editing by rememberSaveable(hasKey) { mutableStateOf(!hasKey) }
    var value by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (hasKey && !editing) {
            RgListItem(
                title = label,
                subtitle = "••••••••••••  " + stringResource(R.string.account_key_stored),
                icon = Icons.Rounded.Key, iconTint = iconTone(Icons.Rounded.Key).content, iconBackground = iconTone(Icons.Rounded.Key).container,
                trailing = {
                    Row {
                        RgTextButton(stringResource(R.string.account_replace), { editing = true })
                        RgTextButton(stringResource(R.string.account_remove), onRemove, color = RgTheme.colors.danger)
                    }
                },
            )
        } else {
            RgTextField(
                value = value,
                onValueChange = { value = it.trim() },
                label = label,
                leadingIcon = Icons.Rounded.Key,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = stringResource(R.string.account_key_note),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgSecondaryButton(stringResource(R.string.account_save), {
                    onSave(value)
                    value = ""
                    editing = false
                }, enabled = value.length >= 8, size = RgButtonSize.SMALL)
                AnimatedVisibility(hasKey) { RgOutlineButton(stringResource(com.ravango.core.ui.R.string.action_cancel), { editing = false; value = "" }, size = RgButtonSize.SMALL) }
            }
        }
    }
}

@Composable
private fun EndpointEditor(baseUrl: String, model: String, onSave: (String, String) -> Unit) {
    var url by rememberSaveable(baseUrl) { mutableStateOf(baseUrl) }
    var m by rememberSaveable(model) { mutableStateOf(model) }
    val urlValid = url.isBlank() || url.startsWith("https://") || url.startsWith("http://")
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RgTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = stringResource(R.string.account_base_url),
            placeholder = "https://api.openai.com/v1",
            leadingIcon = Icons.Rounded.Link,
            isError = !urlValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        RgTextField(value = m, onValueChange = { m = it }, label = stringResource(R.string.account_model), placeholder = "gpt-4o-mini")
        if (url != baseUrl || m != model) {
            RgSecondaryButton(stringResource(R.string.account_save), { onSave(url, m) }, enabled = urlValid && url.isNotBlank() && m.isNotBlank(), size = RgButtonSize.SMALL)
        }
        Text(stringResource(R.string.account_openai_note), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
    }
}

package com.ravango.feature.account.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.ui.findActivity
import com.ravango.feature.account.R
import com.ravango.feature.account.common.InfoBanner
import com.ravango.feature.account.common.messageRes

@Composable
fun SignInScreen(
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val done by rememberUpdatedState(onSignedIn)
    LaunchedEffect(viewModel) { viewModel.signedIn.collect { done() } }

    RgScreen(title = stringResource(R.string.account_signin_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Benefits()
            if (!state.configured) {
                InfoBanner(stringResource(R.string.account_cloud_not_configured_long))
                RgPrimaryButton(stringResource(R.string.account_continue_guest), onBack, modifier = Modifier.fillMaxWidth())
                return@Column
            }

            GlassSurface(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    RgSegmentedControl(
                        options = SignInMethod.entries,
                        selected = state.method,
                        onSelect = viewModel::setMethod,
                        label = { method ->
                            stringResource(
                                when (method) {
                                    SignInMethod.EMAIL_CODE -> R.string.account_tab_email_code
                                    SignInMethod.PASSWORD -> R.string.account_tab_password
                                    SignInMethod.PHONE -> R.string.account_tab_phone
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AnimatedContent(
                        targetState = state.method to (state.codeSentTo != null),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "form",
                    ) { (method, codeStep) ->
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            when {
                                method == SignInMethod.PASSWORD -> PasswordForm(state, viewModel)
                                codeStep -> CodeStep(state, viewModel)
                                method == SignInMethod.PHONE -> PhoneForm(state, viewModel)
                                else -> EmailForm(state, viewModel)
                            }
                        }
                    }
                    AnimatedVisibility(state.error != null) {
                        state.error?.let { InfoBanner(stringResource(it.messageRes()), tint = RgTheme.colors.danger) }
                    }
                    AnimatedVisibility(state.info != null) {
                        val text = when (state.info) {
                            SignInInfo.CONFIRMATION_EMAIL_SENT -> stringResource(R.string.account_signup_confirm_sent, state.email.trim())
                            SignInInfo.RESET_EMAIL_SENT -> stringResource(R.string.account_reset_sent, state.email.trim())
                            null -> ""
                        }
                        InfoBanner(text, tint = RgTheme.colors.success)
                    }
                }
            }

            if (state.googleConfigured) {
                OrDivider()
                RgOutlineButton(
                    text = stringResource(R.string.account_google),
                    onClick = { context.findActivity()?.let(viewModel::signInWithGoogle) },
                    modifier = Modifier.fillMaxWidth(),
                    size = RgButtonSize.LARGE,
                    enabled = !state.loading,
                    icon = Icons.Rounded.Person,
                )
            }
            RgTextButton(
                stringResource(R.string.account_continue_guest),
                onBack,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = RgTheme.colors.textSecondary,
            )
            Text(
                stringResource(R.string.account_signin_legal),
                style = MaterialTheme.typography.bodySmall,
                color = RgTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Benefits() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(R.string.account_signin_headline), style = MaterialTheme.typography.headlineSmall, color = RgTheme.colors.textPrimary)
        BenefitRow(Icons.Rounded.Devices, stringResource(R.string.account_benefit_devices))
        BenefitRow(Icons.Rounded.Cloud, stringResource(R.string.account_benefit_backup))
        BenefitRow(Icons.Rounded.WorkspacePremium, stringResource(R.string.account_benefit_purchases))
        BenefitRow(Icons.Rounded.AutoAwesome, stringResource(R.string.account_benefit_optional))
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(RgTheme.colors.brandGradientSoft, RoundedCornerShape(Radius.sm)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = RgTheme.colors.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Spacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
    }
}

@Composable
private fun EmailForm(state: SignInUiState, vm: SignInViewModel) {
    Text(stringResource(R.string.account_email_code_hint), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
    LtrField {
        RgTextField(
            value = state.email,
            onValueChange = vm::setEmail,
            label = stringResource(R.string.account_email),
            leadingIcon = Icons.Rounded.AlternateEmail,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (state.emailValid) vm.sendCode() }),
        )
    }
    RgPrimaryButton(stringResource(R.string.account_send_code), vm::sendCode, Modifier.fillMaxWidth(), enabled = state.emailValid, loading = state.loading)
}

@Composable
private fun PhoneForm(state: SignInUiState, vm: SignInViewModel) {
    Text(stringResource(R.string.account_phone_hint), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
    LtrField {
        RgTextField(
            value = state.phone,
            onValueChange = vm::setPhone,
            label = stringResource(R.string.account_phone),
            placeholder = "0912 345 6789",
            leadingIcon = Icons.Rounded.Phone,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (state.phoneValid) vm.sendCode() }),
        )
    }
    RgPrimaryButton(stringResource(R.string.account_send_sms), vm::sendCode, Modifier.fillMaxWidth(), enabled = state.phoneValid, loading = state.loading)
}

@Composable
private fun CodeStep(state: SignInUiState, vm: SignInViewModel) {
    Text(
        stringResource(R.string.account_code_sent_to, "⁦${state.codeSentTo.orEmpty()}⁩"),
        style = MaterialTheme.typography.bodyMedium,
        color = RgTheme.colors.textPrimary,
    )
    OtpBoxes(code = state.code, length = SignInViewModel.CODE_LENGTH, enabled = !state.loading, onChange = vm::setCode)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RgTextButton(stringResource(R.string.account_change_destination), vm::editDestination, color = RgTheme.colors.textSecondary)
        Spacer(Modifier.weight(1f))
        if (state.resendInSeconds > 0) {
            Text(
                stringResource(R.string.account_resend_in, formatNumber(state.resendInSeconds)),
                style = MaterialTheme.typography.labelLarge,
                color = RgTheme.colors.textTertiary,
            )
        } else {
            RgTextButton(stringResource(R.string.account_resend), vm::sendCode, enabled = !state.loading)
        }
    }
    if (state.loading) {
        Text(stringResource(R.string.account_verifying), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
    }
}

@Composable
private fun PasswordForm(state: SignInUiState, vm: SignInViewModel) {
    var visible by rememberSaveable { mutableStateOf(false) }
    if (state.creatingAccount) {
        RgTextField(
            value = state.displayName,
            onValueChange = vm::setDisplayName,
            label = stringResource(R.string.account_display_name),
            leadingIcon = Icons.Rounded.Person,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
    }
    LtrField {
        RgTextField(
            value = state.email,
            onValueChange = vm::setEmail,
            label = stringResource(R.string.account_email),
            leadingIcon = Icons.Rounded.AlternateEmail,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
    }
    LtrField {
        RgTextField(
            value = state.password,
            onValueChange = vm::setPassword,
            label = stringResource(R.string.account_password),
            leadingIcon = Icons.Rounded.Lock,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (state.emailValid && state.passwordValid) vm.submitPassword() }),
            supportingText = if (state.creatingAccount) stringResource(R.string.account_password_rule) else null,
            trailing = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.account_hide_password else R.string.account_show_password),
                        tint = RgTheme.colors.textSecondary,
                    )
                }
            },
        )
    }
    RgPrimaryButton(
        stringResource(if (state.creatingAccount) R.string.account_create_account else R.string.account_sign_in),
        vm::submitPassword,
        Modifier.fillMaxWidth(),
        enabled = state.emailValid && state.passwordValid,
        loading = state.loading,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        RgTextButton(
            stringResource(if (state.creatingAccount) R.string.account_have_account else R.string.account_new_here),
            vm::toggleCreateAccount,
        )
        Spacer(Modifier.weight(1f))
        if (!state.creatingAccount) {
            RgTextButton(stringResource(R.string.account_forgot_password), vm::resetPassword, color = RgTheme.colors.textSecondary, enabled = state.emailValid && !state.loading)
        }
    }
}

/** Emails, phone numbers and passwords are always typed left-to-right. */
@Composable
private fun LtrField(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** Six code boxes backed by one hidden text field (supports paste and SMS autofill, Persian digits). */
@Composable
private fun OtpBoxes(code: String, length: Int, enabled: Boolean, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val colors = RgTheme.colors
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = code,
            onValueChange = onChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            singleLine = true,
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    repeat(length) { i ->
                        val char = code.getOrNull(i)
                        val active = i == code.length && enabled
                        val border by animateColorAsState(if (active) colors.accent else if (char != null) colors.outlineStrong else colors.outline, label = "otp")
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(if (char != null) colors.accentSoft.copy(alpha = 0.5f) else colors.surface, RoundedCornerShape(Radius.sm))
                                .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(Radius.sm)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                char?.let { formatNumber(it.digitToInt()) } ?: "",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(RgTheme.colors.outline))
        Text(stringResource(R.string.account_or), style = MaterialTheme.typography.labelMedium, color = RgTheme.colors.textTertiary, modifier = Modifier.padding(horizontal = Spacing.md))
        Box(Modifier.weight(1f).height(1.dp).background(RgTheme.colors.outline))
    }
}

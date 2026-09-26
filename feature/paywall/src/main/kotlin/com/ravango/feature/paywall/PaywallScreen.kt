package com.ravango.feature.paywall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatBytes
import com.ravango.core.common.format.formatNumber
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.GradientBackground
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.ShimmerBox
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.ravango.core.designsystem.component.softShadow
import com.ravango.core.designsystem.motion.rgFadeThrough
import com.ravango.core.designsystem.theme.Elevation
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.TabularNumbers
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.theme.rememberHaptics
import com.ravango.core.model.Plan
import com.ravango.core.ui.findActivity
import com.ravango.core.ui.message
import com.ravango.core.ui.messageRes
import com.ravango.core.ui.openUrl
import com.ravango.platform.billing.BillingAvailability
import com.ravango.platform.billing.BillingFailure
import com.ravango.platform.billing.PackOffer
import com.ravango.platform.billing.PlanChoice
import com.ravango.platform.billing.PlanOffer
import com.ravango.platform.billing.UnavailableReason
import kotlinx.coroutines.delay

@Composable
fun PaywallScreen(
    onClose: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: PaywallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val close by rememberUpdatedState(onClose)

    val msgPro = stringResource(R.string.paywall_msg_pro)
    val msgLifetime = stringResource(R.string.paywall_msg_lifetime)
    val msgPending = stringResource(R.string.paywall_msg_pending)
    val msgCancelled = stringResource(R.string.paywall_msg_cancelled)
    val msgOwned = stringResource(R.string.paywall_msg_owned)
    val msgFailed = stringResource(R.string.paywall_msg_failed)
    val msgFailedNetwork = stringResource(R.string.paywall_msg_failed_network)
    val msgRestoredPaid = stringResource(R.string.paywall_msg_restored_paid)
    val msgRestoredNone = stringResource(R.string.paywall_msg_restored_none)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is PaywallMessage.Activated -> if (message.plan == Plan.LIFETIME) msgLifetime else msgPro
                is PaywallMessage.CreditsAdded -> context.getString(R.string.paywall_msg_credits, formatNumber(message.credits))
                PaywallMessage.Pending -> msgPending
                PaywallMessage.Cancelled -> msgCancelled
                PaywallMessage.AlreadyOwned -> msgOwned
                is PaywallMessage.Failed -> if (message.reason == BillingFailure.NETWORK) msgFailedNetwork else msgFailed
                is PaywallMessage.Error -> context.getString(message.kind.messageRes())
                is PaywallMessage.Restored -> if (message.plan == Plan.FREE) msgRestoredNone else msgRestoredPaid
            }
            if (message is PaywallMessage.Activated) {
                haptics.perform(HapticEvent.CONFIRM)
                snackbar.showSnackbar(text)
                delay(600)
                close()
            } else {
                snackbar.showSnackbar(text)
            }
        }
    }

    PaywallContent(
        state = state,
        snackbar = snackbar,
        onClose = onClose,
        onRestore = viewModel::restore,
        onSelect = viewModel::select,
        onRetry = viewModel::loadCatalog,
        onManage = { viewModel.manageSubscriptionUrl()?.let(context::openUrl) },
        onBuyPack = { pack -> context.findActivity()?.let { viewModel.purchasePack(it, pack) } },
        onTerms = { if (state.termsUrl.isNotBlank()) context.openUrl(state.termsUrl) else onOpenTerms() },
        onPrivacy = { if (state.privacyUrl.isNotBlank()) context.openUrl(state.privacyUrl) else onOpenPrivacy() },
        onPurchase = { context.findActivity()?.let(viewModel::purchase) },
    )
}

/** Stateless paywall (rendered by screenshot tests for free / loading / unavailable / owned states). */
@Composable
internal fun PaywallContent(
    state: PaywallUiState,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onSelect: (PlanChoice) -> Unit,
    onRetry: () -> Unit,
    onManage: () -> Unit,
    onBuyPack: (PackOffer) -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    onPurchase: () -> Unit,
) {
    GradientBackground {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                item(key = "top") { TopRow(restoring = state.restoring, onRestore = onRestore, onClose = onClose) }
                item(key = "hero") { Hero(state) }
                if (state.isPaid) {
                    item(key = "owned") {
                        AlreadyPaidCard(state, onManage = onManage)
                    }
                }
                if (!state.isPaid || state.entitlements.plan == Plan.LIFETIME) {
                    item(key = "plans") {
                        PlansSection(state, onSelect = onSelect, onRetry = onRetry)
                    }
                }
                item(key = "benefits") { Benefits(state) }
                val packs = state.catalog?.packs.orEmpty()
                if (packs.isNotEmpty()) {
                    item(key = "packs-header") { CreditsHeader(state) }
                    items(packs, key = { it.product.productId }) { pack ->
                        PackRow(pack, enabled = !state.purchasing) { onBuyPack(pack) }
                    }
                }
                item(key = "footer") {
                    Footer(onTerms = onTerms, onPrivacy = onPrivacy)
                }
            }
            BottomBar(
                state = state,
                onPurchase = onPurchase,
                onContinueFree = onClose,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp))
    }
}

@Composable
private fun TopRow(restoring: Boolean, onRestore: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RgIconButton(Icons.Rounded.Close, stringResource(R.string.paywall_close), onClose, container = RgTheme.colors.surface.copy(alpha = 0.7f))
        Spacer(Modifier.weight(1f))
        RgTextButton(stringResource(R.string.paywall_restore), onRestore, enabled = !restoring)
    }
}

@Composable
private fun Hero(state: PaywallUiState) {
    val colors = RgTheme.colors
    val reduceMotion = RgTheme.reduceMotion
    val shift = if (reduceMotion) {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.3f) }
    } else {
        rememberInfiniteTransition(label = "hero").animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
    }
    val shape = RoundedCornerShape(Radius.xxl)
    Box(
        Modifier
            .padding(horizontal = Spacing.gutter)
            .fillMaxWidth()
            .softShadow(Elevation.high, shape, Palette.Ink)
            .clip(shape)
            .drawBehind {
                val w = size.width
                val h = size.height
                val p = shift.value
                // Midnight ink → brand navy, lit by a drifting gold glow and a cool blue rim light.
                drawRect(Brush.linearGradient(listOf(Palette.Ink, Color(0xFF141B3D), Palette.Blue800), start = Offset.Zero, end = Offset(w, h)))
                val gold = Offset(w * (0.78f - 0.12f * p), h * 0.12f)
                drawCircle(Brush.radialGradient(listOf(Palette.Gold400.copy(alpha = 0.42f), Color.Transparent), gold, h * 0.75f), h * 0.75f, gold)
                val blue = Offset(w * (0.1f + 0.1f * p), h * 1.05f)
                drawCircle(Brush.radialGradient(listOf(Palette.LogoSky.copy(alpha = 0.35f), Color.Transparent), blue, h * 0.7f), h * 0.7f, blue)
            }
            .border(1.dp, Brush.verticalGradient(listOf(Palette.Gold300.copy(alpha = 0.5f), Color.White.copy(alpha = 0.04f))), shape)
            .padding(Spacing.xxl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(Radius.md)).background(colors.proGradient),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.WorkspacePremium, null, tint = colors.onPro, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(Spacing.md))
                Text(stringResource(R.string.paywall_title), style = MaterialTheme.typography.titleMedium, color = Palette.Gold300)
            }
            Text(
                stringResource(state.feature?.headlineRes() ?: R.string.paywall_hero_default),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                stringResource(if (state.feature != null) R.string.paywall_hero_subtitle_feature else R.string.paywall_hero_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun AlreadyPaidCard(state: PaywallUiState, onManage: () -> Unit) {
    RgCard(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = RgTheme.colors.accent)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                stringResource(if (state.entitlements.plan == Plan.LIFETIME) R.string.paywall_already_lifetime_title else R.string.paywall_already_pro_title),
                style = MaterialTheme.typography.titleLarge,
                color = RgTheme.colors.textPrimary,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        if (state.entitlements.isTrial) Text(stringResource(R.string.paywall_already_trial), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.accent)
        Text(stringResource(R.string.paywall_already_body), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
        if (state.entitlements.plan == Plan.PRO && state.canManageSubscription) {
            Spacer(Modifier.height(Spacing.md))
            RgOutlineButton(stringResource(R.string.paywall_manage), onManage)
        }
    }
}

@Composable
private fun Benefits(state: PaywallUiState) {
    val highlighted = state.feature?.benefit()
    val pro = state.config.pro
    val rows: List<Triple<Benefit, ImageVector, String>> = listOf(
        Triple(Benefit.CAPTURE, Icons.Rounded.Videocam, stringResource(R.string.paywall_benefit_capture)),
        Triple(Benefit.BEAUTY, Icons.Rounded.Face, stringResource(R.string.paywall_benefit_beauty)),
        Triple(Benefit.PROMPTER, Icons.Rounded.PictureInPicture, stringResource(R.string.paywall_benefit_prompter)),
        Triple(Benefit.EDITOR, Icons.Rounded.Layers, stringResource(R.string.paywall_benefit_editor)),
        Triple(Benefit.AUDIO, Icons.Rounded.GraphicEq, stringResource(R.string.paywall_benefit_audio)),
        Triple(Benefit.EXPORT, Icons.Rounded.HighQuality, stringResource(R.string.paywall_benefit_export)),
        Triple(Benefit.AI, Icons.Rounded.AutoAwesome, stringResource(R.string.paywall_benefit_ai, formatNumber(pro.aiCreditsPerMonth))),
        Triple(Benefit.CLOUD, Icons.Rounded.CloudDone, stringResource(R.string.paywall_benefit_cloud, formatBytes(pro.cloudQuotaBytes))),
        Triple(Benefit.PRESETS, Icons.Rounded.Tune, stringResource(R.string.paywall_benefit_presets)),
    ).sortedByDescending { it.first == highlighted }

    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        Text(stringResource(R.string.paywall_benefits_title), style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary)
        Spacer(Modifier.height(Spacing.md))
        GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.md)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                rows.forEach { (benefit, icon, text) ->
                    val isHighlighted = benefit == highlighted
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tone = RgTheme.colors.tones.all[benefit.ordinal % RgTheme.colors.tones.all.size]
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(Radius.sm))
                                .background(if (isHighlighted) RgTheme.colors.proGradient else Brush.linearGradient(listOf(tone.container, tone.container))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(icon, null, tint = if (isHighlighted) RgTheme.colors.onPro else tone.content, modifier = Modifier.size(20.dp)) }
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                            color = RgTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(22.dp).clip(CircleShape).background(RgTheme.colors.tones.mint.container), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, tint = RgTheme.colors.tones.mint.content, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlansSection(state: PaywallUiState, onSelect: (PlanChoice) -> Unit, onRetry: () -> Unit) {
    val reduceMotion = RgTheme.reduceMotion
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.paywall_plans_title), style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary)
        val availability = state.availability
        val catalog = state.catalog
        AnimatedContent(
            targetState = when {
                availability is BillingAvailability.Unavailable -> 0
                state.loadingCatalog -> 1
                state.catalogError != null || catalog == null || catalog.isEmpty -> 2
                else -> 3
            },
            transitionSpec = { rgFadeThrough(reduceMotion) },
            label = "plans",
        ) { mode ->
            when (mode) {
                0 -> UnavailableCard(state, (availability as? BillingAvailability.Unavailable)?.reason, onRetry)
                1 -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    repeat(3) { ShimmerBox(Modifier.fillMaxWidth().height(88.dp), RoundedCornerShape(Radius.xl)) }
                    Text(stringResource(R.string.paywall_loading), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
                }
                2 -> ProductsMissingCard(state, onRetry)
                else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    val lifetimeOwned = state.entitlements.plan == Plan.LIFETIME
                    catalog?.yearly?.takeIf { !lifetimeOwned }?.let { PlanCard(it, state, catalog.yearlySavingsPercent, onSelect) }
                    catalog?.monthly?.takeIf { !lifetimeOwned }?.let { PlanCard(it, state, null, onSelect) }
                    catalog?.lifetime?.takeIf { !lifetimeOwned }?.let { PlanCard(it, state, null, onSelect) }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(offer: PlanOffer, state: PaywallUiState, savings: Int?, onSelect: (PlanChoice) -> Unit) {
    val colors = RgTheme.colors
    val selected = state.selected == offer.choice
    val borderColor by animateColorAsState(if (selected) colors.accent else colors.outline, Motion.quick(), label = "border")
    val container by animateColorAsState(if (selected) colors.accentSoft else colors.surface, Motion.quick(), label = "container")
    val shape = RoundedCornerShape(Radius.xl)
    val title = stringResource(
        when (offer.choice) {
            PlanChoice.YEARLY -> R.string.paywall_plan_yearly
            PlanChoice.MONTHLY -> R.string.paywall_plan_monthly
            PlanChoice.LIFETIME -> R.string.paywall_plan_lifetime
        },
    )
    val period = stringResource(
        when (offer.choice) {
            PlanChoice.YEARLY -> R.string.paywall_period_year
            PlanChoice.MONTHLY -> R.string.paywall_period_month
            PlanChoice.LIFETIME -> R.string.paywall_period_once
        },
    )
    val subtitle = when (offer.choice) {
        PlanChoice.YEARLY -> stringResource(R.string.paywall_per_month_equivalent, formatMicros(offer.price.priceMicros / 12, offer.price.currencyCode))
        PlanChoice.MONTHLY -> stringResource(R.string.paywall_monthly_note)
        PlanChoice.LIFETIME -> stringResource(
            R.string.paywall_lifetime_note,
            formatNumber(state.config.lifetime.aiCreditsPerMonth),
            formatBytes(state.config.lifetime.cloudQuotaBytes),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.softShadow(Elevation.mid, shape, colors.accentGlow) else Modifier)
            .clip(shape)
            .background(container)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .pressable(shape = shape, haptic = HapticEvent.SNAP) { onSelect(offer.choice) }
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (savings != null || offer.trialDays != null || offer.choice == PlanChoice.YEARLY) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (offer.choice == PlanChoice.YEARLY) RgTag(stringResource(R.string.paywall_best_value), color = colors.tones.mint.container, contentColor = colors.tones.mint.content)
                if (savings != null) RgTag(stringResource(R.string.paywall_save_badge, formatNumber(savings)), color = colors.tones.blush.container, contentColor = colors.tones.blush.content)
                offer.trialDays?.let { RgTag(stringResource(R.string.paywall_trial_badge, formatNumber(it)), color = colors.tones.sky.container, contentColor = colors.tones.sky.content) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val radioScale by animateFloatAsState(if (selected) 1f else 0f, Motion.spatialBouncy(), label = "radio")
            Box(
                Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (selected) colors.accent else colors.outlineStrong, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(12.dp).graphicsLayer { scaleX = radioScale; scaleY = radioScale }.clip(CircleShape).background(colors.accent))
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(horizontalAlignment = Alignment.End) {
                Text(offer.price.formattedPrice, style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), color = colors.textPrimary, maxLines = 1)
                Text(period, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UnavailableCard(state: PaywallUiState, reason: UnavailableReason?, onRetry: () -> Unit) {
    RgCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.paywall_unavailable_title), style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
        Spacer(Modifier.height(Spacing.sm))
        val text = when (reason) {
            UnavailableReason.STORE_NOT_SUPPORTED -> stringResource(R.string.paywall_unavailable_store, state.storeName)
            UnavailableReason.PLAY_BILLING_UNAVAILABLE -> stringResource(R.string.paywall_unavailable_play)
            UnavailableReason.SERVICE_UNAVAILABLE, null -> stringResource(R.string.paywall_unavailable_service, state.storeName)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary)
        if (reason != UnavailableReason.STORE_NOT_SUPPORTED) {
            Spacer(Modifier.height(Spacing.md))
            RgSecondaryButton(stringResource(R.string.paywall_retry), onRetry, size = RgButtonSize.MEDIUM)
        }
    }
}

@Composable
private fun ProductsMissingCard(state: PaywallUiState, onRetry: () -> Unit) {
    RgCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.paywall_products_missing, state.storeName), style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textPrimary)
        state.catalogError?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(it.message(), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(Spacing.md))
        RgSecondaryButton(stringResource(R.string.paywall_retry), onRetry, size = RgButtonSize.MEDIUM)
    }
}

@Composable
private fun CreditsHeader(state: PaywallUiState) {
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.paywall_credits_title), style = MaterialTheme.typography.titleLarge, color = RgTheme.colors.textPrimary)
        Text(stringResource(R.string.paywall_credits_subtitle), style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
        Text(
            stringResource(R.string.paywall_credits_balance, formatNumber(state.entitlements.aiCreditsRemaining)),
            style = MaterialTheme.typography.labelLarge,
            color = RgTheme.colors.accent,
        )
    }
}

@Composable
private fun PackRow(pack: PackOffer, enabled: Boolean, onBuy: () -> Unit) {
    RgCard(Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth(), contentPadding = PaddingValues(Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(RgTheme.colors.tones.lilac.container), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = RgTheme.colors.tones.lilac.content)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.paywall_credits_amount, formatNumber(pack.credits)), style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
                Text(pack.price.formattedPrice, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
            }
            RgSecondaryButton(stringResource(R.string.paywall_buy), onBuy, enabled = enabled, size = RgButtonSize.SMALL)
        }
    }
}

@Composable
private fun Footer(onTerms: () -> Unit, onPrivacy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        RgTextButton(stringResource(R.string.paywall_terms), onTerms, color = RgTheme.colors.textSecondary)
        Text("·", color = RgTheme.colors.textTertiary)
        RgTextButton(stringResource(R.string.paywall_privacy), onPrivacy, color = RgTheme.colors.textSecondary)
    }
}

@Composable
private fun BottomBar(state: PaywallUiState, onPurchase: () -> Unit, onContinueFree: () -> Unit) {
    val offer = state.catalog?.offer(state.selected)
    val purchasable = offer != null && state.availability is BillingAvailability.Available && !(state.isPaid && state.entitlements.plan != Plan.LIFETIME)
    GlassSurface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.lg, bottom = Spacing.sm),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (purchasable && offer != null) {
                val store = state.storeName
                val trialDays = offer.trialDays
                val cta = when {
                    offer.choice == PlanChoice.YEARLY && trialDays != null -> stringResource(R.string.paywall_cta_trial, formatNumber(trialDays))
                    offer.choice == PlanChoice.LIFETIME -> stringResource(R.string.paywall_cta_lifetime)
                    else -> stringResource(R.string.paywall_cta_subscribe)
                }
                val terms = when {
                    offer.choice == PlanChoice.YEARLY && trialDays != null ->
                        stringResource(R.string.paywall_terms_trial, formatNumber(trialDays), offer.price.formattedPrice, store)
                    offer.choice == PlanChoice.YEARLY -> stringResource(R.string.paywall_terms_yearly, offer.price.formattedPrice, store)
                    offer.choice == PlanChoice.MONTHLY -> stringResource(R.string.paywall_terms_monthly, offer.price.formattedPrice, store)
                    else -> stringResource(R.string.paywall_terms_lifetime, offer.price.formattedPrice)
                }
                RgPrimaryButton(
                    text = cta,
                    onClick = onPurchase,
                    modifier = Modifier.fillMaxWidth(),
                    loading = state.purchasing,
                    size = RgButtonSize.HERO,
                    brush = RgTheme.colors.proGradient,
                    // Dark ink on the gold Pro gradient: ≥8:1 everywhere (white measures ~1.6:1 on gold).
                    contentColor = RgTheme.colors.onPro,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(terms, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary, textAlign = TextAlign.Center)
            }
            RgTextButton(
                stringResource(if (state.isPaid) R.string.paywall_close else R.string.paywall_continue_free),
                onContinueFree,
                color = RgTheme.colors.textSecondary,
            )
        }
    }
}

package com.ravango.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.HapticEvent
import com.ravango.core.designsystem.theme.Motion
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgColors
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.AppLanguage
import com.ravango.core.model.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.ravango.core.ui.R as UiR

@Composable
fun OnboardingDestination(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { if (it is OnboardingEvent.Finished) onFinished() }
    }
    OnboardingContent(
        state = state,
        onPageSettled = viewModel::setPage,
        onLanguage = viewModel::selectLanguage,
        onTheme = viewModel::selectTheme,
        onAnalytics = viewModel::setAnalyticsConsent,
        onCrashReports = viewModel::setCrashReportsConsent,
        onToggleFocus = viewModel::toggleFocus,
        onFinish = viewModel::finish,
    )
}

/** Pastel background pairs per page; interpolated while swiping. */
private fun pageColors(colors: RgColors, page: Int): Pair<Color, Color> = with(colors.tones) {
    when (page) {
        0 -> sky.container to periwinkle.container
        1 -> periwinkle.container to sky.container
        2 -> lilac.container to blush.container
        else -> mint.container to sky.container
    }
}

@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    onPageSettled: (Int) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onAnalytics: (Boolean) -> Unit,
    onCrashReports: (Boolean) -> Unit,
    onToggleFocus: (CreatorFocus) -> Unit,
    onFinish: () -> Unit,
) {
    val colors = RgTheme.colors
    val pager = rememberPagerState(initialPage = state.page) { ONBOARDING_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val lastPage = ONBOARDING_PAGE_COUNT - 1

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect(onPageSettled)
    }
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .drawBehind {
                // Read pager position only while drawing: swiping repaints without recomposing.
                val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, lastPage.toFloat())
                val from = position.toInt()
                val to = (from + 1).coerceAtMost(lastPage)
                val f = position - from
                val (a1, b1) = pageColors(colors, from)
                val (a2, b2) = pageColors(colors, to)
                val top = lerp(a1, a2, f)
                val bottom = lerp(b1, b2, f)
                drawRect(Brush.verticalGradient(listOf(top, colors.background, bottom)))
                drawCircle(
                    Brush.radialGradient(listOf(top.copy(alpha = 0.9f), Color.Transparent), Offset(size.width * 0.8f, size.height * 0.15f), size.width * 0.7f),
                    size.width * 0.7f,
                    Offset(size.width * 0.8f, size.height * 0.15f),
                )
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopRow(
                page = pager.currentPage,
                onBack = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                onSkip = onFinish,
                skipEnabled = !state.finishing,
            )
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                beyondViewportPageCount = 0,
            ) { page ->
                val offset = { (pager.currentPage - page) + pager.currentPageOffsetFraction }
                when (page) {
                    0 -> LanguagePage(state.language, onLanguage, offset)
                    1 -> ValuePage(
                        title = stringResource(R.string.onboarding_prompter_title),
                        body = stringResource(R.string.onboarding_prompter_body),
                        offset = offset,
                    ) { PrompterIllustration(it) }
                    2 -> ValuePage(
                        title = stringResource(R.string.onboarding_beauty_title),
                        body = stringResource(R.string.onboarding_beauty_body),
                        offset = offset,
                    ) { BeautyEditIllustration(it) }
                    else -> PrivacyPage(state, onTheme, onAnalytics, onCrashReports, onToggleFocus, offset)
                }
            }
            BottomBar(
                pager = pager,
                isLast = pager.currentPage == lastPage,
                finishing = state.finishing,
                onNext = {
                    if (pager.currentPage >= lastPage) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
            )
        }
    }
}

@Composable
private fun TopRow(page: Int, onBack: () -> Unit, onSkip: () -> Unit, skipEnabled: Boolean) {
    val colors = RgTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(page > 0, enter = fadeIn(), exit = fadeOut()) {
            RgIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(UiR.string.action_back), onBack, container = colors.surface.copy(alpha = 0.6f))
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(page < ONBOARDING_PAGE_COUNT - 1, enter = fadeIn(), exit = fadeOut()) {
            RgTextButton(stringResource(UiR.string.action_skip), onSkip, color = colors.textSecondary, enabled = skipEnabled)
        }
    }
}

@Composable
private fun BottomBar(pager: PagerState, isLast: Boolean, finishing: Boolean, onNext: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        PageIndicator(pager)
        RgPrimaryButton(
            text = stringResource(if (isLast) R.string.onboarding_get_started else UiR.string.action_continue),
            onClick = onNext,
            loading = finishing,
            size = RgButtonSize.HERO,
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        )
    }
}

/**
 * Page dots that follow the finger: while swiping, the active pill stretches out of one dot and into the next and its
 * color blends between them. Everything is computed in the draw phase from the pager position (no recomposition per
 * frame). Mirrors in RTL.
 */
@Composable
private fun PageIndicator(pager: PagerState) {
    val colors = RgTheme.colors
    val description = stringResource(
        R.string.onboarding_page_indicator,
        (pager.currentPage + 1).toString().localizeDigits(),
        ONBOARDING_PAGE_COUNT.toString().localizeDigits(),
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inactive = colors.outlineStrong
    val active = colors.accent
    val dot = 8.dp
    val stretch = 20.dp
    val gap = 8.dp
    val count = ONBOARDING_PAGE_COUNT
    Canvas(
        Modifier
            .semantics { contentDescription = description }
            .size(width = dot * count + gap * (count - 1) + stretch, height = dot),
    ) {
        val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (count - 1).toFloat())
        val d = dot.toPx()
        val g = gap.toPx()
        val extra = stretch.toPx()
        var x = 0f
        repeat(count) { i ->
            val weight = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
            val w = d + extra * weight
            val left = if (rtl) size.width - x - w else x
            drawRoundRect(
                color = lerp(inactive, active, weight),
                topLeft = Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size(w, d),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(d / 2),
            )
            x += w + g
        }
    }
}

/** Parallax: the illustration moves slower than the page and fades as it leaves. */
private fun Modifier.parallax(offset: () -> Float, factor: Float): Modifier = graphicsLayer {
    val o = offset()
    translationX = o * size.width * factor
    alpha = 1f - (o.absoluteValue * 0.9f).coerceIn(0f, 0.9f)
    val s = 1f - o.absoluteValue * 0.08f
    scaleX = s
    scaleY = s
}

private fun Modifier.textParallax(offset: () -> Float): Modifier = graphicsLayer {
    val o = offset()
    translationX = o * size.width * 0.15f
    alpha = 1f - o.absoluteValue.coerceIn(0f, 1f)
}

@Composable
private fun LanguagePage(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, offset: () -> Float) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LanguageIllustration(Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1.25f).parallax(offset, 0.45f))
        Spacer(Modifier.height(Spacing.lg))
        Column(Modifier.textParallax(offset), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.displaySmall, color = RgTheme.colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.sm))
            Text(stringResource(R.string.onboarding_language_body), style = MaterialTheme.typography.bodyLarge, color = RgTheme.colors.textSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.xxl))
            Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Native names are intentionally not translated: users recognise their own language.
                LanguageOption("فارسی", stringResource(R.string.onboarding_language_persian_hint), "فا", selected == AppLanguage.PERSIAN) { onSelect(AppLanguage.PERSIAN) }
                LanguageOption("English", stringResource(R.string.onboarding_language_english_hint), "En", selected == AppLanguage.ENGLISH) { onSelect(AppLanguage.ENGLISH) }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

@Composable
private fun LanguageOption(name: String, hint: String, badge: String, selected: Boolean, onClick: () -> Unit) {
    val colors = RgTheme.colors
    val border by animateColorAsState(if (selected) colors.accent else colors.glassStroke, Motion.quick(), label = "border")
    val shape = RoundedCornerShape(Radius.xl)
    GlassSurface(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .clip(shape)
            .semantics { this.selected = selected }
            .pressable(role = Role.RadioButton, haptic = HapticEvent.SNAP, onClick = onClick),
        shape = shape,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (selected) colors.brandGradient else colors.brandGradientSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, style = MaterialTheme.typography.titleSmall, color = if (selected) Color.White else colors.accent)
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            }
            val checkScale by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy(), label = "check")
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer { scaleX = checkScale; scaleY = checkScale; alpha = checkScale.coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = colors.onAccent, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun ValuePage(title: String, body: String, offset: () -> Float, illustration: @Composable (Modifier) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        illustration(Modifier.fillMaxWidth().widthIn(max = 440.dp).aspectRatio(0.95f).parallax(offset, 0.5f))
        Spacer(Modifier.height(Spacing.xl))
        Column(Modifier.widthIn(max = 520.dp).textParallax(offset), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = RgTheme.colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.md))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = RgTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacyPage(
    state: OnboardingUiState,
    onTheme: (ThemeMode) -> Unit,
    onAnalytics: (Boolean) -> Unit,
    onCrashReports: (Boolean) -> Unit,
    onToggleFocus: (CreatorFocus) -> Unit,
    offset: () -> Float,
) {
    val colors = RgTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter).textParallax(offset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PrivacyIllustration(Modifier.size(132.dp))
        Text(stringResource(R.string.onboarding_privacy_title), style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.lg))
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    PromiseRow(Icons.Rounded.PhoneAndroid, colors.tones.mint.container, colors.tones.mint.content, stringResource(R.string.onboarding_promise_device_title), stringResource(R.string.onboarding_promise_device_body))
                    PromiseRow(Icons.Rounded.CloudOff, colors.tones.sky.container, colors.tones.sky.content, stringResource(R.string.onboarding_promise_consent_title), stringResource(R.string.onboarding_promise_consent_body))
                    PromiseRow(Icons.Rounded.Tune, colors.tones.periwinkle.container, colors.tones.periwinkle.content, stringResource(R.string.onboarding_promise_control_title), stringResource(R.string.onboarding_promise_control_body))
                }
            }

            SectionLabel(stringResource(R.string.onboarding_section_sharing))
            GlassSurface(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Column {
                    ToggleRow(stringResource(R.string.onboarding_analytics_title), stringResource(R.string.onboarding_analytics_body), state.analyticsConsent, onAnalytics)
                    ToggleRow(stringResource(R.string.onboarding_crash_title), stringResource(R.string.onboarding_crash_body), state.crashReportsConsent, onCrashReports)
                }
            }

            SectionLabel(stringResource(R.string.onboarding_section_theme))
            RgSegmentedControl(
                options = ThemeMode.entries,
                selected = state.themeMode,
                onSelect = onTheme,
                label = { mode ->
                    stringResource(
                        when (mode) {
                            ThemeMode.SYSTEM -> R.string.onboarding_theme_system
                            ThemeMode.LIGHT -> R.string.onboarding_theme_light
                            ThemeMode.DARK -> R.string.onboarding_theme_dark
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionLabel(stringResource(R.string.onboarding_section_focus))
            Text(stringResource(R.string.onboarding_focus_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CreatorFocus.entries.forEach { focus ->
                    val (label, icon) = focusLabel(focus)
                    RgChip(label, focus in state.focus, { onToggleFocus(focus) }, icon = icon)
                }
            }
            Spacer(Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun focusLabel(focus: CreatorFocus): Pair<String, ImageVector> = when (focus) {
    CreatorFocus.REELS -> stringResource(R.string.onboarding_focus_reels) to Icons.Rounded.MovieFilter
    CreatorFocus.YOUTUBE -> stringResource(R.string.onboarding_focus_youtube) to Icons.Rounded.SmartDisplay
    CreatorFocus.PODCAST -> stringResource(R.string.onboarding_focus_podcast) to Icons.Rounded.Mic
    CreatorFocus.BUSINESS -> stringResource(R.string.onboarding_focus_business) to Icons.Rounded.Business
    CreatorFocus.EDUCATION -> stringResource(R.string.onboarding_focus_education) to Icons.Rounded.School
    CreatorFocus.VLOG -> stringResource(R.string.onboarding_focus_vlog) to Icons.Rounded.Videocam
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PromiseRow(icon: ImageVector, bubble: Color, tint: Color, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(bubble), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .pressable(role = Role.Switch, haptic = if (checked) HapticEvent.TOGGLE_OFF else HapticEvent.TOGGLE_ON) { onChange(!checked) }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = RgTheme.colors.textPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = RgTheme.colors.textSecondary)
        }
        Spacer(Modifier.width(Spacing.md))
        RgSwitch(checked, onChange)
    }
}

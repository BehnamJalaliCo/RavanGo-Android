package com.ravango.feature.projects.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.component.rememberSnackbarHostState
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ProjectTemplate
import com.ravango.core.ui.messageRes
import com.ravango.feature.projects.R
import com.ravango.feature.projects.common.AspectFramePreview
import com.ravango.feature.projects.common.TemplateSheet
import com.ravango.feature.projects.common.aspectLabel
import com.ravango.feature.projects.common.currentLocale
import com.ravango.feature.projects.common.formatSuggestedDuration
import com.ravango.feature.projects.common.outlineHeadings
import com.ravango.core.designsystem.motion.rememberEntranceActive
import com.ravango.feature.projects.common.rememberHeadingTranslator
import com.ravango.core.designsystem.motion.staggeredEntrance
import com.ravango.feature.projects.common.templateDescription
import com.ravango.feature.projects.common.templateName
import com.ravango.feature.projects.common.TemplateDetailContent
import com.ravango.feature.projects.common.TemplateStartMode
import com.ravango.feature.projects.common.TemplateTexts
import com.ravango.core.designsystem.motion.RgExpandHost
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun TemplatesRoute(
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
    viewModel: TemplatesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarHostState()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TemplatesEvent.Navigate -> onNavigate(event.route)
                is TemplatesEvent.Error -> snackbar.showSnackbar(context.getString(event.kind.messageRes()))
            }
        }
    }
    BackHandler(enabled = state.selected != null) { viewModel.select(null) }
    TemplatesContent(state, snackbar, onBack, onSelect = viewModel::select, onDismiss = { viewModel.select(null) }, onStart = viewModel::start)
}

/**
 * Template gallery. Tapping a tile expands it into the start-project card (container transform) instead of opening a
 * detached bottom sheet; the scrim or back collapses it into its tile again.
 */
@Composable
internal fun TemplatesContent(
    state: TemplatesUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSelect: (ProjectTemplate?) -> Unit,
    onDismiss: () -> Unit = { onSelect(null) },
    onStart: (TemplateStartMode, TemplateTexts) -> Unit = { _, _ -> },
) {
    val entrance = rememberEntranceActive()
    val selectedId = state.selected?.id
    RgExpandHost(Modifier.fillMaxSize()) {
        RgScreen(
            title = stringResource(R.string.projects_templates_title),
            subtitle = stringResource(R.string.projects_templates_subtitle),
            onBack = onBack,
            snackbarHostState = snackbar,
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.huge),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.projects_templates_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RgTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
                itemsIndexed(state.templates, key = { _, t -> t.id }) { index, template ->
                    ExpandSource(key = template.id, expandedKey = selectedId, modifier = Modifier.staggeredEntrance(index, entrance)) {
                        TemplateCard(template, onClick = { onSelect(template) })
                    }
                }
            }
        }
        ExpandTarget(
            expandedKey = selectedId,
            onDismiss = onDismiss,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(Spacing.md)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        ) { key ->
            val template = state.templates.firstOrNull { it.id == key } ?: return@ExpandTarget
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(RgTheme.colors.backgroundElevated)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xl),
            ) {
                TemplateDetailContent(template, state.busyMode, state.captionsIncluded, onStart)
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ProjectTemplate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RgTheme.colors
    val accent = Color(template.accentArgb)
    val locale = currentLocale()
    val translate = rememberHeadingTranslator()
    val headings = outlineHeadings(template.scriptOutline)
    RgCard(modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(Radius.xl), contentPadding = PaddingValues(0.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(196.dp)
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.62f), accent.copy(alpha = 0.16f)))),
        ) {
            AspectFramePreview(template.aspectRatio, accent, template.autoCaptions, Modifier.fillMaxSize().padding(Spacing.lg))
            Row(Modifier.align(Alignment.TopStart).padding(Spacing.sm), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassPill(formatSuggestedDuration(template.suggestedDurationSec))
                GlassPill(aspectLabel(template.aspectRatio, locale))
            }
            if (template.autoCaptions) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(Spacing.sm).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.34f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ClosedCaption, stringResource(R.string.projects_template_auto_captions), tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(templateName(template), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(templateDescription(template), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            headings.take(3).forEach { heading ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(6.dp))
                    Text(translate(heading) ?: heading, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (headings.size > 3) {
                Text(
                    stringResource(R.string.projects_template_more_sections, com.ravango.core.common.format.formatNumber(headings.size - 3, locale)),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun GlassPill(text: String) {
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

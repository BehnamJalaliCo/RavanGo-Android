package com.ravango.feature.teleprompter.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.pressable
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.ContentDirection
import com.ravango.core.ui.resolve
import com.ravango.engine.teleprompter.PrompterSection
import com.ravango.engine.teleprompter.markup.ScriptMarkup
import com.ravango.feature.teleprompter.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionsSheet(
    sections: List<PrompterSection>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.prompter_sections), dark = true) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(horizontal = Spacing.md)) {
            itemsIndexed(sections, key = { _, s -> s.index }) { i, section ->
                SheetRow(
                    leading = (i + 1).toString().localizeDigits(),
                    text = section.title,
                    selected = i == currentIndex,
                    onClick = { onSelect(section.index) },
                )
            }
        }
    }
}

/** "Where should the prompter start?" — beginning, resume point, a section, or any paragraph. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartPointSheet(
    body: String,
    direction: ContentDirection,
    sections: List<PrompterSection>,
    resumeOffset: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(body) { ScriptMarkup.parse(body) }
    val paragraphs = remember(parsed) {
        parsed.paragraphs().map { range ->
            parsed.displayToSource(range.first) to parsed.text.substring(range.first, range.last + 1).trim()
        }
    }
    RgBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.prompter_start_point), dark = true) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = Spacing.md)) {
            item {
                QuickRow(Icons.Rounded.Start, stringResource(R.string.prompter_from_beginning)) { onSelect(0) }
            }
            if (resumeOffset != null) {
                item { QuickRow(Icons.Rounded.History, stringResource(R.string.prompter_resume)) { onSelect(resumeOffset) } }
            }
            if (sections.isNotEmpty()) {
                item { SheetHeader(stringResource(R.string.prompter_sections)) }
                itemsIndexed(sections, key = { _, s -> "s" + s.index }) { i, section ->
                    SheetRow((i + 1).toString().localizeDigits(), section.title, selected = false) { onSelect(section.charOffset) }
                }
            }
            item { SheetHeader(stringResource(R.string.prompter_tap_paragraph)) }
            itemsIndexed(paragraphs, key = { i, _ -> "p$i" }) { i, (offset, text) ->
                ParagraphRow(index = i + 1, text = text, direction = direction) { onSelect(offset) }
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = Spacing.sm, top = Spacing.lg, bottom = Spacing.sm),
    )
}

@Composable
private fun QuickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .pressable(onClick = onClick)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = RgTheme.colors.accent)
        Spacer(Modifier.width(Spacing.md))
        Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SheetRow(leading: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (selected) RgTheme.colors.accent.copy(alpha = 0.18f) else Color.Transparent)
            .pressable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Text(leading, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(Spacing.md))
        Text(
            text,
            color = if (selected) RgTheme.colors.accent else Color.White,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ParagraphRow(index: Int, text: String, direction: ContentDirection, onClick: () -> Unit) {
    val ui = LocalLayoutDirection.current
    val dir = remember(text, direction, ui) { direction.resolve(text, ui) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(Color.White.copy(alpha = 0.05f))
            .pressable(onClick = onClick)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.prompter_paragraph_n, index.toString().localizeDigits()), color = RgTheme.colors.accent, style = MaterialTheme.typography.labelSmall)
        CompositionLocalProvider(LocalLayoutDirection provides dir) {
            Text(text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
    }
}

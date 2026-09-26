package com.ravango.app.navigation

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.chipColors
import com.mikepenz.aboutlibraries.ui.compose.m3.libraryColors
import com.ravango.app.R
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.theme.Dimens
import com.ravango.core.designsystem.theme.Radius
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.R as DsR

/**
 * Licenses: the commercial brand font (with its usage restrictions, as the licence requires users to be informed),
 * bundled open fonts, and every third-party library (generated at build time by the AboutLibraries plugin).
 * The library list is themed with the RavanGo tokens (transparent rows over the gradient, pastel badges, hairline
 * dividers aligned to the gutter) so it doesn't read as a stock Material page.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    val ravaghLicensed = booleanResource(DsR.bool.brand_font_is_licensed_ravagh)
    val colors = RgTheme.colors
    RgScreen(title = stringResource(R.string.licenses_title), onBack = onBack) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            colors = LibraryDefaults.libraryColors(
                libraryBackgroundColor = Color.Transparent,
                libraryContentColor = colors.textPrimary,
                versionChipColors = LibraryDefaults.chipColors(containerColor = colors.surfaceMuted, contentColor = colors.textSecondary),
                licenseChipColors = LibraryDefaults.chipColors(containerColor = colors.accentSoft, contentColor = colors.onAccentSoft),
                fundingChipColors = LibraryDefaults.chipColors(containerColor = colors.pastelMint, contentColor = colors.textPrimary),
                dialogBackgroundColor = colors.backgroundElevated,
                dialogContentColor = colors.textPrimary,
                dialogConfirmButtonColor = colors.accent,
            ),
            header = {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        FontLicenseCard(
                            icon = Icons.Rounded.FontDownload,
                            title = stringResource(R.string.licenses_brand_font_title, stringResource(DsR.string.brand_font_name)),
                            body = if (ravaghLicensed) stringResource(R.string.licenses_ravagh_body) else stringResource(R.string.licenses_brand_fallback_body),
                        )
                        FontLicenseCard(
                            icon = Icons.Rounded.Gavel,
                            title = stringResource(R.string.licenses_open_fonts_title),
                            body = stringResource(R.string.licenses_open_fonts_body),
                        )
                        Text(
                            stringResource(R.string.licenses_libraries_header),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(top = Spacing.lg, start = Spacing.xs),
                        )
                    }
                }
            },
            divider = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .height(Dimens.hairline)
                        .background(colors.divider),
                )
            },
            footer = { item { Spacer(Modifier.navigationBarsPadding()) } },
        )
    }
}

@Composable
private fun FontLicenseCard(icon: ImageVector, title: String, body: String) {
    val colors = RgTheme.colors
    RgCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(Dimens.listIcon).clip(RoundedCornerShape(Radius.sm)).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(Dimens.iconMedium + 2.dp)) }
            Spacer(Modifier.width(Spacing.md))
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.padding(top = Spacing.md))
    }
}

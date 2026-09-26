package com.ravango.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.android.rememberLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.ravango.app.R
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgScreen
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.designsystem.R as DsR

/**
 * Licenses: the commercial brand font (with its usage restrictions, as the licence requires users to be informed),
 * bundled open fonts, and every third-party library (generated at build time by the AboutLibraries plugin).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val libraries by rememberLibraries(R.raw.aboutlibraries)
    val ravaghLicensed = booleanResource(DsR.bool.brand_font_is_licensed_ravagh)
    RgScreen(title = stringResource(R.string.licenses_title), onBack = onBack) { padding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
            header = {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        FontLicenseCard(
                            title = stringResource(R.string.licenses_brand_font_title, stringResource(DsR.string.brand_font_name)),
                            body = if (ravaghLicensed) stringResource(R.string.licenses_ravagh_body) else stringResource(R.string.licenses_brand_fallback_body),
                        )
                        FontLicenseCard(
                            title = stringResource(R.string.licenses_open_fonts_title),
                            body = stringResource(R.string.licenses_open_fonts_body),
                        )
                        Text(
                            stringResource(R.string.licenses_libraries_header),
                            style = MaterialTheme.typography.titleLarge,
                            color = RgTheme.colors.textPrimary,
                            modifier = Modifier.padding(top = Spacing.md),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun FontLicenseCard(title: String, body: String) {
    RgCard(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (title.contains("OFL")) Icons.Rounded.Gavel else Icons.Rounded.FontDownload, null, tint = RgTheme.colors.accent, modifier = Modifier.padding(end = 10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = RgTheme.colors.textSecondary, modifier = Modifier.padding(top = Spacing.sm))
    }
}

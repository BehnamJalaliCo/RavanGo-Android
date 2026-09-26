package com.ravango.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The screenshot harness switches resource qualifiers but not [Locale.getDefault], which the digit/date formatters
 * read. Sync it with the composition's configuration so Persian captures show Persian digits and dates.
 */
@Composable
internal fun WithDeviceLocale(content: @Composable () -> Unit) {
    Locale.setDefault(LocalConfiguration.current.locales[0])
    content()
}

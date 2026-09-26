package com.ravango.feature.beauty

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ravango.core.model.ProFeature

/**
 * CONTRACT — beauty & makeup control panel embedded in Camera Studio (dark glass bottom panel).
 * Sliders for every beauty/makeup option (0–100), color pickers for makeup, presets, before/after.
 * Pro-gated options call [onRequirePro] instead of applying.
 */
@Composable
fun BeautyPanel(
    modifier: Modifier = Modifier,
    onOpenPresets: () -> Unit,
    onRequirePro: (ProFeature) -> Unit,
) {
    BeautyPanelImpl(modifier, onOpenPresets, onRequirePro)
}

@Composable
internal fun BeautyPanelImpl(modifier: Modifier, onOpenPresets: () -> Unit, onRequirePro: (ProFeature) -> Unit) {
    // Placeholder until the beauty UI is implemented.
}

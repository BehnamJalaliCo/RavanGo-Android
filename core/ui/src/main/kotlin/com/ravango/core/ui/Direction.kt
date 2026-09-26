package com.ravango.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ravango.core.model.ContentDirection

/** Direction of the first strong character (Unicode bidi rule P2), used for "auto" content direction. */
fun detectDirection(text: CharSequence): LayoutDirection? {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return LayoutDirection.Rtl
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return LayoutDirection.Ltr
        }
    }
    return null
}

/** Resolves a content direction preference against the text and the UI direction. */
fun ContentDirection.resolve(text: CharSequence, uiDirection: LayoutDirection): LayoutDirection = when (this) {
    ContentDirection.RTL -> LayoutDirection.Rtl
    ContentDirection.LTR -> LayoutDirection.Ltr
    ContentDirection.AUTO -> detectDirection(text) ?: uiDirection
}

val isRtl: Boolean
    @Composable @ReadOnlyComposable get() = LocalLayoutDirection.current == LayoutDirection.Rtl

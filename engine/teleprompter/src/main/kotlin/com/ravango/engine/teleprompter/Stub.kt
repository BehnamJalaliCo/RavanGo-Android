package com.ravango.engine.teleprompter

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ravango.core.model.TeleprompterSettings

// Placeholder implementations; replaced by the teleprompter engine implementation.
@Composable
internal fun TeleprompterViewImpl(
    text: String,
    settings: TeleprompterSettings,
    controller: PrompterController,
    modifier: Modifier,
    startCharOffset: Int,
    interactive: Boolean,
    onFontSizeChange: ((Float) -> Unit)?,
) { TODO("teleprompter engine") }

@Composable
internal fun rememberPrompterControllerImpl(text: String, settings: TeleprompterSettings, startCharOffset: Int): PrompterController =
    TODO("teleprompter engine")

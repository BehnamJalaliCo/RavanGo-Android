package com.ravango.feature.teleprompter.floating

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgSlider
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.TeleprompterSettings
import com.ravango.engine.teleprompter.PrompterPhase
import com.ravango.engine.teleprompter.TeleprompterView
import com.ravango.engine.teleprompter.rememberPrompterController
import com.ravango.feature.teleprompter.R
import com.ravango.core.ui.R as UiR

/** Compact prompter shown in the overlay window: drag bar, prompter, control strip, resize grip. */
@Composable
internal fun FloatingPrompterWindow(host: FloatingHost) {
    val script by host.script.collectAsState()
    val base by host.settings.collectAsState()
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        val s = script
        val b = base
        if (s == null || b == null) {
            Box(Modifier.fillMaxSize().background(Color(0xE6141220)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.prompter_loading), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
            }
            return@Box
        }
        FloatingContent(host, s.id, s.title, s.body, s.startCharOffset, b)
    }
}

@Composable
private fun FloatingContent(host: FloatingHost, scriptId: String, title: String, body: String, startOffset: Int, base: TeleprompterSettings) {
    // Window-local adjustments (not persisted): smaller default text and adjustable transparency.
    var opacity by remember(scriptId) { mutableFloatStateOf(base.backgroundOpacity.coerceIn(0.2f, 0.85f)) }
    var fontSize by remember(scriptId) { mutableFloatStateOf(base.fontSizeSp.coerceAtMost(30f)) }
    var showOpacity by remember { mutableStateOf(false) }
    val settings = base.copy(fontSizeSp = fontSize, backgroundOpacity = opacity, eyeLinePosition = base.eyeLinePosition.coerceAtMost(0.4f))
    val controller = rememberPrompterController(body, settings, startOffset)
    val snapshot by controller.snapshot.collectAsState()
    val playing = snapshot.phase == PrompterPhase.SCROLLING || snapshot.phase == PrompterPhase.COUNTDOWN

    LaunchedEffect(controller) {
        host.commands.collect { command ->
            when (command) {
                FloatingCommand.TOGGLE -> controller.toggle()
            }
        }
    }
    LaunchedEffect(playing) { host.onPlayingChanged(playing) }
    LaunchedEffect(snapshot.phase) {
        when (snapshot.phase) {
            PrompterPhase.PAUSED -> host.saveReadingPosition(snapshot.readingCharOffset)
            PrompterPhase.FINISHED -> host.saveReadingPosition(0)
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Drag bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xF0141220))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        host.moveBy(drag.x, drag.y)
                    }
                }
                .padding(horizontal = Spacing.xs, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.DragIndicator, stringResource(R.string.prompter_floating_move), tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(4.dp).size(20.dp))
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RgIconButton(Icons.Rounded.Opacity, stringResource(R.string.prompter_floating_opacity), { showOpacity = !showOpacity }, size = 34.dp, iconSize = 18.dp, glass = true, selected = showOpacity)
            Spacer(Modifier.width(4.dp))
            RgIconButton(Icons.Rounded.Close, stringResource(UiR.string.action_close), {
                host.saveReadingPosition(snapshot.readingCharOffset)
                host.close()
            }, size = 34.dp, iconSize = 18.dp, glass = true)
        }
        AnimatedVisibility(showOpacity) {
            Row(Modifier.fillMaxWidth().background(Color(0xF0141220)).padding(horizontal = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prompter_floating_opacity), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(Spacing.sm))
                RgSlider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f), trackColor = Color.White.copy(alpha = 0.15f))
            }
        }
        TeleprompterView(
            text = body,
            settings = settings,
            controller = controller,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            startCharOffset = startOffset,
            interactive = true,
            onFontSizeChange = { fontSize = it },
        )
        // Control strip
        Box(Modifier.fillMaxWidth().background(Color(0xF0141220))) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.xs, end = 28.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                RgIconButton(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.prompter_pause else R.string.prompter_play),
                    controller::toggle,
                    size = 38.dp,
                    container = RgTheme.colors.accent,
                    tint = RgTheme.colors.onAccent,
                )
                RgIconButton(Icons.Rounded.Remove, stringResource(R.string.prompter_speed_down), { controller.nudgeSpeed(-10) }, size = 34.dp, iconSize = 18.dp, glass = true)
                Text(
                    stringResource(R.string.prompter_wpm_value, snapshot.wordsPerMinute.toString().localizeDigits()),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                RgIconButton(Icons.Rounded.Add, stringResource(R.string.prompter_speed_up), { controller.nudgeSpeed(10) }, size = 34.dp, iconSize = 18.dp, glass = true)
                Spacer(Modifier.weight(1f))
                RgIconButton(Icons.Rounded.TextDecrease, stringResource(R.string.prompter_font_smaller), {
                    fontSize = (fontSize - 2f).coerceAtLeast(TeleprompterSettings.MIN_FONT_SP)
                }, size = 34.dp, iconSize = 18.dp, glass = true)
                RgIconButton(Icons.Rounded.TextIncrease, stringResource(R.string.prompter_font_larger), {
                    fontSize = (fontSize + 2f).coerceAtMost(TeleprompterSettings.MAX_FONT_SP)
                }, size = 34.dp, iconSize = 18.dp, glass = true)
            }
            // Resize grip: always at the physical bottom-right corner (window coordinates are absolute).
            Box(
                Modifier
                    .align(AbsoluteAlignment.BottomRight)
                    .size(30.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            host.resizeBy(drag.x, drag.y)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.OpenInFull, stringResource(R.string.prompter_floating_resize), tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

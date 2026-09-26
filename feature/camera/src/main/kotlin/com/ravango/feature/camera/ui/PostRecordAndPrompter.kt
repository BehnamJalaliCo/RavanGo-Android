@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.ravango.feature.camera.ui

import com.ravango.core.designsystem.component.RgSpinner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.core.common.format.formatDuration
import com.ravango.core.designsystem.component.RgBottomSheet
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.model.PrompterPlacement
import com.ravango.core.ui.message
import com.ravango.engine.teleprompter.PrompterController
import com.ravango.engine.teleprompter.PrompterPhase
import com.ravango.engine.teleprompter.TeleprompterView
import com.ravango.feature.camera.PostRecordState
import com.ravango.feature.camera.PrompterUi
import com.ravango.feature.camera.R

@Composable
internal fun PostRecordSheet(
    state: PostRecordState,
    onOpenEditor: (String) -> Unit,
    onRecordAnother: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    RgBottomSheet(onDismiss = onDismiss, dark = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            when (state) {
                PostRecordState.Saving -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RgSpinner(Modifier.size(28.dp), color = RgTheme.colors.accent, strokeWidth = 3.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.camera_saving_take), style = MaterialTheme.typography.titleMedium, color = Color.White)
                }
                is PostRecordState.Saved -> {
                    val take = state.take
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LastTakeThumbnail(take.thumbnailPath, onClick = { onOpenEditor(take.projectId) })
                        Spacer(Modifier.width(Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(if (take.isAudio) R.string.camera_saved_audio else R.string.camera_saved_take), style = MaterialTheme.typography.titleLarge, color = Color.White)
                            val sub = buildString {
                                append(formatDuration(take.durationUs))
                                if (take.publishedUri != null) append(" · ").append(stringResource(R.string.camera_saved_gallery))
                            }
                            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                    RgPrimaryButton(
                        stringResource(R.string.camera_open_editor),
                        onClick = { onOpenEditor(take.projectId) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        RgSecondaryButton(stringResource(R.string.camera_record_another), onRecordAnother, Modifier.weight(1f), size = RgButtonSize.MEDIUM)
                        RgOutlineButton(stringResource(R.string.camera_share), onShare, Modifier.weight(1f), icon = Icons.Rounded.Share, contentColor = Color.White)
                    }
                }
                is PostRecordState.Failed -> {
                    Text(stringResource(R.string.camera_save_failed), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(state.kind.message(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                    RgSecondaryButton(stringResource(R.string.camera_close), onDismiss, Modifier.fillMaxWidth(), size = RgButtonSize.MEDIUM)
                }
            }
        }
    }
}

/**
 * Teleprompter area near the lens with mini controls. Placement/height/opacity come from the effective prompter
 * settings; the view itself honours the rest (fonts, mirroring, eye line…).
 */
@Composable
internal fun PrompterOverlay(
    prompter: PrompterUi,
    controller: PrompterController,
    topInset: androidx.compose.ui.unit.Dp,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = prompter.settings
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val alignment = when (settings.placement) {
        PrompterPlacement.TOP -> Alignment.TopCenter
        PrompterPlacement.CENTER -> Alignment.Center
        PrompterPlacement.BOTTOM -> Alignment.BottomCenter
    }
    Box(modifier.fillMaxSize().padding(top = if (settings.placement == PrompterPlacement.TOP) topInset else 0.dp), contentAlignment = alignment) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(settings.areaHeightFraction.coerceIn(0.2f, 0.8f)),
        ) {
            TeleprompterView(
                text = prompter.script.body,
                settings = settings,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)),
                startCharOffset = prompter.script.startCharOffset,
            )
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val playing = snapshot.phase == PrompterPhase.SCROLLING || snapshot.phase == PrompterPhase.COUNTDOWN
                RgIconButton(Icons.Rounded.Remove, stringResource(R.string.camera_prompter_slower), { controller.nudgeSpeed(-10) }, size = 34.dp, iconSize = 18.dp, glass = true)
                RgIconButton(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.camera_prompter_pause else R.string.camera_prompter_play),
                    { controller.toggle() },
                    size = 38.dp,
                    iconSize = 20.dp,
                    selected = playing,
                )
                RgIconButton(Icons.Rounded.Add, stringResource(R.string.camera_prompter_faster), { controller.nudgeSpeed(10) }, size = 34.dp, iconSize = 18.dp, glass = true)
                RgIconButton(Icons.Rounded.VisibilityOff, stringResource(R.string.camera_prompter_hide), onHide, size = 34.dp, iconSize = 18.dp, glass = true)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

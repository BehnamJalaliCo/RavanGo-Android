package com.ravango.feature.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.FlashMode
import com.ravango.core.model.GridType
import com.ravango.core.model.SafeAreaType
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.feature.camera.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** "+0.7" style EV label from a compensation index and step. */
internal fun evLabel(index: Int, step: Float): String {
    val ev = index * step
    val text = when {
        abs(ev) < 0.05f -> "0"
        ev > 0 -> String.format(Locale.US, "+%.1f", ev)
        else -> String.format(Locale.US, "%.1f", ev)
    }
    return "$text EV".localizeDigits()
}

/** Shutter speed label: "1/60" or "0.5\"". */
internal fun shutterLabel(ns: Long): String {
    val seconds = ns / 1e9
    val text = if (seconds >= 0.3) String.format(Locale.US, "%.1f\"", seconds) else "1/${(1.0 / seconds).roundToLong()}"
    return text.localizeDigits()
}

internal fun isoLabel(iso: Int): String = "ISO $iso".localizeDigits()

internal fun kelvinLabel(kelvin: Int): String = "${kelvin}K".localizeDigits()

internal fun videoModeLabel(size: VideoSize, fps: Int): String = "${size.label} · $fps".localizeDigits()

internal fun aspectLabel(aspect: AspectRatioSpec): String = aspect.label.localizeDigits()

internal fun zoomLabel(ratio: Float): String {
    val rounded = (ratio * 10f).roundToInt() / 10f
    val text = if (abs(rounded - rounded.roundToInt()) < 0.05f) rounded.roundToInt().toString() else String.format(Locale.US, "%.1f", rounded)
    return "$text×".localizeDigits()
}

/** Standard shutter speeds (ns) for the manual shutter slider. */
internal val SHUTTER_STOPS_NS: List<Long> = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 100, 60, 50, 30, 25, 15, 8, 4)
    .map { 1_000_000_000L / it }

@Composable
internal fun FlashMode.label(): String = stringResource(
    when (this) {
        FlashMode.OFF -> R.string.camera_flash_off
        FlashMode.TORCH -> R.string.camera_flash_torch
        FlashMode.SCREEN -> R.string.camera_flash_screen
    },
)

@Composable
internal fun GridType.label(): String = stringResource(
    when (this) {
        GridType.NONE -> R.string.camera_grid_none
        GridType.THIRDS -> R.string.camera_grid_thirds
        GridType.GOLDEN -> R.string.camera_grid_golden
        GridType.SQUARE -> R.string.camera_grid_square
        GridType.CENTER -> R.string.camera_grid_center
    },
)

@Composable
internal fun SafeAreaType.label(): String = stringResource(
    when (this) {
        SafeAreaType.NONE -> R.string.camera_safe_none
        SafeAreaType.TITLE_SAFE -> R.string.camera_safe_title
        SafeAreaType.ACTION_SAFE -> R.string.camera_safe_action
        SafeAreaType.SOCIAL_VERTICAL -> R.string.camera_safe_social
    },
)

@Composable
internal fun StabilizationMode.label(): String = stringResource(
    when (this) {
        StabilizationMode.OFF -> R.string.camera_stab_off
        StabilizationMode.STANDARD -> R.string.camera_stab_standard
        StabilizationMode.PREVIEW_OPTIMIZED -> R.string.camera_stab_enhanced
    },
)

@Composable
internal fun WhiteBalanceMode.label(): String = stringResource(
    when (this) {
        WhiteBalanceMode.AUTO -> R.string.camera_wb_auto
        WhiteBalanceMode.INCANDESCENT -> R.string.camera_wb_incandescent
        WhiteBalanceMode.FLUORESCENT -> R.string.camera_wb_fluorescent
        WhiteBalanceMode.WARM_FLUORESCENT -> R.string.camera_wb_warm_fluorescent
        WhiteBalanceMode.DAYLIGHT -> R.string.camera_wb_daylight
        WhiteBalanceMode.CLOUDY -> R.string.camera_wb_cloudy
        WhiteBalanceMode.TWILIGHT -> R.string.camera_wb_twilight
        WhiteBalanceMode.SHADE -> R.string.camera_wb_shade
        WhiteBalanceMode.MANUAL_KELVIN -> R.string.camera_wb_kelvin
    },
)

@Composable
internal fun CaptureMode.label(): String = stringResource(
    when (this) {
        CaptureMode.VIDEO_WITH_AUDIO -> R.string.camera_capture_video_audio
        CaptureMode.VIDEO_ONLY -> R.string.camera_capture_video_only
        CaptureMode.AUDIO_ONLY -> R.string.camera_capture_audio_only
    },
)

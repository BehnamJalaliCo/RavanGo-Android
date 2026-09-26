package com.ravango.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.CameraSettings
import com.ravango.core.model.CaptureMode
import com.ravango.core.model.Entitlements
import com.ravango.core.model.GridType
import com.ravango.core.model.LensFacing
import com.ravango.core.model.StabilizationMode
import com.ravango.core.model.VideoCodec
import com.ravango.core.model.VideoSize
import com.ravango.core.model.WhiteBalanceMode
import com.ravango.core.testing.captureAllVariants
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.effects.SpriteAtlas
import com.ravango.engine.camera.ActiveCameraConfig
import com.ravango.engine.camera.CameraState
import com.ravango.engine.camera.PreviewFrame
import com.ravango.engine.camera.RecordingPhase
import com.ravango.engine.camera.RecordingStatus
import com.ravango.engine.camera.capability.CameraCapabilities
import com.ravango.engine.camera.capability.HardwareLevel
import com.ravango.engine.camera.capability.LensKind
import com.ravango.engine.camera.capability.LensOption
import com.ravango.engine.camera.capability.OutputSize
import com.ravango.engine.camera.capability.SensorArea
import com.ravango.engine.camera.capability.VideoMode
import com.ravango.feature.camera.ui.EffectsSheetContent
import com.ravango.feature.camera.ui.EffectsTab
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CameraStudioScreenshotTest {

    private val atlas by lazy { SpriteAtlas.draw().asImageBitmap() }

    @Test
    fun studioIdle() = captureAllVariants(name = "camera-studio") {
        Studio(sampleState().copy(effects = EffectsState(filter = LiveFilter.WARM, filterIntensity = 80)))
    }

    @Test
    fun studioLensCarousel() = captureAllVariants(name = "camera-studio-lenses") {
        Studio(sampleState().copy(lensTrayOpen = true, effects = EffectsState(lens = Lens.SUNGLASSES)))
    }

    @Test
    fun studioRecording() = captureAllVariants(name = "camera-studio-recording") {
        Studio(
            sampleState().copy(recording = RecordingStatus(phase = RecordingPhase.RECORDING, durationUs = 83_000_000, bytesWritten = 96_000_000, remainingSeconds = 5_400)),
            clock = RecordingStatus(phase = RecordingPhase.RECORDING, durationUs = 83_000_000, bytesWritten = 96_000_000, remainingSeconds = 5_400),
        )
    }

    @Test
    fun effectsSheetFilters() = captureAllVariants(name = "camera-effects-filters") {
        SheetPanel {
            EffectsSheetContent(
                tab = EffectsTab.FILTERS,
                effects = EffectsState(filter = LiveFilter.TEAL_ORANGE, filterIntensity = 70),
                status = EffectsStatus(),
                entitlements = Entitlements(),
                hasBackgroundImage = false,
                onTab = {}, onFilter = {}, onIntensity = {}, onBackground = {}, onPickPhoto = {}, onRequirePro = {}, onClearAll = {},
            )
        }
    }

    @Test
    fun effectsSheetBackground() = captureAllVariants(name = "camera-effects-background") {
        SheetPanel {
            EffectsSheetContent(
                tab = EffectsTab.BACKGROUND,
                effects = EffectsState(background = BackgroundEffect.Blur(65)),
                status = EffectsStatus(backgroundWarmingUp = true),
                entitlements = Entitlements(),
                hasBackgroundImage = false,
                onTab = {}, onFilter = {}, onIntensity = {}, onBackground = {}, onPickPhoto = {}, onRequirePro = {}, onClearAll = {},
            )
        }
    }

    @Composable
    private fun Studio(state: CameraUiState, clock: RecordingStatus = RecordingStatus()) = WithDeviceLocale {
        StudioFrame {
            CameraStudioContent(
                state = state,
                chrome = StudioChrome(),
                actions = remember { StudioActions() },
                level = remember { mutableStateOf(AudioLevel(peakDbfs = -12f, rmsDbfs = -24f, clipping = false)) },
                clipping = remember { mutableStateOf(false) },
                recordingClock = remember { mutableStateOf(clock) },
                atlas = atlas,
                snackbar = remember { SnackbarHostState() },
                surface = { PreviewPlaceholder() },
            )
        }
    }

    @Composable
    private fun SheetPanel(content: @Composable () -> Unit) = WithDeviceLocale {
        StudioFrame {
            Box(Modifier.fillMaxSize()) {
                PreviewPlaceholder()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                        .background(Palette.Ink900.copy(alpha = 0.97f))
                        .padding(top = 24.dp),
                ) { content() }
            }
        }
    }

    private fun sampleState(): CameraUiState {
        val caps = CameraCapabilities(
            cameraId = "1", facing = LensFacing.FRONT, hardwareLevel = HardwareLevel.FULL, sensorOrientation = 270,
            isLogicalMultiCamera = false, physicalCameraIds = emptyList(), focalLengthsMm = listOf(2.2f), equivalentFocalLengthMm = 24f,
            lensKind = LensKind.WIDE, streamSizes = listOf(VideoSize(1920, 1080)),
            videoModes = listOf(VideoMode(VideoSize(1920, 1080), 30, setOf(VideoCodec.H264)), VideoMode(VideoSize(1920, 1080), 60, setOf(VideoCodec.H264))),
            fpsRanges = listOf(30 to 30), codecs = setOf(VideoCodec.H264), manualSensor = true, isoRange = 100..3200,
            exposureTimeRangeNs = 100_000L..100_000_000L, manualFocus = false, minFocusDistanceDiopters = 0f, autoFocus = true,
            exposureCompensationRange = -12..12, exposureCompensationStep = 1f / 6f,
            whiteBalanceModes = listOf(WhiteBalanceMode.AUTO, WhiteBalanceMode.DAYLIGHT), manualKelvin = true,
            aeLockAvailable = true, awbLockAvailable = true, flashAvailable = false,
            stabilizationModes = listOf(StabilizationMode.OFF, StabilizationMode.STANDARD), electronicStabilization = true,
            opticalStabilization = false, hdrOptions = emptySet(), tenBitHdrOnDevice = false, maxAfRegions = 1, maxAeRegions = 1,
            zoomRange = 1f..8f, zoomRatioApi = true, activeArray = SensorArea(0, 0, 4000, 3000), timestampRealtime = true,
        )
        val config = ActiveCameraConfig(
            cameraId = "1", facing = LensFacing.FRONT, streamSize = VideoSize(1920, 1080), resolution = VideoSize(1920, 1080),
            output = OutputSize(1080, 1920), frameRate = 30, codec = VideoCodec.H264, aspectRatio = AspectRatioSpec.Portrait9x16,
            stabilization = StabilizationMode.STANDARD, hdr = false, mirrorPreview = true, mirrorRecording = false,
        )
        return CameraUiState(
            initialized = true,
            settings = CameraSettings(grid = GridType.NONE, captureMode = CaptureMode.VIDEO_WITH_AUDIO, timerSeconds = 3),
            cameraState = CameraState.Streaming(config),
            capabilities = caps,
            lenses = listOf(
                LensOption("u", "1", LensFacing.FRONT, LensKind.ULTRA_WIDE, 0.6f, 0.6f),
                LensOption("w", "1", LensFacing.FRONT, LensKind.WIDE, 1f, 1f),
            ),
            facings = listOf(LensFacing.FRONT, LensFacing.BACK),
            previewFrame = PreviewFrame(1080, 1920, 0),
            storageEstimateSeconds = 3 * 3600L,
            entitlements = Entitlements(),
            effectsStatus = EffectsStatus(),
        )
    }
}

/**
 * A photo-like stand-in for the live camera preview: a softly lit room with bokeh lights and a person in the
 * foreground, so glass controls are reviewed against realistic contrast (bright and dark areas).
 */
@Composable
internal fun PreviewPlaceholder() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        // Room: warm wall to cool shadow, a bright window on the start side.
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6D7A86), Color(0xFFB9A48E), Color(0xFF5E4B45))))
        drawRect(Brush.linearGradient(listOf(Color(0xFFF4EBDD), Color(0xFFD9CDBE)), Offset(0f, 0f), Offset(w * 0.3f, h * 0.4f)), Offset(w * 0.04f, h * 0.1f), Size(w * 0.3f, h * 0.38f))
        drawRect(Color(0xFF8A6E5E).copy(alpha = 0.5f), Offset(w * 0.18f, h * 0.1f), Size(w * 0.012f, h * 0.38f))
        // Bokeh lights.
        val lights = listOf(0.72f to 0.14f, 0.86f to 0.22f, 0.64f to 0.3f, 0.9f to 0.4f, 0.78f to 0.08f, 0.55f to 0.18f)
        lights.forEachIndexed { i, (x, y) ->
            val r = w * (0.035f + 0.012f * (i % 3))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A8).copy(alpha = 0.85f), Color(0xFFFFC56B).copy(alpha = 0f)), Offset(w * x, h * y), r), r, Offset(w * x, h * y))
        }
        // Head proportions follow the width (portrait frame), centred a little above the middle.
        val cx = w * 0.5f
        val headW = w * 0.36f
        val headH = headW * 1.3f
        val headTop = h * 0.3f
        // Shoulders.
        val shoulderY = headTop + headH + w * 0.16f
        val body = Path().apply {
            moveTo(w * 0.02f, h)
            cubicTo(w * 0.04f, shoulderY + w * 0.1f, cx - w * 0.24f, shoulderY, cx, shoulderY)
            cubicTo(cx + w * 0.24f, shoulderY, w * 0.96f, shoulderY + w * 0.1f, w * 0.98f, h)
            close()
        }
        drawPath(body, Brush.verticalGradient(listOf(Color(0xFF3F4A6B), Color(0xFF232838)), shoulderY, h))
        // Neck and face.
        drawRect(Color(0xFFC98E6E), Offset(cx - w * 0.07f, headTop + headH * 0.8f), Size(w * 0.14f, shoulderY - headTop - headH * 0.8f + 2f))
        drawOval(Brush.radialGradient(listOf(Color(0xFFF1C3A2), Color(0xFFD39A78)), Offset(cx - headW * 0.1f, headTop + headH * 0.4f), headW * 0.8f), Offset(cx - headW / 2, headTop), Size(headW, headH))
        // Hair.
        val hair = Path().apply {
            moveTo(cx - headW * 0.55f, headTop + headH * 0.55f)
            cubicTo(cx - headW * 0.66f, headTop - headH * 0.1f, cx - headW * 0.1f, headTop - headH * 0.16f, cx + headW * 0.1f, headTop - headH * 0.12f)
            cubicTo(cx + headW * 0.5f, headTop - headH * 0.06f, cx + headW * 0.66f, headTop + headH * 0.2f, cx + headW * 0.55f, headTop + headH * 0.55f)
            cubicTo(cx + headW * 0.45f, headTop + headH * 0.2f, cx + headW * 0.1f, headTop + headH * 0.08f, cx, headTop + headH * 0.1f)
            cubicTo(cx - headW * 0.2f, headTop + headH * 0.12f, cx - headW * 0.45f, headTop + headH * 0.25f, cx - headW * 0.55f, headTop + headH * 0.55f)
            close()
        }
        drawPath(hair, Color(0xFF3A2620))
        // Brows, eyes and lips.
        val eyeY = headTop + headH * 0.46f
        for (side in listOf(-1f, 1f)) {
            val ex = cx + side * headW * 0.2f
            drawOval(Color(0xFF5A3A30), Offset(ex - headW * 0.12f, eyeY - headH * 0.09f), Size(headW * 0.24f, headH * 0.025f))
            drawOval(Color.White.copy(alpha = 0.9f), Offset(ex - headW * 0.09f, eyeY - headH * 0.025f), Size(headW * 0.18f, headH * 0.05f))
            drawCircle(Color(0xFF3B2A25), headW * 0.04f, Offset(ex, eyeY))
        }
        drawOval(Color(0xFFC0666A), Offset(cx - headW * 0.14f, headTop + headH * 0.74f), Size(headW * 0.28f, headH * 0.06f))
    }
}

/** The harness switches resource qualifiers but not Locale.getDefault(), which the digit formatters read. */
@Composable
private fun WithDeviceLocale(content: @Composable () -> Unit) {
    java.util.Locale.setDefault(LocalConfiguration.current.locales[0])
    content()
}

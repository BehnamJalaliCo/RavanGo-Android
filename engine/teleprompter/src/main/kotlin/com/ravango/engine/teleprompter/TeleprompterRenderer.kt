package com.ravango.engine.teleprompter

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import com.ravango.core.common.format.localizeDigits
import com.ravango.core.model.TeleprompterSettings
import com.ravango.core.ui.resolve
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun rememberPrompterControllerImpl(text: String, settings: TeleprompterSettings, startCharOffset: Int): PrompterController {
    val scope = rememberCoroutineScope()
    val engine = remember(scope) { PrompterEngine(scope) }
    // Parsing is linear and cheap; binding during composition lets the first frame already show the text.
    remember(engine, text, startCharOffset) { engine.bind(text, startCharOffset) }
    SideEffect { engine.applySettings(settings) }
    DisposableEffect(engine) { onDispose { engine.dispose() } }
    return engine
}

@Composable
internal fun TeleprompterViewImpl(
    text: String,
    settings: TeleprompterSettings,
    controller: PrompterController,
    modifier: Modifier,
    startCharOffset: Int,
    interactive: Boolean,
    onFontSizeChange: ((Float) -> Unit)?,
) {
    val engine = controller as? PrompterEngine
        ?: error("TeleprompterView requires a controller from rememberPrompterController()")
    remember(engine, text, startCharOffset) { engine.bind(text, startCharOffset) }

    val uiDirection = LocalLayoutDirection.current
    val contentDirection = remember(text, settings.direction, uiDirection) { settings.direction.resolve(text, uiDirection) }
    val textColor = Color(settings.textColor)
    val highlightColor = Color(settings.highlightColor)
    val background = Color(settings.backgroundColor).copy(alpha = settings.backgroundOpacity.coerceIn(0f, 1f))
    val parsed = engine.parsed
    val annotated = rememberPrompterAnnotatedString(parsed, textColor, highlightColor, settings.fontWeight)
    val inlineContent = remember(highlightColor) { prompterInlineContent(highlightColor) }
    val style = remember(settings, contentDirection) { prompterTextStyle(settings, contentDirection) }
    val density = LocalDensity.current
    val lineHeightPx = with(density) { (settings.fontSizeSp * settings.lineSpacing).sp.toPx() }

    val fontSize = rememberUpdatedState(settings.fontSizeSp)
    val fontCallback = rememberUpdatedState(onFontSizeChange)

    BoxWithConstraints(modifier.background(background).clipToBounds()) {
        val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else with(density) { 480.dp.toPx() }
        val eyeY = viewportHeight * settings.eyeLinePosition.coerceIn(0.05f, 0.9f)
        SideEffect { engine.setViewport(viewportHeight) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (settings.mirrorHorizontal) -1f else 1f
                    scaleY = if (settings.mirrorVertical) -1f else 1f
                }
                .prompterGestures(
                    engine = engine,
                    enabled = interactive,
                    tapToPause = settings.tapToPause,
                    mirrorVertical = settings.mirrorVertical,
                    fontSize = fontSize,
                    onFontSizeChange = fontCallback,
                ),
        ) {
            // Text layer, faded with a luminance-independent alpha mask (works on transparent backgrounds too).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(fadeMask(size.height, eyeY, lineHeightPx, settings.dimReadText), blendMode = BlendMode.DstIn)
                    },
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                    BasicText(
                        text = annotated,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(settings.textWidthFraction.coerceIn(0.3f, 1f))
                            .padding(horizontal = settings.horizontalPaddingDp.coerceIn(0f, 120f).dp)
                            // Only this lambda reads the fast-changing position: no recomposition per frame.
                            .graphicsLayer { translationY = eyeY - engine.readingY }
                            .wrapContentHeight(align = Alignment.Top, unbounded = true),
                        style = style,
                        onTextLayout = engine::onLayout,
                        inlineContent = inlineContent,
                    )
                }
            }
            if (settings.showEyeLine) EyeLine(eyeY, highlightColor)
            CountdownOverlay(engine, highlightColor)
        }
    }
}

/** Vertical alpha mask: dims read text above the eye line and softly fades both edges. */
private fun fadeMask(height: Float, eyeY: Float, lineHeight: Float, dimRead: Boolean): Brush {
    if (height <= 0f) return Brush.verticalGradient(listOf(Color.White, Color.White))
    fun f(y: Float) = (y / height).coerceIn(0f, 1f)
    val stops = ArrayList<Pair<Float, Color>>()
    val half = lineHeight / 2f
    if (dimRead) {
        stops += 0f to Color.White.copy(alpha = 0.10f)
        stops += f(eyeY - half * 3f) to Color.White.copy(alpha = 0.38f)
        stops += f(eyeY - half * 1.05f) to Color.White
    } else {
        stops += 0f to Color.White.copy(alpha = 0.25f)
        stops += f(minOf(eyeY - half, height * 0.08f)) to Color.White
    }
    stops += 0.84f.coerceAtLeast(f(eyeY + half * 2f)) to Color.White
    stops += 1f to Color.White.copy(alpha = 0.3f)
    // Gradient stops must be non-decreasing.
    var previous = 0f
    val ordered = stops.map { (stop, color) -> val s = maxOf(previous, stop); previous = s; s to color }
    return Brush.verticalGradient(*ordered.toTypedArray(), startY = 0f, endY = height)
}

@Composable
private fun EyeLine(eyeY: Float, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val marker = 9.dp.toPx()
        val inset = 4.dp.toPx()
        drawLine(
            color.copy(alpha = 0.14f),
            start = Offset(inset + marker * 1.6f, eyeY),
            end = Offset(size.width - inset - marker * 1.6f, eyeY),
            strokeWidth = 1.dp.toPx(),
        )
        val left = Path().apply {
            moveTo(inset, eyeY - marker * 0.7f)
            lineTo(inset + marker, eyeY)
            lineTo(inset, eyeY + marker * 0.7f)
            close()
        }
        val right = Path().apply {
            moveTo(size.width - inset, eyeY - marker * 0.7f)
            lineTo(size.width - inset - marker, eyeY)
            lineTo(size.width - inset, eyeY + marker * 0.7f)
            close()
        }
        drawPath(left, color.copy(alpha = 0.85f))
        drawPath(right, color.copy(alpha = 0.85f))
    }
}

@Composable
private fun CountdownOverlay(engine: PrompterEngine, accent: Color) {
    val snapshot by engine.snapshot.collectAsState()
    AnimatedVisibility(
        visible = snapshot.phase == PrompterPhase.COUNTDOWN,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(260)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(168.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = snapshot.countdownRemaining,
                    transitionSpec = {
                        (scaleIn(tween(320), initialScale = 1.7f) + fadeIn(tween(220))) togetherWith
                            (scaleOut(tween(260), targetScale = 0.5f) + fadeOut(tween(200)))
                    },
                    label = "countdown",
                ) { value ->
                    Text(
                        text = value.coerceAtLeast(1).toString().localizeDigits(),
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}

private fun Modifier.prompterGestures(
    engine: PrompterEngine,
    enabled: Boolean,
    tapToPause: Boolean,
    mirrorVertical: Boolean,
    fontSize: State<Float>,
    onFontSizeChange: State<((Float) -> Unit)?>,
): Modifier {
    if (!enabled) return this
    return pointerInput(engine, tapToPause, mirrorVertical) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var mode = GESTURE_NONE
            var totalDx = 0f
            var totalDy = 0f
            var zoom = 1f
            val baseSize = fontSize.value
            var reported = baseSize
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2 && onFontSizeChange.value != null) {
                    if (mode == GESTURE_DRAG) engine.endDrag(0f)
                    mode = GESTURE_ZOOM
                    zoom *= event.calculateZoom()
                    val size = (baseSize * zoom).coerceIn(TeleprompterSettings.MIN_FONT_SP, TeleprompterSettings.MAX_FONT_SP)
                    val rounded = (size * 2f).roundToInt() / 2f
                    if (rounded != reported) {
                        reported = rounded
                        onFontSizeChange.value?.invoke(rounded)
                    }
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (mode == GESTURE_ZOOM) {
                    event.changes.forEach { it.consume() }
                    continue
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                tracker.addPosition(change.uptimeMillis, change.position)
                if (mode == GESTURE_NONE && abs(totalDy) > slop && abs(totalDy) > abs(totalDx)) {
                    mode = GESTURE_DRAG
                    engine.beginDrag()
                }
                if (mode == GESTURE_DRAG) {
                    // Screen-space drag → content-space: a vertically mirrored text moves opposite to the finger.
                    engine.dragBy(if (mirrorVertical) -delta.y else delta.y)
                    change.consume()
                }
            }
            when (mode) {
                GESTURE_DRAG -> {
                    val velocity = tracker.calculateVelocity().y
                    engine.endDrag(if (mirrorVertical) -velocity else velocity)
                }
                GESTURE_NONE -> if (tapToPause && abs(totalDx) <= slop && abs(totalDy) <= slop) engine.toggle()
            }
        }
    }
}

private const val GESTURE_NONE = 0
private const val GESTURE_DRAG = 1
private const val GESTURE_ZOOM = 2

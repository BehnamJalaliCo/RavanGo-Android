package com.ravango.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ravango.core.designsystem.theme.RavanGoTheme
import com.ravango.core.media.PcmDecoder
import com.ravango.core.model.AudioClip
import com.ravango.core.model.AudioTrack
import com.ravango.core.model.AudioTrackKind
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.Entitlements
import com.ravango.core.model.ExportSettings
import com.ravango.core.model.MediaKind
import com.ravango.core.model.MediaSource
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.OverlayTrack
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleTrack
import com.ravango.core.model.Transform2D
import com.ravango.core.model.Transition
import com.ravango.core.model.TransitionType
import com.ravango.core.model.VideoClip
import com.ravango.core.testing.PHONE_QUALIFIERS
import com.ravango.core.testing.ScreenVariant
import com.ravango.core.testing.captureScreen
import com.ravango.engine.editor.export.EditorError
import com.ravango.engine.editor.export.ExportState
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.media.WaveformProvider
import com.ravango.feature.editor.export.ExportActions
import com.ravango.feature.editor.export.ExportContent
import com.ravango.feature.editor.export.ExportUiState
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class EditorScreenshotTest {

    private fun video(uri: String, seconds: Int) = MediaSource(uri = uri, kind = MediaKind.VIDEO, durationUs = seconds * 1_000_000L, width = 1080, height = 1920)

    private val clips = listOf(
        VideoClip(id = "c1", source = video("content://v/intro", 6), transitionOut = Transition(TransitionType.FADE_BLACK)),
        VideoClip(id = "c2", source = video("content://v/talk", 14), speed = 1.5f),
        VideoClip(id = "c3", source = video("content://v/broll", 5), muted = true),
        VideoClip(id = "c4", source = MediaSource("content://i/logo", MediaKind.IMAGE, 0, 1080, 1080), stillDurationUs = 3_000_000),
    )

    private val document = EditorDocument(
        mainTrack = clips,
        overlayTracks = listOf(
            OverlayTrack(
                id = "o1",
                items = listOf(
                    OverlayItem.Text(id = "t1", startUs = 1_000_000, endUs = 5_500_000, text = "سه عادت ساده", transform = Transform2D(centerY = 0.2f)),
                    OverlayItem.Sticker(id = "st1", startUs = 7_000_000, endUs = 9_000_000, emoji = "🔥"),
                ),
            ),
        ),
        audioTracks = listOf(
            AudioTrack(id = "a1", kind = AudioTrackKind.MUSIC, ducking = true, clips = listOf(AudioClip(id = "m1", source = MediaSource("content://a/music", MediaKind.AUDIO, 40_000_000), startUs = 0, loop = true))),
            AudioTrack(id = "a2", kind = AudioTrackKind.VOICEOVER, clips = listOf(AudioClip(id = "vo1", source = MediaSource("content://a/vo", MediaKind.AUDIO, 6_000_000), startUs = 3_000_000))),
        ),
        subtitles = SubtitleTrack(
            cues = listOf(
                SubtitleCue(id = "q1", startUs = 0, endUs = 2_400_000, text = "سلام دوستان!"),
                SubtitleCue(id = "q2", startUs = 2_400_000, endUs = 5_600_000, text = "امروز دربارهٔ سه عادت ساده صحبت می‌کنم"),
                SubtitleCue(id = "q3", startUs = 5_600_000, endUs = 9_000_000, text = "که بهره‌وری‌ام را دو برابر کرد."),
            ),
        ),
    )

    private val loaded = EditorUiState(
        loading = false,
        projectId = "p1",
        projectTitle = "ولاگ صبحگاهی — سه عادت ساده",
        document = document,
        canUndo = true,
    )

    @Test fun editorTimeline() = studio("editor-main") { Editor(loaded, playheadUs = 4_200_000) }
    @Test fun editorClipSelected() = studio("editor-clip-selected") { Editor(loaded.copy(selection = Selection.Clip("c2"), tool = EditorTool.EDIT), playheadUs = 8_000_000) }
    @Test fun editorSmallPhone() = studio("editor-small", SMALL_PHONE) { Editor(loaded.copy(selection = Selection.Clip("c2")), playheadUs = 8_000_000) }
    @Test fun editorEmpty() = studio("editor-empty") { Editor(EditorUiState(loading = false, projectTitle = "پروژهٔ تازه"), playheadUs = 0) }
    @Test fun editorBusy() = studio("editor-busy") { Editor(loaded.copy(busy = BusyTask(R.string.editor_tool_captions, progress = 0.42f, cancellable = true)), playheadUs = 4_200_000) }

    @Test fun toolPanels() {
        EditorTool.entries.forEach { tool ->
            val selection = when (tool) {
                EditorTool.EDIT, EditorTool.FILTERS, EditorTool.ADJUST, EditorTool.AUDIO -> Selection.Clip("c2")
                EditorTool.TEXT -> Selection.Overlay("t1")
                EditorTool.STICKERS -> Selection.Overlay("st1")
                EditorTool.MUSIC -> Selection.Audio("m1")
                EditorTool.CAPTIONS -> Selection.Cue("q2")
                else -> Selection.None
            }
            studio("editor-tool-${tool.name.lowercase()}") { Editor(loaded.copy(tool = tool, selection = selection), playheadUs = 4_200_000) }
        }
    }

    // ------------------------------------------------------------------ export

    private val exportBase = ExportUiState(
        loading = false,
        title = "ولاگ صبحگاهی — سه عادت ساده",
        document = document,
        settings = ExportSettings(),
        entitlements = Entitlements(),
        hevcSupported = true,
    )

    @Test fun exportForm() = studio("export-form") { Export(exportBase) }
    @Test fun exportFormSmall() = studio("export-form-small", SMALL_PHONE) { Export(exportBase) }
    @Test fun exportRunning() = studio("export-running") { Export(exportBase.copy(export = ExportState.Running("p1", 0.63f, System.currentTimeMillis() - 40_000))) }
    @Test fun exportDone() = studio("export-done") {
        Export(exportBase.copy(export = ExportState.Succeeded("p1", File("out.mp4"), "content://media/1", 48_300_000, 28_000_000, 1080, 1920, File("out.srt"), false)))
    }
    @Test fun exportFailed() = studio("export-failed") { Export(exportBase.copy(export = ExportState.Failed("p1", EditorError.STORAGE_FULL, null))) }

    // ------------------------------------------------------------------ helpers

    @Composable
    private fun Editor(state: EditorUiState, playheadUs: Long) {
        val thumbnails = remember { seededThumbnails() }
        val waveforms = remember { seededWaveforms() }
        EditorContent(
            state = state,
            actions = noOpActions(thumbnails),
            playhead = remember { mutableStateOf(playheadUs) },
            isPlaying = remember { mutableStateOf(false) },
            thumbnails = thumbnails,
            waveforms = waveforms,
            onBack = {},
            onAddMedia = {},
            onTransition = {},
            surface = { StillFrame() },
        )
    }

    @Composable
    private fun Export(state: ExportUiState) = ExportContent(state, noOpExport(), onBack = {}, onBackToEditor = {})

    @Composable
    private fun StillFrame() {
        val bitmap = remember { frameBitmap(540, 960, 0) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.DarkGray)) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }

    /** Studio screens are always dark: capture the Persian (RTL) and English (LTR) variants. */
    private fun studio(name: String, qualifiers: String = PHONE_QUALIFIERS, content: @Composable () -> Unit) {
        listOf(ScreenVariant.FA_DARK, ScreenVariant.EN_LIGHT).forEach { v ->
            captureScreen(name, v, qualifiers) { RavanGoTheme(darkTheme = true, reduceMotion = true) { WithDeviceLocale(content) } }
        }
    }

    private fun seededThumbnails(): ThumbnailProvider {
        val provider = ThumbnailProvider(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined)
        @Suppress("UNCHECKED_CAST")
        val cache = ThumbnailProvider::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(provider) as LruCache<String, Bitmap>
        val heightPx = (58 * RuntimeEnvironment.getApplication().resources.displayMetrics.density + 0.5f).toInt()
        clips.forEachIndexed { index, clip ->
            var t = 0L
            while (t <= clip.source.durationUs.coerceAtLeast(3_000_000)) {
                cache.put("${clip.source.uri}@${t / 100_000}@$heightPx", frameBitmap(heightPx * 9 / 16, heightPx, index * 97 + (t / 250_000).toInt()))
                t += 250_000
            }
            // Filter previews use the clip's middle frame at 64dp.
            val filterPx = (64 * RuntimeEnvironment.getApplication().resources.displayMetrics.density + 0.5f).toInt()
            val mid = (clip.trimStartUs + clip.trimEndUs) / 2
            cache.put("${clip.source.uri}@${mid / 100_000}@$filterPx", frameBitmap(filterPx * 9 / 16, filterPx, index * 97 + 5))
        }
        return provider
    }

    private fun seededWaveforms(): WaveformProvider {
        val app = RuntimeEnvironment.getApplication()
        val provider = WaveformProvider(PcmDecoder(app, Dispatchers.Unconfined))
        @Suppress("UNCHECKED_CAST")
        val cache = WaveformProvider::class.java.getDeclaredField("cache").apply { isAccessible = true }.get(provider) as LruCache<String, FloatArray>
        document.audioTracks.flatMap { it.clips }.forEach { clip ->
            val n = WaveformProvider.bucketsFor(clip.source.durationUs)
            cache.put(clip.source.uri, FloatArray(n) { i -> (0.25f + 0.75f * abs(sin(i * 0.37f) * sin(i * 0.051f + clip.source.uri.length))).coerceIn(0.05f, 1f) })
        }
        return provider
    }

    /** A soft two-tone "video frame" so clip thumbnails and the preview look like real footage. */
    private fun frameBitmap(w: Int, h: Int, seed: Int): Bitmap {
        val palette = listOf(0xFF5B6C8F, 0xFFB58463, 0xFF3E6B5E, 0xFF8C5A7A, 0xFF6F7F9A, 0xFFC49A6C).map { it.toInt() }
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val a = palette[seed % palette.size]
        val b = palette[(seed / 3 + 2) % palette.size]
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.5f, h * 0.42f, w * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF })
        c.drawRect(w * 0.2f, h * 0.62f, w * 0.8f, h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44000000 })
        return bmp
    }

    private fun noOpActions(thumbnails: ThumbnailProvider): EditorActions =
        Proxy.newProxyInstance(EditorActions::class.java.classLoader, arrayOf(EditorActions::class.java)) { _, method, _ ->
            when {
                method.name == "getThumbnails" -> thumbnails
                method.returnType == java.lang.Boolean.TYPE -> true
                else -> null
            }
        } as EditorActions

    private fun noOpExport(): ExportActions =
        Proxy.newProxyInstance(ExportActions::class.java.classLoader, arrayOf(ExportActions::class.java)) { _, _, _ -> null } as ExportActions

    private companion object {
        const val SMALL_PHONE = "w360dp-h740dp"
    }
}

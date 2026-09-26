package com.ravango.engine.editor.composition

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.media.toUri
import com.ravango.core.model.AudioClip
import com.ravango.core.model.AudioTrack
import com.ravango.core.model.EditorDocument
import com.ravango.core.model.MediaKind
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.VideoClip
import com.ravango.engine.editor.audio.GainEnvelope
import com.ravango.engine.editor.audio.GainEnvelopeAudioProcessor
import com.ravango.engine.editor.audio.SpeechActivityAnalyzer
import com.ravango.engine.editor.audio.VoiceCleanupAudioProcessor
import com.ravango.engine.editor.effects.CanvasEffect
import com.ravango.engine.editor.effects.ColorGradeEffect
import com.ravango.engine.editor.effects.GradeParams
import com.ravango.engine.editor.effects.OverlayBitmapFactory
import com.ravango.engine.editor.effects.PipEffect
import com.ravango.engine.editor.effects.SubtitleOverlay
import com.ravango.engine.editor.effects.TimedItemOverlay
import com.ravango.engine.editor.effects.TransitionEffect
import com.ravango.engine.editor.effects.WatermarkOverlay
import com.ravango.engine.editor.effects.overlayEffects
import com.ravango.engine.editor.timeline.TimelineMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** How a composition is going to be used. */
data class BuildOptions(
    /** Short side of the output canvas in pixels. */
    val shortSide: Int = CanvasSizing.PREVIEW_SHORT_SIDE,
    val forExport: Boolean = false,
    val includeAudio: Boolean = true,
    val watermark: Boolean = false,
    /** Export frame rate; frames above it are dropped. Null keeps the source rate (preview). */
    val targetFrameRate: Int? = null,
    /** Draw subtitles into the picture. */
    val burnSubtitles: Boolean = true,
)

data class BuiltComposition(
    val composition: Composition,
    val width: Int,
    val height: Int,
    val durationUs: Long,
)

/**
 * Turns an [EditorDocument] into a Media3 [Composition] — the same graph drives [androidx.media3.transformer.CompositionPlayer]
 * preview and [androidx.media3.transformer.Transformer] export, so what you see is what you export.
 *
 * Structure:
 * - Sequence 0 (primary): the main track. Each clip is an [EditedMediaItem] with trim (clipping), speed
 *   ([Effects.createExperimentalSpeedChangingEffect]), per-clip audio processors (voice cleanup, gain envelope) and video
 *   effects ([ColorGradeEffect], [CanvasEffect] which normalises every clip to the canvas size). Photos/freeze frames are
 *   image items with a duration and frame rate.
 * - Additional audio-only sequences: music/voice-over/extracted tracks positioned with gaps (looping expanded), plus
 *   PiP audio when a PiP has volume.
 * - Composition-level video effects evaluated on output-timeline time: transitions/fades, PiP ([PipEffect]),
 *   text/sticker/image overlays, burnt-in subtitles and the free-plan watermark.
 */
@Singleton
class CompositionBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val speech: SpeechActivityAnalyzer,
) {
    val bitmapFactory = OverlayBitmapFactory(context)

    suspend fun build(doc: EditorDocument, options: BuildOptions): BuiltComposition? = withContext(io) {
        if (doc.mainTrack.isEmpty()) return@withContext null
        val (width, height) = CanvasSizing.size(doc.canvas.aspectRatio, options.shortSide)
        val duration = doc.durationUs
        val placements = TimelineMath.placements(doc)

        // ---- ducking: speech on the main track, in timeline time.
        val duckTimeline: List<LongRange> = if (options.includeAudio && doc.audioTracks.any { it.ducking && !it.muted }) {
            placements.flatMap { p ->
                val c = p.clip
                if (c.muted || !c.source.hasAudio || c.source.kind != MediaKind.VIDEO || c.reversed) emptyList()
                else speech.speechRanges(c.source.uri).mapNotNull { r ->
                    val s = maxOf(r.first, c.trimStartUs)
                    val e = minOf(r.last, c.trimEndUs)
                    if (e <= s) null else {
                        val a = TimelineMath.sourceToTimeline(c, p.startUs, s) ?: return@mapNotNull null
                        val b = TimelineMath.sourceToTimeline(c, p.startUs, e) ?: return@mapNotNull null
                        minOf(a, b)..maxOf(a, b)
                    }
                }
            }
        } else emptyList()

        // ---- main sequence
        val mainItems = placements.map { p -> mainItem(p.clip, doc, width, height, options) }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences += EditedMediaItemSequence.Builder(mainItems)
            .experimentalSetForceAudioTrack(options.includeAudio)
            .build()

        // ---- audio tracks
        if (options.includeAudio) {
            doc.audioTracks.filter { !it.muted && it.clips.isNotEmpty() }.forEach { track ->
                audioSequence(track, duration, if (track.ducking) duckTimeline else emptyList())?.let { sequences += it }
            }
            doc.overlayTracks.filter { !it.hidden }.flatMap { it.items }.filterIsInstance<OverlayItem.Video>()
                .filter { it.volume > 0f && it.source.hasAudio }
                .forEach { pip -> pipAudioSequence(pip, duration)?.let { sequences += it } }
        }

        // ---- composition-level video effects
        val videoEffects = ArrayList<Effect>()
        options.targetFrameRate?.let { videoEffects += FrameDropEffect.createDefaultFrameDropEffect(it.toFloat()) }
        val windows = TransitionTiming.windows(doc)
        if (windows.isNotEmpty()) videoEffects += TransitionEffect(windows)
        for (track in doc.overlayTracks) {
            if (track.hidden) continue
            val pending = ArrayList<TextureOverlay>()
            for (item in track.items.sortedBy { it.startUs }) {
                if (item is OverlayItem.Video) {
                    videoEffects += PipEffect(item)
                } else {
                    val bitmap = bitmapFactory.bitmapFor(item, width, height) ?: continue
                    pending += TimedItemOverlay(item, bitmapFactory, width, height, bitmap)
                }
            }
            videoEffects += overlayEffects(pending)
        }
        val subs = doc.subtitles
        if (subs.visible && subs.cues.isNotEmpty() && options.burnSubtitles) {
            videoEffects += overlayEffects(listOf(SubtitleOverlay(subs, bitmapFactory.textRenderer, width, height)))
        }
        if (options.watermark) {
            val bmp = WatermarkOverlay.render(bitmapFactory.textRenderer, width, height)
            videoEffects += overlayEffects(listOf(WatermarkOverlay(bmp, width, height)))
        }

        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        BuiltComposition(composition, width, height, duration)
    }

    private fun mainItem(clip: VideoClip, doc: EditorDocument, width: Int, height: Int, options: BuildOptions): EditedMediaItem {
        val isImage = clip.source.kind == MediaKind.IMAGE
        val useReversed = clip.reversed && clip.reversedUri != null && !isImage
        val uri = if (useReversed) clip.reversedUri!! else clip.source.uri
        val mediaItem = MediaItem.Builder().setUri(uri.toUri()).setMediaId(clip.id).apply {
            if (isImage) {
                setImageDurationMs(clip.outputDurationUs / 1000)
                setMimeType(imageMimeType(uri))
            } else if (!useReversed) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(clip.trimStartUs)
                        .setEndPositionUs(clip.trimEndUs)
                        .build(),
                )
            }
        }.build()

        val audio = ArrayList<AudioProcessor>()
        val video = ArrayList<Effect>()
        if (!isImage && clip.speed != 1f) {
            val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeedProvider(clip.speed))
            audio += pair.first
            video += pair.second
        }
        val hasAudio = options.includeAudio && !isImage && clip.source.hasAudio && !clip.muted && !useReversed && !clip.reversed
        if (hasAudio) {
            if (clip.noiseReduction > 0f || clip.voiceEnhance) audio += VoiceCleanupAudioProcessor(clip.noiseReduction, clip.voiceEnhance)
            val env = GainEnvelope(clip.volume.coerceIn(0f, 2f), clip.audioFadeInUs, clip.audioFadeOutUs, clip.outputDurationUs)
            if (!env.isUnity) audio += GainEnvelopeAudioProcessor(env)
        }
        val grade = GradeParams.resolve(clip.filter, clip.filterIntensity, clip.adjustments)
        if (!grade.isNeutral) video += ColorGradeEffect(grade)
        video += CanvasEffect(
            outputWidth = width,
            outputHeight = height,
            crop = clip.crop,
            quarterTurns = clip.rotationQuarterTurns,
            flipHorizontal = clip.flipHorizontal,
            flipVertical = clip.flipVertical,
            transform = clip.transform,
            fit = doc.canvas.fit,
            background = doc.canvas.background,
        )
        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(!hasAudio)
            .setEffects(Effects(audio, video))
        if (isImage) {
            builder.setDurationUs(clip.outputDurationUs).setFrameRate(IMAGE_FRAME_RATE)
        } else {
            builder.setDurationUs(if (useReversed) (clip.trimEndUs - clip.trimStartUs).coerceAtLeast(1) else clip.source.durationUs.coerceAtLeast(clip.trimEndUs))
        }
        return builder.build()
    }

    /** Positions a track's clips on the timeline with gaps; looping clips repeat until the next clip or the end. */
    private fun audioSequence(track: AudioTrack, timelineEndUs: Long, duckTimeline: List<LongRange>): EditedMediaItemSequence? {
        val clips = track.clips.sortedBy { it.startUs }
        val builder = EditedMediaItemSequence.Builder()
        var cursor = 0L
        var added = 0
        clips.forEachIndexed { i, clip ->
            if (clip.startUs >= timelineEndUs) return@forEachIndexed
            val start = maxOf(clip.startUs, cursor)
            val limit = minOf(clips.getOrNull(i + 1)?.startUs ?: timelineEndUs, timelineEndUs)
            val oneLength = clip.durationUs
            if (oneLength <= 0 || start >= limit) return@forEachIndexed
            if (start > cursor) builder.addGap(start - cursor)
            var t = start
            var rep = 0
            do {
                val remaining = limit - t
                val len = minOf(oneLength, remaining)
                if (len <= MIN_AUDIO_US) break
                val firstRep = rep == 0
                val lastRep = !clip.loop || t + len >= limit
                val ducks = duckTimeline.mapNotNull { r ->
                    val s = r.first - t
                    val e = r.last - t
                    if (e <= 0 || s >= len) null else s.coerceAtLeast(0)..e.coerceAtMost(len)
                }
                builder.addItem(audioItem(clip, track, len, firstRep, lastRep, ducks))
                added++
                t += len
                rep++
            } while (clip.loop && t < limit)
            cursor = t
        }
        if (added == 0) return null
        return builder.experimentalSetForceAudioTrack(true).build()
    }

    private fun audioItem(clip: AudioClip, track: AudioTrack, outputLengthUs: Long, firstRep: Boolean, lastRep: Boolean, ducks: List<LongRange>): EditedMediaItem {
        val sourceLen = (outputLengthUs * clip.speed.toDouble()).toLong()
        val mediaItem = MediaItem.Builder().setUri(clip.source.uri.toUri()).setMediaId(clip.id)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.trimStartUs)
                    .setEndPositionUs((clip.trimStartUs + sourceLen).coerceAtMost(clip.trimEndUs))
                    .build(),
            ).build()
        val audio = ArrayList<AudioProcessor>()
        if (clip.speed != 1f) audio += Effects.createExperimentalSpeedChangingEffect(ConstantSpeedProvider(clip.speed)).first
        val env = GainEnvelope(
            volume = (clip.volume * track.volume).coerceIn(0f, 2f),
            fadeInUs = if (firstRep) clip.fadeInUs else 0,
            fadeOutUs = if (lastRep) clip.fadeOutUs else 0,
            durationUs = outputLengthUs,
            duckRanges = ducks,
        )
        if (!env.isUnity) audio += GainEnvelopeAudioProcessor(env)
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(clip.source.durationUs.coerceAtLeast(clip.trimEndUs))
            .setEffects(Effects(audio, emptyList()))
            .build()
    }

    private fun pipAudioSequence(pip: OverlayItem.Video, timelineEndUs: Long): EditedMediaItemSequence? {
        val start = pip.startUs.coerceAtLeast(0)
        val end = minOf(pip.endUs, timelineEndUs)
        if (end - start <= MIN_AUDIO_US) return null
        val sourceEnd = (pip.trimStartUs + (end - start)).coerceAtMost(pip.source.durationUs)
        if (sourceEnd <= pip.trimStartUs) return null
        val mediaItem = MediaItem.Builder().setUri(pip.source.uri.toUri()).setMediaId(pip.id + "-audio")
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionUs(pip.trimStartUs).setEndPositionUs(sourceEnd).build())
            .build()
        val env = GainEnvelope(pip.volume.coerceIn(0f, 2f), fadeInUs = 150_000, fadeOutUs = 150_000, durationUs = sourceEnd - pip.trimStartUs)
        val item = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(pip.source.durationUs)
            .setEffects(Effects(listOf(GainEnvelopeAudioProcessor(env)), emptyList()))
            .build()
        val builder = EditedMediaItemSequence.Builder()
        if (start > 0) builder.addGap(start)
        return builder.addItem(item).experimentalSetForceAudioTrack(true).build()
    }

    private fun imageMimeType(uri: String): String {
        val parsed: Uri = uri.toUri()
        val resolved = if (parsed.scheme == "content") runCatching { context.contentResolver.getType(parsed) }.getOrNull() else null
        if (resolved != null && resolved.startsWith("image/")) return resolved
        return when (parsed.path?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> MimeTypes.IMAGE_PNG
            "webp" -> MimeTypes.IMAGE_WEBP
            "heic", "heif" -> MimeTypes.IMAGE_HEIF
            else -> MimeTypes.IMAGE_JPEG
        }
    }

    companion object {
        const val IMAGE_FRAME_RATE = 30
        private const val MIN_AUDIO_US = 20_000L
    }
}

/** A constant playback speed for [Effects.createExperimentalSpeedChangingEffect]. */
class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

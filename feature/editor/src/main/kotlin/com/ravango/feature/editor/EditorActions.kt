package com.ravango.feature.editor

import android.net.Uri
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.AudioClip
import com.ravango.core.model.CanvasBackground
import com.ravango.core.model.ColorAdjustments
import com.ravango.core.model.ContentFit
import com.ravango.core.model.CropRect
import com.ravango.core.model.FilterPreset
import com.ravango.core.model.OverlayAnimation
import com.ravango.core.model.OverlayItem
import com.ravango.core.model.ProFeature
import com.ravango.core.model.SubtitleCue
import com.ravango.core.model.SubtitleStyle
import com.ravango.core.model.TextStyleSpec
import com.ravango.core.model.TransitionType
import com.ravango.engine.editor.media.ThumbnailProvider
import com.ravango.engine.editor.ops.Edge

/**
 * Every command the editor UI (top bar, transport, timeline, tool panels) can issue. Implemented by
 * [EditorViewModel]; the panels depend on this interface only, so they render stateless in screenshot tests.
 */
interface EditorActions {
    /** Source-frame thumbnails (filter previews). */
    val thumbnails: ThumbnailProvider

    fun endGesture()
    fun undo()
    fun redo()
    fun select(selection: Selection)
    fun selectClipAtPlayhead()
    fun upgrade(feature: ProFeature)
    fun openTool(tool: EditorTool?)
    fun consumeMessage(id: Long)
    fun cancelBusy()
    fun togglePlay()
    fun seekTo(timeUs: Long, scrubbing: Boolean = false)
    fun stepFrame(forward: Boolean)
    fun onStop()
    fun importMedia(uris: List<Uri>)
    fun split()
    fun deleteSelection()
    fun duplicateSelection()
    fun setSpeed(speed: Float, gesture: Boolean)
    fun rotateClip()
    fun flipClip(horizontal: Boolean)
    fun setCrop(crop: CropRect, gesture: Boolean)
    fun resetClipTransform()
    fun joinWithNext()
    fun canJoin(clipId: String): Boolean
    fun moveClip(clipId: String, toIndex: Int)
    fun trimClip(clipId: String, edge: Edge, deltaUs: Long)
    fun setTransition(clipId: String, type: TransitionType, durationUs: Long)
    fun setTransitionForAll(type: TransitionType, durationUs: Long)
    fun toggleReverse()
    fun freezeFrame(durationUs: Long = 2_000_000)
    fun setClipVolume(volume: Float, gesture: Boolean)
    fun toggleMute()
    fun setAudioFades(inUs: Long, outUs: Long, gesture: Boolean)
    fun setVideoFades(inUs: Long, outUs: Long, gesture: Boolean)
    fun setStillDuration(us: Long, gesture: Boolean)
    fun setAspect(aspect: AspectRatioSpec)
    fun setBackground(bg: CanvasBackground, gesture: Boolean = false)
    fun setFit(fit: ContentFit)
    fun setFilter(filter: FilterPreset)
    fun setFilterIntensity(v: Float, gesture: Boolean)
    fun applyFilterToAll()
    fun setAdjustments(adjustments: ColorAdjustments, field: String, advanced: Boolean)
    fun resetAdjustments()
    fun addText(text: String, style: TextStyleSpec)
    fun addSticker(emoji: String)
    fun updateOverlay(id: String, gestureKey: String? = null, transform: (OverlayItem) -> OverlayItem)
    fun setOverlayAnimations(id: String, animIn: OverlayAnimation, animOut: OverlayAnimation)
    fun retimeOverlay(id: String, startUs: Long, endUs: Long)
    fun transformSelection(dx: Float, dy: Float, zoom: Float, rotation: Float)
    fun addMediaOverlay(uri: Uri)
    fun addLogo(uri: Uri, asWatermark: Boolean)
    fun importMusic(uri: Uri)
    fun updateAudioClip(id: String, gestureKey: String? = null, transform: (AudioClip) -> AudioClip)
    fun trimAudio(id: String, edge: Edge, deltaUs: Long)
    fun moveAudio(id: String, startUs: Long)
    fun setTrackDucking(trackId: String, ducking: Boolean)
    fun setTrackMuted(trackId: String, muted: Boolean)
    fun extractAudio()
    fun detachAudio()
    fun setNoiseReduction(v: Float, gesture: Boolean)
    fun setVoiceEnhance(on: Boolean)
    fun aiAudioCleanup()
    fun startVoiceOver()
    fun stopVoiceOver()
    fun autoCaption(language: String?)
    fun updateCue(id: String, gestureKey: String? = null, transform: (SubtitleCue) -> SubtitleCue)
    fun retimeCue(id: String, startUs: Long, endUs: Long)
    fun addCueAtPlayhead(text: String)
    fun splitCueAtPlayhead(id: String)
    fun mergeCueWithNext(id: String)
    fun deleteCue(id: String)
    fun clearCues()
    fun setSubtitleStyle(style: SubtitleStyle, gesture: Boolean = false)
    fun setSubtitlesVisible(visible: Boolean)
    fun setBurnIn(burn: Boolean)
    fun importSrt(uri: Uri)
    fun exportSrt(uri: Uri)
    fun detectSilences()
    fun applySilenceRemoval()
    fun dismissAi()
    fun findHighlights()
    fun jumpToSource(clipId: String, sourceUs: Long)
    fun suggestShorts()
    fun createShort(index: Int)
    fun planAutoEdit()
    fun applyAutoEdit()
    fun correctEyeContact()
    fun openExport()
    fun openSettings()
}

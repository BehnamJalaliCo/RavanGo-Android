package com.ravango.engine.editor.preview

import android.content.Context
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.ravango.core.common.log.RgLog
import com.ravango.core.model.EditorDocument
import com.ravango.engine.editor.composition.BuildOptions
import com.ravango.engine.editor.composition.CompositionBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Real-time preview of the edit with Media3 [CompositionPlayer], fed with the exact [androidx.media3.transformer.Composition]
 * that export uses (at preview resolution). Exposes playback position/state as flows; rebuilding the composition keeps
 * the playhead and play state. One instance per editor screen; call [release] when done. Main-thread API.
 */
class PreviewController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val builder: CompositionBuilder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _player: CompositionPlayer? = null
    private var positionJob: Job? = null
    private var buildJob: Job? = null
    private var hasComposition = false

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _durationUs = MutableStateFlow(0L)
    val durationUs: StateFlow<Long> = _durationUs.asStateFlow()
    private val _error = MutableStateFlow<PlaybackException?>(null)
    val error: StateFlow<PlaybackException?> = _error.asStateFlow()
    private val _building = MutableStateFlow(false)
    val building: StateFlow<Boolean> = _building.asStateFlow()
    private val _canvasSize = MutableStateFlow(0 to 0)
    val canvasSize: StateFlow<Pair<Int, Int>> = _canvasSize.asStateFlow()

    /** The player to attach to a surface (created lazily on the main thread). */
    val player: Player
        get() = ensurePlayer()

    private fun ensurePlayer(): CompositionPlayer {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PreviewController is main-thread only" }
        _player?.let { return it }
        // Default single-input video graph: the only video sequence is the main track (PiP is a GL effect);
        // secondary sequences are audio-only and mixed by the player's audio graph.
        val p = CompositionPlayer.Builder(context).build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startPositionUpdates() else {
                    positionJob?.cancel()
                    _positionUs.value = p.currentPosition * 1000
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _positionUs.value = _durationUs.value
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                RgLog.e(TAG, "Preview error", error)
                _error.value = error
            }
        })
        _player = p
        return p
    }

    /**
     * Rebuilds the preview for [document]. Calls are coalesced: a newer call cancels an in-flight build.
     */
    fun setDocument(document: EditorDocument, watermark: Boolean) {
        buildJob?.cancel()
        buildJob = scope.launch {
            _building.value = true
            try {
                val built = builder.build(document, BuildOptions(watermark = watermark))
                val p = ensurePlayer()
                if (built == null) {
                    p.stop()
                    hasComposition = false
                    _durationUs.value = 0
                    return@launch
                }
                val wasPlaying = p.isPlaying
                val position = _positionUs.value.coerceIn(0, (built.durationUs - 1).coerceAtLeast(0))
                _error.value = null
                p.setComposition(built.composition)
                p.seekTo(position / 1000)
                p.prepare()
                p.playWhenReady = wasPlaying
                hasComposition = true
                _durationUs.value = built.durationUs
                _canvasSize.value = built.width to built.height
                _positionUs.value = position
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.e(TAG, "Preview build failed", e)
            } finally {
                _building.value = false
            }
        }
    }

    fun play() {
        val p = _player ?: return
        if (!hasComposition) return
        if (p.playbackState == Player.STATE_ENDED || _positionUs.value >= _durationUs.value - 20_000) p.seekTo(0)
        p.play()
    }

    fun pause() {
        _player?.pause()
    }

    fun togglePlay() = if (_isPlaying.value) pause() else play()

    /** Seeks to [timeUs]; while [scrubbing], seeks are optimised for rapid successive calls. */
    fun seekTo(timeUs: Long, scrubbing: Boolean = false) {
        val p = _player ?: return
        val t = timeUs.coerceIn(0, _durationUs.value.coerceAtLeast(0))
        _positionUs.value = t
        if (!hasComposition) return
        if (p.isScrubbingModeEnabled != scrubbing) p.setScrubbingModeEnabled(scrubbing)
        p.seekTo(t / 1000)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                _player?.let { _positionUs.value = it.currentPosition * 1000 }
                delay(33)
            }
        }
    }

    suspend fun awaitIdle() = withContext(Dispatchers.Main) { buildJob?.join() }

    fun release() {
        scope.cancel()
        _player?.release()
        _player = null
        hasComposition = false
    }

    private companion object {
        const val TAG = "Preview"
    }
}

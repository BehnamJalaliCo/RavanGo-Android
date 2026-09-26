package com.ravango.engine.camera.recovery

import com.ravango.core.common.crash.CrashGuard
import com.ravango.core.common.device.StorageInfo
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.common.startup.StartupTask
import com.ravango.core.model.AspectRatioSpec
import com.ravango.core.model.CaptureMode
import com.ravango.engine.camera.muxer.ManifestCodec
import com.ravango.engine.camera.muxer.RecordingFinalizer
import com.ravango.engine.camera.recorder.ActiveRecordings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A recording rebuilt from the segments of an interrupted session. */
@Serializable
data class RecoveredRecording(
    val path: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val hasAudio: Boolean,
    val captureMode: CaptureMode,
    val createdAt: Long,
) {
    val aspectRatio: AspectRatioSpec get() = if (width > 0 && height > 0) AspectRatioSpec(width, height) else AspectRatioSpec.Portrait9x16
}

/**
 * Finalizes recordings interrupted by a crash, kill or power loss: every completed segment listed in the
 * recording's manifest is concatenated into a playable MP4. Results are kept in a small pending list until a
 * feature imports them into projects ([consumeRecovered]).
 */
@Singleton
class RecordingRecovery @Inject constructor(
    private val storage: StorageInfo,
    private val crashGuard: CrashGuard,
    private val audioEngine: com.ravango.engine.audio.AudioEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _pendingCount = MutableStateFlow(0)

    /** Number of recovered recordings waiting to be imported. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val pendingFile: File get() = File(storage.recordingsDir, PENDING_FILE)

    /** Scans for interrupted recordings and finalizes them. Returns what was recovered in this run. */
    suspend fun recoverInterrupted(): List<RecoveredRecording> = withContext(io) {
        mutex.withLock {
            val root = storage.recordingsDir
            val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith(DIR_PREFIX) }.orEmpty()
            val recovered = ArrayList<RecoveredRecording>()
            for (dir in dirs) {
                val id = dir.name.removePrefix(DIR_PREFIX)
                if (ActiveRecordings.contains(id)) continue
                val manifest = ManifestCodec.read(dir)
                if (manifest == null) {
                    RgLog.w(TAG, "no manifest in ${dir.name}; removing")
                    dir.deleteRecursively()
                    continue
                }
                val result = runCatching { RecordingFinalizer.finalize(dir, manifest, root) }
                    .onFailure { RgLog.e(TAG, "recovery of ${dir.name} failed", it) }
                    .getOrNull() ?: continue
                RgLog.i(TAG, "recovered ${result.file.name} (${result.durationUs / 1000}ms)")
                recovered += RecoveredRecording(
                    path = result.file.absolutePath,
                    durationUs = result.durationUs,
                    width = manifest.width,
                    height = manifest.height,
                    frameRate = manifest.frameRate,
                    hasAudio = manifest.hasAudio,
                    captureMode = manifest.captureMode,
                    createdAt = manifest.createdAt,
                )
            }
            // Audio-only takes are written by the audio engine with a crash journal; rebuild any that were cut off.
            runCatching { audioEngine.recoverInterruptedRecordings(root) }
                .onFailure { RgLog.e(TAG, "audio-only recovery failed", it) }
                .getOrDefault(emptyList())
                .forEach { file ->
                    recovered += RecoveredRecording(
                        path = file.absolutePath,
                        durationUs = probeDurationUs(file),
                        width = 0,
                        height = 0,
                        frameRate = 0,
                        hasAudio = true,
                        captureMode = CaptureMode.AUDIO_ONLY,
                        createdAt = file.lastModified(),
                    )
                }
            if (crashGuard.interruptedPayload(SECTION) != null && ActiveRecordings.isEmpty()) crashGuard.endSection(SECTION)
            if (recovered.isNotEmpty()) writePending(readPending() + recovered)
            _pendingCount.value = readPending().size
            recovered
        }
    }

    /** Returns recovered recordings not yet imported and clears the list. */
    suspend fun consumeRecovered(): List<RecoveredRecording> = withContext(io) {
        mutex.withLock {
            val list = readPending().filter { File(it.path).exists() }
            pendingFile.delete()
            _pendingCount.value = 0
            list
        }
    }

    private fun probeDurationUs(file: File): Long = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        } finally {
            r.release()
        }
    }.getOrDefault(0L)

    private fun readPending(): List<RecoveredRecording> = runCatching {
        val f = pendingFile
        if (!f.exists()) emptyList() else json.decodeFromString(ListSerializer(RecoveredRecording.serializer()), f.readText())
    }.getOrDefault(emptyList())

    private fun writePending(list: List<RecoveredRecording>) {
        runCatching {
            val tmp = File(storage.recordingsDir, "$PENDING_FILE.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(RecoveredRecording.serializer()), list))
            tmp.renameTo(pendingFile)
        }.onFailure { RgLog.e(TAG, "pending list write failed", it) }
    }

    companion object {
        private const val TAG = "RecRecovery"
        internal const val DIR_PREFIX = ".rec_"
        internal const val SECTION = "recording"
        private const val PENDING_FILE = ".recovered.json"
    }
}

/** Runs recovery shortly after launch (never blocks the first frame). */
class RecordingRecoveryStartupTask @Inject constructor(private val recovery: RecordingRecovery) : StartupTask {
    override val priority: Int = 20
    override suspend fun run() {
        recovery.recoverInterrupted()
    }
}

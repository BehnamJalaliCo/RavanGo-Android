package com.ravango.engine.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.media.dsp.LevelAccumulator
import com.ravango.core.media.dsp.VoiceProcessingConfig
import com.ravango.core.media.dsp.VoiceProcessor
import com.ravango.core.model.AudioInputDevice
import com.ravango.core.model.AudioInputType
import com.ravango.core.model.AudioLevel
import com.ravango.core.model.AudioSettings
import com.ravango.engine.audio.internal.AacFileRecorder
import com.ravango.engine.audio.internal.AudioDeviceCatalog
import com.ravango.engine.audio.internal.AudioRecordingRecovery
import com.ravango.engine.audio.internal.BluetoothMicRouter
import com.ravango.engine.audio.internal.InputEntry
import com.ravango.engine.audio.internal.InputTypeMapper.isBluetooth
import com.ravango.engine.audio.internal.LevelBallistics
import com.ravango.engine.audio.internal.PresentationClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioEngine] on AudioRecord/AudioTrack/MediaCodec.
 *
 * Threads:
 * - "RavanGo-AudioCapture" (THREAD_PRIORITY_URGENT_AUDIO): opens the input, reads 10 ms chunks, runs the
 *   [VoiceProcessor], meters, fans out to sinks and feeds the monitor. It never allocates per chunk.
 * - "RavanGo-AudioEvents": device hot-plug and routing callbacks.
 * Public methods are thread-safe (state changes are serialized on [lock]).
 *
 * Settings: initialised from and kept in sync with the persisted [PreferencesDataSource.audioSettings];
 * [updateSettings] applies a value immediately (persisting is the caller's job).
 */
@Singleton
class AndroidAudioEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    preferences: PreferencesDataSource,
    @ApplicationScope scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : AudioEngine {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val catalog = AudioDeviceCatalog(context, audioManager)

    private val _inputs = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    override val inputs: StateFlow<List<AudioInputDevice>> = _inputs.asStateFlow()
    private val _activeInput = MutableStateFlow<AudioInputDevice?>(null)
    override val activeInput: StateFlow<AudioInputDevice?> = _activeInput.asStateFlow()
    private val _level = MutableStateFlow(AudioLevel.Silent)
    override val level: StateFlow<AudioLevel> = _level.asStateFlow()
    private val _monitoringAvailable = MutableStateFlow(false)
    override val monitoringAvailable: StateFlow<Boolean> = _monitoringAvailable.asStateFlow()
    private val _settings = MutableStateFlow(AudioSettings())
    override val settings: StateFlow<AudioSettings> = _settings.asStateFlow()
    private val _error = MutableStateFlow<AudioEngineError?>(null)
    override val error: StateFlow<AudioEngineError?> = _error.asStateFlow()

    private val lock = Any()
    private val dspLock = Any()
    private var previewRequested = false
    @Volatile private var loop: CaptureLoop? = null
    private var stoppingLoop: CaptureLoop? = null
    @Volatile private var sinks: Array<SinkEntry> = emptyArray()
    private val activeRecordings = HashSet<File>()

    /** Inputs we failed to open (Bluetooth link failure, missing permission) — skipped until settings change or re-plug. */
    private val excludedInputIds = HashSet<Int>()

    @Volatile private var monitorWanted = false
    @Volatile private var entries: List<InputEntry> = emptyList()

    private var eventThread: HandlerThread? = null
    private var eventHandler: Handler? = null
    private var deviceCallbackRegistered = false

    // One processor for the engine lifetime; reconfigured on format changes.
    private val processor = VoiceProcessor(VoiceProcessingConfig(48_000, 1))

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged(removed = emptyList())
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged(removedDevices.map { it.id })
    }

    init {
        synchronized(lock) { ensureDeviceTracking() }
        refreshDevices()
        scope.launch {
            preferences.audioSettings.collect { updateSettings(it) }
        }
    }

    // ------------------------------------------------------------------ public API

    override fun updateSettings(settings: AudioSettings) {
        val old: AudioSettings
        synchronized(dspLock) {
            old = _settings.value
            if (old == settings) return
            _settings.value = settings
            loop?.format?.let { processor.updateConfig(dspConfig(settings, it)) }
        }
        updateMonitorWanted()
        val routingChanged = old.preferredInput != settings.preferredInput ||
            old.preferredDeviceName != settings.preferredDeviceName ||
            old.sampleRate != settings.sampleRate ||
            old.stereo != settings.stereo ||
            old.useUnprocessedSource != settings.useUnprocessedSource
        // Platform effects depend on these (see openSession); reopen only when they are in use.
        val effectsChanged = (old.noiseReduction != settings.noiseReduction || (old.gainDb == 0f) != (settings.gainDb == 0f)) &&
            loop?.usesPlatformEffects == true
        if (routingChanged) synchronized(lock) { excludedInputIds.clear() }
        if (routingChanged || effectsChanged) loop?.requestRestart()
    }

    override fun startPreview() {
        requireRecordPermission()
        val target = catalog.resolve(_settings.value, entries)
        if (target != null && target.device.type.isBluetooth && !hasBluetoothPermission()) {
            throw AudioPermissionException(Manifest.permission.BLUETOOTH_CONNECT)
        }
        synchronized(lock) {
            excludedInputIds.clear()
            previewRequested = true
            reconcile()
        }
    }

    override fun stopPreview() {
        synchronized(lock) {
            previewRequested = false
            reconcile()
        }
    }

    override fun attachSink(sink: PcmSink): PcmSinkHandle {
        requireRecordPermission()
        val entry = SinkEntry(sink)
        synchronized(lock) {
            sinks = sinks + entry
            reconcile()
        }
        return object : PcmSinkHandle {
            override fun detach() {
                synchronized(lock) {
                    if (entry.detached) return
                    entry.detached = true
                    sinks = sinks.filter { it !== entry }.toTypedArray()
                    reconcile()
                }
            }
        }
    }

    override fun startAudioOnlyRecording(output: File): AudioRecordingHandle {
        requireRecordPermission()
        synchronized(lock) { activeRecordings += output.absoluteFile }
        val recorder = AacFileRecorder(output, _settings.value.audioBitrateKbps) { publishError(it) }
        val handle = attachSink(recorder)
        recorder.detachFromEngine = {
            handle.detach()
            synchronized(lock) { activeRecordings -= output.absoluteFile }
        }
        return recorder
    }

    override fun release() {
        synchronized(lock) {
            previewRequested = false
            for (s in sinks) s.detached = true
            sinks = emptyArray()
            reconcile()
            if (deviceCallbackRegistered) {
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                deviceCallbackRegistered = false
            }
        }
        _level.value = AudioLevel.Silent
    }

    override fun clearError() {
        _error.value = null
    }

    override suspend fun recoverInterruptedRecordings(directory: File): List<File> = withContext(io) {
        val busy = synchronized(lock) { activeRecordings.toSet() }
        val journals = directory.listFiles { f -> f.isFile && f.name.endsWith(AudioRecordingRecovery.JOURNAL_SUFFIX) }.orEmpty()
        journals.filter { File(it.path.removeSuffix(AudioRecordingRecovery.JOURNAL_SUFFIX)).absoluteFile !in busy }
            .mapNotNull { runCatching { AudioRecordingRecovery.recover(it) }.getOrNull() }
    }

    // ------------------------------------------------------------------ internals

    private class SinkEntry(val sink: PcmSink) {
        @Volatile var detached = false
        var formatGeneration = -1 // capture thread only
    }

    private fun requireRecordPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioPermissionException(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun publishError(error: AudioEngineError) {
        RgLog.w(TAG, "Audio problem: $error")
        _error.value = error
    }

    /** Starts/stops the capture thread to match demand. Caller holds [lock]. */
    private fun reconcile() {
        val needed = previewRequested || sinks.isNotEmpty()
        val current = loop
        if (needed && current == null) {
            ensureDeviceTracking()
            loop = CaptureLoop(previous = stoppingLoop).also { it.start() }
            stoppingLoop = null
        } else if (!needed && current != null) {
            current.requestStop()
            stoppingLoop = current
            loop = null
        }
    }

    private fun ensureDeviceTracking() {
        if (eventHandler == null) {
            val t = HandlerThread("RavanGo-AudioEvents").apply { start() }
            eventThread = t
            eventHandler = Handler(t.looper)
        }
        if (!deviceCallbackRegistered) {
            audioManager.registerAudioDeviceCallback(deviceCallback, eventHandler)
            deviceCallbackRegistered = true
        }
    }

    private fun refreshDevices(): List<InputEntry> {
        val list = catalog.inputs()
        entries = list
        _inputs.value = list.map { it.device }
        _monitoringAvailable.value = catalog.monitoringOutput() != null
        updateMonitorWanted()
        return list
    }

    private fun updateMonitorWanted() {
        monitorWanted = _settings.value.monitoring && _monitoringAvailable.value
    }

    private fun onDevicesChanged(removed: List<Int>) {
        val before = entries
        val list = refreshDevices()
        val capture = loop ?: return
        val active = capture.targetEntry
        synchronized(lock) { excludedInputIds.removeAll(removed.toSet()) }
        if (active != null && active.device.id in removed) {
            val fallback = resolveTarget(list)
            publishError(AudioEngineError.InputLost(active.device, fallback?.device))
            capture.requestRestart()
            return
        }
        // A better match for the preference appeared (e.g. the preferred USB mic was plugged in).
        val target = resolveTarget(list)
        if (target != null && active != null && target.device.id != active.device.id && before.none { it.device.id == target.device.id }) {
            capture.requestRestart()
        }
    }

    private fun resolveTarget(list: List<InputEntry>): InputEntry? {
        val excluded = synchronized(lock) { excludedInputIds.toSet() }
        return catalog.resolve(_settings.value, list.filter { it.device.id !in excluded })
    }

    private fun dspConfig(s: AudioSettings, format: PcmFormat) = VoiceProcessingConfig(
        sampleRate = format.sampleRate,
        channels = format.channels,
        gainDb = s.gainDb.coerceIn(-12f, 24f),
        highPass = s.highPassFilter,
        noiseReduction = if (s.noiseReduction) s.noiseReductionStrength.coerceIn(0f, 1f) else 0f,
        voiceEnhance = s.voiceEnhancement,
        limiter = s.limiter,
    )

    // ------------------------------------------------------------------ capture thread

    private class Session(
        val record: AudioRecord,
        val format: PcmFormat,
        val target: InputEntry?,
        val bluetooth: Boolean,
        val effects: List<AudioEffect>,
        val routingListener: AudioRouting.OnRoutingChangedListener,
        val usesPlatformEffects: Boolean,
    )

    private inner class CaptureLoop(private val previous: CaptureLoop?) : Thread("RavanGo-AudioCapture") {
        @Volatile private var running = true
        @Volatile private var restartRequested = false
        @Volatile var format: PcmFormat? = null
            private set
        @Volatile var targetEntry: InputEntry? = null
            private set
        @Volatile var usesPlatformEffects = false
            private set

        private val router = BluetoothMicRouter(context, audioManager, eventHandler!!)
        private val clock = PresentationClock(48_000)
        private val timestamp = AudioTimestamp()
        private val meter = LevelAccumulator()
        private val ballistics = LevelBallistics()
        private var formatGeneration = 0
        private var buffer = ShortArray(0)
        private var silence = ShortArray(0)
        private var lastEndPts = Long.MIN_VALUE
        private var monitor: AudioTrack? = null
        private var consecutiveFailures = 0

        fun requestStop() { running = false }
        fun requestRestart() { restartRequested = true }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCatching { previous?.join(5_000) }
            processor.reset()
            try {
                while (running) {
                    restartRequested = false
                    val session = try {
                        openSession()
                    } catch (e: SecurityException) {
                        publishError(AudioEngineError.PermissionRequired(Manifest.permission.RECORD_AUDIO))
                        RgLog.e(TAG, "Microphone permission lost", e)
                        break
                    }
                    if (session == null) {
                        consecutiveFailures++
                        if (consecutiveFailures == 3) publishError(AudioEngineError.MicrophoneUnavailable)
                        sleepWhileRunning(minOf(2_000L, 200L * consecutiveFailures))
                        continue
                    }
                    try {
                        readLoop(session)
                    } finally {
                        closeSession(session)
                    }
                }
            } catch (t: Throwable) {
                RgLog.e(TAG, "Capture thread crashed", t)
            } finally {
                releaseMonitor()
                router.disconnect()
                format = null
                targetEntry = null
                _level.value = AudioLevel.Silent
                if (loop == null || loop === this) _activeInput.value = null
            }
        }

        private fun sleepWhileRunning(ms: Long) {
            val until = SystemClock.elapsedRealtime() + ms
            while (running && !restartRequested && SystemClock.elapsedRealtime() < until) {
                try { sleep(20) } catch (_: InterruptedException) { return }
            }
        }

        @SuppressLint("MissingPermission")
        private fun openSession(): Session? {
            val settings = _settings.value
            val list = refreshDevices()
            var target = resolveTarget(list)
            var bluetooth = false

            if (target != null && target.device.type.isBluetooth) {
                if (!hasBluetoothPermission()) {
                    publishError(AudioEngineError.PermissionRequired(Manifest.permission.BLUETOOTH_CONNECT))
                    synchronized(lock) { excludedInputIds += target!!.device.id }
                    target = resolveTarget(list)
                } else if (router.connect(target.info)) {
                    bluetooth = true
                } else {
                    publishError(AudioEngineError.BluetoothUnavailable(target.device))
                    synchronized(lock) { excludedInputIds += target!!.device.id }
                    target = resolveTarget(list)
                }
            }
            if (!running) { if (bluetooth) router.disconnect(); return null }

            val primarySource = when {
                bluetooth -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                settings.useUnprocessedSource && catalog.supportsUnprocessedSource() -> MediaRecorder.AudioSource.UNPROCESSED
                else -> MediaRecorder.AudioSource.CAMCORDER
            }
            val sources = listOf(primarySource, MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).distinct()
            val device = target?.device
            val wantStereo = settings.stereo && !bluetooth && (device == null || device.channelCounts.isEmpty() || 2 in device.channelCounts)
            val channelOptions = if (wantStereo) listOf(2, 1) else listOf(1)
            val rates = listOf(settings.sampleRate, 48_000, 44_100).filter { it in 8_000..192_000 }.distinct()
            // Built-in without an explicit choice: let the platform pick the mic that matches the source (e.g. camcorder).
            val preferred = target?.info?.takeUnless { device?.type == AudioInputType.BUILT_IN && settings.preferredDeviceName == null }

            var record: AudioRecord? = null
            var fmt: PcmFormat? = null
            var usedSource = primarySource
            loop@ for (source in sources) for (ch in channelOptions) for (rate in rates) {
                record = createRecord(source, rate, ch, preferred)
                if (record != null) { fmt = PcmFormat(rate, ch); usedSource = source; break@loop }
            }
            if (record == null || fmt == null) {
                if (bluetooth) router.disconnect()
                return null
            }

            // Platform processing: never AGC (the user controls gain); our NR replaces the platform NS.
            val effects = ArrayList<AudioEffect>()
            val usesEffects = usedSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION
            if (usesEffects) {
                if (AutomaticGainControl.isAvailable()) {
                    runCatching { AutomaticGainControl.create(record.audioSessionId)?.apply { setEnabled(false) }?.let(effects::add) }
                }
                if (settings.noiseReduction && NoiseSuppressor.isAvailable()) {
                    runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { setEnabled(false) }?.let(effects::add) }
                }
            }

            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                RgLog.w(TAG, "startRecording failed", e)
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                effects.forEach { runCatching { it.release() } }
                record.release()
                if (bluetooth) router.disconnect()
                return null
            }

            val listener = AudioRouting.OnRoutingChangedListener { routing -> onRouted(routing.routedDevice) }
            runCatching { record.addOnRoutingChangedListener(listener, eventHandler) }

            // Format + DSP.
            if (fmt != format) {
                format = fmt
                formatGeneration++
                val chunk = fmt.sampleRate / 100 * fmt.channels
                buffer = ShortArray(chunk)
                silence = ShortArray(chunk)
                releaseMonitor()
            }
            synchronized(dspLock) { processor.updateConfig(dspConfig(_settings.value, fmt)) }
            processor.process(buffer, 0, 0) // apply the config now so latency is current
            clock.reset(fmt.sampleRate)
            meter.reset()
            targetEntry = target
            usesPlatformEffects = usesEffects
            onRouted(record.routedDevice ?: target?.info)
            consecutiveFailures = 0
            RgLog.i(TAG, "Capture ${fmt.sampleRate} Hz × ${fmt.channels}, source=$usedSource, input=${target?.device?.name}, bt=$bluetooth")
            return Session(record, fmt, target, bluetooth, effects, listener, usesEffects)
        }

        @SuppressLint("MissingPermission")
        private fun createRecord(source: Int, rate: Int, channels: Int, preferred: AudioDeviceInfo?): AudioRecord? {
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            val chunkBytes = rate / 100 * channels * 2
            val size = maxOf(min * 2, chunkBytes * 8)
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    )
                    .setBufferSizeInBytes(size)
                    .build()
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                RgLog.d(TAG, "AudioRecord($source, $rate, $channels) unavailable: ${e.message}")
                return null
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            if (preferred != null) runCatching { record.setPreferredDevice(preferred) }
            return record
        }

        private fun onRouted(info: AudioDeviceInfo?) {
            val current = entries
            val mapped = info?.let { i -> current.firstOrNull { it.device.id == i.id }?.device }
                ?: targetEntry?.device
            if (loop === this || loop == null) _activeInput.value = mapped
        }

        private fun closeSession(s: Session) {
            runCatching { s.record.removeOnRoutingChangedListener(s.routingListener) }
            runCatching { s.record.stop() }
            s.record.release()
            s.effects.forEach { runCatching { it.release() } }
            if (s.bluetooth) router.disconnect()
        }

        private fun readLoop(s: Session) {
            val record = s.record
            val fmt = s.format
            val ch = fmt.channels
            val buf = buffer
            val chunkSamples = buf.size
            val chunkNs = 10_000_000L
            val latencyNs = processor.latencyNs
            val meterWindow = fmt.sampleRate * ch / 30
            var framesRead = 0L
            var errors = 0
            while (running && !restartRequested) {
                val n = record.read(buf, 0, chunkSamples, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    RgLog.w(TAG, "AudioRecord.read error $n${if (n == AudioRecord.ERROR_DEAD_OBJECT) " (dead object)" else ""}")
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || ++errors > 3) return // reopen
                    continue
                }
                if (n == 0) {
                    if (++errors > 50) return
                    continue
                }
                errors = 0
                val frames = n / ch
                val now = System.nanoTime()
                if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    clock.onHardwareTimestamp(timestamp.framePosition, timestamp.nanoTime)
                }
                val pts = clock.ptsFor(framesRead, frames, now) - latencyNs
                framesRead += frames

                var rawClip = false
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    if (v >= 32700 || v <= -32700) { rawClip = true; break }
                }

                processor.process(buf, 0, n)

                meter.add(buf, 0, n)
                if (rawClip) clipSeen = true
                if (meter.count >= meterWindow) {
                    _level.value = ballistics.update(meter.peakDbfs, meter.rmsDbfs, clipSeen || meter.clipped, SystemClock.uptimeMillis())
                    meter.reset()
                    clipSeen = false
                }

                // Keep sink timelines continuous across capture restarts (device switch): fill short gaps with silence.
                val last = lastEndPts
                if (last != Long.MIN_VALUE && pts - last > chunkNs * 3 / 2 && pts - last < 2_000_000_000L) {
                    var t = last
                    while (pts - t >= chunkNs) {
                        deliver(silence, silence.size, t, fmt)
                        t += chunkNs
                    }
                }
                deliver(buf, n, pts, fmt)
                lastEndPts = pts + frames * 1_000_000_000L / fmt.sampleRate

                feedMonitor(buf, n, fmt)
            }
        }

        private var clipSeen = false

        private fun deliver(data: ShortArray, length: Int, pts: Long, fmt: PcmFormat) {
            val arr = sinks
            for (i in arr.indices) {
                val e = arr[i]
                if (e.detached) continue
                try {
                    if (e.formatGeneration != formatGeneration) {
                        e.sink.onFormat(fmt)
                        e.formatGeneration = formatGeneration
                    }
                    e.sink.onPcm(data, length, pts)
                } catch (t: Throwable) {
                    RgLog.e(TAG, "PcmSink failed", t)
                }
            }
        }

        private fun feedMonitor(data: ShortArray, length: Int, fmt: PcmFormat) {
            val want = monitorWanted
            var track = monitor
            if (!want) {
                if (track != null) releaseMonitor()
                return
            }
            if (track == null) {
                track = createMonitor(fmt) ?: return
                monitor = track
            }
            track.write(data, 0, length, AudioTrack.WRITE_NON_BLOCKING)
        }

        private fun createMonitor(fmt: PcmFormat): AudioTrack? {
            val output = catalog.monitoringOutput() ?: run { monitorWanted = false; return null }
            val mask = if (fmt.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(fmt.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return null
            return try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(fmt.sampleRate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(maxOf(min, fmt.sampleRate / 100 * fmt.channels * 2 * 2))
                    .build()
                    .also {
                        it.setPreferredDevice(output)
                        it.play()
                    }
            } catch (e: Exception) {
                RgLog.w(TAG, "Monitor track unavailable", e)
                monitorWanted = false
                null
            }
        }

        private fun releaseMonitor() {
            val t = monitor ?: return
            monitor = null
            runCatching { t.pause(); t.flush() }
            t.release()
        }
    }

    private companion object { const val TAG = "AudioEngine" }
}

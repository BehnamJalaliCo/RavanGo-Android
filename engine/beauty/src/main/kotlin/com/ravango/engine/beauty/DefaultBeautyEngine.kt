package com.ravango.engine.beauty

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.ravango.core.common.device.DeviceProfiler
import com.ravango.core.common.device.DeviceTier
import com.ravango.core.common.device.ThermalLevel
import com.ravango.core.common.device.ThermalMonitor
import com.ravango.core.common.di.ApplicationScope
import com.ravango.core.common.di.IoDispatcher
import com.ravango.core.common.diagnostics.Diagnostics
import com.ravango.core.common.log.RgLog
import com.ravango.core.datastore.PreferencesDataSource
import com.ravango.core.model.BeautyState
import com.ravango.engine.beauty.effects.BackgroundEffect
import com.ravango.engine.beauty.effects.CameraEffects
import com.ravango.engine.beauty.effects.EffectsAssets
import com.ravango.engine.beauty.effects.EffectsDefaults
import com.ravango.engine.beauty.effects.EffectsState
import com.ravango.engine.beauty.effects.EffectsStatus
import com.ravango.engine.beauty.effects.FilterSwipe
import com.ravango.engine.beauty.effects.Lens
import com.ravango.engine.beauty.effects.LiveFilter
import com.ravango.engine.beauty.quality.QualityController
import com.ravango.engine.beauty.quality.ThermalHint
import com.ravango.engine.beauty.quality.TierHint
import com.ravango.engine.render.GlFrameProcessor
import kotlinx.coroutines.CancellationException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide beauty engine. Owns the live [BeautyState] (restored from and debounced back to
 * [PreferencesDataSource]) and the eye colour (kept in this module's own preferences file), feeds device tier /
 * thermal / power-save hints to the adaptive quality controller and exposes the GL [processor] the camera attaches
 * to its pipeline.
 *
 * ADDED — also implements [CameraEffects] (lenses, live filters, background effects) on the same processor, so
 * effects share face tracking and full-resolution passes with beauty. The filter and background choice persist
 * (the background photo is copied into app storage); the lens does not (like Snapchat, the camera opens clean).
 */
@Singleton
class DefaultBeautyEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: PreferencesDataSource,
    private val deviceProfiler: DeviceProfiler,
    private val thermalMonitor: ThermalMonitor,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BeautyEngine, CameraEffects {

    private val controls = BeautyControls(TierHint.MID)
    private val _state = MutableStateFlow(BeautyState())
    private val _status = MutableStateFlow(BeautyStatus(quality = QualityController.capFor(TierHint.MID, ThermalHint.NORMAL, false)))
    private val _previewBypass = MutableStateFlow(false)
    private val _eyeColor = MutableStateFlow(EyeColorSetting())
    private val userChanged = AtomicBoolean(false)
    private val eyeColorChanged = AtomicBoolean(false)
    private val _effects = MutableStateFlow(EffectsState())
    private val _effectsStatus = MutableStateFlow(EffectsStatus())
    private val _hasBackgroundImage = MutableStateFlow(false)
    private val effectsChanged = AtomicBoolean(false)

    override val processor: GlFrameProcessor = BeautyProcessor(controls, context.applicationContext)
    override val status: StateFlow<BeautyStatus> = _status.asStateFlow()
    override val state: StateFlow<BeautyState> = _state.asStateFlow()
    override val previewBypass: StateFlow<Boolean> = _previewBypass.asStateFlow()
    override val eyeColor: StateFlow<EyeColorSetting> = _eyeColor.asStateFlow()
    override val effects: StateFlow<EffectsState> = _effects.asStateFlow()
    override val effectsStatus: StateFlow<EffectsStatus> = _effectsStatus.asStateFlow()
    override val hasBackgroundImage: StateFlow<Boolean> = _hasBackgroundImage.asStateFlow()

    init {
        controls.state = _state.value
        controls.onStatus = { _status.value = it }
        controls.onEffectsStatus = { _effectsStatus.value = it }
        scope.launch { observeDevice() }
        scope.launch { restoreAndPersist() }
        scope.launch { restoreAndPersistEyeColor() }
        scope.launch { restoreAndPersistEffects() }
    }

    // ------------------------------------------------------------------------------------------------ effects

    private fun updateEffects(transform: (EffectsState) -> EffectsState) {
        effectsChanged.set(true)
        val next = transform(_effects.value)
        controls.effects = next
        _effects.value = next
        Diagnostics.setEnv("camera.effects", "lens=${next.lens?.id ?: "-"} filter=${next.filter.id}@${next.filterIntensity} bg=${next.background::class.simpleName}")
    }

    override fun setLens(lens: Lens?) = updateEffects { it.copy(lens = lens) }

    override fun setFilter(filter: LiveFilter) {
        EffectsAssets.request(filter)
        // Picking a filter while the strength slider sits at 0 would show nothing: restore full strength.
        updateEffects { EffectsDefaults.withFilter(it, filter) }
    }

    override fun setFilterIntensity(intensity: Int) = updateEffects { it.copy(filterIntensity = intensity.coerceIn(0, 100)) }

    override fun setFilterSwipe(swipe: FilterSwipe?) {
        if (swipe != null) EffectsAssets.request(swipe.target)
        controls.filterSwipe = swipe
    }

    override fun setBackground(effect: BackgroundEffect) = updateEffects { it.copy(background = effect) }

    override fun prefetch(filters: List<LiveFilter>) = filters.forEach { EffectsAssets.request(it) }

    override suspend fun setBackgroundImage(uri: Uri): Boolean {
        val bitmap = try {
            withContext(io) {
                val decoded = decodeScaled(uri) ?: return@withContext null
                val file = backgroundFile()
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                decoded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not load background photo", e)
            null
        } catch (e: OutOfMemoryError) {
            // A huge or unusual image: never crash, report it like any unreadable photo.
            Diagnostics.record(TAG, "Out of memory decoding the background photo", e)
            null
        } ?: return false
        controls.backgroundBitmap = bitmap
        _hasBackgroundImage.value = true
        return true
    }

    private fun backgroundFile() = File(context.filesDir, "effects/background.jpg")

    /** Decodes [uri] as an upright ARGB_8888 software bitmap whose long side is at most [MAX_BACKGROUND_SIDE]. */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > MAX_BACKGROUND_SIDE) {
                    val k = MAX_BACKGROUND_SIDE.toFloat() / long
                    decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                }
            }
            return if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_BACKGROUND_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        if (degrees == 0) return raw
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private suspend fun restoreAndPersistEffects() {
        val prefs: SharedPreferences = try {
            withContext(io) {
                val p = context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE)
                val saved = EffectsState(
                    filter = LiveFilter.fromId(p.getString(KEY_FX_FILTER, null)),
                    filterIntensity = p.getInt(KEY_FX_FILTER_INTENSITY, 100).coerceIn(0, 100),
                    background = decodeBackground(p),
                )
                val file = backgroundFile()
                val bitmap = if (file.exists()) {
                    runCatching {
                        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    }.getOrNull()
                } else {
                    null
                }
                if (bitmap != null && controls.backgroundBitmap == null) {
                    controls.backgroundBitmap = bitmap
                    _hasBackgroundImage.value = true
                }
                if (!effectsChanged.get()) {
                    // Lenses are never restored: the camera opens without one.
                    controls.effects = saved
                    _effects.value = saved
                    EffectsAssets.request(saved.filter)
                }
                p
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore camera effects", e)
            return
        }
        val initial = if (effectsChanged.get()) _effects else _effects.drop(1)
        initial.debounce(PERSIST_DEBOUNCE_MS).collect { fx ->
            try {
                withContext(io) {
                    val e = prefs.edit()
                        .putString(KEY_FX_FILTER, fx.filter.id)
                        .putInt(KEY_FX_FILTER_INTENSITY, fx.filterIntensity)
                    encodeBackground(e, fx.background)
                    e.apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Could not save camera effects", e)
            }
        }
    }

    private fun decodeBackground(p: SharedPreferences): BackgroundEffect = when (p.getString(KEY_FX_BG, null)) {
        "blur" -> BackgroundEffect.Blur(p.getInt(KEY_FX_BG_STRENGTH, 60))
        "color" -> BackgroundEffect.Color(p.getLong(KEY_FX_BG_A, 0xFF1B1928))
        "gradient" -> BackgroundEffect.Gradient(p.getLong(KEY_FX_BG_A, 0xFFA394FB), p.getLong(KEY_FX_BG_B, 0xFFFF93AF))
        "image" -> BackgroundEffect.Image
        else -> BackgroundEffect.None
    }

    private fun encodeBackground(e: SharedPreferences.Editor, bg: BackgroundEffect) {
        when (bg) {
            BackgroundEffect.None -> e.putString(KEY_FX_BG, "none")
            is BackgroundEffect.Blur -> e.putString(KEY_FX_BG, "blur").putInt(KEY_FX_BG_STRENGTH, bg.strength)
            is BackgroundEffect.Color -> e.putString(KEY_FX_BG, "color").putLong(KEY_FX_BG_A, bg.argb)
            is BackgroundEffect.Gradient -> e.putString(KEY_FX_BG, "gradient").putLong(KEY_FX_BG_A, bg.top).putLong(KEY_FX_BG_B, bg.bottom)
            BackgroundEffect.Image -> e.putString(KEY_FX_BG, "image")
        }
    }

    override fun setEyeColor(setting: EyeColorSetting) {
        val clean = setting.copy(intensity = setting.intensity.coerceIn(0, 100))
        eyeColorChanged.set(true)
        controls.eyeColor = clean
        _eyeColor.value = clean
    }

    override fun setState(state: BeautyState) {
        userChanged.set(true)
        controls.state = state
        _state.value = state
    }

    override fun setCompareMode(showOriginal: Boolean, affectsRecording: Boolean) {
        controls.bypassAll = showOriginal && affectsRecording
        _previewBypass.value = showOriginal && !affectsRecording
    }

    override fun setRecording(recording: Boolean) {
        controls.recording = recording
    }

    private suspend fun observeDevice() {
        try {
            controls.tier = when (deviceProfiler.profile.tier) {
                DeviceTier.LOW -> TierHint.LOW
                DeviceTier.MID -> TierHint.MID
                DeviceTier.HIGH -> TierHint.HIGH
            }
            _status.value = _status.value.copy(quality = QualityController.capFor(controls.tier, controls.thermal, controls.powerSave))
            thermalMonitor.levels
                .catch { e -> RgLog.w(TAG, "Thermal status unavailable", e) }
                .collect { level ->
                    controls.thermal = when (level) {
                        ThermalLevel.NORMAL -> ThermalHint.NORMAL
                        ThermalLevel.WARM -> ThermalHint.WARM
                        ThermalLevel.HOT -> ThermalHint.HOT
                        ThermalLevel.CRITICAL -> ThermalHint.CRITICAL
                    }
                    controls.powerSave = thermalMonitor.isPowerSaveMode
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Device profiling failed; keeping defaults", e)
        }
    }

    private suspend fun restoreAndPersist() {
        val saved = try {
            preferences.beautyState.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore beauty state", e)
            null
        }
        if (saved != null && !userChanged.get()) {
            controls.state = saved
            _state.value = saved
        } else if (userChanged.get()) {
            persist(_state.value)
        }
        _state.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { persist(it) }
    }

    private suspend fun restoreAndPersistEyeColor() {
        val prefs: SharedPreferences? = try {
            withContext(io) {
                context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE).also { p ->
                    val saved = EyeColorSetting(p.getInt(KEY_EYE_INTENSITY, 0), p.getLong(KEY_EYE_COLOR, EyeColorSetting.DEFAULT_COLOR))
                    if (!eyeColorChanged.get()) {
                        controls.eyeColor = saved
                        _eyeColor.value = saved
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not restore eye colour", e)
            null
        }
        prefs ?: return
        // A value set before the restore finished is persisted right away; later ones are debounced.
        val initial = if (eyeColorChanged.get()) _eyeColor else _eyeColor.drop(1)
        initial.debounce(PERSIST_DEBOUNCE_MS).collect { setting ->
            try {
                withContext(io) {
                    prefs.edit().putInt(KEY_EYE_INTENSITY, setting.intensity).putLong(KEY_EYE_COLOR, setting.color).apply()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RgLog.w(TAG, "Could not save eye colour", e)
            }
        }
    }

    private suspend fun persist(state: BeautyState) {
        try {
            preferences.updateBeautyState { state }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RgLog.w(TAG, "Could not save beauty state", e)
        }
    }

    private companion object {
        const val TAG = "BeautyEngine"
        const val PERSIST_DEBOUNCE_MS = 600L
        const val EXTRAS_PREFS = "ravango_beauty_extras"
        const val KEY_EYE_INTENSITY = "eye_color_intensity"
        const val KEY_EYE_COLOR = "eye_color_argb"
        const val KEY_FX_FILTER = "fx_filter"
        const val KEY_FX_FILTER_INTENSITY = "fx_filter_intensity"
        const val KEY_FX_BG = "fx_background"
        const val KEY_FX_BG_A = "fx_background_a"
        const val KEY_FX_BG_B = "fx_background_b"
        const val KEY_FX_BG_STRENGTH = "fx_background_strength"
        const val MAX_BACKGROUND_SIDE = 1600
    }
}

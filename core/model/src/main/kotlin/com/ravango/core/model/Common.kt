package com.ravango.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Generates a new globally unique identifier. All entities use UUID strings so they can be created offline and synced. */
fun newId(): String = UUID.randomUUID().toString()

/** Wall-clock milliseconds; injectable where determinism matters. */
fun interface Clock {
    fun now(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}

/**
 * Local-first sync state carried by every syncable entity.
 * - [SYNCED]: identical to the last known server copy.
 * - [PENDING]: changed locally, must be pushed.
 * - [CONFLICT]: server copy changed concurrently; resolved by last-writer-wins but flagged for UI.
 */
@Serializable
enum class SyncStatus { SYNCED, PENDING, CONFLICT }

/** Text direction for content (scripts, subtitles), independent of the app UI locale. */
@Serializable
enum class ContentDirection { AUTO, RTL, LTR }

/** Aspect ratio used by the camera, editor canvas and templates. */
@Serializable
data class AspectRatioSpec(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height.toFloat()
    val isPortrait: Boolean get() = height > width
    val label: String get() = "$width:$height"

    companion object {
        val Portrait9x16 = AspectRatioSpec(9, 16)
        val Landscape16x9 = AspectRatioSpec(16, 9)
        val Square1x1 = AspectRatioSpec(1, 1)
        val Portrait4x5 = AspectRatioSpec(4, 5)
        val Portrait3x4 = AspectRatioSpec(3, 4)
        val Landscape4x3 = AspectRatioSpec(4, 3)
        val Cinema21x9 = AspectRatioSpec(21, 9)
        val Presets = listOf(Portrait9x16, Landscape16x9, Square1x1, Portrait4x5, Portrait3x4, Landscape4x3, Cinema21x9)
    }
}

/** Simple ARGB color packed into a Long so it serializes cleanly (and avoids sign issues with Int). */
typealias ArgbColor = Long

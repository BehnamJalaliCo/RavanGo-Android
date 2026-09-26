package com.ravango.core.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ravango.core.common.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies finished media from app storage into the shared gallery (Movies/RavanGo, Music/RavanGo).
 * Uses scoped storage on Android 10+ (no permission); falls back to the public directory on older versions.
 */
@Singleton
class MediaStorePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun publishVideo(file: File, displayName: String = file.name, mimeType: String = "video/mp4"): Uri? =
        publish(file, displayName, mimeType, isAudio = false)

    suspend fun publishAudio(file: File, displayName: String = file.name, mimeType: String = "audio/mp4"): Uri? =
        publish(file, displayName, mimeType, isAudio = true)

    private suspend fun publish(file: File, displayName: String, mimeType: String, isAudio: Boolean): Uri? = withContext(io) {
        if (!file.exists()) return@withContext null
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val relative = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/RavanGo"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "RavanGo").apply { mkdirs() }
            val target = File(dir, displayName)
            runCatching {
                file.copyTo(target, overwrite = true)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
                Uri.fromFile(target)
            }.getOrNull()
        }
    }
}

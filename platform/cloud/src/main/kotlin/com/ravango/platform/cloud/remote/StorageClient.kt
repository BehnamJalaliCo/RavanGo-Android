package com.ravango.platform.cloud.remote

import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttp
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.auth.supabase.await
import com.ravango.platform.auth.supabase.executeForText
import com.ravango.platform.auth.supabase.parseSupabaseError
import com.ravango.platform.auth.supabase.toRequestBody
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** A local file to upload, readable any number of times (plain file or content:// URI). */
class UploadSource(val sizeBytes: Long, val mimeType: String, val open: () -> InputStream)

/** Persists resumable-upload URLs so an interrupted upload continues where it stopped (even after a restart). */
interface ResumableUploadStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, uploadUrl: String?)
}

/**
 * Supabase Storage client. Small files use a single `POST /object` (with `x-upsert`); files above
 * [RESUMABLE_THRESHOLD] use the TUS resumable protocol (`/upload/resumable`, 6 MB chunks as required by
 * Supabase) so large recordings survive flaky connections and process death.
 */
@Singleton
class StorageClient @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    @SupabaseHttp private val http: OkHttpClient,
) {

    suspend fun upload(
        bucket: String,
        path: String,
        source: UploadSource,
        accessToken: String,
        resumeStore: ResumableUploadStore,
        onProgress: (Long) -> Unit,
    ) {
        if (source.sizeBytes <= RESUMABLE_THRESHOLD) {
            simpleUpload(bucket, path, source, accessToken)
            onProgress(source.sizeBytes)
        } else {
            resumableUpload(bucket, path, source, accessToken, resumeStore, onProgress)
        }
    }

    private suspend fun simpleUpload(bucket: String, path: String, source: UploadSource, accessToken: String) {
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = source.mimeType.toMediaTypeOrNull()
            override fun contentLength(): Long = source.sizeBytes
            override fun writeTo(sink: BufferedSink) {
                source.open().use { input -> sink.writeAll(input.source()) }
            }
        }
        val request = endpoints.request(endpoints.storage("object/$bucket/$path"), accessToken)
            .header("x-upsert", "true")
            .header("cache-control", "3600")
            .post(body)
            .build()
        http.executeForText(request)
    }

    private suspend fun resumableUpload(
        bucket: String,
        path: String,
        source: UploadSource,
        accessToken: String,
        resumeStore: ResumableUploadStore,
        onProgress: (Long) -> Unit,
    ) {
        val key = "$bucket/$path"
        val saved = resumeStore.get(key)
        val savedOffset = saved?.let { currentOffset(it, accessToken) }
        val uploadUrl: String
        var offset: Long
        if (saved != null && savedOffset != null) {
            uploadUrl = saved
            offset = savedOffset
        } else {
            uploadUrl = createUpload(bucket, path, source, accessToken)
            resumeStore.put(key, uploadUrl)
            offset = 0L
        }
        onProgress(offset)
        source.open().use { input ->
            skipFully(input, offset)
            val buffer = ByteArray(CHUNK_SIZE)
            while (offset < source.sizeBytes) {
                currentCoroutineContext().ensureActive()
                val read = readChunk(input, buffer)
                if (read <= 0) throw IOException("file shorter than expected ($offset/${source.sizeBytes})")
                val request = endpoints.request(uploadUrl.toHttpUrlOrNull() ?: throw IOException("bad upload url"), accessToken)
                    .header("Tus-Resumable", TUS_VERSION)
                    .header("Upload-Offset", offset.toString())
                    .patch(buffer.toRequestBody(OFFSET_OCTET_STREAM, 0, read))
                    .build()
                http.newCall(request).await().use { response ->
                    if (response.code == 404 || response.code == 410) {
                        resumeStore.put(key, null) // expired upload: next attempt starts over
                        throw IOException("resumable upload expired")
                    }
                    if (!response.isSuccessful) throw parseSupabaseError(response.code, response.body?.string().orEmpty())
                    offset = response.header("Upload-Offset")?.toLongOrNull() ?: (offset + read)
                }
                onProgress(offset)
            }
        }
        resumeStore.put(key, null)
    }

    private suspend fun createUpload(bucket: String, path: String, source: UploadSource, accessToken: String): String {
        fun b64(v: String) = Base64.getEncoder().encodeToString(v.toByteArray())
        val metadata = listOf(
            "bucketName" to bucket,
            "objectName" to path,
            "contentType" to source.mimeType,
            "cacheControl" to "3600",
        ).joinToString(",") { (k, v) -> "$k ${b64(v)}" }
        val request = endpoints.request(endpoints.storage("upload/resumable"), accessToken)
            .header("Tus-Resumable", TUS_VERSION)
            .header("Upload-Length", source.sizeBytes.toString())
            .header("Upload-Metadata", metadata)
            .header("x-upsert", "true")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw parseSupabaseError(response.code, response.body?.string().orEmpty())
            val location = response.header("Location") ?: throw SupabaseHttpException(response.code, "tus_no_location", "missing Location")
            return location.toHttpUrlOrNull()?.toString() ?: (endpoints.baseUrl + "/" + location.trimStart('/'))
        }
    }

    /** Server offset of an existing upload, or null if it no longer exists. */
    private suspend fun currentOffset(uploadUrl: String, accessToken: String): Long? {
        val url = uploadUrl.toHttpUrlOrNull() ?: return null
        val request = endpoints.request(url, accessToken).header("Tus-Resumable", TUS_VERSION).head().build()
        return runCatching {
            http.newCall(request).await().use { r -> if (r.isSuccessful) r.header("Upload-Offset")?.toLongOrNull() else null }
        }.getOrNull()
    }

    /** Downloads an object the user owns into [target] (written atomically). */
    suspend fun download(bucket: String, path: String, target: File, accessToken: String) {
        val request = endpoints.request(endpoints.storage("object/authenticated/$bucket/$path"), accessToken).get().build()
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw parseSupabaseError(response.code, response.body?.string().orEmpty())
            val body = response.body ?: throw IOException("empty body")
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
            if (!tmp.renameTo(target)) throw IOException("could not move downloaded file")
        }
    }

    /** Deletes objects by path. Missing objects are ignored by the server. */
    suspend fun delete(bucket: String, paths: List<String>, accessToken: String) {
        paths.chunked(100).forEach { chunk ->
            val body = buildJsonObject { put("prefixes", JsonArray(chunk.map { JsonPrimitive(it) })) }
            val request = endpoints.request(endpoints.storage("object/$bucket"), accessToken)
                .delete(body.toRequestBody())
                .build()
            http.executeForText(request)
        }
    }

    /** Lists object names directly under [prefix] (one level), paging through all results. */
    suspend fun list(bucket: String, prefix: String, accessToken: String): List<String> {
        val names = mutableListOf<String>()
        var offset = 0
        while (true) {
            val body = buildJsonObject {
                put("prefix", prefix)
                put("limit", LIST_PAGE)
                put("offset", offset)
                putJsonObject("sortBy") { put("column", "name"); put("order", "asc") }
            }
            val request = endpoints.request(endpoints.storage("object/list/$bucket"), accessToken).post(body.toRequestBody()).build()
            val array = SupabaseJson.parseToJsonElement(http.executeForText(request)) as? JsonArray ?: break
            array.forEach { item -> (item as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull?.let(names::add) }
            if (array.size < LIST_PAGE) break
            offset += array.size
        }
        return names
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) throw IOException("unexpected end of file while resuming")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    companion object {
        const val MEDIA_BUCKET = "media"
        const val CHUNK_SIZE = 6 * 1024 * 1024
        const val RESUMABLE_THRESHOLD = 6L * 1024 * 1024
        private const val TUS_VERSION = "1.0.0"
        private const val LIST_PAGE = 1000
        private val OFFSET_OCTET_STREAM = "application/offset+octet-stream".toMediaTypeOrNull()
    }
}

package com.ravango.platform.cloud.remote

import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttp
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.auth.supabase.executeForText
import com.ravango.platform.auth.supabase.toRequestBody
import com.ravango.platform.cloud.merge.CursorPager
import com.ravango.platform.cloud.merge.PageCursor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One synced row as stored in Supabase. Every sync table has the same shape (see backend/sql/schema.sql):
 * `id text pk, user_id uuid, updated_at bigint (client ms), deleted_at bigint null, data jsonb,
 * server_updated_at timestamptz` — the entity fields live in `data` so the schema never needs a migration when a
 * model gains a field.
 */
data class RemoteRow(
    val id: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val data: JsonObject,
    val serverUpdatedAtUs: Long,
)

/** A row to upsert. */
data class OutgoingRow(val id: String, val updatedAt: Long, val deletedAt: Long?, val data: JsonObject)

/** Minimal PostgREST client for the sync tables. */
@Singleton
class PostgrestClient @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    @SupabaseHttp private val http: OkHttpClient,
) {

    /**
     * Upserts rows (`on_conflict=id`, merge-duplicates) and returns what the server stored. The server-side
     * trigger keeps the existing row when it is newer, so a returned `updated_at` different from ours means
     * another device won the race.
     */
    suspend fun upsert(table: String, userId: String, rows: List<OutgoingRow>, accessToken: String): List<RemoteRow> {
        if (rows.isEmpty()) return emptyList()
        val body = JsonArray(
            rows.map { row ->
                buildJsonObject {
                    put("id", row.id)
                    put("user_id", userId)
                    put("updated_at", row.updatedAt)
                    put("deleted_at", row.deletedAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("data", row.data)
                }
            },
        )
        val url = endpoints.rest(table).newBuilder()
            .addQueryParameter("on_conflict", "id")
            .addQueryParameter("select", COLUMNS)
            .build()
        val request = endpoints.request(url, accessToken)
            .header("Prefer", "resolution=merge-duplicates,return=representation")
            .post(body.toRequestBody())
            .build()
        return parseRows(http.executeForText(request))
    }

    /** One page of rows changed since [cursor], ordered by (server_updated_at, id). */
    suspend fun pullPage(table: String, userId: String, cursor: PageCursor, limit: Int, accessToken: String): List<RemoteRow> {
        val url = endpoints.rest(table).newBuilder()
            .addQueryParameter("select", COLUMNS)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("server_updated_at", "gte.${CursorPager.formatTimestampUs(cursor.sinceUs)}")
            .addQueryParameter("order", "server_updated_at.asc,id.asc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", cursor.offset.toString())
            .build()
        val request = endpoints.request(url, accessToken).get().build()
        return parseRows(http.executeForText(request))
    }

    /** Fetches a single row by id (used for the per-user settings row). */
    suspend fun getRow(table: String, id: String, accessToken: String): RemoteRow? {
        val url = endpoints.rest(table).newBuilder()
            .addQueryParameter("select", COLUMNS)
            .addQueryParameter("id", "eq.$id")
            .build()
        return parseRows(http.executeForText(endpoints.request(url, accessToken).get().build())).firstOrNull()
    }

    private fun parseRows(text: String): List<RemoteRow> {
        if (text.isBlank()) return emptyList()
        val array = runCatching { SupabaseJson.parseToJsonElement(text).jsonArray }.getOrElse { return emptyList() }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            RemoteRow(
                id = id,
                updatedAt = obj["updated_at"]?.jsonPrimitive?.longOrNull ?: 0L,
                deletedAt = (obj["deleted_at"] as? JsonPrimitive)?.longOrNull,
                data = (obj["data"] as? JsonObject) ?: JsonObject(emptyMap()),
                serverUpdatedAtUs = obj["server_updated_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { CursorPager.parseTimestampUs(it) }.getOrNull() } ?: 0L,
            )
        }
    }

    private companion object {
        const val COLUMNS = "id,updated_at,deleted_at,data,server_updated_at"
    }
}

package com.ravango.platform.billing.server

import com.ravango.core.model.Plan
import com.ravango.platform.auth.supabase.SupabaseEndpoints
import com.ravango.platform.auth.supabase.SupabaseHttp
import com.ravango.platform.auth.supabase.SupabaseHttpException
import com.ravango.platform.auth.supabase.SupabaseJson
import com.ravango.platform.auth.supabase.await
import com.ravango.platform.auth.supabase.executeForText
import com.ravango.platform.auth.supabase.parseSupabaseError
import com.ravango.platform.auth.supabase.toRequestBody
import com.ravango.platform.billing.ProductType
import com.ravango.platform.billing.PurchaseRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** The user's entitlement as recorded server-side (`public.entitlements`, written only by the service role). */
data class ServerEntitlement(
    val plan: Plan,
    val isTrial: Boolean,
    val expiresAt: Long?,
    /** Purchased AI credit packs remaining (server authoritative when present). */
    val aiPackBalance: Int?,
    /** Monthly AI credits used in the current period, as metered by the AI gateway. */
    val aiCreditsUsed: Int?,
    val periodStart: Long?,
)

data class VerificationResult(
    val valid: Boolean,
    val entitlement: ServerEntitlement?,
    /** Credits the server added for a consumable pack (so the client must not add them again). */
    val creditsAdded: Int,
    val reason: String?,
)

/**
 * Server-side purchase verification seam. Contract (see backend/README.md):
 *
 * `POST /functions/v1/verify-purchase` with the user's bearer token and
 * `{store, packageName, productId, purchaseToken, type: "subs"|"inapp"}` →
 * `{valid, entitlement: {plan, is_trial, expires_at, ai_pack_balance, ai_credits_used, period_start}, credits_added, reason}`.
 * The function validates the token with the Google Play Developer API, acknowledges it, records it in
 * `purchases`, and upserts `entitlements`. A 404 means the function is not deployed → local trust.
 */
@Singleton
class EntitlementServer @Inject constructor(
    private val endpoints: SupabaseEndpoints,
    @SupabaseHttp private val http: OkHttpClient,
) {
    val isConfigured: Boolean get() = endpoints.isConfigured

    /** Null when the function is not deployed. Throws on network/server errors. */
    suspend fun verify(purchase: PurchaseRecord, productId: String, type: ProductType, store: String, accessToken: String): VerificationResult? {
        val body = buildJsonObject {
            put("store", store)
            put("packageName", purchase.packageName)
            put("productId", productId)
            put("purchaseToken", purchase.purchaseToken)
            put("orderId", purchase.orderId ?: "")
            put("type", if (type == ProductType.SUBSCRIPTION) "subs" else "inapp")
        }
        val request = endpoints.request(endpoints.function("verify-purchase"), accessToken).post(body.toRequestBody()).build()
        http.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404) return null
            val obj = runCatching { SupabaseJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (!response.isSuccessful && (obj == null || obj["valid"] == null)) throw parseSupabaseError(response.code, text)
            obj ?: throw SupabaseHttpException(response.code, "bad_response", "empty verification response")
            return VerificationResult(
                valid = obj.bool("valid") ?: false,
                entitlement = (obj["entitlement"] as? JsonObject)?.toServerEntitlement(),
                creditsAdded = obj.int("credits_added") ?: 0,
                reason = obj.str("reason"),
            )
        }
    }

    /** The signed-in user's entitlement row, or null when there is none. */
    suspend fun fetch(userId: String, accessToken: String): ServerEntitlement? {
        val url = endpoints.rest("entitlements").newBuilder()
            .addQueryParameter("select", "plan,is_trial,expires_at,ai_pack_balance,ai_credits_used,period_start")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("limit", "1")
            .build()
        val array = SupabaseJson.parseToJsonElement(http.executeForText(endpoints.request(url, accessToken).get().build())) as? JsonArray
        return (array?.firstOrNull() as? JsonObject)?.toServerEntitlement()
    }

    /** Reads the public `app_config` value for [key] (anon key). */
    suspend fun appConfig(key: String): JsonElement? {
        val url = endpoints.rest("app_config").newBuilder()
            .addQueryParameter("select", "value")
            .addQueryParameter("key", "eq.$key")
            .build()
        val array = SupabaseJson.parseToJsonElement(http.executeForText(endpoints.request(url).get().build())) as? JsonArray
        return (array?.firstOrNull() as? JsonObject)?.get("value")
    }
}

internal fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

/** Accepts epoch ms numbers or ISO timestamps. */
internal fun JsonObject.time(key: String): Long? {
    val p = get(key) as? JsonPrimitive ?: return null
    p.longOrNull?.let { return it }
    return p.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}

internal fun JsonObject.toServerEntitlement(): ServerEntitlement = ServerEntitlement(
    plan = str("plan")?.uppercase()?.let { name -> Plan.entries.firstOrNull { it.name == name } } ?: Plan.FREE,
    isTrial = bool("is_trial") ?: false,
    expiresAt = time("expires_at"),
    aiPackBalance = int("ai_pack_balance"),
    aiCreditsUsed = int("ai_credits_used"),
    periodStart = time("period_start"),
)

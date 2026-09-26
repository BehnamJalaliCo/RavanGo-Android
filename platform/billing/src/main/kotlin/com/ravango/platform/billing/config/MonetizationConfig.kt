package com.ravango.platform.billing.config

import com.ravango.core.model.Plan
import com.ravango.core.model.ProFeature
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Limits and features granted by one plan. `features` accepts ProFeature names or "*" (all). */
@Serializable
data class PlanLimits(
    val features: List<String> = emptyList(),
    val aiCreditsPerMonth: Int = 20,
    val cloudQuotaBytes: Long = 0,
    val maxRecordShortSide: Int = 1080,
    val maxRecordFps: Int = 30,
    val maxExportShortSide: Int = 1080,
    val maxExportFps: Int = 30,
    /** -1 = unlimited. */
    val maxSavedPresets: Int = 3,
    val watermarkOnExport: Boolean = true,
) {
    /** Known features only: names added server-side for newer app versions are ignored here. */
    fun featureSet(): Set<ProFeature> =
        if ("*" in features) ProFeature.entries.toSet() else features.mapNotNull { name -> ProFeature.entries.firstOrNull { it.name == name } }.toSet()

    val presetLimit: Int get() = if (maxSavedPresets < 0) Int.MAX_VALUE else maxSavedPresets
}

@Serializable
data class CreditPack(val productId: String, val credits: Int)

/** Store product identifiers (Google Play Console). */
@Serializable
data class ProductIds(
    /** One subscription with two base plans; the yearly base plan carries the free-trial offer. */
    val subscriptionId: String = "ravango_pro",
    val monthlyBasePlanId: String = "monthly",
    val yearlyBasePlanId: String = "yearly",
    val yearlyTrialOfferId: String = "yearly-free-trial",
    val lifetimeProductId: String = "ravango_lifetime",
    val creditPacks: List<CreditPack> = listOf(CreditPack("ai_credits_200", 200), CreditPack("ai_credits_1000", 1000)),
) {
    fun packFor(productId: String): CreditPack? = creditPacks.firstOrNull { it.productId == productId }
}

/**
 * The commercial model as data (see docs/MONETIZATION.md). Built-in defaults ship with the app; a remote
 * override (`app_config.key = 'monetization'`) can change any field without an app update.
 */
@Serializable
data class MonetizationConfig(
    val version: Int = 1,
    val free: PlanLimits = PlanLimits(),
    val pro: PlanLimits = PlanLimits(),
    val lifetime: PlanLimits = PlanLimits(),
    val products: ProductIds = ProductIds(),
    val trialDays: Int = 7,
) {
    fun limitsFor(plan: Plan): PlanLimits = when (plan) {
        Plan.FREE -> free
        Plan.PRO -> pro
        Plan.LIFETIME -> lifetime
    }

    companion object {
        private const val GB = 1024L * 1024 * 1024

        val Default = MonetizationConfig(
            version = 1,
            free = PlanLimits(
                features = emptyList(),
                aiCreditsPerMonth = 20,
                cloudQuotaBytes = 0, // scripts & settings sync only; no media backup
                maxRecordShortSide = 1080,
                maxRecordFps = 30,
                maxExportShortSide = 1080,
                maxExportFps = 30,
                maxSavedPresets = 3,
                watermarkOnExport = true,
            ),
            pro = PlanLimits(
                features = listOf("*"),
                aiCreditsPerMonth = 1000,
                cloudQuotaBytes = 50 * GB,
                maxRecordShortSide = 2160,
                maxRecordFps = 60,
                maxExportShortSide = 2160,
                maxExportFps = 60,
                maxSavedPresets = -1,
                watermarkOnExport = false,
            ),
            lifetime = PlanLimits(
                features = listOf("*"),
                aiCreditsPerMonth = 200, // AI has running costs; lifetime gets a smaller monthly allowance
                cloudQuotaBytes = 20 * GB,
                maxRecordShortSide = 2160,
                maxRecordFps = 60,
                maxExportShortSide = 2160,
                maxExportFps = 60,
                maxSavedPresets = -1,
                watermarkOnExport = false,
            ),
            products = ProductIds(),
            trialDays = 7,
        )

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
            coerceInputValues = true
        }

        fun encode(config: MonetizationConfig): String = json.encodeToString(serializer(), config)

        fun decode(raw: String): MonetizationConfig? = runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()?.takeIf { it.isValid() }

        /**
         * Applies a partial remote override on top of [base] (deep merge: objects merge key by key, everything else
         * replaces). Returns null when the result is invalid, so a bad remote value can never break the app.
         */
        fun applyOverride(base: MonetizationConfig, override: JsonElement): MonetizationConfig? {
            val overrideObject = override as? JsonObject ?: return null
            val merged = deepMerge(json.encodeToJsonElement(serializer(), base).jsonObject, overrideObject)
            return runCatching { json.decodeFromJsonElement(serializer(), merged) }.getOrNull()?.takeIf { it.isValid() }
        }

        internal fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
            val result = base.toMutableMap()
            for ((key, value) in override) {
                val existing = result[key]
                result[key] = if (existing is JsonObject && value is JsonObject) deepMerge(existing, value) else value
            }
            return JsonObject(result)
        }
    }

    fun isValid(): Boolean {
        val plans = listOf(free, pro, lifetime)
        return plans.all { it.aiCreditsPerMonth >= 0 && it.cloudQuotaBytes >= 0 && it.maxRecordShortSide > 0 && it.maxExportShortSide > 0 && it.maxRecordFps > 0 && it.maxExportFps > 0 } &&
            products.subscriptionId.isNotBlank() && products.lifetimeProductId.isNotBlank() &&
            products.creditPacks.all { it.productId.isNotBlank() && it.credits > 0 } && trialDays >= 0
    }
}

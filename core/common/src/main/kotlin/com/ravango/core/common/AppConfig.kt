package com.ravango.core.common

/**
 * Build-time configuration provided by the app module (values come from `secrets.properties` / CI secrets).
 * Empty values mean the corresponding service is not configured; features must degrade gracefully and say so.
 */
data class AppConfig(
    val versionName: String,
    val versionCode: Int,
    val isDebug: Boolean,
    /** Distribution channel: "play", "bazaar", "myket", "direct". Selects the billing provider. */
    val distribution: String,
    /** Supabase project URL, e.g. https://xyz.supabase.co — powers auth, sync and storage. */
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    /** OAuth web client id for Google Sign-In via Credential Manager. */
    val googleWebClientId: String,
    /** RavanGo AI gateway base URL (server-side proxy that holds provider keys and meters credits). */
    val aiGatewayUrl: String,
    val privacyPolicyUrl: String,
    val termsUrl: String,
    val supportEmail: String,
) {
    val isCloudConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()
    val isAiGatewayConfigured: Boolean get() = aiGatewayUrl.isNotBlank()
    val isGoogleSignInConfigured: Boolean get() = googleWebClientId.isNotBlank()
}

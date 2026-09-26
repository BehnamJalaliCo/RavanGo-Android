import java.util.Properties

plugins {
    alias(libs.plugins.ravango.android.application)
    alias(libs.plugins.ravango.android.compose)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
}

/** Resolves a config value: env var RAVANGO_<NAME> → secrets.properties → secrets.defaults.properties. */
fun secret(name: String): String {
    val env = System.getenv("RAVANGO_" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
    if (!env.isNullOrBlank()) return env
    val props = Properties()
    listOf("secrets.defaults.properties", "secrets.properties").forEach { file ->
        rootProject.file(file).takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    }
    return props.getProperty(name, "")
}

fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.ravango.app"

    defaultConfig {
        applicationId = "com.ravango.app"
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SUPABASE_URL", quoted(secret("supabaseUrl")))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted(secret("supabaseAnonKey")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(secret("googleWebClientId")))
        buildConfigField("String", "AI_GATEWAY_URL", quoted(secret("aiGatewayUrl")))
        buildConfigField("String", "PRIVACY_URL", quoted(secret("privacyPolicyUrl")))
        buildConfigField("String", "TERMS_URL", quoted(secret("termsUrl")))
        buildConfigField("String", "SUPPORT_EMAIL", quoted(secret("supportEmail")))
        buildConfigField("String", "DISTRIBUTION", quoted(secret("distribution").ifBlank { "play" }))
    }

    androidResources {
        // Per-app language support (Android 13+) generated from res folders.
        generateLocaleConfig = true
        localeFilters += listOf("fa", "en")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Shared debug key (public, non-secret) so debug/CI builds install over each other across machines.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val storeFilePath = secret("releaseStoreFile")
            if (storeFilePath.isNotBlank()) {
                storeFile = file(storeFilePath)
                storePassword = secret("releaseStorePassword")
                keyAlias = secret("releaseKeyAlias")
                keyPassword = secret("releaseKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))

    implementation(project(":engine:render"))
    implementation(project(":engine:camera"))
    implementation(project(":engine:audio"))
    implementation(project(":engine:beauty"))
    implementation(project(":engine:teleprompter"))
    implementation(project(":engine:editor"))
    implementation(project(":engine:ai"))

    implementation(project(":platform:auth"))
    implementation(project(":platform:cloud"))
    implementation(project(":platform:billing"))

    implementation(project(":feature:onboarding"))
    implementation(project(":feature:home"))
    implementation(project(":feature:scripts"))
    implementation(project(":feature:teleprompter"))
    implementation(project(":feature:camera"))
    implementation(project(":feature:beauty"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:account"))
    implementation(project(":feature:paywall"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

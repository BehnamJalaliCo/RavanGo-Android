plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:media"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    api(libs.media3.transformer)
    api(libs.media3.effect)
    api(libs.media3.common)
    api(libs.media3.exoplayer)
    implementation(libs.media3.muxer)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
}

plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    buildFeatures.buildConfig = true
}

dependencies {
    api(project(":core:model"))
    api(project(":core:media"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.anthropic.java)
}

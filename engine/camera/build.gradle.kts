plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    api(project(":engine:render"))
    api(project(":engine:audio"))
    api(project(":core:common"))
    implementation(project(":core:media"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
}

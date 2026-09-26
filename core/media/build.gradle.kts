plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}

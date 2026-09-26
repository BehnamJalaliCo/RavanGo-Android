plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:media"))
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
}

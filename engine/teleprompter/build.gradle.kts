plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.android.compose)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:ui"))
}

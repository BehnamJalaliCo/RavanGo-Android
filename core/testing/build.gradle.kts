plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.android.compose)
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:model"))
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.androidx.compose.ui.test.junit4)
    implementation(libs.junit)
}

plugins {
    alias(libs.plugins.ravango.android.feature)
    alias(libs.plugins.ravango.android.screenshot)
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.activity.compose)
}

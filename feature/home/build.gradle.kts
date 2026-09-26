plugins {
    alias(libs.plugins.ravango.android.feature)
    alias(libs.plugins.ravango.android.screenshot)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.activity.compose)
}

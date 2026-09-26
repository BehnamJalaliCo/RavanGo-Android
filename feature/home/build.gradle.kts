plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.activity.compose)
}

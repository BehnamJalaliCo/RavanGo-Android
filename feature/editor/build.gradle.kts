plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":engine:editor"))
    implementation(project(":engine:audio"))
    implementation(project(":engine:ai"))
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}

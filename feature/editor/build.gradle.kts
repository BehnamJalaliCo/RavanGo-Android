plugins {
    alias(libs.plugins.ravango.android.feature)
    alias(libs.plugins.ravango.android.screenshot)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=androidx.media3.common.util.UnstableApi", "-Xannotation-default-target=param-property")
    }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}

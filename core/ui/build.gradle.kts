plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.android.compose)
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

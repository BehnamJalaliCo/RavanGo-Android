plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.android.compose)
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.util)
    api(libs.androidx.compose.animation)
    implementation(libs.androidx.core.ktx)
}

plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":engine:render"))
    api(project(":engine:audio"))
    implementation(project(":core:common"))
    implementation(project(":core:media"))
    implementation(libs.androidx.core.ktx)
}

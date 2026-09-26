plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":engine:render"))
    implementation(project(":core:common"))
    implementation(libs.mlkit.face.detection)
}

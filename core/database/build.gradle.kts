plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.ravango.room)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
}

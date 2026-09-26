plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}

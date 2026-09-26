plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
}

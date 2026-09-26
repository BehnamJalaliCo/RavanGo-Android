plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:datastore"))
    api(project(":core:common"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
}

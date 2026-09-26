plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":engine:beauty"))
}

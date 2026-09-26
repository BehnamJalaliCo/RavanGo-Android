plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":platform:billing"))
}

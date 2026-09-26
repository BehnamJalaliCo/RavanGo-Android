plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:media"))
    implementation(project(":engine:camera"))
    implementation(project(":engine:audio"))
    implementation(project(":engine:beauty"))
    implementation(project(":engine:teleprompter"))
    implementation(project(":feature:beauty"))
}

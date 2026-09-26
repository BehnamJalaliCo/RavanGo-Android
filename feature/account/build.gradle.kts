plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":platform:auth"))
    implementation(project(":platform:cloud"))
    implementation(project(":platform:billing"))
    implementation(project(":engine:ai"))
}

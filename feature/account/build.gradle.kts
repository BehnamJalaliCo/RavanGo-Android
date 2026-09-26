plugins {
    alias(libs.plugins.ravango.android.feature)
    alias(libs.plugins.ravango.android.screenshot)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":platform:auth"))
    implementation(project(":platform:cloud"))
    implementation(project(":platform:billing"))
    implementation(project(":engine:ai"))
}

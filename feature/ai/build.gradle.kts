plugins {
    alias(libs.plugins.ravango.android.feature)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":engine:ai"))
}

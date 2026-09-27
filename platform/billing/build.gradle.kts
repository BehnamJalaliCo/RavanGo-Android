plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":platform:auth"))
    implementation(libs.billing.ktx)
    implementation(libs.poolakey)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}

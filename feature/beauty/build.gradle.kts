plugins {
    alias(libs.plugins.ravango.android.feature)
    alias(libs.plugins.ravango.android.screenshot)
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":engine:beauty"))
    // Looks: favourites, recents, the active look and saved custom looks.
    implementation(libs.androidx.datastore.preferences)
}

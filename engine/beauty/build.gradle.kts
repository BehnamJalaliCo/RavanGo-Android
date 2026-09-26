plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.hilt)
}

dependencies {
    api(project(":core:model"))
    api(project(":engine:render"))
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    // MediaPipe Face Landmarker: 478-point dense face mesh (468 + iris), blendshapes, facial transformation matrix.
    implementation(libs.mediapipe.tasks.vision)
}

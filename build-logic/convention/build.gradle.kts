plugins {
    `kotlin-dsl`
}

group = "com.ravango.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.room.gradlePlugin)
    implementation(libs.roborazzi.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "ravango.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "ravango.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "ravango.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "ravango.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = "ravango.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("room") {
            id = "ravango.room"
            implementationClass = "RoomConventionPlugin"
        }
        register("androidScreenshot") {
            id = "ravango.android.screenshot"
            implementationClass = "AndroidScreenshotConventionPlugin"
        }
        register("jvmLibrary") {
            id = "ravango.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}

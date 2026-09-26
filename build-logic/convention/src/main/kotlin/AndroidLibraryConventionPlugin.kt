import com.android.build.gradle.LibraryExtension
import com.ravango.buildlogic.configureKotlinAndroid
import com.ravango.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            // Namespace derives from the Gradle path: :engine:camera -> com.ravango.engine.camera
            namespace = "com.ravango" + path.replace(':', '.').replace('-', '_')
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
            testOptions.unitTests.isIncludeAndroidResources = true
        }
        dependencies {
            add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
            add("testImplementation", libs.findLibrary("junit").get())
            add("testImplementation", libs.findLibrary("truth").get())
            add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}

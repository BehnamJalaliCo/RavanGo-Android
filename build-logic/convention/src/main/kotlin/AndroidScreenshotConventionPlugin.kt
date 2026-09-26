import com.android.build.gradle.LibraryExtension
import com.ravango.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * JVM screenshot tests (Robolectric native graphics + Roborazzi). Renders real Compose screens to PNG without an
 * emulator: `./gradlew :feature:home:recordRoborazziDebug` → `build/outputs/roborazzi/`.
 */
class AndroidScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.github.takahirom.roborazzi")
        extensions.configure<LibraryExtension> {
            testOptions.unitTests.isIncludeAndroidResources = true
        }
        dependencies {
            add("testImplementation", project(":core:testing"))
            add("testImplementation", libs.findLibrary("robolectric").get())
            add("testImplementation", libs.findLibrary("roborazzi").get())
            add("testImplementation", libs.findLibrary("roborazzi-compose").get())
            add("testImplementation", libs.findLibrary("roborazzi-junit-rule").get())
            add("testImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        }
        tasks.withType<Test>().configureEach {
            maxHeapSize = "2g"
            // Optional mirror for Robolectric's android-all jars (set robolectricRepoUrl in ~/.gradle/gradle.properties).
            (findProperty("robolectricRepoUrl") as String?)?.let { systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
}

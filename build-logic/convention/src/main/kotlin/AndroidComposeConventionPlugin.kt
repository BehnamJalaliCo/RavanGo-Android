import com.android.build.api.dsl.CommonExtension
import com.ravango.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.gradle.kotlin.dsl.configure

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByName("android") as CommonExtension<*, *, *, *, *, *>
        android.buildFeatures.compose = true
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
        }
        dependencies {
            val bom = libs.findLibrary("androidx-compose-bom").get()
            add("implementation", platform(bom))
            add("androidTestImplementation", platform(bom))
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        }
    }
}

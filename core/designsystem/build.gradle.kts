import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    alias(libs.plugins.ravango.android.library)
    alias(libs.plugins.ravango.android.compose)
    alias(libs.plugins.ravango.android.screenshot)
}

dependencies {
    api(project(":core:model"))
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.util)
    api(libs.androidx.compose.animation)
    implementation(libs.androidx.core.ktx)
}

/*
 * Brand font ("Ravagh", licensed from fontiran.com — license #136710).
 *
 * The font is commercial and must not be published in this (public) repository. Its files live in the git-ignored
 * `private/fonts/ravagh/` directory (locally) or are decrypted there in CI from `fonts-private/ravagh-fonts.tar.gz.gpg`
 * using the RAVAGH_FONT_PASSPHRASE secret. When they are absent (forks, open builds) the build falls back to the
 * OFL-licensed Vazirmatn so the project always compiles. Resources are generated as `R.font.brand_*`.
 */
abstract class GenerateBrandFontsTask : DefaultTask() {
    @get:InputFiles @get:Optional @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val privateFonts: ConfigurableFileCollection

    @get:InputDirectory @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fallbackFontDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val fontOut = File(out, "font").apply { mkdirs() }
        val valuesOut = File(out, "values").apply { mkdirs() }
        val weights = listOf("light", "regular", "medium", "semibold", "bold", "extrabold", "black")
        val private = privateFonts.files.associateBy { it.nameWithoutExtension }
        val usePrivate = weights.all { private["brand_$it"]?.isFile == true }
        val fallback = mapOf(
            "light" to "vazirmatn_light", "regular" to "vazirmatn_regular", "medium" to "vazirmatn_medium",
            "semibold" to "vazirmatn_semi_bold", "bold" to "vazirmatn_bold", "extrabold" to "vazirmatn_extra_bold",
            "black" to "vazirmatn_extra_bold",
        )
        weights.forEach { w ->
            val src = if (usePrivate) private.getValue("brand_$w") else File(fallbackFontDir.get().asFile, "${fallback.getValue(w)}.ttf")
            src.copyTo(File(fontOut, "brand_$w.ttf"), overwrite = true)
        }
        val name = if (usePrivate) "Ravagh" else "Vazirmatn"
        File(valuesOut, "brand_font.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<resources>
            |    <string name="brand_font_name" translatable="false">$name</string>
            |    <bool name="brand_font_is_licensed_ravagh">$usePrivate</bool>
            |</resources>
            |""".trimMargin(),
        )
        logger.lifecycle("RavanGo brand font: $name")
    }
}

val generateBrandFonts = tasks.register<GenerateBrandFontsTask>("generateBrandFonts") {
    privateFonts.from(rootProject.fileTree("private/fonts/ravagh") { include("brand_*.ttf") })
    fallbackFontDir.set(layout.projectDirectory.dir("src/main/res/font"))
    outputDir.set(layout.buildDirectory.dir("generated/brandFonts/res"))
}

extensions.configure<LibraryAndroidComponentsExtension> {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateBrandFonts, GenerateBrandFontsTask::outputDir)
    }
}

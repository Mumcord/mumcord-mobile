// SPDX-License-Identifier: AGPL-3.0-only

// The design system of the Android app: theme, fonts, icons and base components. Colours, type
// scale, spacing, radii, shadows and icons are generated at build time from the shared files in
// design/ at the repository root, which the iOS app uses as well, so nothing is typed twice.

import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val designDir = rootProject.layout.projectDirectory.dir("../design")

/** Turns design/tokens.json and design/icons.json into Kotlin sources. */
abstract class GenerateDesignTokens : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokens: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icons: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun camel(key: String, upperFirst: Boolean = false): String {
        val joined = key.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        return if (upperFirst) joined else joined.replaceFirstChar(Char::lowercase)
    }

    /** "#rrggbb" or "#rrggbbaa" as a Compose colour literal (which is 0xAARRGGBB). */
    private fun color(hex: String): String {
        val digits = hex.removePrefix("#").uppercase()
        require(digits.length == 6 || digits.length == 8) { "Bad colour value $hex" }
        val alpha = if (digits.length == 8) digits.substring(6) else "FF"
        return "Color(0x$alpha${digits.substring(0, 6)})"
    }

    private fun float(value: Any?): String = "${(value as Number).toFloat()}f"

    @Suppress("UNCHECKED_CAST")
    private fun section(root: Map<String, Any?>, name: String): Map<String, Any?> =
        (root[name] as Map<String, Any?>).filterKeys { it != "comment" }

    @Suppress("UNCHECKED_CAST")
    private fun themed(root: Map<String, Any?>, name: String, theme: String): Map<String, Any?> =
        section(root, name)[theme] as Map<String, Any?>

    @TaskAction
    @Suppress("UNCHECKED_CAST")
    fun generate() {
        val root = JsonSlurper().parse(tokens.get().asFile) as Map<String, Any?>
        val out = StringBuilder()
        out.appendLine("// Generated from design/tokens.json. Do not edit.")
        out.appendLine("package app.mumcord.mobile.design.tokens")
        out.appendLine()
        out.appendLine("import androidx.compose.runtime.Immutable")
        out.appendLine("import androidx.compose.ui.graphics.Color")
        out.appendLine("import androidx.compose.ui.text.TextStyle")
        out.appendLine("import androidx.compose.ui.unit.Dp")
        out.appendLine("import androidx.compose.ui.unit.dp")
        out.appendLine()

        val dark = themed(root, "color", "dark")
        val light = themed(root, "color", "light")
        require(dark.keys == light.keys) { "Dark and light must define the same colours" }
        out.appendLine("@Immutable\ndata class MumcordColors(")
        dark.keys.forEach { out.appendLine("    val ${camel(it)}: Color,") }
        out.appendLine(")\n")
        for ((name, values) in listOf("DarkColors" to dark, "LightColors" to light)) {
            out.appendLine("val $name = MumcordColors(")
            values.forEach { (key, value) -> out.appendLine("    ${camel(key)} = ${color(value as String)},") }
            out.appendLine(")\n")
        }

        val darkOpacity = themed(root, "opacity", "dark")
        val lightOpacity = themed(root, "opacity", "light")
        require(darkOpacity.keys == lightOpacity.keys) { "Dark and light must define the same opacities" }
        out.appendLine("@Immutable\ndata class MumcordOpacities(")
        darkOpacity.keys.forEach { out.appendLine("    val ${camel(it)}: Float,") }
        out.appendLine(")\n")
        for ((name, values) in listOf("DarkOpacities" to darkOpacity, "LightOpacities" to lightOpacity)) {
            out.appendLine("val $name = MumcordOpacities(")
            values.forEach { (key, value) -> out.appendLine("    ${camel(key)} = ${float(value)},") }
            out.appendLine(")\n")
        }

        val darkShadow = themed(root, "shadow", "dark")
        val lightShadow = themed(root, "shadow", "light")
        require(darkShadow.keys == lightShadow.keys) { "Dark and light must define the same shadows" }
        out.appendLine("@Immutable\ndata class MumcordShadows(")
        darkShadow.keys.forEach { out.appendLine("    val ${camel(it)}: ShadowToken,") }
        out.appendLine(")\n")
        for ((name, values) in listOf("DarkShadows" to darkShadow, "LightShadows" to lightShadow)) {
            out.appendLine("val $name = MumcordShadows(")
            values.forEach { (key, value) ->
                val shadow = value as Map<String, Any?>
                out.appendLine(
                    "    ${camel(key)} = ShadowToken(y = ${shadow["y"]}.dp, blur = ${shadow["blur"]}.dp, " +
                        "spread = (${shadow["spread"]}).dp, color = ${color(shadow["color"] as String)}),",
                )
            }
            out.appendLine(")\n")
        }

        val text = section(root, "text")
        out.appendLine("@Immutable\ndata class MumcordTypography(")
        text.keys.forEach { out.appendLine("    val ${camel(it)}: TextStyle,") }
        out.appendLine(")\n")
        out.appendLine("val TextTokens: Map<String, TextToken> = mapOf(")
        text.forEach { (key, value) ->
            val style = value as Map<String, Any?>
            out.appendLine(
                "    \"${camel(key)}\" to TextToken(display = ${style["font"] == "display"}, " +
                    "size = ${float(style["size"])}, weight = ${style["weight"]}, " +
                    "lineHeight = ${float(style["line-height"])}, " +
                    "letterSpacing = ${float(style["letter-spacing"])}),",
            )
        }
        out.appendLine(")\n")
        out.appendLine("internal fun mumcordTypography(resolve: (TextToken) -> TextStyle) = MumcordTypography(")
        text.keys.forEach { out.appendLine("    ${camel(it)} = resolve(TextTokens.getValue(\"${camel(it)}\")),") }
        out.appendLine(")\n")

        val font = section(root, "font")
        out.appendLine("internal object FontAxes {")
        font.forEach { (key, value) ->
            val axes = (value as Map<String, Any?>)["axes"] as Map<String, Any?>
            val entries = axes.entries.joinToString { "\"${it.key}\" to ${float(it.value)}" }
            out.appendLine("    val $key: Map<String, Float> = mapOf($entries)")
        }
        out.appendLine("}\n")

        for ((objectName, sectionName, prefix) in listOf(
            Triple("MumcordSpace", "space", "s"),
            Triple("MumcordRadius", "radius", ""),
            Triple("MumcordSize", "size", ""),
        )) {
            out.appendLine("object $objectName {")
            section(root, sectionName).forEach { (key, value) ->
                out.appendLine("    val ${camel(prefix + key)}: Dp = $value.dp")
            }
            out.appendLine("}\n")
        }

        val iconRoot = JsonSlurper().parse(icons.get().asFile) as Map<String, Any?>
        val iconSet = iconRoot["icons"] as Map<String, List<String>>
        val iconOut = StringBuilder()
        iconOut.appendLine("// Generated from design/icons.json. Do not edit.")
        iconOut.appendLine("package app.mumcord.mobile.design.icons")
        iconOut.appendLine()
        iconOut.appendLine("import androidx.compose.ui.graphics.vector.ImageVector")
        iconOut.appendLine()
        iconOut.appendLine("object MumcordIcons {")
        iconOut.appendLine("    private const val VIEW_BOX = ${float(iconRoot["viewBox"])}")
        iconOut.appendLine("    private const val STROKE = ${float(iconRoot["strokeWidth"])}")
        iconOut.appendLine()
        iconSet.forEach { (id, paths) ->
            val data = paths.joinToString { "\"$it\"" }
            iconOut.appendLine(
                "    val ${camel(id, upperFirst = true)}: ImageVector by lazy { strokeIcon(\"$id\", VIEW_BOX, STROKE, $data) }",
            )
        }
        iconOut.appendLine()
        iconOut.appendLine("    /** Every icon by its id in design/icons.json. */")
        iconOut.appendLine("    val all: Map<String, ImageVector> by lazy {")
        iconOut.appendLine("        mapOf(")
        iconSet.keys.forEach { iconOut.appendLine("            \"$it\" to ${camel(it, upperFirst = true)},") }
        iconOut.appendLine("        )")
        iconOut.appendLine("    }")
        iconOut.appendLine("}")

        val base = outputDir.get().asFile
        base.deleteRecursively()
        File(base, "app/mumcord/mobile/design/tokens").apply { mkdirs() }
            .resolve("GeneratedTokens.kt").writeText(out.toString())
        File(base, "app/mumcord/mobile/design/icons").apply { mkdirs() }
            .resolve("MumcordIcons.kt").writeText(iconOut.toString())
    }
}

/** Copies the font files under the names Android resources allow (lower case, no brackets). */
abstract class CopyDesignFonts : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bodyFont: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val displayFont: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val fontDir = File(outputDir.get().asFile, "font")
        fontDir.deleteRecursively()
        fontDir.mkdirs()
        bodyFont.get().asFile.copyTo(File(fontDir, "instrument_sans.ttf"))
        displayFont.get().asFile.copyTo(File(fontDir, "bricolage_grotesque.ttf"))
    }
}

val generateDesignTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    tokens = designDir.file("tokens.json")
    icons = designDir.file("icons.json")
}

val copyDesignFonts = tasks.register<CopyDesignFonts>("copyDesignFonts") {
    bodyFont = designDir.file("fonts/instrument-sans/InstrumentSans[wdth,wght].ttf")
    displayFont = designDir.file("fonts/bricolage-grotesque/BricolageGrotesque[opsz,wdth,wght].ttf")
}

android {
    namespace = "app.mumcord.mobile.design"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            // The token tests compare the theme with the shared source files.
            it.systemProperty("mumcord.design.dir", designDir.asFile.absolutePath)
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned on purpose and raised deliberately, not whenever a newer one exists.
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // Lint rules shipped inside a library that this lint version cannot load; not our code.
        disable += "ObsoleteLintCustomCheck"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateDesignTokens, GenerateDesignTokens::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(copyDesignFonts, CopyDesignFonts::outputDir)
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    // Only for the Material colour scheme and ripple that screens outside the design system use.
    api(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

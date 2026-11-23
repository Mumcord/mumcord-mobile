// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import app.mumcord.mobile.design.icons.MumcordIcons
import app.mumcord.mobile.design.theme.mumcordColors
import app.mumcord.mobile.design.tokens.DarkOpacities
import app.mumcord.mobile.design.tokens.DarkShadows
import app.mumcord.mobile.design.tokens.LightOpacities
import app.mumcord.mobile.design.tokens.LightShadows
import app.mumcord.mobile.design.tokens.MumcordRadius
import app.mumcord.mobile.design.tokens.MumcordSize
import app.mumcord.mobile.design.tokens.MumcordSpace
import app.mumcord.mobile.design.tokens.ShadowToken
import app.mumcord.mobile.design.tokens.TextTokens
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android theme must carry exactly the values of the shared files in design/, which the iOS
 * app uses too. The theme is generated from them; these tests read the files independently of
 * the generator and compare.
 */
class TokenSourceTest {
    private val designDir = File(checkNotNull(System.getProperty("mumcord.design.dir")))
    private val tokens = JSONObject(File(designDir, "tokens.json").readText())

    private fun camel(key: String): String =
        key.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }.replaceFirstChar(Char::lowercase)

    /** "#rrggbb" or "#rrggbbaa" as 0xAARRGGBB. */
    private fun argb(hex: String): Int {
        val digits = hex.removePrefix("#")
        val alpha = if (digits.length == 8) digits.substring(6) else "ff"
        return (alpha + digits.substring(0, 6)).toLong(16).toInt()
    }

    /** Reads a property of a generated class by name; colours and sizes are inline classes. */
    private fun property(target: Any, name: String): Any {
        val getter = "get" + name.replaceFirstChar(Char::uppercase)
        val method = target.javaClass.methods.single { it.name == getter || it.name.startsWith("$getter-") }
        return checkNotNull(method.invoke(target))
    }

    private fun entries(section: JSONObject): List<Pair<String, Any>> =
        section.keys().asSequence().filter { it != "comment" }.map { it to section.get(it) }.toList()

    @Test
    fun coloursOfBothThemesEqualTheSharedSource() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            val colors = mumcordColors(darkTheme = dark)
            val source = entries(tokens.getJSONObject("color").getJSONObject(theme))
            assertTrue("$theme colours missing", source.size > 40)
            for ((key, value) in source) {
                val actual = Color((property(colors, camel(key)) as Long).toULong()).toArgb()
                assertEquals("$theme colour $key", argb(value as String), actual)
            }
        }
    }

    @Test
    fun opacitiesAndShadowsOfBothThemesEqualTheSharedSource() {
        for ((theme, opacities, shadows) in listOf(
            Triple("dark", DarkOpacities, DarkShadows),
            Triple("light", LightOpacities, LightShadows),
        )) {
            for ((key, value) in entries(tokens.getJSONObject("opacity").getJSONObject(theme))) {
                assertEquals("$theme opacity $key", (value as Number).toFloat(), property(opacities, camel(key)) as Float, 0f)
            }
            for ((key, value) in entries(tokens.getJSONObject("shadow").getJSONObject(theme))) {
                val source = value as JSONObject
                val expected = ShadowToken(
                    y = Dp(source.getDouble("y").toFloat()),
                    blur = Dp(source.getDouble("blur").toFloat()),
                    spread = Dp(source.getDouble("spread").toFloat()),
                    color = Color(argb(source.getString("color"))),
                )
                assertEquals("$theme shadow $key", expected, property(shadows, camel(key)))
            }
        }
    }

    @Test
    fun textStylesEqualTheSharedSource() {
        val source = entries(tokens.getJSONObject("text"))
        assertEquals(source.map { camel(it.first) }.toSet(), TextTokens.keys)
        for ((key, value) in source) {
            val style = value as JSONObject
            val token = TextTokens.getValue(camel(key))
            assertEquals("$key font", style.getString("font") == "display", token.display)
            assertEquals("$key size", style.getDouble("size").toFloat(), token.size, 0f)
            assertEquals("$key weight", style.getInt("weight"), token.weight)
            assertEquals("$key line height", style.getDouble("line-height").toFloat(), token.lineHeight, 0f)
            assertEquals("$key letter spacing", style.getDouble("letter-spacing").toFloat(), token.letterSpacing, 0f)
        }
    }

    @Test
    fun spacingRadiiAndSizesEqualTheSharedSource() {
        for ((section, target, prefix) in listOf(
            Triple("space", MumcordSpace, "s"),
            Triple("radius", MumcordRadius, ""),
            Triple("size", MumcordSize, ""),
        )) {
            for ((key, value) in entries(tokens.getJSONObject(section))) {
                val actual = property(target, camel(prefix + key)) as Float
                assertEquals("$section $key", (value as Number).toFloat(), actual, 0f)
            }
        }
    }

    @Test
    fun everyIconOfTheSharedSourceExists() {
        val source = JSONObject(File(designDir, "icons.json").readText()).getJSONObject("icons")
        val ids = source.keys().asSequence().toSet()
        assertEquals(ids, MumcordIcons.all.keys)
        for (id in ids) {
            val paths = source.getJSONArray(id).length()
            assertEquals("$id path count", paths, MumcordIcons.all.getValue(id).root.size)
        }
    }
}

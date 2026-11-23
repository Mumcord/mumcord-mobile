// SPDX-License-Identifier: AGPL-3.0-only
package app.mumcord.mobile.session

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The texts written by `scripts/translations.py` (FR-5.82): every text of every module exists in
 * every shipped language, is never empty and has the placeholders of its English text, so no
 * screen shows a key name or crashes on a format argument.
 */
class TranslationsTest {
    /** The `res` folders with texts: the app's and those of the platform modules. */
    private val resources: List<File> =
        listOf(File("src/main/res")) +
            File("../platform").listFiles().orEmpty().map { File(it, "src/main/res") }.filter { englishFiles(it).isNotEmpty() }

    /** The English text files of a `res` folder: `strings.xml` and the screens' own `strings_*.xml`. */
    private fun englishFiles(res: File): List<String> =
        File(res, "values").listFiles().orEmpty().map { it.name }.filter { it.startsWith("strings") && it.endsWith(".xml") }.sorted()

    @Test
    fun everyTextIsInEveryLanguageWithItsPlaceholders() {
        for (res in resources) for (name in englishFiles(res)) {
            val english = texts(File(res, "values/$name"))
            assertTrue("no texts in $res/values/$name", english.isNotEmpty())
            for (language in APP_LANGUAGES - "en") {
                val file = File(res, "values-$language/$name")
                assertTrue("$file is missing", file.exists())
                val translated = texts(file)
                assertEquals("texts of $file", english.keys, translated.keys)
                for ((name, forms) in translated) {
                    val englishPlaceholders = english.getValue(name).values.flatMap(::placeholders).toSet()
                    for ((quantity, text) in forms) {
                        assertTrue("$language/$name ($quantity) is empty", text.isNotBlank())
                        assertTrue(
                            "$language/$name ($quantity) has placeholders its English text lacks",
                            englishPlaceholders.containsAll(placeholders(text)),
                        )
                    }
                    // A plural form may leave its number out ("ein Server"); a plain text may not.
                    assertEquals("placeholders of $language/$name", englishPlaceholders, forms.values.flatMap(::placeholders).toSet())
                }
            }
        }
    }

    @Test
    fun theShippedLanguagesAreTheDesktopsTwentyFour() {
        assertEquals(24, APP_LANGUAGES.size)
        for (res in resources) {
            val translated = res.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }
            assertEquals("languages of $res", (APP_LANGUAGES - "en").map { "values-$it" }.toSet(), translated.toSet())
        }
        val config = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml"))
        val locales = config.getElementsByTagName("locale")
        val listed = (0 until locales.length).map { (locales.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        assertEquals(APP_LANGUAGES, listed)
    }

    @Test
    fun everyLanguageHasItsOwnName() {
        val names = APP_LANGUAGES.map(::languageEndonym)
        assertEquals("Deutsch", languageEndonym("de"))
        assertEquals("Français", languageEndonym("fr"))
        assertEquals(names.size, names.toSet().size)
        assertEquals("English", languageEndonym("en"))
    }

    /** Name → quantity → text; a plain text has the quantity "". Not translatable texts are left out. */
    private fun texts(file: File): Map<String, Map<String, String>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = mutableMapOf<String, Map<String, String>>()
        val children = root.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.getAttribute("translatable") == "false") continue
            val name = element.getAttribute("name")
            out[name] = when (element.tagName) {
                "string" -> mapOf("" to element.textContent)
                else -> {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return out
    }

    /** The placeholders of a text, `%d` counted from the left like Android does (`%1$d`). */
    private fun placeholders(text: String): Set<String> {
        var next = 0
        return PLACEHOLDER.findAll(text).map { match ->
            val position = match.groupValues[1].ifEmpty { (++next).toString() }
            "%$position$" + match.groupValues[2]
        }.toSet()
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(?:(\d+)\$)?([sd])""")
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

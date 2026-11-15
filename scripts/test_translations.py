# SPDX-License-Identifier: AGPL-3.0-only
"""Tests of scripts/translations.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import translations as t


def entry(name: str, text: str) -> t.Entry:
    return t.Entry(name, text=text)


def plural(name: str, one: str, other: str) -> t.Entry:
    return t.Entry(name, plural={"one": one, "other": other})


def desktop(**languages: dict[str, str]) -> t.Desktop:
    texts = {lang: {} for lang in t.LANGUAGES}
    for lang, values in languages.items():
        texts[lang] = values
    return t.Desktop(texts)


class AndroidTextTest(unittest.TestCase):
    def test_escapes_are_read_as_android_shows_them(self):
        self.assertEqual(t.android_unescape(r"Don\'t \"quote\"\n\@me"), "Don't \"quote\"\n@me")
        self.assertEqual(t.android_unescape("  a \n  b  "), "a b")
        self.assertEqual(t.android_unescape('"  kept  "'), "  kept  ")
        self.assertEqual(t.android_unescape(r"é"), "é")

    def test_written_texts_read_back_unchanged(self):
        for text in ["It's", 'say "hi"', "@name", "?why", "a & b <c>", " lead", "two  spaces", "line\nbreak", r"back\slash"]:
            value = t.android_escape(text)
            parsed = ET.fromstring(f"<string>{value}</string>").text
            self.assertEqual(t.android_unescape(parsed), text, value)

    def test_a_lone_percent_next_to_placeholders_is_refused(self):
        with self.assertRaises(t.TranslationError):
            t.android_args("%1$d% done", "x")
        self.assertEqual(t.android_args("%1$d%% of %2$s", "x"), [(1, "d"), (2, "s")])


class PlaceholderTest(unittest.TestCase):
    def test_names_follow_the_positions_of_the_english_texts(self):
        formats = t.name_positions("{{name}} joined {{channel}}", "%1$s joined %2$s", "x")
        self.assertEqual(formats, {"name": "%1$s", "channel": "%2$s"})
        self.assertEqual(
            t.to_android("{{channel}}: {{name}} ist beigetreten", formats, "x", require_all=True),
            "%2$s: %1$s ist beigetreten",
        )

    def test_numbers_keep_their_type_and_percent_is_doubled(self):
        formats = t.name_positions("{{count}}% done", "%1$d%% done", "x")
        self.assertEqual(t.to_android("{{count}} % fertig", formats, "x", require_all=True), "%1$d %% fertig")

    def test_a_text_without_placeholders_keeps_its_percent(self):
        self.assertEqual(t.to_android("100 %", {}, "x", require_all=True), "100 %")

    def test_unknown_or_missing_placeholders_are_refused(self):
        formats = {"name": "%1$s"}
        with self.assertRaises(t.TranslationError):
            t.to_android("{{nom}} est là", formats, "x", require_all=True)
        with self.assertRaises(t.TranslationError):
            t.to_android("Jemand ist da", formats, "x", require_all=True)
        # A plural form may leave the number out ("ein Server").
        self.assertEqual(t.to_android("Ein Server", {"count": "%1$d"}, "x", require_all=False), "Ein Server")

    def test_markup_is_refused(self):
        with self.assertRaises(t.TranslationError):
            t.to_android("Read <0>this</0>", {}, "x", require_all=True)

    def test_mobile_texts_are_written_with_numbered_names(self):
        self.assertEqual(t.to_desktop("%1$s of %2$d at 5%%", "x"), "{{arg1}} of {{arg2}} at 5%")
        self.assertEqual(t.mobile_formats("%1$s of %2$d", "x"), {"arg1": "%1$s", "arg2": "%2$d"})

    def test_unnumbered_placeholders_count_from_the_left(self):
        self.assertEqual(t.android_args("%s has %d", "x"), [(1, "s"), (2, "d")])
        self.assertEqual(t.to_desktop("%d watching", "x"), "{{arg1}} watching")
        self.assertEqual(t.to_android("{{arg1}} sehen zu", t.mobile_formats("%d watching", "x"), "x", require_all=True), "%1$d sehen zu")
        with self.assertRaises(t.TranslationError):
            t.android_args("%s and %2$s", "x")


class TranslateTest(unittest.TestCase):
    def test_a_desktop_text_is_taken_with_its_translation(self):
        source = desktop(en={"common:join": "Join {{channel}}"}, de={"common:join": "{{channel}} beitreten"})
        coverage = t.Coverage()
        text = t.translate(entry("join", "Join %1$s"), "de", source, "common:join", {}, coverage)
        self.assertEqual(text, "%1$s beitreten")
        self.assertEqual(coverage.desktop, 1)

    def test_an_untranslated_text_is_english_never_empty(self):
        source = desktop(en={"common:join": "Join"}, de={"common:join": ""})
        coverage = t.Coverage()
        self.assertEqual(t.translate(entry("join", "Join"), "de", source, "common:join", {}, coverage), "Join")
        self.assertEqual(t.translate(entry("only", "Phone only"), "fr", source, None, {}, coverage), "Phone only")
        self.assertEqual(coverage.english, 2)

    def test_each_language_gets_its_plural_categories(self):
        source = desktop(
            en={"a:n_one": "{{count}} server", "a:n_other": "{{count}} servers"},
            pl={"a:n_one": "{{count}} serwer", "a:n_few": "{{count}} serwery", "a:n_other": "{{count}} serwera"},
        )
        forms = t.translate(plural("n", "%1$d server", "%1$d servers"), "pl", source, "a:n", {}, t.Coverage())
        self.assertEqual(list(forms), ["one", "few", "many", "other"])
        # A missing form ("many") falls back to the language's own "other".
        self.assertEqual(forms["many"], "%1$d serwera")
        english = t.translate(plural("n", "%1$d server", "%1$d servers"), "lv", source, "a:n", {}, t.Coverage())
        self.assertEqual(english, {"zero": "%1$d servers", "one": "%1$d server", "other": "%1$d servers"})

    def test_mobile_only_plurals_are_read_from_the_mobile_file(self):
        mobile = {"n_one": "{{arg1}} Server", "n_other": "{{arg1}} Server"}
        forms = t.translate(plural("n", "%1$d server", "%1$d servers"), "de", desktop(), None, mobile, t.Coverage())
        self.assertEqual(forms, {"one": "%1$d Server", "other": "%1$d Server"})


class ChooseKeysTest(unittest.TestCase):
    def test_a_unique_english_match_is_taken(self):
        source = desktop(en={"a:x": "Leave", "a:y": "Stay"})
        self.assertEqual(t.choose_keys([entry("leave", "Leave")], source, {}), {"leave": "a:x"})

    def test_matches_translated_differently_need_a_choice(self):
        source = desktop(en={"a:x": "Light", "b:y": "Light"}, de={"a:x": "Hell", "b:y": "Leicht"})
        with self.assertRaises(t.TranslationError):
            t.choose_keys([entry("light", "Light")], source, {})
        self.assertEqual(t.choose_keys([entry("light", "Light")], source, {"light": "b:y"}), {"light": "b:y"})
        self.assertEqual(t.choose_keys([entry("light", "Light")], source, {"light": None}), {})

    def test_an_override_whose_english_changed_is_refused(self):
        source = desktop(en={"a:x": "Leave now"})
        with self.assertRaises(t.TranslationError):
            t.choose_keys([entry("leave", "Leave")], source, {"leave": "a:x"})
        with self.assertRaises(t.TranslationError):
            t.choose_keys([entry("leave", "Leave")], source, {"gone": "a:x"})

    def test_punctuation_alone_is_not_matched(self):
        source = desktop(en={"a:x": "{{a}} / {{b}}", "b:y": "{{a}} / {{b}}"}, de={"a:x": "{{a}} / {{b}}", "b:y": "{{a}}/{{b}}"})
        self.assertEqual(t.choose_keys([entry("count", "%1$d / %2$d")], source, {}), {})


class BuildTest(unittest.TestCase):
    def test_a_repository_is_written_and_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            (repo / "engine.lock.toml").write_text('[engine]\nrev = "abc123"\n')
            engine = repo / ".engine"
            (engine / "ui/src/locales/en").mkdir(parents=True)
            (engine / "ui/src/locales/de").mkdir(parents=True)
            (engine / ".fetched-ref").write_text("https://example.invalid abc123 testenv\n")
            (engine / "ui/src/locales/en/common.json").write_text(json.dumps({"leave": "Leave"}))
            (engine / "ui/src/locales/de/common.json").write_text(json.dumps({"leave": "Verlassen"}))
            res = repo / "android/app/src/main/res/values"
            res.mkdir(parents=True)
            (res / "strings.xml").write_text(
                '<resources><string name="app" translatable="false">Mumcord</string>'
                '<string name="leave">Leave</string><string name="hello">Hello %1$s</string></resources>'
            )
            (repo / "i18n/mobile").mkdir(parents=True)
            (repo / "i18n/desktop-keys.json").write_text("{}")
            (repo / "i18n/mobile/de.json").write_text(json.dumps({"hello": "Hallo {{arg1}}"}))

            files, coverage = t.build(repo, engine)
            german = files[repo / "android/app/src/main/res/values-de/strings.xml"]
            self.assertIn('<string name="leave">Verlassen</string>', german)
            self.assertIn('<string name="hello">Hallo %1$s</string>', german)
            self.assertNotIn('name="app"', german)
            self.assertIn("abc123", german)
            self.assertEqual(json.loads(files[repo / "i18n/mobile/en.json"]), {"hello": "Hello {{arg1}}"})
            self.assertEqual((coverage["de"].desktop, coverage["de"].mobile, coverage["fr"].english), (1, 1, 2))

            (repo / "i18n/mobile/de.json").write_text(json.dumps({"gone": "Weg"}))
            with self.assertRaises(t.TranslationError):
                t.build(repo, engine)

            (engine / ".fetched-ref").write_text("https://example.invalid other testenv\n")
            with self.assertRaises(t.TranslationError):
                t.build(repo, engine)


if __name__ == "__main__":
    unittest.main()

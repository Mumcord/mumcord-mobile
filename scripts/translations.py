# SPDX-License-Identifier: AGPL-3.0-only
"""Writes the translated Android texts from the desktop app's translation files.

The English texts are written by hand in each module's `res/values/strings*.xml` files. A text whose
English equals a desktop text (`ui/src/locales/<lang>/<namespace>.json` of the Mumcord commit
pinned in engine.lock.toml, checked out into .engine/ by `scripts/testenv.sh fetch`) takes that
text's translations. All other texts are mobile-only: their English is written to
`i18n/mobile/en.json` in the desktop format, ready to be added to the desktop repository, and
their translations are read from `i18n/mobile/<lang>.json`. An untranslated text is English.

`i18n/desktop-keys.json` decides by hand where the English text alone does not: it names the
desktop key of an Android text whose English matches several desktop texts translated
differently, and `null` keeps a matching text mobile-only.

    python3 scripts/translations.py generate   # writes the values-<lang>/strings*.xml files
    python3 scripts/translations.py check      # fails when a written file is out of date

Placeholders: Android's `%1$s` and `%1$d` are `{{name}}` in the desktop files; the names are
matched to the positions through the two English texts, and the mobile-only texts call them
`{{arg1}}`, `{{arg2}}`. A translation with an unknown placeholder, or without one of a plain
text's placeholders, is refused. Plurals are `<plurals>` in Android and `_one`, `_other` …
suffixes in the desktop files; each language gets exactly its CLDR plural categories.

The iOS app's English texts are written by hand in `ios/Mumcord/Resources/en.lproj/
Localizable.strings`, under the names of the Android texts; plural forms end in `.one`,
`.other`. Each language gets a `<language>.lproj/Localizable.strings` and every language,
English too, a `Localizable.stringsdict` with the plurals. An iOS text takes the translation of
the Android text of the same name when both say the same in English; an iOS text that Android
does not have, or words differently, takes a desktop text's translations as above. All other
iOS texts are English. iOS's `%1$@` and `%1$ld` stand for Android's `%1$s` and `%1$d`; the
stringsdict takes the number that picks the plural form as one more argument after the text's own.
"""

import argparse
import json
import re
import sys
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape as xml_escape
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parent.parent

# The 24 languages of the desktop app (its SUPPORTED_LANGUAGES), English first.
LANGUAGES = [
    "en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "ga", "hr",
    "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv",
]

# The CLDR plural categories of each language, as the desktop's i18next uses them.
PLURAL_CATEGORIES = {lang: ["one", "other"] for lang in LANGUAGES}
for lang in ["cs", "ga", "hr", "lt", "mt", "pl", "ro", "sk", "sl"]:
    PLURAL_CATEGORIES[lang].append("few")
for lang in ["ga", "mt", "sl"]:
    PLURAL_CATEGORIES[lang].append("two")
for lang in ["cs", "es", "fr", "ga", "it", "lt", "mt", "pl", "pt", "sk"]:
    PLURAL_CATEGORIES[lang].append("many")
PLURAL_CATEGORIES["lv"].append("zero")
CATEGORY_ORDER = ["zero", "one", "two", "few", "many", "other"]
PLURAL_SUFFIX = re.compile(r"^(.*)_(zero|one|two|few|many|other)$")

# `%1$s` and `%2$d`, or `%s` and `%d` counted from the left; `%%` is a literal '%'.
ANDROID_FORMAT = re.compile(r"%(?:(\d+)\$)?([sd])|%%")
DESKTOP_PLACEHOLDER = re.compile(r"\{\{\s*([A-Za-z0-9_.]+)\s*\}\}")
# Trans components (`<0>…</0>`) and nesting (`$t(…)`) have no Android counterpart.
DESKTOP_UNSUPPORTED = re.compile(r"</?\d+>|\$t\(|\{\{-")
LETTER = re.compile(r"[^\W\d_]")
# iOS: `%1$@`, `%1$ld`, `%1$d` or unnumbered; `%%` is a literal '%'.
IOS_FORMAT = re.compile(r"%(?:(\d+)\$)?(@|ld|d)|%%")
IOS_ENGLISH = Path("ios/Mumcord/Resources/en.lproj/Localizable.strings")
IOS_STRINGS_LINE = re.compile(r'^"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)";$')


class TranslationError(Exception):
    """A text that cannot be written correctly; the message names it."""


@dataclass
class Entry:
    """One `<string>` or `<plurals>` of an English strings.xml, as the app shows it."""

    name: str
    # The text of a `<string>`, or the text of each quantity of a `<plurals>`.
    text: str | None = None
    plural: dict[str, str] | None = None


# --- Android resource text --------------------------------------------------------------------


def android_unescape(raw: str) -> str:
    """The text Android shows for a resource value, as aapt2 reads it: escapes resolved,
    whitespace outside double quotes collapsed to one space and trimmed at both ends, and the
    quotes themselves removed."""
    # Each character with whether it may be trimmed (an unquoted space).
    chars: list[tuple[str, bool]] = []
    quoted = False
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "u":
                chars.append((chr(int(raw[i + 2 : i + 6], 16)), False))
                i += 6
            else:
                chars.append(({"n": "\n", "t": "\t"}.get(n, n), False))
                i += 2
            continue
        if c == '"':
            quoted = not quoted
        elif c.isspace() and not quoted:
            if not chars or not chars[-1][1]:
                chars.append((" ", True))
        else:
            chars.append((c, False))
        i += 1
    while chars and chars[0][1]:
        chars.pop(0)
    while chars and chars[-1][1]:
        chars.pop()
    return "".join(c for c, _ in chars)


def android_escape(text: str) -> str:
    """The resource value that makes Android show `text`."""
    value = (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
    )
    if value[:1] in ("@", "?"):
        value = "\\" + value
    value = xml_escape(value)
    # Leading, trailing or doubled spaces only survive inside double quotes.
    if value != value.strip(" ") or "  " in value:
        value = f'"{value}"'
    return value


def read_strings(path: Path) -> list[Entry]:
    """The translatable texts of an English strings.xml, in file order."""
    entries = []
    for element in ET.parse(path).getroot():
        if element.get("translatable") == "false":
            continue
        name = element.get("name")
        if element.tag == "string":
            entries.append(Entry(name, text=android_unescape("".join(element.itertext()))))
        elif element.tag == "plurals":
            forms = {
                item.get("quantity"): android_unescape("".join(item.itertext())) for item in element
            }
            if "other" not in forms:
                raise TranslationError(f"{path}: plurals {name} has no 'other' quantity")
            entries.append(Entry(name, plural=forms))
        else:
            raise TranslationError(f"{path}: <{element.tag}> {name} is not supported")
    return entries


# --- Placeholders -----------------------------------------------------------------------------


def android_args(text: str, where: str) -> list[tuple[int, str]]:
    """The `(position, type)` of each placeholder of an English Android text, in order."""
    matches = [match for match in ANDROID_FORMAT.finditer(text) if match.group(2)]
    if len({match.group(1) is None for match in matches}) > 1:
        raise TranslationError(f"{where}: numbered and unnumbered placeholders mixed")
    args = [
        (int(match.group(1)) if match.group(1) else index + 1, match.group(2))
        for index, match in enumerate(matches)
    ]
    stripped = ANDROID_FORMAT.sub("", text)
    if args and "%" in stripped:
        raise TranslationError(f"{where}: a lone '%' in a text with placeholders; write '%%'")
    return args


def shape(text: str, pattern: re.Pattern[str]) -> str:
    """A text with its placeholders blanked, to compare Android and desktop texts."""
    if pattern is ANDROID_FORMAT:
        return ANDROID_FORMAT.sub(lambda m: "{}" if m.group(2) else "%", text)
    return pattern.sub("{}", text)


def name_positions(desktop_english: str, android_english: str, where: str) -> dict[str, str]:
    """`{{name}}` of the desktop text → `%n$t` of the Android text, matched by their order."""
    names = DESKTOP_PLACEHOLDER.findall(desktop_english)
    args = android_args(android_english, where)
    if len(names) != len(args):
        raise TranslationError(f"{where}: the desktop and Android texts differ in placeholders")
    formats: dict[str, str] = {}
    for name, (position, kind) in zip(names, args):
        format_ = f"%{position}${kind}"
        if formats.setdefault(name, format_) != format_:
            raise TranslationError(f"{where}: {{{{{name}}}}} stands for two Android placeholders")
    return formats


def to_android(text: str, formats: dict[str, str], where: str, *, require_all: bool) -> str:
    """A desktop-format translation as an Android text with positional placeholders."""
    if DESKTOP_UNSUPPORTED.search(text):
        raise TranslationError(f"{where}: markup or nesting that Android cannot show: {text!r}")
    used = set(DESKTOP_PLACEHOLDER.findall(text))
    unknown = used - formats.keys()
    if unknown:
        raise TranslationError(f"{where}: unknown placeholder {sorted(unknown)} in {text!r}")
    missing = formats.keys() - used
    if require_all and missing:
        raise TranslationError(f"{where}: placeholder {sorted(missing)} missing in {text!r}")
    if formats:
        # In a text with placeholders a literal '%' must be doubled.
        parts = DESKTOP_PLACEHOLDER.split(text)
        return "".join(
            formats[part] if index % 2 else part.replace("%", "%%")
            for index, part in enumerate(parts)
        )
    return text


def mobile_formats(english: str, where: str) -> dict[str, str]:
    """`{{argN}}` → `%N$t` for a mobile-only text."""
    return {f"arg{position}": f"%{position}${kind}" for position, kind in android_args(english, where)}


def to_desktop(english: str, where: str) -> str:
    """A mobile-only English Android text in the desktop format."""
    positions = iter(android_args(english, where))
    return ANDROID_FORMAT.sub(lambda m: f"{{{{arg{next(positions)[0]}}}}}" if m.group(2) else "%", english)


# --- Desktop texts ----------------------------------------------------------------------------


def flatten(tree: dict, prefix: str = "") -> dict[str, str]:
    out = {}
    for key, value in tree.items():
        if isinstance(value, dict):
            out.update(flatten(value, f"{prefix}{key}."))
        elif isinstance(value, str):
            out[f"{prefix}{key}"] = value
    return out


def read_desktop(locales: Path) -> dict[str, dict[str, str]]:
    """Every desktop text by language, keyed `namespace:path.to.key`."""
    texts = {}
    for lang in LANGUAGES:
        texts[lang] = {}
        for file in sorted((locales / lang).glob("*.json")):
            for key, value in flatten(json.loads(file.read_text("utf-8"))).items():
                texts[lang][f"{file.stem}:{key}"] = value
    if not texts["en"]:
        raise TranslationError(f"no desktop texts in {locales}; run scripts/testenv.sh fetch")
    return texts


def desktop_plural(texts: dict[str, str], key: str) -> dict[str, str]:
    return {
        category: texts[f"{key}_{category}"]
        for category in CATEGORY_ORDER
        if f"{key}_{category}" in texts
    }


class Desktop:
    """Finds the desktop text of an English Android text."""

    def __init__(self, texts: dict[str, dict[str, str]]):
        self.texts = texts
        self.strings: dict[str, list[str]] = {}
        self.plurals: dict[str, list[str]] = {}
        for key, value in texts["en"].items():
            match = PLURAL_SUFFIX.match(key)
            if match:
                if match.group(2) == "other":
                    self.plurals.setdefault(shape(value, DESKTOP_PLACEHOLDER), []).append(match.group(1))
            else:
                self.strings.setdefault(shape(value, DESKTOP_PLACEHOLDER), []).append(key)

    def candidates(self, entry: Entry) -> list[str]:
        english = entry.text if entry.plural is None else entry.plural["other"]
        # Placeholders and punctuation alone ("{{current}} / {{total}}") need no translation.
        if not LETTER.search(ANDROID_FORMAT.sub("", english)):
            return []
        if entry.plural is None:
            return self.strings.get(shape(entry.text, ANDROID_FORMAT), [])
        return [
            key
            for key in self.plurals.get(shape(entry.plural["other"], ANDROID_FORMAT), [])
            if self.same_english(entry, key)
        ]

    def same_english(self, entry: Entry, key: str) -> bool:
        english = self.texts["en"]
        if entry.plural is None:
            return key in english and shape(english[key], DESKTOP_PLACEHOLDER) == shape(entry.text, ANDROID_FORMAT)
        forms = desktop_plural(english, key)
        return "other" in forms and all(
            shape(forms.get(quantity, forms["other"]), DESKTOP_PLACEHOLDER) == shape(text, ANDROID_FORMAT)
            for quantity, text in entry.plural.items()
        )

    def translations(self, key: str, plural: bool) -> list[tuple]:
        """What tells two candidate keys apart: their texts in every language."""
        if plural:
            return [tuple(sorted(desktop_plural(self.texts[lang], key).items())) for lang in LANGUAGES]
        return [self.texts[lang].get(key) for lang in LANGUAGES]


def choose_keys(entries: list[Entry], desktop: Desktop, overrides: dict[str, str | None]) -> dict[str, str]:
    """Android name → desktop key of every text taken from the desktop app."""
    names = {entry.name for entry in entries}
    stale = sorted(overrides.keys() - names)
    if stale:
        raise TranslationError(f"i18n/desktop-keys.json names texts that do not exist: {stale}")
    chosen = {}
    errors = []
    for entry in entries:
        if entry.name in overrides:
            key = overrides[entry.name]
            if key is None:
                continue
            if not desktop.same_english(entry, key):
                errors.append(f"{entry.name}: the English of desktop key {key} differs or it is gone")
                continue
            chosen[entry.name] = key
            continue
        candidates = desktop.candidates(entry)
        if not candidates:
            continue
        if len({tuple(desktop.translations(key, entry.plural is not None)) for key in candidates}) > 1:
            errors.append(
                f"{entry.name}: several desktop texts with this English are translated differently;"
                f" name one in i18n/desktop-keys.json: {sorted(candidates)}"
            )
            continue
        chosen[entry.name] = sorted(candidates)[0]
    if errors:
        raise TranslationError("\n".join(errors))
    return chosen


# --- Writing ----------------------------------------------------------------------------------


@dataclass
class Module:
    """An English text file of a module (`res/values/strings*.xml`); each language gets a
    file of the same name."""

    res: Path
    name: str
    entries: list[Entry]


def find_modules(android: Path) -> list[Module]:
    modules = []
    for path in sorted(android.glob("**/src/main/res/values/strings*.xml")):
        if "build" in path.relative_to(android).parts:
            continue
        modules.append(Module(path.parent.parent, path.name, read_strings(path)))
    names = [entry.name for module in modules for entry in module.entries]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise TranslationError(f"texts with the same name in two modules: {duplicates}")
    return modules


@dataclass
class Coverage:
    desktop: int = 0
    mobile: int = 0
    english: int = 0
    # Placeholders and punctuation only: the same in every language.
    neutral: int = 0

    def untranslated(self, english: str) -> None:
        if LETTER.search(ANDROID_FORMAT.sub("", english)):
            self.english += 1
        else:
            self.neutral += 1


def translate(
    entry: Entry,
    lang: str,
    desktop: Desktop,
    key: str | None,
    mobile: dict[str, str],
    coverage: Coverage,
) -> str | dict[str, str]:
    """The text, or the plural forms, of an entry in one language."""
    where = f"{lang}/{entry.name}"
    if entry.plural is None:
        if key is not None:
            formats = name_positions(desktop.texts["en"][key], entry.text, where)
            source = desktop.texts[lang].get(key)
            counter = "desktop"
        else:
            formats = mobile_formats(entry.text, where)
            source = mobile.get(entry.name)
            counter = "mobile"
        if not source:
            coverage.untranslated(entry.text)
            return entry.text
        setattr(coverage, counter, getattr(coverage, counter) + 1)
        return to_android(source, formats, where, require_all=True)

    english_other = entry.plural["other"]
    if key is not None:
        formats = name_positions(desktop_plural(desktop.texts["en"], key)["other"], english_other, where)
        forms = desktop_plural(desktop.texts[lang], key)
        counter = "desktop"
    else:
        formats = mobile_formats(english_other, where)
        forms = {
            category: mobile[f"{entry.name}_{category}"]
            for category in CATEGORY_ORDER
            if mobile.get(f"{entry.name}_{category}")
        }
        counter = "mobile"
    if "other" not in forms:
        coverage.untranslated(english_other)
        # Android picks a missing quantity's text from 'other'.
        return {
            category: entry.plural.get(category, english_other)
            for category in CATEGORY_ORDER
            if category in PLURAL_CATEGORIES[lang]
        }
    setattr(coverage, counter, getattr(coverage, counter) + 1)
    return {
        category: to_android(forms.get(category, forms["other"]), formats, f"{where}_{category}", require_all=False)
        for category in CATEGORY_ORDER
        if category in PLURAL_CATEGORIES[lang]
    }


def strings_xml(values: list[tuple[str, str | dict[str, str]]], source: str) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- SPDX-License-Identifier: AGPL-3.0-only -->",
        f"<!-- Written by scripts/translations.py from {source}. Do not edit by hand. -->",
        "<resources>",
    ]
    for name, value in values:
        if isinstance(value, str):
            lines.append(f'    <string name="{name}">{android_escape(value)}</string>')
        else:
            lines.append(f'    <plurals name="{name}">')
            for category, text in value.items():
                lines.append(f'        <item quantity="{category}">{android_escape(text)}</item>')
            lines.append("    </plurals>")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def locales_config() -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- SPDX-License-Identifier: AGPL-3.0-only -->",
        "<!-- Written by scripts/translations.py. Do not edit by hand. -->",
        '<locale-config xmlns:android="http://schemas.android.com/apk/res/android">',
        *(f'    <locale android:name="{lang}" />' for lang in LANGUAGES),
        "</locale-config>",
    ]
    return "\n".join(lines) + "\n"


def mobile_json(entries: list[Entry], keys: dict[str, str]) -> str:
    """The English mobile-only texts in the desktop format."""
    out: dict[str, str] = {}
    for entry in entries:
        if entry.name in keys:
            continue
        where = f"en/{entry.name}"
        if entry.plural is None:
            out[entry.name] = to_desktop(entry.text, where)
        else:
            for category in CATEGORY_ORDER:
                if category in entry.plural:
                    out[f"{entry.name}_{category}"] = to_desktop(entry.plural[category], where)
    return json.dumps(out, ensure_ascii=False, indent=2) + "\n"


def read_mobile(i18n: Path, lang: str, entries: list[Entry], keys: dict[str, str]) -> dict[str, str]:
    path = i18n / "mobile" / f"{lang}.json"
    if not path.exists():
        return {}
    texts = json.loads(path.read_text("utf-8"))
    known = set()
    for entry in entries:
        if entry.name in keys:
            continue
        if entry.plural is None:
            known.add(entry.name)
        else:
            known.update(f"{entry.name}_{category}" for category in CATEGORY_ORDER)
    stale = sorted(texts.keys() - known)
    if stale:
        raise TranslationError(f"{path} has texts that are not mobile-only texts of the app: {stale}")
    return texts


# --- iOS ----------------------------------------------------------------------------------------


def ios_unescape(value: str) -> str:
    out = []
    i = 0
    while i < len(value):
        if value[i] == "\\" and i + 1 < len(value):
            out.append({"n": "\n", "t": "\t"}.get(value[i + 1], value[i + 1]))
            i += 2
        else:
            out.append(value[i])
            i += 1
    return "".join(out)


def ios_escape(text: str) -> str:
    """The `.strings` value that makes iOS show `text`."""
    return text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")


def read_ios_strings(path: Path) -> list[tuple[str, str]]:
    """The keys and texts of a `.strings` file, in file order; comments and blank lines skipped."""
    texts = []
    in_comment = False
    for number, line in enumerate(path.read_text("utf-8").splitlines(), 1):
        line = line.strip()
        if in_comment or line.startswith("/*"):
            in_comment = "*/" not in line
            continue
        if not line or line.startswith("//"):
            continue
        match = IOS_STRINGS_LINE.match(line)
        if not match:
            raise TranslationError(f"{path}:{number}: not a line of the form \"key\" = \"text\";")
        texts.append((ios_unescape(match.group(1)), ios_unescape(match.group(2))))
    keys = [key for key, _ in texts]
    duplicates = sorted({key for key in keys if keys.count(key) > 1})
    if duplicates:
        raise TranslationError(f"{path}: keys written twice: {duplicates}")
    return texts


def ios_args(text: str, where: str) -> dict[int, str]:
    """Position → iOS conversion (`@`, `ld`, `d`) of each placeholder of an iOS text."""
    matches = [match for match in IOS_FORMAT.finditer(text) if match.group(2)]
    if len({match.group(1) is None for match in matches}) > 1:
        raise TranslationError(f"{where}: numbered and unnumbered placeholders mixed")
    args: dict[int, str] = {}
    for index, match in enumerate(matches):
        position = int(match.group(1)) if match.group(1) else index + 1
        if args.setdefault(position, match.group(2)) != match.group(2):
            raise TranslationError(f"{where}: placeholder {position} with two types")
    return args


def ios_to_android(text: str) -> str:
    """An iOS text with Android's placeholders, to match it with the Android and desktop texts."""
    return IOS_FORMAT.sub(
        lambda m: m.group(0) if not m.group(2) else f"%{m.group(1) + '$' if m.group(1) else ''}{'s' if m.group(2) == '@' else 'd'}",
        text,
    )


def numbered(text: str, pattern: re.Pattern[str], typed: bool) -> str:
    """A text whose placeholders are all numbered (`%1$s`), or only numbered (`%1$`) when the
    type does not matter, so that `%d` and `%1$ld` compare equal."""
    counter = iter(range(1, 1000))

    def number(match: re.Match[str]) -> str:
        if not match.group(2):
            return match.group(0)
        position = match.group(1) or str(next(counter))
        return f"%{position}${match.group(2) if typed else ''}"

    return pattern.sub(number, text)


def to_ios(text: str, specs: dict[int, str], where: str) -> str:
    """An Android text (as `translate` writes it) with the iOS placeholders of the English text."""

    def convert(match: re.Match[str]) -> str:
        if not match.group(2):
            return match.group(0)
        position = int(match.group(1))
        spec = specs.get(position)
        # A number may be passed as text, so that iOS writes it without a thousands separator.
        if spec is None or (match.group(2) == "s" and spec != "@"):
            raise TranslationError(f"{where}: placeholder %{position}${match.group(2)} does not fit the iOS text")
        return f"%{position}${spec}"

    return ANDROID_FORMAT.sub(convert, numbered(text, ANDROID_FORMAT, typed=True))


@dataclass
class IosEntry:
    """A text of the iOS English file; a plural gathers the `.one`, `.other` … keys of one name."""

    entry: Entry
    # The iOS conversion of each placeholder position, from the English text.
    specs: dict[int, str]


def ios_entries(texts: list[tuple[str, str]], path: Path) -> list[IosEntry]:
    entries: dict[str, IosEntry] = {}
    for key, text in texts:
        name, dot, quantity = key.partition(".")
        where = f"{path.name}/{key}"
        if dot and quantity not in CATEGORY_ORDER:
            raise TranslationError(f"{where}: '{quantity}' is not a plural category")
        specs = ios_args(text, where)
        known = entries.get(name)
        if known is None:
            known = entries[name] = IosEntry(Entry(name, plural={} if dot else None), {})
        elif (known.entry.plural is None) == bool(dot) or not dot:
            raise TranslationError(f"{where}: {name} is written as a text and as a plural")
        for position, spec in specs.items():
            if known.specs.setdefault(position, spec) != spec:
                raise TranslationError(f"{where}: placeholder {position} with two types")
        if dot:
            known.entry.plural[quantity] = ios_to_android(text)
        else:
            known.entry.text = ios_to_android(text)
    for ios in entries.values():
        if ios.entry.plural is not None and "other" not in ios.entry.plural:
            raise TranslationError(f"{path.name}/{ios.entry.name}: plural without '.other'")
    return list(entries.values())


def same_text(ios: Entry, android: Entry) -> bool:
    """Whether an iOS and an Android text say the same in English, placeholder types aside."""

    def plain(text: str) -> str:
        return numbered(text, ANDROID_FORMAT, typed=False)

    if ios.plural is None or android.plural is None:
        return ios.plural is None and android.plural is None and plain(ios.text) == plain(android.text)
    return ios.plural.keys() == android.plural.keys() and all(
        plain(text) == plain(android.plural[quantity]) for quantity, text in ios.plural.items()
    )


@dataclass
class IosSource:
    """Where an iOS text takes its translations from."""

    # The Android text of the same name, when it says the same in English.
    android: Entry | None = None
    # Otherwise the desktop text with the same English, if there is one.
    desktop_key: str | None = None
    # Why the text stays English in every language, where it does.
    english_because: str | None = None


def ios_sources(
    ios: list[IosEntry], android: dict[str, Entry], keys: dict[str, str], desktop: Desktop
) -> dict[str, IosSource]:
    sources = {}
    others = []
    for item in ios:
        match = android.get(item.entry.name)
        if match is not None and same_text(item.entry, match):
            sources[item.entry.name] = IosSource(android=match)
        else:
            others.append(item.entry)
            sources[item.entry.name] = IosSource(
                english_because="iOS only" if match is None else "worded differently than on Android"
            )
    # The texts Android does not have alike are matched with the desktop texts by their English.
    for name, key in choose_keys(others, desktop, {}).items():
        sources[name] = IosSource(desktop_key=key)
    return sources


@dataclass
class IosCoverage:
    translated: int = 0
    neutral: int = 0
    # Name → why it is English in this language.
    english: dict[str, str] = field(default_factory=dict)


def translate_ios(
    item: IosEntry,
    lang: str,
    source: IosSource,
    desktop: Desktop,
    keys: dict[str, str],
    mobile: dict[str, str],
    coverage: IosCoverage,
) -> str | dict[str, str]:
    """An iOS text, or its plural forms, in one language, with iOS placeholders."""
    counted = Coverage()
    if source.android is not None:
        android = source.android
        value = translate(android, lang, desktop, keys.get(android.name), mobile, counted)
    else:
        value = translate(item.entry, lang, desktop, source.desktop_key, {}, counted)
    if counted.neutral:
        coverage.neutral += 1
    elif counted.english:
        coverage.english[item.entry.name] = source.english_because or "no translation in the sources"
    else:
        coverage.translated += 1
    where = f"{lang}.lproj/{item.entry.name}"
    if isinstance(value, str):
        return to_ios(value, item.specs, where)
    return {category: to_ios(text, item.specs, f"{where}.{category}") for category, text in value.items()}


GENERATED_BY = "Written by scripts/translations.py from {source}. Do not edit by hand."


def ios_strings(values: list[tuple[str, str]], source: str) -> str:
    lines = [
        "/* SPDX-License-Identifier: AGPL-3.0-only */",
        f"/* {GENERATED_BY.format(source=source)} */",
        "",
    ]
    lines += [f'"{ios_escape(key)}" = "{ios_escape(text)}";' for key, text in values]
    return "\n".join(lines) + "\n"


def ios_stringsdict(plurals: list[tuple[str, dict[str, str], int]], source: str) -> str:
    """The plurals of a language. The number that picks the form is the argument after the
    text's own placeholders (`count`), as Android's quantity is apart from its arguments."""
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">',
        "<!-- SPDX-License-Identifier: AGPL-3.0-only -->",
        f"<!-- {GENERATED_BY.format(source=source)} -->",
        '<plist version="1.0">',
        "<dict>",
    ]
    for name, forms, count in plurals:
        lines += [
            f"    <key>{xml_escape(name)}</key>",
            "    <dict>",
            "        <key>NSStringLocalizedFormatKey</key>",
            f"        <string>%{count}$#@count@</string>",
            "        <key>count</key>",
            "        <dict>",
            "            <key>NSStringFormatSpecTypeKey</key>",
            "            <string>NSStringPluralRuleType</string>",
            "            <key>NSStringFormatValueTypeKey</key>",
            "            <string>ld</string>",
        ]
        for category, text in forms.items():
            lines += [f"            <key>{category}</key>", f"            <string>{xml_escape(text)}</string>"]
        lines += ["        </dict>", "    </dict>"]
    lines += ["</dict>", "</plist>"]
    return "\n".join(lines) + "\n"


def build_ios(
    repo: Path,
    desktop: Desktop,
    android: dict[str, Entry],
    keys: dict[str, str],
    mobiles: dict[str, dict[str, str]],
    ref: str,
) -> tuple[dict[Path, str], dict[str, IosCoverage]]:
    english_path = repo / IOS_ENGLISH
    resources = english_path.parent.parent
    items = ios_entries(read_ios_strings(english_path), english_path)
    sources = ios_sources(items, android, keys, desktop)
    files: dict[Path, str] = {}
    coverage: dict[str, IosCoverage] = {}

    def count_position(item: IosEntry) -> int:
        return max(item.specs, default=0) + 1

    english_plurals = [
        (item.entry.name, {
            category: to_ios(numbered(item.entry.plural[category], ANDROID_FORMAT, typed=True), item.specs, f"en/{item.entry.name}")
            for category in CATEGORY_ORDER
            if category in item.entry.plural
        }, count_position(item))
        for item in items
        if item.entry.plural is not None
    ]
    files[resources / "en.lproj" / "Localizable.stringsdict"] = ios_stringsdict(english_plurals, IOS_ENGLISH.as_posix())
    for lang in LANGUAGES[1:]:
        coverage[lang] = IosCoverage()
        source = f"Mumcord {ref[:12]} (ui/src/locales/{lang}), i18n/mobile/{lang}.json and {IOS_ENGLISH.as_posix()}"
        strings = []
        plurals = []
        for item in items:
            value = translate_ios(item, lang, sources[item.entry.name], desktop, keys, mobiles[lang], coverage[lang])
            if isinstance(value, str):
                strings.append((item.entry.name, value))
            else:
                plurals.append((item.entry.name, value, count_position(item)))
        files[resources / f"{lang}.lproj" / "Localizable.strings"] = ios_strings(strings, source)
        files[resources / f"{lang}.lproj" / "Localizable.stringsdict"] = ios_stringsdict(plurals, source)
    return files, coverage


def engine_ref(repo: Path) -> str:
    engine = tomllib.loads((repo / "engine.lock.toml").read_text("utf-8"))["engine"]
    return engine.get("rev") or engine["tag"]


def build(
    repo: Path, engine: Path
) -> tuple[dict[Path, str], dict[str, Coverage], dict[str, IosCoverage]]:
    """Every file this script writes, with its content, and the coverage per language of the
    Android and of the iOS texts."""
    ref = engine_ref(repo)
    stamp = engine / ".fetched-ref"
    if not stamp.exists() or ref not in stamp.read_text("utf-8").split():
        raise TranslationError(f"{engine} is not at the pinned commit {ref}; run scripts/testenv.sh fetch")
    desktop = Desktop(read_desktop(engine / "ui" / "src" / "locales"))
    i18n = repo / "i18n"
    overrides = json.loads((i18n / "desktop-keys.json").read_text("utf-8"))
    modules = find_modules(repo / "android")
    entries = [entry for module in modules for entry in module.entries]
    keys = choose_keys(entries, desktop, overrides)

    files: dict[Path, str] = {i18n / "mobile" / "en.json": mobile_json(entries, keys)}
    files[repo / "android/app/src/main/res/xml/locales_config.xml"] = locales_config()
    coverage = {}
    mobiles = {}
    for lang in LANGUAGES[1:]:
        mobile = mobiles[lang] = read_mobile(i18n, lang, entries, keys)
        coverage[lang] = Coverage()
        source = f"Mumcord {ref[:12]} (ui/src/locales/{lang}) and i18n/mobile/{lang}.json"
        for module in modules:
            values = [
                (entry.name, translate(entry, lang, desktop, keys.get(entry.name), mobile, coverage[lang]))
                for entry in module.entries
            ]
            files[module.res / f"values-{lang}" / module.name] = strings_xml(values, source)
    ios_coverage: dict[str, IosCoverage] = {}
    if (repo / IOS_ENGLISH).exists():
        android = {entry.name: entry for entry in entries}
        ios_files, ios_coverage = build_ios(repo, desktop, android, keys, mobiles, ref)
        files.update(ios_files)
    return files, coverage, ios_coverage


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("command", choices=["generate", "check"])
    parser.add_argument("--engine", type=Path, default=REPO / ".engine", help="Mumcord checkout")
    parser.add_argument("--verbose", action="store_true", help="list the iOS texts that stay English")
    args = parser.parse_args()
    try:
        files, coverage, ios_coverage = build(REPO, args.engine)
    except TranslationError as error:
        print(f"translations: {error}", file=sys.stderr)
        return 1
    outdated = [path for path, content in files.items() if not path.exists() or path.read_text("utf-8") != content]
    if args.command == "check":
        for path in outdated:
            print(f"translations: out of date: {path.relative_to(REPO)}", file=sys.stderr)
        if outdated:
            print("translations: run python3 scripts/translations.py generate", file=sys.stderr)
            return 1
    else:
        for path in outdated:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(files[path], "utf-8")
    total = next(iter(coverage.values()))
    count = total.desktop + total.mobile + total.english + total.neutral
    print(
        f"translations: {count} texts per language, {total.neutral} without words;"
        " translated from the desktop + mobile-only, still English:"
    )
    for lang, numbers in coverage.items():
        print(f"  {lang}: {numbers.desktop} + {numbers.mobile}, {numbers.english} English")
    if ios_coverage:
        first = next(iter(ios_coverage.values()))
        count = first.translated + first.neutral + len(first.english)
        print(f"translations: iOS, {count} texts per language, {first.neutral} without words; translated, still English:")
        for lang, numbers in ios_coverage.items():
            print(f"  {lang}: {numbers.translated}, {len(numbers.english)} English")
        if args.verbose:
            for lang, numbers in ios_coverage.items():
                for name, why in numbers.english.items():
                    print(f"  {lang} {name}: {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

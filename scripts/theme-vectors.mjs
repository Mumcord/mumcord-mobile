// SPDX-License-Identifier: AGPL-3.0-only

// Writes the test vectors of the bridge's theme logic (bridge/src/themes/vectors.json) from the
// desktop app's theme code, so the phone makes the same colours and theme codes as the desktop.
//
//   node scripts/theme-vectors.mjs <Mumcord checkout> > bridge/src/themes/vectors.json
//
// The checkout is the engine commit pinned in engine.lock.toml (e.g. `.engine/` after
// `scripts/testenv.sh fetch`, or any clone checked out at that commit). Needs Node 22.18 or
// newer, which runs the TypeScript sources directly.

import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const checkout = process.argv[2];
if (!checkout) {
  console.error("usage: node scripts/theme-vectors.mjs <Mumcord checkout>");
  process.exit(2);
}

// The desktop sources import without file extensions, which Node does not resolve.
const SOURCES = ["contrast", "derive", "themeModel", "versions", "shareCodec"];
const work = mkdtempSync(join(tmpdir(), "theme-vectors-"));
try {
  for (const name of SOURCES) {
    const source = readFileSync(join(checkout, "ui/src/theme", `${name}.ts`), "utf8");
    writeFileSync(join(work, `${name}.ts`), source.replace(/from "\.\/(\w+)"/g, 'from "./$1.ts"'));
  }
  copyFileSync(join(checkout, "ui/src/theme/tokens.css"), join(work, "tokens.css"));
  const load = (name) => import(pathToFileURL(join(work, `${name}.ts`)).href);
  const contrast = await load("contrast");
  const derive = await load("derive");
  const model = await load("themeModel");
  const versions = await load("versions");
  const codec = await load("shareCodec");
  // The engine's version, which the bridge writes into the codes it makes.
  const manifest = readFileSync(join(checkout, "Cargo.toml"), "utf8");
  const mumcordVersion = /^version = "([^"]+)"/m.exec(manifest)?.[1];
  if (!mumcordVersion) throw new Error("no version in the checkout's Cargo.toml");
  const all = vectors(contrast, derive, model, versions, codec, mumcordVersion);
  process.stdout.write(`${JSON.stringify(all)}\n`);
} finally {
  rmSync(work, { recursive: true, force: true });
}

function vectors(contrast, derive, model, versions, codec, mumcordVersion) {
  // A fixed pseudo-random sequence (mulberry32), so the vectors are the same on every run.
  let seed = 0x6d756d63;
  const random = () => {
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const hex = () =>
    `#${Array.from({ length: 3 }, () =>
      Math.floor(random() * 256)
        .toString(16)
        .padStart(2, "0"),
    ).join("")}`;
  const shade = (dark) => {
    const pick = () => (dark ? Math.floor(random() * 70) : 185 + Math.floor(random() * 71));
    return `#${[pick(), pick(), pick()].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
  };
  const randomSet = () => {
    const dark = random() < 0.5;
    return {
      background: shade(dark),
      surface: shade(dark),
      text: random() < 0.8 ? shade(!dark) : hex(),
      accent: hex(),
      talking: hex(),
      warning: hex(),
      error: hex(),
    };
  };

  const { MUMCORD_PRESET, PRESET_THEMES } = model;
  const D = MUMCORD_PRESET.dark;
  const L = MUMCORD_PRESET.light;
  const sets = [
    ...PRESET_THEMES.flatMap((preset) => [preset.dark, preset.light]),
    // The colour sets of the desktop's derive and versions tests.
    { ...D, accent: "#2a2060" },
    { ...L, warning: "#fde68a", error: "#fecaca", talking: "#bbf7d0" },
    { ...D, text: "#3a3070" },
    { ...L, surface: "#3d3566" },
    { ...L, background: "#b8b0d0", surface: "#c4bdd9", text: "#000000" },
    { ...D, accent: "#ff3030" },
    {
      background: "#000000",
      surface: "#000000",
      text: "#ffffff",
      accent: "#000080",
      talking: "#003300",
      warning: "#330000",
      error: "#110000",
    },
    {
      background: "#808080",
      surface: "#808080",
      text: "#808080",
      accent: "#808080",
      talking: "#808080",
      warning: "#808080",
      error: "#808080",
    },
    ...Array.from({ length: 40 }, randomSet),
  ];

  const colorSets = sets.map((colors) => {
    const validation = derive.validateTheme(colors);
    return {
      colors,
      base: derive.themeBase(colors),
      tokens: derive.deriveTokens(colors),
      ok: validation.ok,
      issues: validation.issues.map((issue) => issue.key),
      tokenFailures: derive.checkTokens(derive.deriveTokens(colors)).length,
      variantDark: derive.generateVariant(colors, "dark"),
      variantLight: derive.generateVariant(colors, "light"),
      versionDark: versions.generateVersion(colors, "dark"),
      versionLight: versions.generateVersion(colors, "light"),
    };
  });

  const luminance = [...new Set(sets.flatMap((set) => Object.values(set)))]
    .slice(0, 100)
    .map((color) => ({ color, luminance: contrast.relativeLuminance(color) }));

  const partialSets = [
    {},
    { accent: "#9bd46a" },
    { background: "#fafafa" },
    { text: "#000000" },
    { background: "#123456", text: "#fedcba" },
    ...sets.slice(0, 20),
  ];
  const singleSets = partialSets.map((colors) => ({ colors, ...versions.fromSingleSet(colors) }));

  const names = [
    "Deep Ocean",
    "Nächtliche Ozeane 🌊",
    '  "Quoted" \\ back\\slash </script>  ',
    "  Evil‮\u0000Name  ",
    "🎨".repeat(100),
    "👩‍👩‍👧‍👦 family".repeat(10),
    "é́ combining",
    "   spaced 　",
    "​zero‍width﻿",
    "日本語のテーマ",
    "🇩🇪 flags 🇺🇸",
    "",
    " ‮ ",
    "x".repeat(200),
  ];
  const cleanNames = names.map((name) => ({ name, clean: codec.cleanThemeName(name) }));

  const encodeCases = [
    ...PRESET_THEMES.map((preset) => ({ name: preset.name, dark: preset.dark, light: preset.light })),
    ...names.map((name, index) => ({
      name,
      dark: sets[(index * 2) % 8],
      light: sets[(index * 2 + 1) % 8],
    })),
  ];
  const encoded = encodeCases.flatMap((theme) =>
    ["0.9.0", "1.12.3-beta.1"].map((version) => ({
      theme,
      version,
      code: codec.encodeThemeCode(theme, version),
      message: codec.formatShareMessage(theme, version),
    })),
  );

  const rawCode = (payload) =>
    `mct1.${Buffer.from(JSON.stringify(payload), "utf8").toString("base64url")}`;
  const ocean = PRESET_THEMES[1];
  const codes = [
    ...encoded.map((entry) => entry.code),
    rawCode({ v: "0.9.0", n: "Future", dark: { ...ocean.dark, glow: "#ff00ff" }, light: ocean.light, x: 1 }),
    rawCode({ v: "0.3.0", n: "Old", colors: ocean.dark }),
    rawCode({ v: "0.3.0", n: "Old", c: ocean.light }),
    rawCode({ v: "0.4.0", n: "Half", light: ocean.light }),
    rawCode({ v: "0.4.0", n: "Half", dark: ocean.dark, light: {} }),
    rawCode({ v: "0.3.0", n: "Old", c: { accent: "#9bd46a" } }),
    rawCode({ v: "0.3.0", n: "Old", c: { background: "#fafafa" } }),
    rawCode({ v: "0.3.0", n: "Both", c: { accent: "#9bd46a" }, colors: { accent: "#ff0000" } }),
    rawCode({ v: "0.3.0", n: "NullC", c: null, colors: { accent: "#ff0000" } }),
    rawCode({ v: "0.3.0", n: "NullColors", colors: null }),
    rawCode({ v: "0.4.0", n: "Caps", c: { accent: "#A78BFF" } }),
    rawCode({ v: "0.4.0", n: "Murky", c: { ...D, text: "#2a2060" } }),
    rawCode({ v: "0.4.0", n: "Murky", dark: D, light: { ...L, text: L.background } }),
    rawCode({ v: "0.4.0", n: "A" }),
    rawCode({ v: "0.4.0", n: "A", c: { accent: "url(x)" } }),
    rawCode({ v: "0.4.0", n: "A", c: { accent: "red" } }),
    rawCode({ v: "0.4.0", n: "A", c: { accent: 255 } }),
    rawCode({ v: "0.4.0", n: "A", c: { accent: "#abc" } }),
    rawCode({ v: "0.4.0", n: "A", c: { accent: " #aabbcc" } }),
    rawCode({ v: "0.4.0", n: "A", c: [] }),
    rawCode({ v: "0.4.0", n: "A", dark: "#000000" }),
    rawCode({ v: "0.4.0", n: "A", dark: null, light: L }),
    rawCode({ n: "A", c: {} }),
    rawCode({ v: "<b>1</b>", n: "A", c: {} }),
    rawCode({ v: "1.2", n: "A", c: { accent: "#9bd46a" } }),
    rawCode({ v: 1, n: "A", c: { accent: "#9bd46a" } }),
    rawCode({ v: `1.2.3-${"x".repeat(35)}`, n: "A", c: { accent: "#9bd46a" } }),
    rawCode({ v: `1.2.3-${"x".repeat(34)}`, n: "A", c: { accent: "#9bd46a" } }),
    rawCode({ v: "0.4.0", n: " ‮ ", c: {} }),
    rawCode({ v: "0.4.0", n: 5, c: { accent: "#9bd46a" } }),
    rawCode({ v: "0.4.0", n: "Unknown only", c: { glow: "#ffffff" } }),
    `mct1.${Buffer.from('{"v":"0.4.0","n":"Proto","__proto__":{"x":1},"c":{"accent":"#9bd46a"}}').toString("base64url")}`,
    rawCode([1, 2]),
    rawCode("a string"),
    rawCode(null),
    `${rawCode({ v: "0.4.0", n: "Dup", c: { accent: "#ff0000" } }).slice(0, -2)}`,
    `mct1.${Buffer.from('{"v":"0.4.0","n":"Dup","n":"Second","c":{"accent":"#9bd46a"}}').toString("base64url")}`,
    `mct1.${Buffer.from(' {"v":"0.4.0","n":"Space","c":{"accent":"#9bd46a"}} \n').toString("base64url")}`,
    `mct1.${Buffer.from('{"v":"0.4.0","n":"Esc \\u00e9\\ud83c\\udf0a","c":{"accent":"#9bd46a"}}').toString("base64url")}`,
    `mct1.${Buffer.from('\ufeff{"v":"0.4.0","n":"Bom","c":{"accent":"#9bd46a"}}').toString("base64url")}`,
    `mct1.${Buffer.from('\ufeff\ufeff{"v":"0.4.0","n":"Bom","c":{"accent":"#9bd46a"}}').toString("base64url")}`,
    "mct2.e30",
    "mct1.",
    "mct1.e30=+/",
    `mct1.${Buffer.from("hello").toString("base64url")}`,
    `mct1.${Buffer.from([0xff, 0xfe]).toString("base64url")}`,
    `mct1.${"A".repeat(2048)}`,
    `mct1.${"A".repeat(2043)}`,
    "mct1.e30",
    "mct1.e",
    "mct1.e3",
    "mct1.e31",
    "MCT1.e30",
    " mct1.e30",
  ];
  // Truncations (every residue of the base64 length) and single-character changes of one valid
  // code: base64, UTF-8 and JSON must fail (or pass) the same way.
  const valid = encoded[2].code;
  for (let length = 5; length < valid.length; length += 5) codes.push(valid.slice(0, length));
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  for (let index = 0; index < 60; index += 1) {
    const position = 5 + Math.floor(random() * (valid.length - 5));
    const char = alphabet[Math.floor(random() * alphabet.length)];
    codes.push(valid.slice(0, position) + char + valid.slice(position + 1));
  }
  const decoded = codes.map((code) => ({ code, result: codec.decodeThemeCode(code, "0.4.0") }));

  const texts = [
    `look: ${encoded[0].message} nice`,
    "no theme here, mct1. nothing",
    "two: mct1.abc and mct1.def",
    `x${"mct1."}${"B".repeat(3000)}`,
    "MCT1.abc mct1.ok",
  ];
  const found = texts.map((text) => ({ text, code: codec.findThemeCode(text) }));

  const versionPairs = [
    ["0.10.0", "0.9.9"],
    ["1.0.0", "1.0.0"],
    ["0.3.1", "0.4.0"],
    ["0.5.0-beta.1", "0.4.0"],
    ["garbage", "0.4.0"],
    ["0.4.0", "garbage"],
    ["1.0.0+build.5", "0.9.0"],
    ["01.2.3", "1.2.2"],
    ["99999999999999999999.0.0", "1.0.0"],
  ];
  const newer = versionPairs.map(([version, current]) => ({
    version,
    current,
    newer: codec.isNewerVersion(version, current),
  }));

  // A custom theme as a desktop user shares it: the first random dark set that passes, with its
  // generated light version. The phone imports it and must draw the same shades; shared again
  // by the phone (which writes the engine's version), it must be the same code.
  const dark = sets
    .slice(16)
    .find((set) => derive.themeBase(set) === "dark" && derive.validateTheme(set).ok);
  const light = versions.generateVersion(dark, "light");
  const shared = { name: "From the desktop", dark, light };
  const desktopTheme = {
    ...shared,
    version: mumcordVersion,
    code: codec.encodeThemeCode(shared, mumcordVersion),
    message: codec.formatShareMessage(shared, mumcordVersion),
    tokensDark: derive.deriveTokens(dark),
    tokensLight: derive.deriveTokens(light),
  };

  return {
    colorSets,
    luminance,
    singleSets,
    cleanNames,
    encoded,
    decoded,
    found,
    newer,
    desktopTheme,
  };
}

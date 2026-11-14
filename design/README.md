# Design system source

The look of Mumcord Mobile is defined once, here, and used by both apps. The binding mockups
(`mock.css` of the mockup kit) and the desktop app (`ui/src/theme/tokens.css` in the Mumcord
repository) are where the values come from; the desktop app stays the source of truth for
colours, type scale, spacing and radii.

| File | Content |
| --- | --- |
| `tokens.json` | Colours for dark and light, opacities, shadows, fonts, text styles, spacing, radii, sizes |
| `icons.json` | The icon set: per icon id a list of SVG path data strings |
| `fonts/` | Instrument Sans (body) and Bricolage Grotesque (titles) as variable fonts, each with its licence (`OFL.txt`) |

## Format of `tokens.json`

- `color.dark` / `color.light`: the same keys in both; values are `#rrggbb` or `#rrggbbaa`.
  Keys are the CSS custom property names without `--color-`.
- `opacity.dark` / `opacity.light`: factors from 0 to 1 (film grain, tints).
- `shadow.dark` / `shadow.light`: CSS box shadows as `y`, `blur`, `spread` (points) and `color`.
- `font`: the font file of the body and the display family and the axis values to fix. The
  mockups use Bricolage Grotesque at optical size 14.
- `text`: named text styles. `size` is in scalable points (sp on Android, Dynamic Type points on
  iOS) so that text follows the system font size; `line-height` is a multiple of the size and
  `letter-spacing` is in em.
- `space`, `radius`, `size`: lengths in density-independent points (dp on Android, pt on iOS).

## Format of `icons.json`

Every icon is drawn on a 24 x 24 grid as unfilled paths with stroke width 2 and round caps and
joins (Lucide/Feather geometry, the same family as the desktop UI). Circles and rectangles of
the original SVG are already converted to paths.

## Android

`android/design` generates Kotlin from `tokens.json` and `icons.json` at build time
(`generateDesignTokens`) and packages the fonts (`copyDesignFonts`). Nothing generated is
checked in. `TokenSourceTest` reads the files independently and compares them with the theme,
and `ContrastTest` checks the text and background pairs for WCAG AA.

Screens use `MumcordTheme.colors`, `MumcordTheme.typography`, `MumcordSpace`, `MumcordRadius`,
`MumcordIcons` and the components in `app.mumcord.mobile.design.components`; they contain no
raw colour values.

Debug builds contain a component gallery and the channels reference screen (mockup
`02-channels` with static demo data):

```sh
adb shell am start -n app.mumcord.mobile/.debug.DesignGalleryActivity
adb shell am start -n app.mumcord.mobile/.debug.DesignGalleryActivity --es screen channels --es theme light
```

Screenshots of both are drawn on the computer, without an emulator, at the size of the mockups
(390 x 844 dp):

```sh
cd android
./gradlew :app:testDebugUnitTest     # part of `test`: draws them into app/build/outputs/screenshots
./gradlew :app:recordScreenshots     # writes docs/images/screenshots/android-*.png
./gradlew :app:verifyScreenshots     # compares with the files there (2 % of the pixels may differ)
```

The comparison is its own task and not part of `test`, because text is drawn slightly
differently on other machines.

## iOS

`ios/Tools/generate_design.py` generates Swift from `tokens.json` and `icons.json` and copies the
fonts into `ios/Mumcord/Design/Generated/` (git-ignored, nothing generated is checked in). It
runs in `scripts/build-ios.sh` before the Xcode project is generated and again as a build phase
of the app before every compile. `DesignTokenTests` reads the JSON files independently and
compares them with the generated values, `DesignContrastTests` checks the text and background
pairs for WCAG AA, and `DesignIconTests` checks that the icon set is complete.

Screens put the theme into the environment with `.mumcordTheme()` (system, dark or light) and
read `@Environment(\.mumcordTheme)` for `colors`, `opacities` and `shadows`; text uses the
`TextToken` styles through `LineText` and `ParagraphText`, which follow Dynamic Type; lengths
come from `MumcordSpace`, `MumcordRadius` and `MumcordSize`, icons from `MumcordIcons`. The
components in `ios/Mumcord/Design/Components` have the same names as on Android. Screens contain
no raw colour values.

Debug builds contain the component gallery and the channels reference screen; a debug build
started with launch arguments opens them (nothing in the app leads there, and release builds do
not contain them):

```sh
xcrun simctl launch booted app.mumcord.mobile -mumcordDesignScreen gallery
xcrun simctl launch booted app.mumcord.mobile -mumcordDesignScreen channels -mumcordDesignTheme light
```

`DesignReferenceUITests` opens the reference screen in dark and light in the simulator and
attaches a screenshot of each; CI publishes them as the artifact `ios-screenshots`.

## Changing a value

Change it in the desktop app first if it exists there, then here. Both themes must define the
same keys; the Android build fails otherwise.

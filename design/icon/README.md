# App icon masters

The app icon of Mumcord Mobile is the icon of the Mumcord desktop app: two people with a cap and a
beanie forming an "M" on a dark purple tile. It is not redesigned here.

## Source

Copied unmodified from the Mumcord repository (`Mumcord/mumcord`), folder `assets/brand/`,
commit `2143310b24bc798a4f266d09b8ddaee376860128`:

| File | Used for |
| --- | --- |
| `mumcord-icon.svg` | The full icon on its tile: iOS app icon (as a full square) and the Android background gradient |
| `mumcord-logo.svg` | The mark without the tile: Android foreground layer |
| `mumcord-tray.svg` | One-colour silhouette: Android themed icon layer and notification glyph |

All three are vector masters. To update them, copy the new files from that folder, change the commit
above and run the script.

## Generating the platform assets

`scripts/generate-app-icons.sh` (needs `rsvg-convert`, ImageMagick 7 and `python3`) writes every
asset; nothing is edited by hand. Android: foreground and monochrome layers (432 px, mark inside
the 66 dp safe circle, which the script verifies), the background gradient, and the notification
glyph (PNG per density) of the call and message notifications. iOS: the single 1024 x 1024 universal icon without
alpha channel and without rounded corners. There are no dark or tinted iOS variants: the tile is
already dark, and a clean tinted variant would need a separate artwork.

## Licence

The Mumcord repository is licensed under AGPL-3.0-only (see its `LICENSE`); this repository uses the
same licence. The artwork has no separate licence or trademark notice there.

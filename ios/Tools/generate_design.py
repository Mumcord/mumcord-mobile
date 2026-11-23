#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Generates the Swift design tokens and icons of the iOS app from the shared design source.

Reads `design/tokens.json` and `design/icons.json` and writes into
`ios/Mumcord/Design/Generated/` (git-ignored, never edited by hand):

  MumcordTokens.swift   colours, opacities, shadows, text styles, spacing, radii, sizes
  MumcordIcons.swift    every icon as drawing commands on its 24 x 24 grid
  Fonts/*.ttf           the two font files under names without brackets

`scripts/build-ios.sh` runs it before the Xcode project is generated, and the app target runs
it again before every compile, so a change of the JSON files is picked up from Xcode as well.
Files are only rewritten when their content changes, so an unchanged source costs no rebuild.

Only the Python standard library is used: the script has to run with the Python that ships
with Xcode.
"""

import json
import math
import re
import sys
from pathlib import Path

IOS_DIR = Path(__file__).resolve().parent.parent
DESIGN_DIR = IOS_DIR.parent / "design"
OUTPUT_DIR = IOS_DIR / "Mumcord" / "Design" / "Generated"

HEADER = "// Generated from design/{source} by ios/Tools/generate_design.py. Do not edit.\n\n"


def camel(key: str, prefix: str = "") -> str:
    """`bg-servers` becomes `bgServers`; a prefix keeps names such as `1` valid (`s1`)."""
    parts = (prefix + key).split("-")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


def number(value) -> str:
    """A JSON number as a Swift literal without a trailing `.0`."""
    as_float = float(value)
    return str(int(as_float)) if as_float.is_integer() else repr(as_float)


def color(value: str) -> str:
    """`#rrggbb` or `#rrggbbaa` as `Color(rgba: 0xRRGGBBAA)`."""
    if not re.fullmatch(r"#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?", value):
        raise ValueError(f"not a colour: {value}")
    digits = value[1:].lower()
    if len(digits) == 6:
        digits += "ff"
    return f"Color(rgba: 0x{digits})"


def themed(root: dict, section: str) -> tuple[dict, dict]:
    """The dark and the light values of a section; both themes must define the same keys."""
    dark = {k: v for k, v in root[section]["dark"].items() if k != "comment"}
    light = {k: v for k, v in root[section]["light"].items() if k != "comment"}
    if dark.keys() != light.keys():
        raise ValueError(f"dark and light must define the same keys in '{section}'")
    return dark, light


def plain(root: dict, section: str) -> dict:
    return {k: v for k, v in root[section].items() if k != "comment"}


def themed_struct(name: str, swift_type: str, dark: dict, light: dict, literal) -> str:
    """A struct with one property per key, its dark and light value and a lookup by JSON key."""
    out = [f"struct {name} {{"]
    out += [f"    let {camel(key)}: {swift_type}" for key in dark]
    out.append("")
    for theme, values in (("dark", dark), ("light", light)):
        out.append(f"    static let {theme} = {name}(")
        out.append(",\n".join(f"        {camel(key)}: {literal(value)}" for key, value in values.items()))
        out.append("    )")
        out.append("")
    out.append("    /// Every value by its key in design/tokens.json.")
    out.append(f"    var byKey: [String: {swift_type}] {{")
    out.append("        [")
    out += [f'            "{key}": {camel(key)},' for key in dark]
    out.append("        ]")
    out.append("    }")
    out.append("}")
    return "\n".join(out) + "\n"


def shadow(value: dict) -> str:
    return (
        f"ShadowToken(y: {number(value['y'])}, blur: {number(value['blur'])}, "
        f"spread: {number(value['spread'])}, color: {color(value['color'])})"
    )


def font_file_name(font: dict) -> str:
    """`instrument-sans/InstrumentSans[wdth,wght].ttf` becomes `InstrumentSans`."""
    return Path(font["file"]).name.split("[")[0].removesuffix(".ttf")


def tokens_swift(root: dict) -> str:
    out = [HEADER.format(source="tokens.json") + "import SwiftUI\n"]

    out.append(themed_struct("MumcordColors", "Color", *themed(root, "color"), color))
    out.append(themed_struct("MumcordOpacities", "Double", *themed(root, "opacity"), number))
    out.append(themed_struct("MumcordShadows", "ShadowToken", *themed(root, "shadow"), shadow))

    text = plain(root, "text")
    lines = ["extension TextToken {"]
    for key, style in text.items():
        lines.append(
            f"    static let {camel(key)} = TextToken(display: {str(style['font'] == 'display').lower()}, "
            f"size: {number(style['size'])}, weight: {number(style['weight'])}, "
            f"lineHeight: {number(style['line-height'])}, letterSpacing: {number(style['letter-spacing'])})"
        )
    lines.append("")
    lines.append("    /// Every text style by its key in design/tokens.json.")
    lines.append("    static let byKey: [String: TextToken] = [")
    lines += [f'        "{key}": .{camel(key)},' for key in text]
    lines.append("    ]")
    lines.append("}")
    out.append("\n".join(lines) + "\n")

    fonts = plain(root, "font")
    lines = ["extension FontToken {"]
    for key, font in fonts.items():
        axes = ", ".join(f'"{axis}": {number(value)}' for axis, value in font["axes"].items())
        lines.append(
            f'    static let {camel(key)} = FontToken(family: "{font["family"]}", '
            f'file: "{font_file_name(font)}", axes: [{axes}])'
        )
    lines.append("}")
    out.append("\n".join(lines) + "\n")

    for name, section, prefix in (
        ("MumcordSpace", "space", "s"),
        ("MumcordRadius", "radius", ""),
        ("MumcordSize", "size", ""),
    ):
        values = plain(root, section)
        lines = [f"enum {name} {{"]
        lines += [f"    static let {camel(key, prefix)}: CGFloat = {number(value)}" for key, value in values.items()]
        lines.append("")
        lines.append("    /// Every value by its key in design/tokens.json.")
        lines.append("    static let byKey: [String: CGFloat] = [")
        lines += [f'        "{key}": {camel(key, prefix)},' for key in values]
        lines.append("    ]")
        lines.append("}")
        out.append("\n".join(lines) + "\n")

    return "\n".join(out)


# --- SVG path data to drawing commands -------------------------------------------------------

PATH_TOKEN = re.compile(r"[A-Za-z]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
ARGUMENT_COUNT = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "A": 7, "Z": 0}


def arc_to_curves(x1, y1, rx, ry, rotation, large_arc, sweep, x2, y2):
    """An SVG elliptical arc as cubic Bézier curves (SVG 1.1, implementation notes F.6)."""
    if rx == 0 or ry == 0:
        return [("L", x2, y2)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(rotation)
    cos_phi, sin_phi = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p = cos_phi * dx + sin_phi * dy
    y1p = -sin_phi * dx + cos_phi * dy
    # Radii too small to reach the end point are scaled up, as the specification says.
    scale = x1p**2 / rx**2 + y1p**2 / ry**2
    if scale > 1:
        rx *= math.sqrt(scale)
        ry *= math.sqrt(scale)
    numerator = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    denominator = rx**2 * y1p**2 + ry**2 * x1p**2
    factor = math.sqrt(max(numerator / denominator, 0)) if denominator else 0
    if large_arc == sweep:
        factor = -factor
    cxp = factor * rx * y1p / ry
    cyp = -factor * ry * x1p / rx
    cx = cos_phi * cxp - sin_phi * cyp + (x1 + x2) / 2
    cy = sin_phi * cxp + cos_phi * cyp + (y1 + y2) / 2
    start = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    end = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
    delta = end - start
    if sweep and delta < 0:
        delta += 2 * math.pi
    elif not sweep and delta > 0:
        delta -= 2 * math.pi

    segments = max(1, math.ceil(abs(delta) / (math.pi / 2) - 1e-9))
    step = delta / segments
    handle = 4 / 3 * math.tan(step / 4)

    def point(angle, d_angle=None):
        """A point of the ellipse, or with `d_angle` its derivative there, in user space."""
        ex, ey = (rx * math.cos(angle), ry * math.sin(angle)) if d_angle is None else (
            -rx * math.sin(angle), ry * math.cos(angle))
        x = cos_phi * ex - sin_phi * ey
        y = sin_phi * ex + cos_phi * ey
        return (x + cx, y + cy) if d_angle is None else (x, y)

    curves = []
    for index in range(segments):
        a0 = start + index * step
        a1 = a0 + step
        p0, p3 = point(a0), point(a1)
        d0, d3 = point(a0, True), point(a1, True)
        curves.append((
            "C",
            p0[0] + handle * d0[0], p0[1] + handle * d0[1],
            p3[0] - handle * d3[0], p3[1] - handle * d3[1],
            p3[0], p3[1],
        ))
    # End exactly where the path data says, without rounding drift.
    last = curves[-1]
    curves[-1] = last[:5] + (x2, y2)
    return curves


def path_commands(data: str) -> list[tuple]:
    """SVG path data as absolute `M`, `L`, `C` and `Z` commands."""
    tokens = PATH_TOKEN.findall(data)
    commands = []
    x = y = start_x = start_y = 0.0
    last_control = None
    index = 0
    command = None
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
        elif command is None:
            raise ValueError(f"path data does not start with a command: {data}")
        upper = command.upper()
        if upper not in ARGUMENT_COUNT:
            raise ValueError(f"unsupported path command '{command}' in: {data}")
        count = ARGUMENT_COUNT[upper]
        args = [float(token) for token in tokens[index:index + count]]
        if len(args) != count:
            raise ValueError(f"too few numbers for '{command}' in: {data}")
        index += count
        relative = command.islower()
        control = None

        if upper == "M":
            x, y = (x + args[0], y + args[1]) if relative else (args[0], args[1])
            start_x, start_y = x, y
            commands.append(("M", x, y))
            # Further coordinate pairs after a move are lines.
            command = "l" if relative else "L"
        elif upper == "L":
            x, y = (x + args[0], y + args[1]) if relative else (args[0], args[1])
            commands.append(("L", x, y))
        elif upper == "H":
            x = x + args[0] if relative else args[0]
            commands.append(("L", x, y))
        elif upper == "V":
            y = y + args[0] if relative else args[0]
            commands.append(("L", x, y))
        elif upper in ("C", "S"):
            if relative:
                args = [value + (x if position % 2 == 0 else y) for position, value in enumerate(args)]
            if upper == "S":
                # The first control point mirrors the last one of the previous curve.
                mirrored = (2 * x - last_control[0], 2 * y - last_control[1]) if last_control else (x, y)
                args = [*mirrored, *args]
            commands.append(("C", *args))
            control = (args[2], args[3])
            x, y = args[4], args[5]
        elif upper == "A":
            end_x, end_y = (x + args[5], y + args[6]) if relative else (args[5], args[6])
            commands += arc_to_curves(x, y, args[0], args[1], args[2], args[3] != 0, args[4] != 0, end_x, end_y)
            x, y = end_x, end_y
        elif upper == "Z":
            commands.append(("Z",))
            x, y = start_x, start_y
        last_control = control
    return commands


def coordinate(value: float) -> str:
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def swift_path(commands: list[tuple]) -> list[str]:
    lines = []
    for command in commands:
        kind, args = command[0], [coordinate(value) for value in command[1:]]
        if kind == "M":
            lines.append(f"path.move(to: CGPoint(x: {args[0]}, y: {args[1]}))")
        elif kind == "L":
            lines.append(f"path.addLine(to: CGPoint(x: {args[0]}, y: {args[1]}))")
        elif kind == "C":
            lines.append(
                f"path.addCurve(to: CGPoint(x: {args[4]}, y: {args[5]}), "
                f"control1: CGPoint(x: {args[0]}, y: {args[1]}), control2: CGPoint(x: {args[2]}, y: {args[3]}))"
            )
        else:
            lines.append("path.closeSubpath()")
    return lines


def icons_swift(root: dict) -> str:
    icons = root["icons"]
    out = [HEADER.format(source="icons.json") + "import SwiftUI\n"]
    out.append("enum MumcordIcons {")
    out.append(f"    static let viewBox: CGFloat = {number(root['viewBox'])}")
    out.append(f"    static let strokeWidth: CGFloat = {number(root['strokeWidth'])}")
    out.append("")
    for icon_id, paths in icons.items():
        if not paths:
            raise ValueError(f"icon '{icon_id}' has no paths")
        out.append(f'    static let {camel(icon_id)} = MumcordIconData(id: "{icon_id}") {{ path in')
        for data in paths:
            out += [f"        {line}" for line in swift_path(path_commands(data))]
        out.append("    }")
    out.append("")
    out.append("    /// Every icon by its id in design/icons.json.")
    out.append("    static let all: [String: MumcordIconData] = [")
    out += [f'        "{icon_id}": {camel(icon_id)},' for icon_id in icons]
    out.append("    ]")
    out.append("}")
    return "\n".join(out) + "\n"


def write_if_changed(path: Path, content: bytes) -> None:
    if path.exists() and path.read_bytes() == content:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def main() -> int:
    tokens = json.loads((DESIGN_DIR / "tokens.json").read_text(encoding="utf-8"))
    icons = json.loads((DESIGN_DIR / "icons.json").read_text(encoding="utf-8"))

    write_if_changed(OUTPUT_DIR / "MumcordTokens.swift", tokens_swift(tokens).encode())
    write_if_changed(OUTPUT_DIR / "MumcordIcons.swift", icons_swift(icons).encode())
    for font in plain(tokens, "font").values():
        source = DESIGN_DIR / "fonts" / font["file"]
        write_if_changed(OUTPUT_DIR / "Fonts" / f"{font_file_name(font)}.ttf", source.read_bytes())
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, KeyError, ValueError) as error:
        # Shown by Xcode as a build error of the script phase.
        print(f"error: generate_design.py: {error}", file=sys.stderr)
        sys.exit(1)

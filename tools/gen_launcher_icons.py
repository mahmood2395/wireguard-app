#!/usr/bin/env python3
"""Regenerates the legacy launcher rasters from the adaptive icon's own path.

ICON.md says to delete res/mipmap-hdpi … mipmap-xxxhdpi outright, on the grounds that an
adaptive icon needs no per-density rasters. That is true from API 26. This app's minSdk is 24,
so on API 24 and 25 `@mipmap/ic_launcher` resolves to nothing but these bitmaps — deleting them
leaves those devices with no launcher icon at all. A vector in mipmap/ is not a substitute
either: pre-26 launchers inflate the icon in their own process and historically fail on
VectorDrawable. So the rasters stay, and this script keeps them honest by drawing them from the
same path the adaptive foreground uses, rather than leaving hand-made PNGs to drift.

    python3 tools/gen_launcher_icons.py

Requires rsvg-convert (brew install librsvg). Rerun it if the mark or either colour changes.
"""
import subprocess
import sys
from pathlib import Path

# The one mark. Kept byte-identical to res/drawable/ic_launcher_foreground.xml.
ARCH = "M36,80 L36,48 A18,18 0 0 1 72,48 L72,80 Z M44,72 L44,48 A10,10 0 0 1 64,48 L64,72 Z"
GROUND = "#003729"
GLYPH = "#b6e8de"

# The adaptive canvas is 108 with the icon living inside the central 72. A legacy raster is
# that 72 square, which is exactly what a launcher's square mask would show.
VIEWBOX = "18 18 72 72"

# Density bucket -> pixel size of a 48dp launcher icon.
BUCKETS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

SQUARE = """<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="{vb}">
  <clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="16" ry="16"/></clipPath>
  <g clip-path="url(#m)">
    <rect x="18" y="18" width="72" height="72" fill="{ground}"/>
    <path fill="{glyph}" fill-rule="evenodd" d="{arch}"/>
  </g>
</svg>"""

ROUND = """<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" viewBox="{vb}">
  <clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>
  <g clip-path="url(#m)">
    <rect x="18" y="18" width="72" height="72" fill="{ground}"/>
    <path fill="{glyph}" fill-rule="evenodd" d="{arch}"/>
  </g>
</svg>"""


def render(svg: str, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(
        ["rsvg-convert", "-o", str(out)],
        input=svg.encode(),
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.exit(f"rsvg-convert failed for {out}: {proc.stderr.decode()}")


def main() -> None:
    res = Path(__file__).resolve().parent.parent / "wireguard-android/ui/src/main/res"
    if not res.is_dir():
        sys.exit(f"no res directory at {res}")
    for bucket, px in BUCKETS.items():
        for template, name in ((SQUARE, "ic_launcher.png"), (ROUND, "ic_launcher_round.png")):
            svg = template.format(px=px, vb=VIEWBOX, ground=GROUND, glyph=GLYPH, arch=ARCH)
            out = res / f"mipmap-{bucket}" / name
            render(svg, out)
            print(f"{out.relative_to(res)}  {px}x{px}")


if __name__ == "__main__":
    main()

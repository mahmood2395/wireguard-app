#!/usr/bin/env python3
"""Generate a Material 3 colour-token set for Portway from one brand seed.

Material 3 builds each role from a tonal palette: a fixed hue+chroma swept across
lightness (the "tone"). This works in CIELCh, whose L* is the same lightness axis
M3's HCT uses, and clamps chroma per tone to what sRGB can actually show.

The chroma flags exist because M3's stock neutral chroma (4) is effectively grey:
no seed alone can produce the Aurora Dark navy canvas, since the dark background is
neutral tone 10. Raising --neutral-chroma tints the whole neutral ramp toward the
brand hue, which is what makes the dark theme read as navy rather than near-black.

--neutral-hue exists for a re-seed that moves only the accent: the greys keep the hue
they were generated at, so backgrounds and surfaces do not shift while primary does.
Omit it and the neutrals follow the seed, which is the original behaviour.

Usage:
  python3 tools/gen_palette.py '#123A5F' --neutral-chroma 11 > .../res/values/colors.xml
  python3 tools/gen_palette.py '#123A5F' --neutral-chroma 11 --check
"""
import sys, math

# ---------- sRGB <-> CIELAB ----------
def _srgb_to_lin(c): return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def _lin_to_srgb(c): return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
WP = (0.95047, 1.0, 1.08883)  # D65

def hex_to_lab(h):
    h = h.lstrip('#')
    r, g, b = (_srgb_to_lin(int(h[i:i+2], 16) / 255) for i in (0, 2, 4))
    x = (0.4124564*r + 0.3575761*g + 0.1804375*b) / WP[0]
    y = (0.2126729*r + 0.7151522*g + 0.0721750*b) / WP[1]
    z = (0.0193339*r + 0.1191920*g + 0.9503041*b) / WP[2]
    f = lambda t: t ** (1/3) if t > 216/24389 else (24389/27 * t + 16) / 116
    fx, fy, fz = f(x), f(y), f(z)
    return (116*fy - 16, 500*(fx - fy), 200*(fy - fz))

def lab_to_rgb(L, a, b):
    """Returns (r,g,b) floats 0..1 and whether the colour was inside sRGB."""
    fy = (L + 16) / 116; fx = fy + a/500; fz = fy - b/200
    fi = lambda t: t**3 if t**3 > 216/24389 else (116*t - 16) * 27/24389
    x, y, z = fi(fx) * WP[0], fi(fy) * WP[1], fi(fz) * WP[2]
    r =  3.2404542*x - 1.5371385*y - 0.4985314*z
    g = -0.9692660*x + 1.8760108*y + 0.0415560*z
    bb = 0.0556434*x - 0.2040259*y + 1.0572252*z
    inside = all(-1e-4 <= v <= 1 + 1e-4 for v in (r, g, bb))
    return tuple(min(1, max(0, _lin_to_srgb(min(1, max(0, v))))) for v in (r, g, bb)), inside

def tone(hue, chroma, t):
    """One tonal step: lightness t, at the most chroma sRGB can hold there."""
    lo, hi = 0.0, chroma
    for _ in range(24):                       # binary-search the gamut boundary
        mid = (lo + hi) / 2
        _, ok = lab_to_rgb(t, mid * math.cos(math.radians(hue)), mid * math.sin(math.radians(hue)))
        lo, hi = (mid, hi) if ok else (lo, mid)
    (r, g, b), _ = lab_to_rgb(t, lo * math.cos(math.radians(hue)), lo * math.sin(math.radians(hue)))
    return '#%02X%02X%02X' % tuple(round(v * 255) for v in (r, g, b))

def luminance(hexv):
    h = hexv.lstrip('#')
    r, g, b = (_srgb_to_lin(int(h[i:i+2], 16) / 255) for i in (0, 2, 4))
    return 0.2126*r + 0.7152*g + 0.0722*b

def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)))
    return (lb + 0.05) / (la + 0.05)

def _flag(name, default):
    """Minimal flag parsing: --name VALUE. Defaults reproduce M3's stock values."""
    if name in sys.argv:
        return float(sys.argv[sys.argv.index(name) + 1])
    return default

NEUTRAL_CHROMA = _flag('--neutral-chroma', 4)
NEUTRAL_VARIANT_CHROMA = _flag('--neutral-variant-chroma', 8)
PRIMARY_CHROMA_FLOOR = _flag('--primary-chroma', 48)
NEUTRAL_HUE = _flag('--neutral-hue', None)

seed = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('--') else '#123A5F'
L, a, b = hex_to_lab(seed)
hue = math.degrees(math.atan2(b, a)) % 360
chroma = math.hypot(a, b)

# M3's five key palettes, plus the fixed error red.
P  = lambda t: tone(hue, max(PRIMARY_CHROMA_FLOOR, chroma), t)  # primary
S  = lambda t: tone(hue, 16, t)                                 # secondary: muted brand
T  = lambda t: tone((hue + 60) % 360, 24, t)                    # tertiary: analogous accent
nhue = hue if NEUTRAL_HUE is None else NEUTRAL_HUE
N  = lambda t: tone(nhue, NEUTRAL_CHROMA, t)                    # neutral: brand-tinted greys
NV = lambda t: tone(nhue, NEUTRAL_VARIANT_CHROMA, t)            # neutral variant
E  = lambda t: tone(25, 84, t)                 # error

light = {
    'primary': P(40), 'onPrimary': P(100), 'primaryContainer': P(90), 'onPrimaryContainer': P(10),
    'secondary': S(40), 'onSecondary': S(100), 'secondaryContainer': S(90), 'onSecondaryContainer': S(10),
    'tertiary': T(40), 'onTertiary': T(100), 'tertiaryContainer': T(90), 'onTertiaryContainer': T(10),
    'error': E(40), 'onError': E(100), 'errorContainer': E(90), 'onErrorContainer': E(10),
    'background': N(99), 'onBackground': N(10), 'surface': N(99), 'onSurface': N(10),
    'surfaceVariant': NV(90), 'onSurfaceVariant': NV(30), 'outline': NV(50), 'outlineVariant': NV(80),
    'inverseSurface': N(20), 'inverseOnSurface': N(95), 'inversePrimary': P(80),
    'shadow': N(0), 'scrim': N(0), 'surfaceTint': P(40),
}
dark = {
    'primary': P(80), 'onPrimary': P(20), 'primaryContainer': P(30), 'onPrimaryContainer': P(90),
    'secondary': S(80), 'onSecondary': S(20), 'secondaryContainer': S(30), 'onSecondaryContainer': S(90),
    'tertiary': T(80), 'onTertiary': T(20), 'tertiaryContainer': T(30), 'onTertiaryContainer': T(90),
    'error': E(80), 'onError': E(20), 'errorContainer': E(30), 'onErrorContainer': E(90),
    'background': N(10), 'onBackground': N(90), 'surface': N(10), 'onSurface': N(90),
    'surfaceVariant': NV(30), 'onSurfaceVariant': NV(80), 'outline': NV(60), 'outlineVariant': NV(30),
    'inverseSurface': N(90), 'inverseOnSurface': N(20), 'inversePrimary': P(40),
    'shadow': N(0), 'scrim': N(0), 'surfaceTint': P(80),
}

ORDER = ['primary','onPrimary','primaryContainer','onPrimaryContainer','secondary','onSecondary',
         'secondaryContainer','onSecondaryContainer','tertiary','onTertiary','tertiaryContainer',
         'onTertiaryContainer','error','errorContainer','onError','onErrorContainer','background',
         'onBackground','surface','onSurface','surfaceVariant','onSurfaceVariant','outline',
         'inverseOnSurface','inverseSurface','inversePrimary','shadow','surfaceTint','outlineVariant','scrim']

if '--check' in sys.argv:
    pairs = [('onPrimary','primary'), ('onPrimaryContainer','primaryContainer'),
             ('onSecondaryContainer','secondaryContainer'), ('onTertiaryContainer','tertiaryContainer'),
             ('onSurface','surface'), ('onSurfaceVariant','surfaceVariant'),
             ('onBackground','background'), ('onError','error'), ('onErrorContainer','errorContainer')]
    for label, scheme in (('light', light), ('dark', dark)):
        for fg, bg in pairs:
            r = contrast(scheme[fg], scheme[bg])
            print(f"{label:5} {fg:22} on {bg:20} {scheme[fg]} / {scheme[bg]}  {r:5.2f}:1  {'PASS' if r >= 4.5 else 'FAIL'}")
    sys.exit(0)

print('<?xml version="1.0" encoding="utf-8"?><!--')
print('  ~ Copyright © 2026 Portway. All Rights Reserved.')
print('  ~ SPDX-License-Identifier: Apache-2.0')
print('  ~ Modified from the WireGuard Android project: Material 3 tokens regenerated')
print(f'  ~ from the Portway brand seed by tools/gen_palette.py. Do not hand-edit.')
# The exact invocation lives in DESIGN.md, not here: XML forbids "--" inside a
# comment, so a literal command line containing long flags cannot be embedded.
print(f'  ~ Seed {seed}; see DESIGN.md for the exact generator invocation.')
print('  -->')
print('<resources>')
print(f'    <color name="seed">{seed}</color>')
for scheme, name in ((light, 'light'), (dark, 'dark')):
    for k in ORDER:
        print(f'    <color name="md_theme_{name}_{k}">{scheme[k]}</color>')
print('</resources>')

# Launcher icon — the portway (option 2b)

## The mark

An archway with a raised sill: a doorway, drawn literally, because that is what the app is. It
matches the onboarding line — "Portway is a door, not a service."

Two subpaths, one fill, `evenOdd`. No group transform needed: the glyph already sits inside the
66dp safe circle of the 108dp adaptive canvas, so nothing clips under a circle, squircle or
teardrop mask.

```
M36,80 L36,48 A18,18 0 0 1 72,48 L72,80 Z M44,72 L44,48 A10,10 0 0 1 64,48 L64,72 Z
```

Geometry, in the 108 viewport:

| Part | Value |
|---|---|
| Outer arch | x 36 → 72, springline y 48, apex y 30 (r 18), feet y 80 |
| Inner opening | x 44 → 64, springline y 48, apex y 38 (r 10), stops at y 72 |
| Jamb thickness | 8dp each side |
| Arch apex thickness | 10dp |
| Sill | 8dp, **flush with the feet** |
| Extreme point from centre | 31.6dp (safe circle is 33dp) |

**The sill must stay flush with the feet.** An earlier version had it floating across the opening
at mid-height, and the mark read unmistakably as a capital **A** at every size. If you ever adjust
proportions, check that first — it is the failure mode of this shape.

Nothing in the mark is thinner than 8dp, which is why it survives 24px (notification and Quick
Settings tile size).

## Colour

| Role | Value |
|---|---|
| Ground (`ic_launcher_background`) | `#262a60` |
| Glyph (`fillColor`) | `#d2cefd` (accent-300) |

8.8:1. `#262a60` is `--color-section` — Nocturne's one sanctioned saturated field, the same value
its deck uses for section dividers. An icon is exactly that case: presence at small scale. Do
**not** flood the accent itself (`#9184d9`); the board shows why the system rules it out.

Replaces today's `#123A5F` ground with a white shield.

## Files

### 1. `res/drawable/ic_launcher_foreground.xml`

Keep the file, keep the 108dp/108 viewport, replace the path. Keep the comment about the one-fill
rule — it is still the binding constraint.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Archway with a flush sill: one fill, so the Android 13 monochrome (themed icon)
         variant survives tinting instead of collapsing into a blob. The sill must stay
         level with the frame's feet — floated across the opening it reads as an "A". -->
    <path
        android:fillColor="#d2cefd"
        android:fillType="evenOdd"
        android:pathData="M36,80 L36,48 A18,18 0 0 1 72,48 L72,80 Z M44,72 L44,48 A10,10 0 0 1 64,48 L64,72 Z" />
</vector>
```

### 2. `res/values/ic_launcher_background.xml`

```xml
<color name="ic_launcher_background">#262a60</color>
```

### 3. `res/mipmap-anydpi-v26/ic_launcher.xml` (and `ic_launcher_round.xml`)

Add the `<monochrome>` entry. This is what the one-fill rule buys you, and the current icon does
not declare it — so on Android 13+ themed icons the launcher falls back to the full-colour tile.

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

### 4. Delete the raster buckets

`res/mipmap-hdpi` … `mipmap-xxxhdpi` PNGs. An adaptive icon needs no per-density rasters; keep
only `mipmap-anydpi-v26`. Play Store still wants a 512×512 PNG, but that is uploaded in the
console, not shipped in the APK.

### 5. Reuse the same path

- `res/drawable/ic_portway_mark.xml` — the settings-row mark. Same path, `fillColor` swapped to
  the tint the row applies. It currently carries its own shield-and-keyhole drawing at a 108
  viewport; replace the `pathData` and nothing else.
- `res/drawable/ic_tile.xml` — Quick Settings tile. Same path; this is the 24px case the
  proportions were checked against.
- `res/drawable/tv_logo_banner.xml` — redraw the banner from the arch, on `#262a60`.
- `res/drawable/ic_nav_connect.xml` — **leave it as the Phosphor Shield.** The nav icon and the
  app icon should not be the same glyph; the tab means "this screen", the icon means "this app".

## Verification

- Under a circular mask nothing is shaved: the extreme point is 31.6dp against a 33dp safe radius.
- At 24dp the opening is still open — that is the test the keyhole in the old mark failed.
- Themed icons on: the mark tints to a single flat colour and stays legible, no blob.
- `aapt dump badging` shows the adaptive icon and no leftover mipmap densities.

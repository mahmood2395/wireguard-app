# Portway design system

> **Status:** the "Aurora Dark" redesign is in progress. Phases 1–3 of
> `~/.claude/plans/zesty-cuddling-engelbart.md` are done (tokens + dark-first theming,
> bottom navigation, the Connect screen's data and logic). Phases 4–9 remain: Connect
> motion, list restyle, detail dashboard, sheet + editor, settings, hardening.

The fork keeps upstream's XML + databinding UI rather than rewriting in Compose. A rewrite would
have cost weeks and destroyed the small diff that lets Portway rebase onto upstream security
fixes — and it was not needed, because upstream is already on Material 3 with a fully tokenised
theme. Re-skinning is therefore a palette regeneration plus targeted component work.

## Colour — one seed, 60 tokens

Everything derives from a single brand seed, `#6fd9b8` (the Nocturne accent). It was `#5B4BD6`
under the earlier Aurora/clay direction, then violet `#9184d9` for the Nocturne redesign;
re-seeded to teal 2026-09-08. `tools/gen_palette.py` builds the Material 3 tonal palettes from it
and writes `res/values/colors.xml`. Do not hand-edit that file:

```bash
# The canonical invocation. Aurora Dark needs the raised neutral chroma: M3's stock
# neutral chroma of 4 is effectively grey, and the dark background IS neutral tone 10,
# so no seed on its own can produce a navy canvas.
#
# --neutral-hue 300 pins the greys at the ORIGINAL violet hue. The teal re-seed was a
# change to the accent role only, and without the pin the whole neutral ramp swings from
# a violet-tinted dark to a green-tinted one — every background and surface in the app
# shifting hue for a change that was meant to touch lines, marks and glows.
python3 tools/gen_palette.py '#6fd9b8' --neutral-chroma 10 --neutral-variant-chroma 14 \
    --neutral-hue 300 > wireguard-android/ui/src/main/res/values/colors.xml

# WCAG contrast report — run after any re-seed, all pairs must PASS
python3 tools/gen_palette.py '#6fd9b8' --neutral-chroma 10 --neutral-variant-chroma 14 \
    --neutral-hue 300 --check
```

**The accent is one role with a 100–900 ramp, not a single colour.** `brand_colors.xml` carries
it as `accent_300`…`accent_900` around `accent`, and the night steps come from the prototype's own
mixing function — 300 and 400 are the accent mixed 54% and 28% toward paper `#f3f5fe`; 600, 700,
800 and 900 are it mixed 22%, 42%, 62% and 78% toward the ground `#161826`. The ring glow is the
same accent mixed 66% toward the ground, which is why `ConnectRingView` computes it rather than
storing it: a re-seed that moved the stroke but not the glow would leave a teal ring on a violet
halo, and Nocturne is mono. The light theme mirrors the ramp — 300 is its DARK end — and its teal
steps were derived by holding each violet step's CIELAB lightness and rotating the hue, so every
contrast ratio in the light theme survived the re-seed to within 0.02:1.

The generator sweeps lightness in CIELCh at fixed hue and chroma — the same lightness axis M3's
HCT uses — clamping chroma per tone to what sRGB can actually display, then maps tones onto the
M3 roles (light `primary` = tone 40, dark = tone 80, and so on). Neutrals carry a little brand
chroma (4–8), which is why the dark theme reads as blue-black rather than grey.

All 18 foreground/background pairs are verified ≥ 4.5:1 (WCAG AA); the `--check` run prints them.
Re-run it after any re-seed.

`res/values/status_colors.xml` and its `values-night` twin mirror `primary` and `onSurfaceVariant`
for the row status text, which databinding cannot read from theme attributes. Update them by hand
if you re-seed.

## Direction: soft depth

Chosen from a gallery of six after two earlier directions were rejected as too plain. Everything
on screen is lit from the top-left by a single implied light source — the connect puck, the stat
tiles, the handshake readout and the list rows all share it, and that shared lighting is what
makes the screens feel like one object rather than a set of panels.

**The puck is a bitmap, not a shape.** Android's `<shape>` cannot blur, and faking a soft shadow
with offset rectangles produces hard edges. `tools/clay/puck.py` renders it from SVG (two blurred
shadows, a gradient face, a rim highlight, a glowing core) into density buckets under
`drawable-*` and `drawable-night-*`. It is a fixed size, so a bitmap is safe.

**Surfaces are a custom view, because Android cannot draw this shadow.** The direction needs
TWO blurred shadows — dark lower-right, light upper-left — and the platform offers neither:
`<shape>` cannot blur at all, and view `elevation` draws a single platform-black shadow that is
effectively invisible on a dark canvas. The first attempt used a gradient plus a hairline stroke
and looked flat next to the mockup, which is exactly what a stroke is: an outline, not depth.

`widget/ClayCardView.kt` draws both shadows with `Paint.setShadowLayer()`, which blurs correctly
at any size, so the same view serves half-width tiles and full-width cards without shipping a
nine-patch per surface. Two constraints to know: `setShadowLayer` is ignored by the hardware
renderer, so the view forces a software layer (cheap here — these views are small, static and
few); and the shadow falls OUTSIDE the view bounds, so the view pads its own children by the
shadow extent. Skip that padding and the content lays out over the shadow gutter and spills
outside its own surface.

**Every surface in the app is now a `ClayCardView`** — connect tiles, handshake card, list rows,
and the detail and editor cards (which were `MaterialCardView`). Two things to know when touching
these:

- The list row root is still called `MultiselectableRelativeLayout`, because `TunnelListFragment`
  casts to that type in `onConfigureRow` and `viewForTunnel`. It now extends `ClayCardView`, so
  the row's children lay out as in a FrameLayout — the old `alignParentEnd` / `toStartOf` rules
  are gone. Selection paints the whole face (`faceOverride`) rather than drawing a border,
  because a soft surface has no border to highlight.
- `MaterialCardView` supplied `contentPadding` from the theme style; `ClayCardView` does not, so
  each card's inner container carries explicit padding. Miss one and the content sits flush
  against the card edge.

**One material, one rendering path.** The connect control is a `ClayCardView` — literally the
same view as the stat tiles, so face and shadows can never drift apart again; only its inner
violet core is a drawable that swaps with state. The nav puck must stay a bitmap (Material's
`itemBackground` takes a drawable), but `tools/clay/navpuck.py` now generates it FROM the tile's
values: `clay_surface_hi/lo`, `clay_shadow_cast/light`, 8dp offset, 13dp blur. If the tile
palette or ClayCardView's shadow constants change, regenerate the nav puck or it will fall out
of family — that drift is exactly how the first mismatch happened.

**The bottom nav is a floating island, not a strip** — the direction study drew it as a rounded
bordered card (`nav_bar_bg`: surface fill, 1dp stroke, 18dp radius) floating on the dark canvas
end with 14dp side / 12dp bottom margins, and the earlier full-width flat bar was a
misreading of that study, which several rounds of puck-tuning could not fix because the bar
itself was the difference. Active icon is violet (`nav_icon_tint`), labels keep the neutral
selector. Two integration points: NavigationBarView consumes the gesture inset as internal
padding even with `paddingBottomSystemWindowInsets="false"`, growing a dead band inside the
island — MainActivity overrides `setOnApplyWindowInsetsListener` to zero it; and
`bottom_nav_height` (the content inset) must cover bar + float margin + clearance (108dp), or
the FAB clips the island's corner.

**The SVG shadow filter bug that shipped shadowless pucks for four review rounds.** The
generators used `feOffset → feGaussianBlur → feFlood → feComposite in2="SourceAlpha"`.
`feFlood` discards its input and `SourceAlpha` is the ORIGINAL silhouette — so the offset and
blur were computed and thrown away, and the "shadow" was an exact copy of the face hidden
underneath it. Every bitmap puck rendered this way had literally zero cast shadow; the perceived
depth was only the face gradient. Worse, the "render the spec as a reference and diff" check
passed perfectly, because the reference SVG had the same broken chain — a reference is only as
good as its independence. The correct chain names the blur result and composites against it:
`feOffset in="SourceAlpha" result="o" → feGaussianBlur in="o" result="b" → feFlood →
feComposite in2="b"`. The check that caught it was a VISIBILITY metric (max darkening of the
ground near the face, in RGB-sum units: broken ≈ 1, fixed ≈ 77, ClayCardView tiles ≈ 60+),
which is the right kind of assertion for "can you see this effect" questions.

**The nav puck's shadows are verified against the study's CSS, not eyeballed.** The study spec
is `4px 4px 10px rgba(5,3,15,.55)` + `-4px -4px 10px rgba(255,255,255,.055)` on a 145°
`#3A3F63→#262B47` face, r16 — and the honest check is rendering that CSS as a reference PNG and
diffing the generated asset against it (`scratchpad` workflow; final mean diff 0.36/255). Three
things had crept in that no amount of value-tuning would have found: a rim-highlight path the
study never had (it read as an etched outline), the light shadow painted above the dark one
(CSS paints the first-listed shadow topmost), and the dark tail clipped by too-small canvas
padding (needs offset + 3×stdDeviation).

**The bottom nav's active tab is a raised puck**, lit like every other surface. Material's own
active indicator accepts only a colour and a `shapeAppearance`, so it cannot draw a two-shadow
face — the puck is a pre-rendered bitmap (`tools/clay/navpuck.py`, density-bucketed, light and
dark) delivered through `itemBackground`, with the built-in indicator switched off. That switch
is **code-only**: `isItemActiveIndicatorEnabled`, set in `MainActivity`; there is no XML
attribute for it. The background is offset upward so the face sits behind the icon rather than
centring over the item, which includes the label.

**Raised vs sunken carries meaning.** Controls and readouts you act on are raised
(`clay_surface`); the handshake card is sunken (`clay_surface_sunken`) because it reports rather
than invites a tap.

**The handshake card** shows the newest handshake across peers, with a dot that dims as it ages.
This is the honest health signal: a tunnel can sit at "Connected" forever while never completing
a handshake, and that state now has somewhere to show. It reads "never" against an unreachable
peer, which is exactly the condition the watchdog acts on.

## Aurora Dark (superseded)

Dark is the designed-for theme and the default on first run, on every API level. Upstream's
`dark_theme` boolean was ignored on API 29+ (hard-coded follow-system, preference removed from
the screen), so it became a tri-state `theme_mode` — Dark / Light / Follow system — with a
migration that preserves an explicit prior choice.

**`DynamicColors.applyToActivitiesIfAvailable()` was removed** from `Application.onCreate`.
On API 31+ it replaced every Material 3 role with the device wallpaper palette, which meant the
generated brand palette never actually reached the screen on a modern phone. A branded client
wants its own colours.

The brand layer that M3 has no roles for — the canvas, the accent ramp, the row and ping inks,
the synthesized connecting state and the handshake decay bar — lives in `values/brand_colors.xml`
(+ `values-night`) and `status_colors.xml`.

Two of those are surfaced as theme attributes (`statusConnectingColor`, `rowSurfaceColor`) and any
attribute that is added must be defined in `AppThemeBase` rather than a Connect-only overlay:
`TvTheme` extends `AppTheme` and would fail to inflate otherwise. **Everything else references
`@color/` directly**, on purpose — a `<gradient>` in a `<shape>` only resolves theme attributes
when the drawable is inflated with a theme, so the `values-night` qualifier does the switching
instead. The seven `?attr/aurora*` declarations this file used to describe were deleted on
2026-09-13: nothing had read them since the Nocturne redesign, and each one kept a dead colour
looking alive to every unused-resource check.

## Connect screen

The home destination. It shows one tunnel — whatever is up, else the last used, else the first
known — with its live state, session duration and throughput.

- **Session timer.** No such timestamp exists in the backend (the latest handshake is a rekey,
  not a session start), so `ObservableTunnel.connectedSinceElapsedRealtime` is stamped inside
  `onStateChanged`, the single place state is assigned, which covers the UI, the quick tile,
  always-on and boot restore alike. Monotonic clock. Tunnels already running at process start
  show "Connected" with no duration — a deliberate gap rather than persisted state.
- **Throughput** is differenced from cumulative counters with a one-pole EMA, discarding samples
  where the gap is implausible (returning from background) and reseeding instead.
- **Connecting/Disconnecting/Error** are synthesized in the UI, since `Tunnel.State` is only
  DOWN/UP. `tunnel.state` stays the source of truth except while a request is in flight, so
  changes made from the quick tile or always-on still render correctly.
- **Polling** uses `repeatOnLifecycle(RESUMED)`, which cancels at pause and restarts at resume —
  verified as zero stats calls while backgrounded. It calls `getStatisticsAsync()` explicitly and
  never the `@Bindable` `statistics` getter, whose getter launches a refetch as a side effect.
- **Motion is property animators, not MotionLayout.** The plan called for a MotionLayout scene;
  it was built and then removed. A nested MotionLayout **swallows touch events over its bounds** —
  the connect control became activatable only by keyboard, with no crash or warning to explain it
  (`adb shell input keyevent ENTER` worked while `input tap` did nothing, which is what isolated
  it). The transition is two views and three properties, so `ViewPropertyAnimator` with an
  overshoot interpolator covers it without intercepting input. The glow's "breathe" while
  connecting stays a separate infinite `ObjectAnimator`, cancelled in `onStop`.
- **Accessibility.** The live values are set imperatively, which fires no accessibility event —
  a screen reader (and `uiautomator dump`) sees stale text. The status and timer are therefore
  `accessibilityLiveRegion="polite"`, and the control carries a phase-dependent
  `contentDescription`, since its meaning inverts with state.

## The handshake decay bar

`HandshakeDecayView` + `HandshakeDecayTrack` replaced the heartbeat lane on Home and on the detail
screen, and with it the "Latest handshake: 12 seconds ago" row — a figure that re-rendered every
second and could therefore never actually be read. Two constants define the whole graphic:
`HANDSHAKE_LIMIT` 180s, WireGuard's cutoff at which the peer counts as gone, is the full width of
the track; `REKEY_DUE` 118s, the nominal rekey, is a hairline at 65.6% of it. The age still ticks,
but now against a limit, so one glance says how much room is left.

- **It got smaller, not bigger.** The lane needed a ring buffer of past handshakes to draw
  anything; this needs only the age of the latest one, which the polling loop already reads. So
  `HandshakeLog` and `HeartbeatLaneView` were both deleted rather than kept alongside it.
- **The hairline is drawn outside the track's rounded clip**, spanning the band's full height.
  Inside it, it reads as a boundary between two fill segments rather than as a mark on a scale,
  which is the one thing it exists to say.
- **"rekey due" hangs off a 0.656 percent guideline**, not a three-item weighted row — weights
  land the label at 50% of the width, pointing at nothing. The guideline is absolute rather than
  layout-direction aware, so RTL flips it to `1 - 0.656` in code, matching the track's own mirror.
- **Only growth is animated** (0.9s linear). A new handshake resets the age to near zero, and
  sliding the bar backwards over 0.9s reads as the bar being wrong; snapping it reads as the rekey
  landing, which is what happened.
- **`HandshakeWatchdog` reads `HANDSHAKE_LIMIT_MS`** rather than keeping its own copy of 180s. The
  bar and the watchdog answer the same question — up but not handshaking — and the bar is the
  visual form of the condition the watchdog acts on, so two constants could only ever drift apart.
  It is `const`, so the compiler inlines it and the headless path loads no View class.
- **It is not hidden while disconnected.** The silent state — a full track in the disconnected ink
  reading "last handshake over three minutes ago" — is designed, and it is the state a user most
  needs to see. Only the absence of a config hides it.
- **What the emulator cannot show.** It never completes a handshake, so only the silent state is
  reachable there; fresh and late render on real hardware only. Do not re-add seeding code to fake
  it — that is what the TEMP-LANE hack did for the heartbeat lane, and it was removed.

## The polish pass

The first Aurora implementation was correct and dull — flat cards the same value as the canvas,
stock Roboto, one accent, a plain filled circle for the hero. Correct Material 3 is not the same
as appealing. What changed:

**Typography.** Inter (UI) and JetBrains Mono (figures) are bundled in `res/font`, both under the
SIL Open Font License so they can ship in a redistributed APK. Static instances, not the variable
font — variable fonts need API 26 and the floor here is 24. Two weights only (400/600), which is
both the design rule and half the APK cost: ~1.1 MB. Display sizes get negative tracking, because
Inter is drawn loose by default and reads generic without it. The type scale is swapped at
**theme level**, so screens this redesign never touched inherit it. The framework ActionBar needs
its own override — it does not read Material 3 text appearances.

**Depth.** `aurora_canvas` is a layer-list: base gradient plus two off-centre radial colour fields.
Without them the screen is one flat value and everything on it looks pasted on. Applied to Connect,
the list and the detail screen so depth is consistent.

**The hero.** A sweep-gradient ring rotating behind a rim-lit radial disc. Slow while connected,
fast while connecting. Note a sweep gradient always meets itself: start and end must both be
transparent, with the accent at the centre stop, or there is a hard seam cutting across the ring.

**Surfaces.** A drop shadow is invisible on a dark canvas, so cards are defined by a hairline
accent stroke (~12% opacity) over a surface one step lighter than the background, at 24dp radius.
Stat tiles use the same treatment.

## The rules applied

- **60/30/10.** Surface carries the screen, text is the 30, and brand colour is spent almost
  nowhere — a connected tunnel and the FAB. Resting rows are deliberately colourless.
- **Hierarchy by weight, not fills.** An early pass gave every row a filled container; three rows
  became three grey slabs with nothing to look at. Rows are now quiet, and the eye goes to the one
  that is connected.
- **The value, not the label.** Each row leads with the tunnel name (`titleMedium`), with live
  state on its own line beneath it — bound to `ObservableTunnel.state`, so it changes the instant
  a tunnel comes up.
- **8-point grid.** Row padding 24/20, status gap 4, empty-state rhythm 32/8, row inset 8/4.
- **Empty states get guidance.** The old one was a 33%-opacity launcher icon and a single line.
  It is now the mark in a `primaryContainer` circle, a headline, and a sentence pointing at the
  FAB — which sits in the thumb zone where it always was.
- **Typography stays bounded.** Four roles only: `headlineSmall`, `titleMedium`, `bodyMedium`,
  `bodySmall`. No custom sizes, no bolding for emphasis.

## What changed, by file

| File | Change |
|------|--------|
| `values/colors.xml` | all 60 M3 tokens regenerated from the brand seed |
| `values/status_colors.xml`, `values-night/status_colors.xml` | row status colours |
| `values/styles.xml` | card corner 4dp → 16dp, content padding 8dp → 16dp |
| `layout/tunnel_list_item.xml` | name + live status line, 8pt padding |
| `layout/tunnel_list_fragment.xml` | empty state rebuilt |
| `drawable/list_item_background.xml` | inset rounded row cards, selection + ripple |
| `databinding/ObservableKeyedRecyclerViewAdapter.kt` | fine-grained list notifications + stable ids |
| `layout/tunnel_detail_fragment.xml` | live dashboard header above the config cards |
| `layout/add_tunnels_bottom_sheet.xml` | handle, title, tinted icons, rounded corners |
| `util/ThroughputMeter.kt` | **new** — shared rate derivation |
| `drawable/empty_state_circle.xml` | new |
| `mipmap-*`, `drawable/ic_launcher_foreground.xml`, `drawable/tv_logo_banner.xml`, `drawable/ic_portway_mark.xml`, `drawable/ic_tile.xml` | the portway arch (Nocturne), one `evenOdd` fill so the themed icon survives tinting |
| `util/GeoResolver.kt`, `util/PeerMeta.kt` | **new** — where a config's traffic surfaces, and the line that states it |

## List behaviour

Selection is keyed by **tunnel name, not adapter position**. Upstream stored positions in a
`HashSet<Int>` and resolved delete as `tunnels[position]`; `notifyDataSetChanged()` on every
change hid the drift, but the moment the adapter reports fine-grained changes those positions
go stale and the wrong tunnels get deleted. The conversion landed and was verified on its own
(select two of four, rotate, delete, confirm exactly those two are gone) *before* the adapter
changed.

The adapter now honours `onItemRange{Inserted,Removed,Moved,Changed}` instead of funnelling
everything into `notifyDataSetChanged()`, with `setHasStableIds(true)`, so inserts and removals
animate. DiffUtil was considered and rejected: `ObservableTunnel` is a mutable `BaseObservable`
with no value equality, so `areContentsTheSame` would always be true and state changes would
never render.

**Do not use `layout_dodgeInsetEdges` to keep content above the bottom nav.** It translates the
whole container upward by the nav height, which pushed the first list row up behind the action
bar. The container takes an explicit `@dimen/bottom_nav_height` bottom margin instead.

## Detail screen

A dashboard header sits above upstream's config cards: connection status, latest handshake
(or "No handshake yet" — the honest state when a peer is unreachable), live down/up rates and
session totals. The rate maths is shared with the Connect screen via `util/ThroughputMeter.kt`
rather than duplicated.

Polling moved to `repeatOnLifecycle(RESUMED)`, replacing the `onResume`/flag/`onStop` pattern
that could leave two loops running if `onResume` fired twice.

The action bar shows the destination at a tab's root and the **tunnel's own name** once you are
inside its detail or editor — otherwise every screen inside the Tunnels tab read "Tunnels".

## Restyling by theme, not by layout

The editor, the naming dialog and the app-list dialog were never edited. They changed because
three styles are set at **theme** level and every screen inherits them:

- `textInputStyle` → outlined boxes at 16dp radius, so all `TextInputLayout`s move together
  without touching a single `@={...}` two-way binding.
- `materialCardViewStyle` → raised surfaces with the shadow tinted toward the canvas
  (`outlineAmbientShadowColor`, API 28+; below that it falls back to black, which on a dark
  ground is invisible rather than wrong).
- `preferenceTheme` → the supported hook for androidx-preference. The row layout
  (`preference_clay.xml`) must keep the exact ids androidx binds by — `@android:id/icon`,
  `@android:id/title`, `@android:id/summary`, `@+id/icon_frame`, `@android:id/widget_frame` —
  or `onBindViewHolder` silently does nothing. `duplicateParentState` on the text is what makes
  disabled preferences look disabled.

Settings is grouped into Connection / Appearance / Tools / About, with checkboxes swapped for
`SwitchPreferenceCompat` (same keys, so the DataStore is untouched). There is deliberately **no**
`initialExpandedChildrenCount`: upstream decremented it as preferences were removed at runtime,
and that arithmetic counts top-level children — adding categories would have silently hidden
preferences behind "Advanced".

**The theme default is now written, not inferred.** `UserKnobs.migrateThemeMode()` persists the
effective mode on first run. Without it the key stayed absent, the preference screen persisted
whatever it resolved on first bind, and the app came up on *follow-system* while the docs claimed
dark-by-default — verified by finding `theme_mode = SYSTEM` in the DataStore.

## Persian, and right-to-left

A large share of this app's users read Persian, and the type system was built for Latin. Four
things had to change, none of them a translation.

**The face.** Inter has no Arabic coverage at all, so every Persian string was drawn by whatever
the system happened to fall back to — a different face with different metrics from the Latin
beside it, varying by vendor and Android version. `res/font-fa/inter.xml` is a locale-qualified
family of the same resource name pointing at **Vazirmatn UI** (SIL OFL, bundled), so the whole
type system swaps in one place with no style, layout or code change. The UI cut, because
Vazirmatn's default vertical metrics grow every line box in a layout this dense; and deliberately
not the Farsi-Digits cut, because these screens carry IP addresses, ports, MTUs and base64 keys.

**Tracking.** Nocturne tracks small uppercase labels out and display text in. Applied to a
cursive script that is not a refinement but a defect: Android implements letter-spacing by
inserting space between glyphs, which pulls the joins apart and leaves words visibly broken.
Every value is now a named float in `values/tracking.xml` and zeroed in `values-fa` and
`values-ar`. Nothing is lost — that hierarchy is carried by size, weight and colour too.

**Bidi.** Two distinct failures, two distinct fixes:

- A Latin value dropped into a Persian sentence (a config name, a host, a version) reorders
  around the surrounding text. Every `%s` in `values-fa/strings.xml` is wrapped in U+2068/U+2069
  isolates. First-Strong Isolate auto-detects, so it is correct whether the value turns out to be
  Latin or Persian.
- Android aligns text by the direction of the TEXT, so in an RTL layout a config named
  "frankfurt" jumps to the left edge while the Persian line under it stays right — one card, two
  margins. Every width-filling field that can hold a Latin value carries
  `android:textAlignment="viewStart"`, which aligns by the LAYOUT instead and is a no-op wherever
  the two already agree.

**Time runs the way the script does.** `HandshakeDecayTrack` and `UsageBandView` are time axes
with "now" at the reading end, so both mirror under RTL — otherwise "now" sits at the far end from the
caption describing it. `ic_caret_right` and `ic_arrow_back` are `autoMirrored`; the quota rails
are `mirrorForRtl`.

Numerals are left to the locale: Persian digits in prose and quantities, Latin in the raw strings
(addresses, keys, hosts, ports) that are never formatted. That is the split Persian readers
expect, and it keeps anything a user has to compare against their .conf file readable.

**The picker offers English and Persian only** — the two languages Portway is actually finished
in. The ~33 community translations inherited from upstream still work when the phone is set to
one of them (that is what "Follow system", the default, means), but they predate this fork's
screens and cover about half its strings, so offering them would be offering a language the app
does not really speak. `values/locales.xml` is the list; add to it when a `values-*` file reaches
zero missing strings against `values/strings.xml`.

Three things this shook out, all fixed and all easy to reintroduce:

- **A style that names a weight FILE bypasses the locale swap.** `Portway.ActionBar.Title` set
  `fontFamily` to `@font/inter_semibold`, not to the `@font/inter` family, so the screen titles
  were the one place still drawn in Inter. `font-fa/` now carries `inter_regular.xml` and
  `inter_semibold.xml` aliases too, so a direct weight reference swaps like everything else.
- **Insets were applied twice in Settings.** `SettingsFragment` set `fitsSystemWindows` on its own
  root, but inside `MainActivity` the root CoordinatorLayout already offsets the fragment
  container and does not consume the insets — so the list started 296px below its container. It
  surfaced only once something re-dispatched insets to a laid-out hierarchy (a preference dialog,
  or a theme or language change recreating the activity), which is why the screen looked right
  until the user opened a popup and then had a band of empty canvas above the first row. The
  fragment now insets itself only in `SettingsActivity`, which hosts it with nothing in between.
- **The switch's knob was invisible.** Nocturne's ON state is an accent pill with the knob in the
  ground colour — a hole in the switch, not a dot on it. But the thumb is drawn over the track and
  at the travelled end it covered the track's cap and its 1dp edge, so a hole the same colour as
  the page behind it stopped being a hole and became a gap: the control rendered as a "D". The
  knob now carries a 1.5dp `accent_300` stroke, which is the ramp's light end at night and its
  dark end by day, so the silhouette survives in both themes.

It appears twice. `preference/LocalePreference.kt` is the full control in Settings — the only
place that can put the choice back to "follow system" — and the welcome screen carries a
two-segment switch at the top end, because the first run is where being unable to read the app
costs the most: everything after it asks the user to trust a permission dialog or a config
someone sent them, and none of that survives being illegible. Choosing recreates the activity,
which is why `OnboardingActivity` keeps its step in the saved state.

## Probing, and what a ping is for

RTT to a peer is a property of that peer, not a live meter — the handshake decay bar is what
shows liveness. So it is measured when the user arrives at a screen and left alone after that:
`repeatOnLifecycle(RESUMED)` re-runs it every time the screen comes back, and a tunnel coming up
re-arms it on Home. It used to run every ten seconds on the Configs list (a burst to every server
the user owns, forever) and every three seconds on Home and detail.

That change exposed an older bug worth keeping in mind. `Pinger.icmp` read the child's output with
`readText()`, which blocks until the process closes stdout — and the timeout guard below it could
only run once that read had returned, so it could never rescue the case it was written for. A
wedged `ping` pinned the coroutine and left the row's dot at "probing" indefinitely; at the old
ten-second cadence it was hidden behind a probe that appeared to be merely slow. `destroy()` from
a watchdog coroutine is what closes the streams and ends the read.

## Not done

The detail, editor, settings and TV screens inherit the new palette but have not had a layout
pass. They look consistent because they were already themed; they are not redesigned.

# Handoff: Portway — Nocturne redesign (Android)

## Overview

Portway is a WireGuard client for Android (a fork of `wireguard-android`, XML + databinding,
Material 3). It is mid-way through an "Aurora Dark / clay" redesign: Connect, the tunnel list and
the bottom navigation had a pass; detail, editor and settings inherited the palette but never got
a layout pass.

This handoff replaces that direction. Three things change:

1. **The clay material is retired.** `widget/ClayCardView.kt` draws two blurred shadows via
   `Paint.setShadowLayer()` under a gradient face, and pads itself by blur + offset = **21dp on
   every side**. A `match_parent` card inside 24dp screen padding therefore paints its face 45dp
   from the screen edge, and two weighted tiles in a row sit **42dp apart**. That is the single
   biggest reason the current screens read as five objects floating in a field rather than one
   product. The replacement is an edge plus ambient darkness: 1px hairline, 8dp radius, flat ground.
2. **Vocabulary.** Portway has no server list — a user owns a set of configs someone handed them.
   "Tunnels" becomes **Configs**, the public key moves behind a disclosure, and the city shown on
   the home screen is **resolved from the peer's endpoint IP**, stated as a fact rather than offered
   as a menu.
3. **The handshake readout becomes a decay bar.** "Latest handshake 12 seconds ago" re-renders
   every second and says nothing, because the number has no limit to be measured against. The age
   is drawn instead as a bar filling toward WireGuard's **180-second cutoff** — the point at which
   the peer counts as gone — with a hairline where the next rekey is due. One glance says how much
   room is left; a bar past the hairline is the news. It needs no data the app is not already
   fetching.

## About the design files

`Portway Redesign.dc.html` and `Portway Current.dc.html` in this bundle are **design references
written in HTML**. They are prototypes showing intended look and behaviour. They are **not**
production code and nothing in them should be ported literally.

The task is to **recreate these designs in Portway's existing environment**: Android XML layouts,
databinding, Material 3 theming, Kotlin fragments. Do not introduce Compose. The fork's whole
premise (see `DESIGN.md`) is that it keeps a small diff against upstream so it can rebase onto
upstream security fixes; a Compose rewrite would destroy that.

Open the HTML files in a browser to see the designs. `Portway Current.dc.html` is a faithful
recreation of the app as it stands today, drawn from the repo — use it as the before/after
reference and as proof of the 21dp gutter problem.

## Fidelity

**High fidelity.** Exact colours, type sizes, weights, letter-spacing, radii and spacing are
specified below and are final. Recreate them precisely using Material 3 theme attributes and
`res/values` tokens — not by hard-coding hexes in layouts. Every colour below belongs in
`brand_colors.xml` / generated `colors.xml`, and every layout should reference `?attr/` or
`@color/` as the current layouts already do.

## Design tokens

The redesign is built on the **Nocturne** design system (a dark, compact, low-chroma system:
near-neutral blue-grey ground, Inter at medium weight, 8px radii, the accent spent as a line and a
glow rather than a fill).

### Ground and surfaces

| Role | Value | Replaces |
|---|---|---|
| Ground (flat, no gradient) | `#161826` | `clay_canvas_top` #1D2032 → `clay_canvas_bottom` #141626 gradient |
| Card surface | `#232532` | `clay_surface_hi` #2A3050 / `clay_surface_lo` #1E2338 gradient face |
| Hairline edge | `#3f424d` | the two blurred `setShadowLayer` shadows |
| Rule / divider | `#292b31` | — |
| Text | `#e9e9ed` | `colorOnSurface` #E6E0F2 |
| Muted text | `#9397ab` | `clay_text_muted` #9EA0BE |
| Secondary text | `#b2b6ca` | — |
| Non-text icon / dot | `#75798c` | — |
| Unreachable-peer dot | `#8a5560` | `ping_fail` #FF6B72 |

Do not use pure black or pure white. Elevation on a dark ground is an edge plus ambient darkness —
do not stack shadows.

### Accent — one role, one ramp

The accent is a **single** role carrying a ramp on a shared perceptual lightness scale. It is spent
as a line, a mark or a glow — **never as a large fill**.

| Step | Value | Used for |
|---|---|---|
| `accent` (500) | `#9184d9` | ring stroke, active nav mark, switch fill, rules |
| `accent-300` | `#d2cefd` | text on accent-tinted grounds, primary button label, active nav label, decay-bar fill when fresh |
| `accent-400` | `#b5abfc` | icons on accent tiles, health dot |
| `accent-600` | `#796cbf` | secondary bars in the usage graphs |
| `accent-700` | `#5d5294` | live throughput sparkline body |
| `accent-800` | `#423a6a` | usage-history bars, chip and tile borders |
| `accent-900` | `#2b2741` | accent-tinted tile and chip fills |

**Critical:** these steps must all derive from one seed. The prototype computes the ramp by mixing
the accent toward the ground (`#161826`) for dark steps and toward `#f3f5fe` for light steps at
fixed proportions — see `ramp()` in the redesign file's logic. In the app, `tools/gen_palette.py`
already does the equivalent in CIELCh, so re-seed rather than hand-picking:

```bash
python3 tools/gen_palette.py '<SEED>' --neutral-chroma 10 --neutral-variant-chroma 14 \
    > wireguard-android/ui/src/main/res/values/colors.xml
python3 tools/gen_palette.py '<SEED>' --neutral-chroma 10 --neutral-variant-chroma 14 --check
```

The `--check` run must PASS on all 18 pairs before you ship. `values-night/brand_colors.xml` and
`status_colors.xml` are **not** generator-owned — update those by hand.

Note the accent-to-ground pair is tuned to ~3:1: enough for icons, marks, large text and chrome,
**not** for body copy. Paragraph-size accent text uses `accent-300`.

### Type

Inter throughout, weights 400 and 500 only. **JetBrains Mono is dropped** — figures are Inter with
`android:fontFeatureSettings="tnum"` (which the current layouts already set). That removes
`res/font/jetbrains_mono*` and roughly half of the ~1.1 MB font cost. Do not bolden headings past
500; hierarchy is size and space.

| Role | Size / line | Weight | Tracking | Used for |
|---|---|---|---|---|
| Wordmark | 19sp | 600 | -0.03em | "portway" in the header |
| Display figure | 40–52sp / 1.0 | 500 | -0.035em | session timer, ring centre, hero headline |
| Screen title | 19sp | 500 | -0.03em | "Configs", "Settings", tunnel name |
| Card title | 32sp / 1.1 | 500 | -0.03em | "beta-frankfurt is ready" |
| Row title | 14.5–15sp / 1.3 | 500 | -0.01em | config name, preference title |
| Body | 14.5sp / 1.65 | 400 | — | onboarding body, descriptions |
| Body small | 12.5–13.5sp / 1.5–1.6 | 400 | — | summaries, captions, meta lines |
| Section label | 10.5sp | 500 | +0.14em, uppercase | "LAST HANDSHAKE", "DOWN", "THIS MONTH" |
| Status kicker | 11sp | 500 | +0.2em, uppercase | "PROTECTED", "NOT PROTECTED" |
| Nav label | 11sp | 500 | — | Home / Configs / Settings |

Minimum text contrast is 4.5:1 against whatever is behind it. `#9397ab` measures 6.06:1 on the
ground and 5.2:1 on `#232532`; `#75798c` fails at both and is therefore **icon-and-dot only**.

### Spacing, radius, elevation

- Screen gutter 22dp. Card padding 12–17dp. Between cards 12dp. Between sections 22–28dp.
- Radius: **8dp** for cards, tiles, buttons and inputs (was 24–26dp, and 44dp on the puck).
  6dp for small icon tiles. 999dp only for the account chip.
- No drop shadows. `--shadow-sm` equivalent is `0 0 0 1px #3f424d` — i.e. a 1dp stroke.
- **Rules fade to transparent at their ends** over 48dp a side rather than stopping cleanly. In
  Android this is a horizontal `<gradient>` with transparent start/end stops, not a plain divider.
  Short accent marks (the 18dp nav mark, the 64dp onboarding rule) stay solid.

### Icons

Phosphor (https://phosphoricons.com), 256×256 viewport, single-path where possible, drawn on
`currentColor`. The prototype uses: Shield (home), MapPin (configs), Gear (settings),
CaretRight (row chevron), ArrowLeft (back), Check (import confirmed). Replace the existing
`ic_nav_*.xml` paths with the Phosphor equivalents; keep the file names so the menu doesn't change.

## Screens

### 1. Onboarding (new — three steps)

Purpose: a consumer who was handed a config file has no idea what WireGuard is. Three screens,
each one sentence of honesty, no illustrations.

Layout: 34dp top / 26dp side padding. Three 26×2dp progress marks at the top, gap 6dp; completed
steps are accent, pending are `#292b31`. Content is vertically centred and flush left: kicker
(11sp/500/+0.2em, accent) → title (40sp/500/-0.035em/1.06) → a solid 64×1dp accent rule → body
(15sp/400/1.65, `#b2b6ca`, max ~34 characters per line). A 50dp outlined primary button pinned to
the bottom, then a 38dp ghost row beneath it.

Content, verbatim:

| Step | Kicker | Title | Body | CTA |
|---|---|---|---|---|
| 1 | Welcome | Portway is a door, not a service. | It carries your traffic to a server you or your provider control. There is no account here and nothing to subscribe to — only the config you were given. | Continue |
| 2 | Step 1 of 2 | Add the code your provider sent. | A QR code or a .conf file. Portway reads it once, keeps the private key on this device, and never sends it anywhere. | Scan a QR code |
| 3 | Step 2 of 2 | Android will ask for permission. | A VPN needs the system's consent to route traffic. Android shows its own dialog — Portway cannot skip or fake it. | Understood |

Ghost row on every step: "Skip — I already have a config file" (13.5sp/400, `#9397ab`) → goes
straight to Home.

Step 3's CTA should be wired to the real `VpnService.prepare()` consent dialog, not a mock.

### 2. Scan provider code (replaces the QR path out of the add sheet)

Header: 56dp, ArrowLeft + "Scan provider code" (16sp/500). Ground darkens to `#0f1018`.

Viewfinder: 250×250dp, 8dp radius, four 30×30dp 2dp accent corner marks inset 16dp, and a 2dp
accent scan line across the top that breathes (opacity 0.35 → 0.8, 1.6s ease-in-out, infinite).
In the app this frame is the camera preview; the prototype fills it with a diagonal hatch.

Below: "Point the camera at the QR code your provider sent you. Nothing leaves the phone."
(14.5sp/400/1.6, centred, max ~30ch). Then a secondary route: "or import a .conf file instead".

### 3. Import confirmation (new)

After a successful decode, before saving. 38dp accent-tinted tile (fill `accent-900`, border
`accent-800`, 8dp radius) with a Check icon in `accent-400` → title "{name} is ready" (32sp/500)
→ "One peer, routing all traffic. Its IP puts it in {city} — give it a name you will recognise in
the list." → a focused name field (46dp, 8dp radius, 1dp accent border, `#232532` fill) prefilled
with the resolved city → a summary card (1dp `#3f424d`, 8dp radius, rows separated by 1dp
`#292b31`) listing Endpoint / Routes / DNS / Plan → "Save and connect" primary, "Not now" ghost.

This is the screen that currently does not exist: upstream drops a scanned config straight into
the list with a naming dialog, which tells a consumer nothing about what they just added.

### 4. Home (replaces `connect_fragment.xml`)

Header 56dp, 22dp side padding: "portway" wordmark left; an account chip right (4dp/9dp padding,
999dp radius, 1dp `accent-800` border, `accent-300` text, 11.5sp/500) reading "23 days left" —
tapping it opens Settings.

**Ring hero.** 236dp square, centred. Two concentric `circle`s at r=106, `stroke-width` 1.5dp:
a track in `#292b31` and a progress stroke in the accent. State is carried entirely by the
stroke's dash:

| Phase | Dash | Rotation | Glow |
|---|---|---|---|
| idle | `0 C` (invisible) | none | opacity 0 |
| connecting / disconnecting | `0.22C 0.78C` | 1.1s linear infinite spin | opacity 0.6 |
| connected | `C 0` (closed) | none | opacity 1, breathing 0.35 → 0.8 over 3.4s |

where `C = 2πr`. Stroke colour transitions over 0.4s. Behind the ring, inset 20dp, a radial
gradient from a 66%-toward-ground mix of the accent to transparent at 68%.

Ring centre, stacked and centred: status kicker ("PROTECTED" in accent / "NOT PROTECTED" in
`#9397ab` / "CONNECTING" / "DISCONNECTING") → a 40sp/500/-0.035em tabular figure (the session
timer `HH:MM:SS` when connected, "···" while busy, "Off" when idle) → a 12.5sp caption
("Frankfurt, DE · 42 ms").

Tapping anywhere in the 236dp square toggles the tunnel. In the app this must remain a plain
`View` with an `onClick` — DESIGN.md records that a nested `MotionLayout` swallows touch events
over its bounds, which made the control keyboard-only with no crash to explain it. Property
animators only.

Then, in order: a one-line status sentence (13.5sp/400, centred, `#9397ab`) — "Your traffic
surfaces in Frankfurt, resolved from the peer's IP." when connected, "Nothing is encrypted right
now. Tap to connect." when idle; a fading rule; the **handshake decay bar** (below); the current-config
row; the two throughput tiles; the monthly usage graph.

**Config row.** 1dp `#3f424d`, 8dp radius, `#232532` fill, 14/16dp padding, 13dp gap: a 32dp
6dp-radius accent-tinted tile holding a MapPin in `accent-400`, then config name (14.5sp/500) over
"Frankfurt, DE · from 5.9.44.12" (12sp/400, `#9397ab`), then a CaretRight in `#75798c`. Opens Configs.

**Throughput tiles.** Two, `flex:1`, 12dp gap, 1dp `#3f424d`, 8dp radius, 12/14dp padding:
section label ("DOWN" / "UP") over an 18sp/500 tabular figure. Both read "—" when idle.

**Monthly usage graph.** Section label "THIS MONTH" left, "12.4 of 30 GB" (12.5sp, tabular) right.
Then a 78dp band of 30 bars, `flex:1` each, 3dp gap, 1dp top radius, bottom-aligned on a 1dp
`#292b31` baseline; bars are `accent-800` with today's bar in the accent. Axis row beneath:
"1 Aug" / "today" (11sp, `#9397ab`). Then a 4dp progress rail (`#292b31`, accent fill at 41%) and
"Renews 29 September · 17.6 GB remaining".

### 5. The handshake decay bar (new — appears on Home, the detail screen and the hero)

Replaces both the handshake row in `connect_fragment.xml`'s sunken card and the
"Latest handshake …" `TextView` in the detail header. It needs **no new data**: the age comes from
`last_handshake_time`, which the polling loop already reads.

Two constants define the whole graphic:

| Constant | Value | Meaning |
|---|---|---|
| `HANDSHAKE_LIMIT` | 180s | WireGuard's cutoff — the peer counts as gone. The full width of the track. |
| `REKEY_DUE` | 118s | Nominal rekey interval. Position of the hairline, at 65.6% of the track. |

Structure, top to bottom:

1. **Header row**, baseline-aligned: section label "LAST HANDSHAKE" left (10.5sp/500, +0.14em,
   uppercase, `#9397ab`); the age right (11.5sp/500, tabular, state ink) — "12s ago", "1m 58s ago",
   or "none in 3m" when disconnected.
2. **Track band**, 12dp tall, 14dp below the header (15dp on the hero). Inside it: a 6dp-tall,
   3dp-radius track in `#2f313c` inset 3dp from the band's top, holding a left-anchored fill of the
   same radius in the state ink, width `min(100, age/180 × 100)`%, transitioning **0.9s linear**;
   and over it a **1dp `#6b7183` hairline** spanning the band's full 12dp height at 65.6%.
   The hairline must sit outside the track's clip so it reads as a scale mark, not a fill segment.
3. **Axis row**, 12dp tall, 9dp below: "0s" flush left, "rekey due" **centred on the hairline**
   (not on the row — position it at 65.6% and translate it -50%), "3m gone" flush right.
   All 11sp/400 `#9397ab`.
4. **Sentence**, 12dp below, 12.5sp/400/1.5 `#9397ab`, one line per state.

State ink and sentence:

| State | Condition | Ink | Sentence |
|---|---|---|---|
| fresh | connected, age ≤ 150s | `accent-300` `#d2cefd` | Handshake inside the last two minutes |
| late | connected, age > 150s | `#c8a24a` | Rekey overdue — still inside the 3m window |
| silent | not connected | `#8a5560` | Last handshake over three minutes ago |

`#c8a24a` is the one non-ramp colour in the design, and it is deliberate: an overdue rekey is a
warning and the mono accent ramp cannot carry that meaning. It appears nowhere else.

The bar is the only element on these screens that updates per second, and it updates a **width**,
not text — no layout pass, no relayout of the sentence, nothing for a screen reader to re-announce
on every tick. Drive it from the existing polling loop; do not add a second timer.

Implement as one `View` (`widget/HandshakeDecayView.kt`) drawing four primitives on a `Canvas`
(track, fill, hairline, and nothing else — the labels are real `TextView`s so they stay
selectable and scale with font settings), exposed as a small compound view so Home, the detail
screen and the hero share it the way `ThroughputMeter` is shared.

The bar and the `auto_reconnect` watchdog answer the same question — "up but not handshaking" — so
they should read the same source. The bar is the visual form of the condition that feature already
acts on; the hairline is roughly where the watchdog starts caring.

Accessibility: the graphic is decorative, so mark the track, fill and hairline
`importantForAccessibility="no"` and put the meaning on the container as a
`contentDescription` that states the age in words plus the state ("Last handshake 12 seconds ago,
inside the rekey window"), with `accessibilityLiveRegion="polite"`. The current screen already
uses live regions because values set imperatively fire no accessibility event.

### 6. Configs (replaces `tunnel_list_fragment.xml` + `tunnel_list_item.xml`)

Header 56dp: "Configs" (19sp/500) left, an outlined "Add" button right (7/12dp padding, 8dp radius,
1dp accent border, `accent-300` label, 12.5sp/500). **The FAB is gone** — with only three
destinations and an add action, a floating button over a list of four rows is more chrome than the
list.

Rows are **not cards**. Each is 15dp vertical padding, separated by a 1dp `#292b31` top border
(including one closing the last row), 13dp gap: a 9dp state dot → config name (15sp/500) over a
meta line (12.5sp/400, `#9397ab`) → ping (12.5sp/400, tabular) → CaretRight.

Dot: accent when that config is up, `#595d6c` at rest, `#8a5560` when the peer is unreachable.
The rows are deliberately quiet — the eye should go to the one that is connected.

Meta line carries the resolved geography, not the interface name twice:

| Name | Meta | Ping |
|---|---|---|
| beta-frankfurt | Connected · Frankfurt, DE | 42 ms |
| alpha-ams | Amsterdam, NL · 45.83.107.4 | 58 ms |
| home-nas | No route to peer · 81.4.22.9 | — |
| work | London, GB · 51.15.9.201 | 91 ms |

Tapping a row switches to that config; tapping the chevron opens its detail. Footnote beneath the
list (13sp/400/1.6, `#9397ab`): "These are the configs you were given — Portway has no server list
of its own. The city under each name is resolved from the peer's IP, not chosen from a menu.
Tapping one switches to it; only one is up at a time unless you allow several in Settings."

**Two things must not change here.** Selection stays keyed by **tunnel name, not adapter
position** — upstream's `HashSet<Int>` went stale the moment the adapter started reporting
fine-grained changes and deleted the wrong tunnels. And the row root must remain
`MultiselectableRelativeLayout`, because `TunnelListFragment` casts to that type in
`onConfigureRow` and `viewForTunnel`. It currently extends `ClayCardView`; point it at whatever
replaces that (`FrameLayout` with a background drawable is enough now) and keep the class name.
Multi-select paints the row face rather than drawing a border.

Empty state: keep the existing structure but restyle — the mark in an `accent-900` circle with an
`accent-800` edge (not a `primaryContainer` flood), "No configs yet" (24sp/500), and a sentence
pointing at the Add button in the header rather than at a FAB that no longer exists.

### 7. Config detail (replaces `tunnel_detail_fragment.xml`)

Header: ArrowLeft + config name (19sp/500) — the action bar already shows the tunnel's own name
inside its detail, keep that.

Body, flush left, 22dp gutter: status kicker → session timer (34sp/500, tabular) → the status
sentence → the two throughput tiles, each with a per-session total beneath the rate ("4.1 GB this
session", 11.5sp) → the **handshake decay bar**, 28dp below the tiles → "LAST 14 DAYS" and a 96dp bar band
(14 bars, 6dp gap, 2dp top radius, `accent-800` with today in the accent, axis "23 Aug" / "5 Sep")
→ "HEALTH" rows → the **Configuration disclosure** → actions.

Health rows: 12dp vertical padding, 1dp `#292b31` top border, a 7dp dot, label (13.5sp/400,
`#9397ab`), value (13.5sp/400, `#e9e9ed`, tabular). Ping (dot `accent-400` when connected,
`#595d6c` otherwise) · Endpoint `5.9.44.12:51820` · Resolved location `Frankfurt, DE`. The
"Latest handshake" row is **gone** — the decay bar replaced it.

**Configuration disclosure.** A 12dp row bordered top and bottom in `#292b31`: "Configuration"
(13.5sp/500) with a "Show" / "Hide" hint (12.5sp/400, `#9397ab`). Collapsed by default. Expanded,
it reveals label/value pairs — label 11.5sp/400 `#9397ab` over value 13.5sp/400 `#e9e9ed` with
`word-break` on, each separated by a 1dp `#292b31` bottom border, animating in with a 16dp rise
over 0.3s: Public key, Addresses, DNS servers, Allowed IPs, Persistent keepalive, MTU. Keep
upstream's tap-to-copy (`ClipboardUtils::copyTextView`) on each value.

This is the main structural move on this screen: the config listing that currently occupies the
whole screen becomes one collapsed row, because a consumer never needs it and a technical user
needs it once.

Actions: an outlined Connect/Disconnect filling the row, a bordered "Edit" beside it, then
"Remove this config" as a plain 13sp centred text row.

### 8. Settings (restyles `preferences.xml` + `preference_clay.xml`)

Header: "Settings" (19sp/500).

**Account card at the top** (new): 1dp `accent-800`, 8dp radius, `accent-900` fill, 15/16dp
padding. Name (15sp/500) left, plan (12.5sp/400, `accent-300`) right; a 4dp progress rail (track
`rgba(233,233,237,.14)`, fill `accent-400` at 41%); then "12.4 GB used" / "23 days left"
(12sp/400, `#b2b6ca`, tabular) on a space-between row. This is the one accent-tinted fill on the
screen, and it is small.

Preference rows drop from 72dp min-height to **15dp vertical padding**, separated by 1dp `#292b31`
top borders, with **no icon column** — the icon frame in `preference_clay.xml` was carrying no
information. Title 14.5sp/500, summary 12.5sp/400/1.5 in `#9397ab`. Switch: 42×24dp, 12dp radius,
1dp border; off = transparent fill, `#595d6c` border, `#9397ab` 16dp knob at 4dp; on = accent fill
and border, `#161826` knob at 22dp; knob position transitions 0.25s
`cubic-bezier(.2,.8,.2,1)`. Value-only rows show 13sp `#9397ab` text instead of a switch.

Category labels: 10.5sp/500/+0.14em uppercase in the **accent**, 26dp above / 4dp below.

Regrouped into three categories (from the current Connection / Protection / Appearance / Tools /
About):

**Protection** — Kill switch ("Block all traffic if the tunnel drops") · Reconnect stalled tunnels
("If a tunnel is up but stops handshaking, restart it") · Connect at startup ("Bring the last
location up when the phone boots").

**Advanced** — Allow several locations at once ("Off means turning one on turns the others off") ·
Provider panel ("Where account and usage figures come from", value "Not set") · Export locations
("Save every config to an encrypted zip").

**App** — Quick Settings tile ("A one-tap toggle in the notification shade") · Application log
("Useful if something will not connect").

Footer, above a `#292b31` rule: "Portway 1.0.1 · WireGuard 1.0.20250521 / Free software under the
GPL. Your keys never leave this device." (12.5sp/400/1.6, `#9397ab`).

Three constraints from DESIGN.md hold: **keep the preference keys unchanged** so
`PreferencesPreferenceDataStore` and `UserKnobs` are untouched; **keep the exact ids** androidx
binds by in the row layout (`@android:id/icon`, `@android:id/title`, `@android:id/summary`,
`@+id/icon_frame`, `@android:id/widget_frame`) even where the icon is no longer shown, or
`onBindViewHolder` silently does nothing; and **do not add
`initialExpandedChildrenCount`** — its arithmetic counts top-level children and adding categories
silently hides preferences behind "Advanced". Keep `duplicateParentState` on the text so disabled
preferences look disabled.

### 9. Bottom navigation (replaces the floating island in `main_activity.xml`)

The island becomes a **flat bar on the ground**: full width, 22dp side padding, 14dp bottom
padding, 9dp top padding, background `#161826` (the same as the ground — it is not a separate
surface), with a **fading 1dp rule** across the top. No fill, no border, no radius, no shadow.

Three items, `flex:1`, each a centred column with 6dp gaps: an **18×2dp solid accent mark**
(transparent when inactive), then a 22dp Phosphor icon, then an 11sp/500 label. Active icon and
label are `accent-300`; inactive are `#75798c` (icon) and `#9397ab` (label — the icon can sit at
3:1, the label cannot).

Labels: **Home · Configs · Settings**.

This deletes a lot: the nav puck bitmaps, `tools/clay/navpuck.py`, `nav_bar_bg.xml`,
`nav_item_background.xml`, and the density buckets under `drawable-*`/`drawable-night-*`.
Material's active indicator cannot draw a top-centre mark, so keep
`isItemActiveIndicatorEnabled = false` (set in `MainActivity` — there is no XML attribute) and
deliver the mark through a small `itemBackground` drawable, or replace the
`BottomNavigationView` with a plain `LinearLayout` of three items, which at three destinations is
honestly simpler.

Two integration points survive: `MainActivity` must keep zeroing the gesture inset that
`NavigationBarView` consumes as internal padding even with
`paddingBottomSystemWindowInsets="false"`; and `@dimen/bottom_nav_height` (the content inset) drops
from 108dp to about **72dp** now that there is no float margin or FAB clearance to cover. Content
containers take that as an explicit bottom margin — **do not** use `layout_dodgeInsetEdges`, which
translates the whole container upward and pushed the first row behind the action bar.

## Interactions and motion

| Trigger | Behaviour |
|---|---|
| Tap ring / hero / primary button while idle | phase → `connecting`, ring dashes to 22% and spins at 1.1s linear, glow to 0.6; after the first handshake → `connected`, dash closes, spin stops, glow breathes |
| Tap while connected | phase → `disconnecting` (0.9s in the prototype), then `idle`; session timer resets |
| Ring stroke colour | 0.4s ease |
| Glow opacity | 0.5s ease; breathing is 3.4s ease-in-out infinite between 0.35 and 0.8 |
| Decay-bar fill width | 0.9s linear, once per poll; resets to 0 on a new handshake |
| Line hero rule (variant 1a) | `scaleX` from left origin: 0.06 idle → 0.45 connecting → 1 connected, 0.9s `cubic-bezier(.2,.8,.2,1)` |
| Thumb hero button (variant 1c) | while busy, an accent sweep gradient traverses the button, `pw-sweep` 1.1s linear infinite |
| Onboarding step change | content rises 16dp and fades in over 0.45s `cubic-bezier(.2,.8,.2,1)` |
| Config disclosure | 16dp rise, 0.3s ease |
| Switch knob | `left` 0.25s `cubic-bezier(.2,.8,.2,1)`, fill and border 0.25s ease |
| QR scan line | opacity 0.35 → 0.8, 1.6s ease-in-out infinite |

Every animation is a property animator on two or three properties. **No `MotionLayout`** — a
nested one swallows touch events over its bounds, and the transition here does not need it. Cancel
infinite animators in `onStop`, as the current `ConnectFragment` already does for the breathe.

Interactive states must be themed, never browser/platform defaults: every tappable element gets a
hover/pressed tint one step along the accent ramp, and keyboard focus is a 2dp accent ring
(`:focus-visible` in the prototype; `state_focused` in Android). Disabled controls drop to 45%
opacity.

## State

| State | Type | Notes |
|---|---|---|
| `phase` | `connected` / `connecting` / `disconnecting` / `idle` | Already synthesized in the UI, since `Tunnel.State` is only DOWN/UP. `tunnel.state` stays the source of truth except while a request is in flight, so quick-tile and always-on changes still render. |
| session seconds | monotonic | Already `ObservableTunnel.connectedSinceElapsedRealtime`, stamped inside `onStateChanged`. Tunnels already running at process start show connected with no duration — keep that honest gap. |
| rx / tx rate | EMA over cumulative counters | Already `util/ThroughputMeter.kt`; keep discarding implausible gaps and reseeding. |
| handshake age | seconds since `last_handshake_time` | **Already available** — the polling loop reads it. Drives the decay bar; no history buffer needed. |
| **resolved geography** | city / country / IP per config | **New** — see below. |
| usage history | daily byte totals, 30 days | **New** — from the provider panel, or accumulated locally per config. |
| account | plan, days left, bytes used | Already fetched from the panel by public key. |
| screen | home / configs / detail / settings / onboard / scan / confirm | Fragment destinations; onboarding and confirm are new. |
| `configOpen` | boolean | Detail-screen disclosure, collapsed by default. |
| preferences | as today | Same DataStore keys. |

**Resolving geography has a privacy decision in it.** Calling a public IP-geo API from the device
tells a third party which endpoint your users connect to — for this app's likely user base that is
the opposite of the point. Two acceptable routes: have the provider panel return the city alongside
the account data you already fetch by public key (no new trust relationship), or bundle a
country-level MMDB and resolve offline. Prefer the panel. Whatever you choose, the UI must degrade
to just the IP when resolution is unavailable — never invent a city.

## Launcher icon

The icon is specified separately in **`ICON.md`** — the chosen direction is an archway with a
flush sill on a `#262a60` ground, one path, `evenOdd`, no group transform, plus the `<monochrome>`
entry the current icon is missing. Read it alongside Phase 9 of `IMPLEMENTATION.md`.

## Assets

No bitmaps, no image files, no generated drawables. Everything is a vector path, a `<shape>`, or a
`<gradient>`. Specifically **deleted**: `tools/clay/puck.py`, `tools/clay/navpuck.py` and every
bitmap they generate under `drawable-*` / `drawable-night-*`; `nav_bar_bg.xml`;
`nav_item_background.xml`; `res/font/jetbrains_mono*`.

Icons are Phosphor, redrawn into the existing `ic_nav_*.xml` / `ic_*.xml` files so nothing that
references them by name has to change. Fonts: Inter only, static instances at 400 and 500 (not the
variable font — variable fonts need API 26 and this floor is 24), already bundled under
`res/font` and already SIL OFL so they can ship in a redistributed APK.

## Files in this bundle

| File | What it is |
|---|---|
| `Portway Redesign.dc.html` | The redesign. Four phone frames: three Connect hero variants (`1a` line, `1b` ring, `1c` thumb-zone) and `1d`, the whole app working end to end. Everything is tappable. |
| `Portway Current.dc.html` | The app as it stands today, recreated from the repo. The before state, and the evidence for the 21dp gutter. |
| `Portway Icon.dc.html` | The icon board: four directions on the 108 adaptive canvas with the 66dp safe and 72dp visible guides drawn, masked to squircle and circle, at 48/32/24, and as the monochrome variant. Option `2b` is the chosen one. |
| `ICON.md` | The chosen icon, specified to paste-ready `pathData`. |
| `IMPLEMENTATION.md` | A file-by-file plan against the real Android tree, in the order to do it. |
| `android-frame.jsx`, `support.js`, `_ds/` | What the two HTML files need in order to render. Not part of the design. |

Read `IMPLEMENTATION.md` next.

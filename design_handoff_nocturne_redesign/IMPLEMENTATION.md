# Implementation plan — Android

Ordered so the app is shippable after each phase. Every path is relative to
`wireguard-android/ui/src/main/`.

## Phase 1 — palette (no layout changes, whole app moves)

1. Re-seed:
   ```bash
   python3 tools/gen_palette.py '<SEED>' --neutral-chroma 10 --neutral-variant-chroma 14 \
       > wireguard-android/ui/src/main/res/values/colors.xml
   python3 tools/gen_palette.py '<SEED>' --neutral-chroma 10 --neutral-variant-chroma 14 --check
   ```
   All 18 pairs must PASS. Never hand-edit `colors.xml`.
2. `res/values-night/brand_colors.xml` — hand-edited, not generator-owned:
   - `clay_canvas_top` and `clay_canvas_bottom` both → `#161826` (the gradient goes flat).
   - `clay_surface_hi` / `clay_surface_lo` → a single `#232532`.
   - `clay_stroke` → `#3f424d`.
   - `clay_text_muted` → `#9397ab`.
   - add `rule` `#292b31`, `text_secondary` `#b2b6ca`, `icon_muted` `#75798c`.
   - add the accent ramp: `accent_300` `#d2cefd`, `accent_400` `#b5abfc`,
     `accent_600` `#796cbf`, `accent_700` `#5d5294`, `accent_800` `#423a6a`,
     `accent_900` `#2b2741`.
   - `ping_fail` → `#8a5560`.
3. Mirror the same names in `res/values/brand_colors.xml` for the light theme.
4. `res/values-night/status_colors.xml` — `status_active` → `accent_300`,
   `status_inactive` → `#9397ab`. Databinding can't read theme attrs here, which is why this file
   exists; keep it in step with the ramp.
5. `res/drawable/aurora_canvas.xml` — collapse to a solid `#161826`, or delete it and set the
   background colour directly. Same for `window_background.xml` so the action-bar strip matches.

Rename note: the `clay_*` names are now misnomers. Renaming them is a wide but purely mechanical
diff; either do it in one commit at the end of Phase 2 or leave them. Do not do it mid-phase.

## Phase 2 — retire the clay material

This is the big one. It deletes more than it adds.

1. `java/com/wireguard/android/widget/ClayCardView.kt` — either delete it and replace every usage
   with `MaterialCardView`, or reduce it to a `FrameLayout` with a background drawable. Either way
   the **21dp self-padding must go**. That padding existed only because `setShadowLayer` draws
   outside the view bounds; with no shadow there is nothing to pad for.
2. New `res/drawable/surface_card.xml`: `<shape>` rectangle, solid `#232532`,
   `<corners android:radius="8dp">`, `<stroke android:width="1dp" android:color="#3f424d">`.
3. `res/values/styles.xml`:
   - `WireGuardTheme.MaterialCardView` — `cornerRadius` 26dp → 8dp, `contentPadding` 24dp → 14dp,
     `cardElevation` 8dp → **0dp**, `strokeColor` → `#3f424d`,
     `cardBackgroundColor` → `#232532`. Drop the `outlineAmbientShadowColor` /
     `outlineSpotShadowColor` overrides — with no elevation they do nothing.
   - `Portway.TextInput` — all four box corner radii 16dp → 8dp.
   - Delete the JetBrains Mono text appearances (`Portway.Text.Mono`, `Portway.Text.MonoTitle`);
     replace their usages with the Inter equivalents and keep `fontFeatureSettings="tnum"` at each
     site. Then delete `res/font/jetbrains_mono*`.
4. `res/layout/tunnel_list_item.xml` — `MultiselectableRelativeLayout` **keeps its class name**
   (`TunnelListFragment` casts to it in `onConfigureRow` and `viewForTunnel`) but now extends
   `FrameLayout`. Selection still paints the face via `faceOverride`; wire that to a background
   tint instead of a gradient override.
5. Every layout that used a `ClayCardView` needs its `layout_margin` values raised, because the
   21dp gutter that was doing the spacing is gone: `connect_fragment.xml`,
   `tunnel_detail_fragment.xml`, `tunnel_list_item.xml`, `tunnel_editor_fragment.xml`. Target
   22dp screen gutters and 12dp between cards.
6. New `res/drawable/rule_fading.xml` — a horizontal `<gradient>` with transparent start and end
   stops and `#292b31` in the centre, used as a 1dp-high `View` background wherever the design
   calls for a rule. Nocturne's rules fade over 48dp a side; short accent marks stay solid.

After this phase the app looks like the redesign's material even though no screen has been
restructured. Screenshot-diff Connect and the list before moving on.

## Phase 3 — bottom navigation

1. `res/layout/main_activity.xml` — drop `layout_marginHorizontal`/`layout_marginBottom` and the
   `nav_bar_bg` background from the `BottomNavigationView`; background becomes `#161826`. Add a
   1dp `View` above it using `rule_fading`. At three destinations, consider replacing the
   `BottomNavigationView` with a plain `LinearLayout` of three items — it removes the whole class
   of indicator/inset fights below.
2. If you keep Material's view: leave `isItemActiveIndicatorEnabled = false` in `MainActivity`
   (code-only, no XML attribute), and deliver the 18×2dp accent mark through a new
   `itemBackground` drawable — a layer-list with a top-centre gravity item. Delete
   `nav_item_background.xml` and `nav_bar_bg.xml`.
3. `res/values/dimens.xml` — `bottom_nav_height` 108dp → **72dp**. Content containers keep taking
   it as an explicit bottom margin. Do **not** switch to `layout_dodgeInsetEdges`.
4. Keep `MainActivity`'s `setOnApplyWindowInsetsListener` override that zeroes the gesture inset
   `NavigationBarView` consumes as internal padding.
5. `res/menu/bottom_nav.xml` — retitle `dest_tunnels` to `@string/nav_configs`. Ids unchanged.
6. Redraw `ic_nav_connect.xml` (Phosphor Shield), `ic_nav_tunnels.xml` (MapPin),
   `ic_nav_settings.xml` (Gear) — same file names, new paths, `viewportWidth/Height` 256.
7. Delete `tools/clay/navpuck.py` and the generated nav-puck bitmaps under
   `drawable-*` / `drawable-night-*`.

## Phase 4 — the hero

1. New `java/com/wireguard/android/widget/ConnectRingView.kt`: a `View` that draws one
   `Canvas.drawArc` with a stroked `Paint` (`strokeWidth` 1.5dp, `strokeCap = ROUND`) plus a
   `RadialGradient` glow behind it. Expose `phase`, animate sweep with a `ValueAnimator` and
   rotation with an `ObjectAnimator`. No software layer, no bitmap, no density buckets.
2. `res/layout/connect_fragment.xml` — replace the 174dp `ClayCardView` puck (and its
   `connect_core` `FrameLayout` + `connect_icon`) with a 236dp `ConnectRingView`, keeping
   `android:onClick="@{fragment::onConnectClicked}"` on it. The status/timer text moves inside the
   ring's centre.
3. Delete the two placeholder `ImageView`s (`glow`, `sweep_ring`) that the current layout keeps
   in the tree for an animator the soft direction no longer uses, plus `connect_core_idle.xml`,
   `connect_core_active.xml`, `connect_ring*.xml`, `hero_*.xml`, `aurora_glow.xml`,
   `stat_tile_background.xml`, and `tools/clay/puck.py` with its bitmaps.
4. Keep the accessibility work: `accessibilityLiveRegion="polite"` on status and timer, and a
   phase-dependent `contentDescription` on the control, since its meaning inverts with state.
5. **No `MotionLayout`.** A nested one swallows touch events over its bounds; the control became
   keyboard-only with no crash to explain it. Property animators only, cancelled in `onStop`.

## Phase 5 — the handshake decay bar

Smaller than it was scoped to be: the earlier heartbeat lane needed a handshake history, this needs
only the age of the latest one, which the polling loop already has. No `HandshakeLog`, no ring
buffer.

1. New `java/com/wireguard/android/widget/HandshakeDecayView.kt` — a compound view holding two
   `TextView`s (label, age), a custom `View` that draws track + fill + hairline, and an axis row of
   three `TextView`s. Expose `ageSeconds: Long?` (null = disconnected) and derive ink, fill width
   and sentence from it per the table in `README.md`. Constants `HANDSHAKE_LIMIT = 180` and
   `REKEY_DUE = 118` live here.
2. Position "rekey due" with a `ConstraintLayout` `layout_constraintGuide_percent="0.656"` guideline
   and centre the label on it — do not use a three-item weighted row, which lands the label at 50%
   and points it at nothing.
3. Feed `ageSeconds` from the existing `repeatOnLifecycle(RESUMED)` polling loop in
   `ConnectFragment` and `TunnelDetailFragment`, from the `getStatisticsAsync()` call already being
   made. Do **not** touch the `@Bindable statistics` getter — its getter launches a refetch as a
   side effect.
4. Animate the fill with a single `ValueAnimator` over 0.9s linear on each poll, cancelled in
   `onStop`. The age `TextView` updates in the same pass; nothing else on the screen re-lays out.
5. Add it to `connect_fragment.xml` and `tunnel_detail_fragment.xml` (28dp below the throughput
   tiles). Remove the handshake row from `connect_fragment.xml`'s sunken card and the
   "Latest handshake …" `TextView` from the detail header — the bar replaces both.
6. `contentDescription` on the container stating age and state in words, with
   `accessibilityLiveRegion="polite"`; `importantForAccessibility="no"` on the drawn view.
7. Point `auto_reconnect`'s watchdog at the same age value, so the feature and its visualisation
   cannot disagree.

## Phase 6 — Configs list

1. `res/values/strings.xml` — `nav_tunnels` → "Configs"; add `nav_configs`;
   `tunnel_list_empty_title` → "No configs yet"; `tunnel_list_placeholder` → point at the header
   Add button, not a FAB. Leave every preference key and databinding variable name alone.
2. `res/layout/tunnel_list_item.xml` — rows stop being cards: no background, 15dp vertical
   padding, a 1dp `#292b31` top border, the 9dp state dot, name over meta, ping, chevron. Keep the
   `ToggleSwitch` only if you want the inline toggle; the design replaces it with tap-to-switch and
   a chevron to detail.
3. `res/layout/tunnel_list_fragment.xml` — remove the `FloatingActionButton`; add the outlined
   "Add" to the header. Restyle the empty state per `README.md`.
4. Meta line needs the resolved city — see Phase 8.
5. **Do not** revert selection to adapter positions. It is keyed by tunnel name because upstream's
   `HashSet<Int>` went stale as soon as the adapter reported fine-grained changes and deleted the
   wrong tunnels. Keep `setHasStableIds(true)` and the `onItemRange*` handling; DiffUtil is still
   the wrong tool here (`ObservableTunnel` is a mutable `BaseObservable` with no value equality,
   so `areContentsTheSame` would always be true and state changes would never render).

## Phase 7 — detail, settings, onboarding, import

1. `res/layout/tunnel_detail_fragment.xml` — the config listing collapses behind a
   "Configuration" disclosure row; the dashboard header, the 14-day bar band and the health rows
   come above it. Keep `ClipboardUtils::copyTextView` on every value.
2. `res/layout/preference_clay.xml` — 72dp min-height → 15dp vertical padding; hide the icon
   column but **keep every id** (`@android:id/icon`, `@android:id/title`, `@android:id/summary`,
   `@+id/icon_frame`, `@android:id/widget_frame`) or `onBindViewHolder` silently does nothing.
   Keep `duplicateParentState` on the text.
3. `res/xml/preferences.xml` — regroup into Protection / Advanced / App per `README.md`. **Keys
   unchanged.** Still no `initialExpandedChildrenCount`. Add the account card as a custom
   `Preference` at the top, or as a header view above the `RecyclerView`.
4. New onboarding: three fragments (or one with a step arg) shown on first run, gated by a new
   `UserKnobs` flag written the same way `migrateThemeMode()` persists the effective theme — write
   the flag, don't infer it.
5. New import-confirmation fragment between the QR decode and the save, replacing the bare naming
   dialog. Restyle `add_tunnels_bottom_sheet.xml` to 8dp geometry and the new type.

## Phase 8 — geography

1. Preferred: extend the existing panel response (already fetched by public key, with
   `panel_url` already a preference) to include city and country per peer. No new trust
   relationship, no new network call.
2. Alternative: bundle a country-level MMDB and resolve offline.
3. Do **not** call a public IP-geo API from the device — it tells a third party which endpoint your
   users connect to.
4. Degrade to the bare IP when resolution is unavailable. Never invent a city.

## Phase 9 — launcher icon

Independent of every other phase; can land first if you want a visible change early.
Full spec in `ICON.md`.

1. `res/drawable/ic_launcher_foreground.xml` — swap `pathData` to
   `M36,80 L36,48 A18,18 0 0 1 72,48 L72,80 Z M44,72 L44,48 A10,10 0 0 1 64,48 L64,72 Z`,
   `fillColor` `#d2cefd`, keep `fillType="evenOdd"` and the one-fill comment.
2. `res/values/ic_launcher_background.xml` — `#123A5F` → `#262a60`.
3. `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` — add
   `<monochrome android:drawable="@drawable/ic_launcher_foreground" />`. Missing today, which is
   why Android 13+ themed icons fall back to the colour tile.
4. Delete the `mipmap-hdpi` … `mipmap-xxxhdpi` PNGs.
5. Reuse the path in `ic_portway_mark.xml` and `ic_tile.xml`; redraw `tv_logo_banner.xml` from it.
   Leave `ic_nav_connect.xml` as the Phosphor Shield — the tab and the app should not be the same
   glyph.

## Verification

- `gen_palette.py --check` passes on all 18 pairs.
- Small text (< 18sp, weight < 600) measures ≥ 4.5:1 against what is behind it. `#9397ab` on
  `#161826` is 6.06:1 and on `#232532` is 5.2:1; `#75798c` fails both and is icons and dots only.
- `adb shell input tap` on the ring toggles the tunnel (not just `keyevent ENTER` — that is the
  signature of the `MotionLayout` touch bug).
- `uiautomator dump` shows current status, timer and handshake age text, proving the live regions
  fire.
- Zero `getStatisticsAsync` calls while backgrounded.
- Select two of four configs, rotate, delete, confirm exactly those two are gone.
- Under a circular launcher mask the icon is not shaved, and the arch opening is still open at
  24dp (the test the old keyhole failed). Themed icons on: one flat colour, still legible.
- Rebase cleanly onto upstream `wireguard-android` — the diff should still be small and mostly
  `res/`.

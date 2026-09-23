# Portway — build, sign, distribute

A fork of [wireguard-android](https://git.zx2c4.com/wireguard-android) (Apache-2.0) that adds the
direct config-import entry point upstream omits. See `DEEPLINK.md` for the URL contract.

## Layout

```
wireguard-app/
├── env.sh                  # source this before any build
├── toolshim/sha256sum      # GNU sha256sum (macOS ships one without GNU -c)
├── keystore/               # release signing key — BACK THIS UP, never commit
├── wireguard-android/      # the fork (git clone of upstream)
├── DEEPLINK.md
└── BUILD.md
```

## Toolchain (already installed on this machine)

- JDK 17 — `brew install openjdk@17` (keg-only, no sudo)
- Android SDK at `~/Library/Android/sdk` (cmdline-tools, platform 36, build-tools 36.0.0)
- NDK 28.2.13676358 + CMake 3.22.1 — installed automatically by AGP on first build
- `brew install flock coreutils` — upstream's Go bootstrap Makefile needs both

Go is **not** a prerequisite: `tunnel/tools/libwg-go/Makefile` downloads its own pinned Go 1.24.3
into `~/.gradle/caches/golang/` and verifies its SHA-256.

## Build

```bash
source ~/dev/wireguard-app/env.sh
cd ~/dev/wireguard-app/wireguard-android
./gradlew assembleRelease      # -> ui/build/outputs/apk/release/ui-release.apk (signed)
```

Signing is opt-in: `ui/build.gradle.kts` reads `keystore.properties` (git-ignored) if present and
otherwise emits an unsigned APK, so a fresh clone builds without secrets.

## The signing key

`keystore/portway-release.jks`, alias `portway`, RSA-4096, valid until 2054.
Backup and restore-verification steps are in `KEYSTORE.md`.
SHA-256 `EE:63:79:85:53:25:48:A3:3F:38:5E:47:BB:78:F7:56:BF:0F:48:E7:B6:4E:E4:C1:6A:49:5B:33:F4:96:7F:DD`

`release.sh` checks the built APK's signer against this fingerprint and refuses to stage a
mismatch. It is the one release property whose failure the user cannot recover from, so it is
verified on every build rather than assumed.

Android identifies an app by *package + signing key*. Lose this key and you cannot ship an update
to anyone who installed the app — they would have to uninstall (losing their tunnels) and
reinstall. Back up the `.jks` **and** its password (stored in `keystore.properties`) somewhere
outside this machine.

## What was changed vs upstream

| Area | Change |
|------|--------|
| `gradle.properties` | `wireguardPackageName=com.myco.vpn`; new `portwayImportScheme=portway` |
| `ui/build.gradle.kts` | namespace pinned to `com.wireguard.android` (see below); signing config; `IMPORT_SCHEME` |
| `AndroidManifest.xml` | `VIEW` intent-filter for `portway://import`; `MainActivity` set `singleTop` |
| `util/ConfigDeepLink.kt` | **new** — parses and validates the link payload |
| `activity/MainActivity.kt` | handles the intent, hands text to the existing importer |
| `util/TunnelImporter.kt`, `fragment/ConfigNamingDialogFragment.kt` | optional `suggestedName` prefill |
| `res/` | app name, launcher icon, TV banner, and the strings that named the app "WireGuard" |
| `model/HandshakeWatchdog.kt` | **new** — restarts tunnels that are up but not handshaking |
| `updater/` | **deleted** — see below |

`namespace` is deliberately **not** derived from the package property: upstream sets
`namespace = pkg`, which relocates the generated `R` class while ~31 sources still
`import com.wireguard.android.R`. Only `applicationId` is rebranded.

## Stalled-tunnel watchdog

A WireGuard tunnel can sit in the UP state while never completing a handshake: the endpoint
moved, the network changed under it, a NAT binding died, or the DNS name now resolves elsewhere.
The user sees "connected" with nothing working.

`model/HandshakeWatchdog.kt` polls every 10s while a tunnel is up and restarts it when there is
no handshake — which forces endpoint re-resolution and a fresh handshake. It needs nothing from
the peer, so it works with a stock MikroTik WireGuard server.

- 25s grace after connecting, 30s after each restart, before it will judge a tunnel again
- A handshake older than 3 minutes counts as stalled (a healthy tunnel rekeys about every 2)
- **Caps at 5 attempts**, then stops and leaves the tunnel up rather than looping forever
- Toggleable in Settings ("Restart stalled tunnels", on by default)
- The Connect screen shows "Reconnecting…" during a restart so it does not read as a random drop

It does **not** attempt DPI evasion. Traffic still looks like WireGuard on the wire; if a network
blocks the protocol itself rather than a particular endpoint, restarting will not help.

## Ping indicators

`util/Pinger.kt` measures endpoint RTT two ways, in order: an ICMP burst (three echoes via
`/system/bin/ping`, average of whatever answered — one echo reads a single lost packet as "no
connectivity"), then a TCP connect-time fallback to :443/:80. The fallback matters twice: many
networks filter ICMP outright, and the emulator's NAT drops app-originated echo while passing
TCP. A SYN answered by an accept **or an RST** costs exactly one round trip, so time-to-refusal
is an honest RTT; only "connection refused" counts, though — other ConnectExceptions (network
unreachable) fail locally in ~0ms and must not be reported as latency.

Surfaces: every tunnel row shows RTT to its endpoint (10s cycle while the list is visible,
probes concurrent, results into `ObservableTunnel.pingMillis` on main); the Connect card shows a
colour-coded ping row for the active tunnel (~3s cadence, single-flight, green < 80ms, amber
< 200ms, red beyond, "—" when unreachable). With a tunnel up and AllowedIPs 0.0.0.0/0 the probe
rides the tunnel like all traffic — which is the honest number, and means a dead tunnel shows
"—" rather than a misleading direct-path RTT.

### Probe states in the UI

The Connect card's ping row has three states: a live colour-coded value; a small spinner while
probes are still trying; and a red **Timeout** once three probes in a row go unanswered (a later
success resets it). Each tunnel row carries a state dot: **flashing red while its probe is in
flight, steady green on a reply, steady red when the endpoint stayed silent**, dim before the
first measurement. The dot drawable must be `mutate()`d before tinting — its constant state is
shared by every dot in the app, and an unmutated tint recolours all of them.

Two findings from building this: the emulator's NAT answers connections to unreachable
addresses with an instant local RST, which the TCP fallback honestly reports as ~0 ms — on real
networks an unreachable endpoint times out and reads red. And the handshake watchdog had a race:
a health-check pass landing inside a restart's DOWN window purged that tunnel's attempt counter,
turning the 5-attempt cap into an infinite bounce loop — the purge now spares the tunnel
currently being restarted.

## Account info from the management panel

The Connect page shows a live account card — name, plan, days left (colour-coded), data used,
and a SUSPENDED/EXPIRED badge — fetched from the mikrotik-manager panel, visible whether or not
the tunnel is up. Refresh: on bind and every 60s while the screen is visible.

The panel URL is baked into the APK: the `portwayPanelUrl` Gradle property becomes
`BuildConfig.PANEL_URL`, so users never enter anything. **It is deliberately not committed.**
The repository's `gradle.properties` carries an empty value; set the real one per machine in
`~/.gradle/gradle.properties` (`portwayPanelUrl=https://panel.example.com`), which Gradle reads
ahead of the project file, or pass `-PportwayPanelUrl=…` for a single build. `release.sh`
refuses to stage a release built without it, since such an APK could never reach the panel —
no account info and, worse, no update checks. The panel's production `.env` must
set `APP_URL` to the same URL to match — that value is what the endpoint advertises
back to apps. **Settings → Panel URL** still exists as an override, and a stored
value always beats the baked-in default. The panel can also move everyone remotely: its
`/api/peer/info` response carries `panel_url` (from Laravel's `APP_URL`), and when that
differs from the URL the app just used, the app stores it — the next fetch goes to the new
address. So a domain change is: stand the panel up at the new URL, keep the old one
answering `/api/peer/info` with the new `APP_URL` for a transition window, done.
Identity is the tunnel's own public key — derived from the
private key, so it exists for every config however it was imported — plus the interface
address as a cross-check: `GET /api/peer/info?pubkey=…&address=…`. Possession of the private
key is the credential; there are no accounts, tokens or markers involved.

Server side (`mikrotik-manager`, deploy required): the endpoint looks up `PeerToken` by
`public_key` and verifies both the address and the router's live peer key before answering.
Rows that predate public_key storage resolve 404 (accepted: a small legacy population).
Cleartext HTTP is enabled in the manifest for LAN-hosted panels; prefer HTTPS.

## File import is content-based

Upstream refused any file not literally named `*.conf` or `*.zip`. Portway's `TunnelImporter`
decides by content instead: a zip is recognised by its `PK\3\4` magic, anything else must be
UTF-8 text containing `[Interface]` that the stock parser accepts — so `.txt` configs from
chat apps and e-mail import fine. Inside a zip every non-directory entry is a candidate;
entries that don't look like a config (a README) are skipped silently, entries that do but
fail to parse are reported. The extension — any extension — is only stripped to form the
tunnel name. Files over 4 MiB are rejected before being read fully.

## Hardening fixes applied (P1)

Four defects inherited from upstream, all verified on device. See the hardening plan artifact
for the full catalog and the remaining phases.

- **Backend failure is no longer silent.** Upstream completed its backend deferred only on
  success, so an init failure left every screen awaiting something that never arrived — a
  permanent spinner with no error. The deferred is still not *failed* (about fifteen call
  sites await it bare inside `launch{}`, so failing it would trade the hang for a crash);
  instead `Application.awaitBackendResult()` reports the outcome without hanging, the tunnel
  list now loads from the config store regardless of the backend, and Connect shows a
  "VPN engine unavailable" card with the reason and a retry.
- **Tunnel names derived from filenames are sanitized.** Names must match
  `[a-zA-Z0-9_=+.-]{1,15}`, so `My Office VPN.conf` used to fail the whole import with
  "Invalid name" while the same config imported fine by QR. Illegal characters become `-`,
  the result is truncated to 15 and de-duplicated with a `-2` suffix.
- **A full-tunnel config that names no DNS server gets one pinned** (`portwayFallbackDns`,
  default `1.1.1.1`), closing the window in Google issue 337961996. Deliberately narrow: only
  when the tunnel carries a default route, since in a split tunnel the absence of a DNS line
  means "use the network's resolver" and overriding it would break resolution. Set the
  property empty to disable.
- **The watchdog no longer overrules an explicit user action.** Its restart pauses 700 ms
  between down and up, and a tap landing in that window used to be swallowed. `TunnelManager`
  now carries a state-change generation counter that the watchdog snapshots across the pause;
  if anything else changed state it abandons the restart.

## Network-change recovery (A1)

Upstream has no network-change handling whatsoever — no `ConnectivityManager`, no
`NetworkCallback`. Walk out of Wi-Fi onto mobile data and the tunnel keeps reading
"connected" while nothing passes, until the watchdog notices a stale handshake up to three
minutes later. `model/NetworkMonitor.kt` closes that gap: a handover now recovers in about a
second and a half.

Three things it gets right, each of which cost a failed test to learn:

- **It does not use `registerDefaultNetworkCallback`.** Once our own tunnel is up, the VPN
  *is* the default network, so that callback reports our own tunnel and the app restarts
  itself forever. `NetworkRequest.Builder` implies `NET_CAPABILITY_NOT_VPN`, so the request
  used here only ever matches real transports.
- **It keys on a network being lost, not on one appearing.** A phone routinely has Wi-Fi and
  cellular up at once, and the framework reports every matching network at registration. An
  earlier "last network wins" rule fired a spurious handover at startup on any device with
  both radios on. A live tunnel only breaks when the network it was riding goes away.
- **With no transport at all it waits.** Restarting into an absent network can only fail and
  would burn the watchdog's attempt budget; the restart happens when connectivity returns.

The recovery itself is a tunnel restart, because the Go layer exposes only
`wgTurnOn`/`wgTurnOff` — there is no bind-update entry point to call. That is no loss: a
restart rebuilds the UDP socket on the new network and re-resolves the endpoint hostname at
the same time. Callbacks are debounced 1.5s, since one handover emits a burst of them.

A network change also **clears the watchdog's attempt counters**. The five-attempt cap exists
to stop it looping against a broken endpoint, not to punish a tunnel for having been on a
network that went away — without the reset, a tunnel that exhausted its attempts while out of
signal stayed dead forever once signal returned.

## Doze-proof watchdog (A3)

`HandshakeWatchdog`'s polling loop is a coroutine `delay`, and the scheduler suspends those in
Doze — so the watchdog was blind during exactly the long idle stretches where tunnels go
stale, and the lived experience was picking the phone up to a tunnel that had been dead for
hours. `model/WatchdogAlarm.kt` adds two wake sources:

- **Leaving idle, and screen-on.** The one that matters. Deep in Doze the OS suspends app
  network access anyway, so a repair attempt down there would usually fail; what the user
  needs is a healthy tunnel by the time they look at it. Screen-on is rate-limited to one pass
  per 30s, since it fires dozens of times a day and a pass costs a statistics round-trip per
  live tunnel.
- **A while-idle alarm** every 15 minutes as a backstop, armed only while a tunnel is up.
  Deliberately inexact: `setAndAllowWhileIdle` needs no special permission (unlike the exact
  variant on API 31+), and the OS coalescing it into a maintenance window is correct rather
  than a limitation.

**A deadlock this introduced, and the rule that avoids it.** Wiring `reschedule()` into
`setTunnelState` deadlocked start-up: `TunnelManager.onCreate` → `restoreState` →
`setTunnelState` → `reschedule` → `getTunnels()`, which awaits the `tunnels` deferred that is
only completed *after* `restoreState` returns. The tunnel list never resolved and the app
showed "No tunnels yet" with tunnels sitting on disk — and only when a tunnel had been running
at last shutdown, so it hid until the right test. The rule: **nothing reachable from
`restoreState` may await `tunnels`.** `TunnelManager.hasTunnelUp()` reads the backing map
directly for exactly this reason.

## Tunnel-state integrity (A7, A8)

**One lock over every path that drives the backend.** Five entry points can toggle a tunnel —
the connect screen, the quick-settings tile, the toggle shortcut/activity, the TV activity and
the remote-control broadcast — and the connect screen guarded only against itself. GoBackend
mutates `currentTunnel` / `currentTunnelHandle` with no lock of its own, so two toggles landing
together could both observe DOWN, both call `establish()`, and leak a native handle while the
tracked state pointed at a file descriptor that was no longer alive. `TunnelManager` now holds
a `Mutex` across `setTunnelState`, `setTunnelConfig` and the backend call inside `delete`.

It is suspending, not blocking, so holding it across a ~1s `establish()` parks the coroutine
rather than a thread. It is **not reentrant**: nothing guarded by it may call another guarded
function. Verified by firing six concurrent toggles with the lock instrumented — five queued
as "waiting" behind the first and then ran strictly one at a time.

**Backend-initiated teardowns now persist.** `saveState()` was only reachable from
`setTunnelState`, so when the system tore the VPN down on its own — permission revoked,
another VPN taking over, the service killed — the UI flipped to disconnected but the stored
running-tunnels set still listed the tunnel, and the next process start silently tried to
reconnect it. `ObservableTunnel.onStateChange`, the single funnel for backend-driven changes,
now routes through `TunnelManager.onBackendStateChange`, which persists and reschedules the
watchdog alarm. Verified by revoking consent via Settings → VPN → Forget VPN with the watchdog
switched off, so nothing else could have cleared it: `enabled_configs` disappeared.

## Endpoint resolution when the server moves

Changing the server's address used to strand users until they rebooted the phone. Three
separate causes, none of which is a low TTL — the router hostname already publishes a 60s TTL:

1. **The endpoint IP is frozen into the tunnel at bring-up.** `Peer.toWgUserspaceString()`
   writes `endpoint=<resolved IP>`, and wireguard-go never re-resolves it, so only a restart
   can pick up a new address.
2. **`InetEndpoint` caches its own resolution for a minute**, so the watchdog's first restart
   — the one most likely to happen — reused the stale address without consulting DNS at all.
3. **Device and carrier resolvers over-cache**, routinely ignoring a 60s TTL. Rebooting works
   precisely because it clears them.

**The hostname in the config is never rewritten.** That is a hard constraint, not an
implementation detail: replacing it with an IP would make every config depend on the panel
forever, and a user whose server moved again while the panel was unreachable would be
stranded with no way to self-heal. Only the *lookup* changed.

`util/EndpointResolver.kt` queries four sources concurrently and consumes them in a
**preference order, not arrival order** — taking whichever answers first would be actively
worse, since a carrier resolver holding a stale record answers in milliseconds while the
source that knows the truth takes longer. Fast and wrong must lose to slow and right:

| Preference | Source | Notes |
|---|---|---|
| 1 | DNS over HTTPS | 1.1.1.1 and 8.8.8.8 by IP literal, so no bootstrap lookup. JSON API. Both queried **in parallel** — one blocked endpoint must not spend the whole budget. |
| 2 | Panel hint | `endpoint_ip` from `/api/peer/info`, attached to the hostname already in the config. A hint about that hostname, never a replacement for it. `endpoint_host` is optional: the panel answered about *this* tunnel, so its router's address is this tunnel's endpoint address. Requiring the panel to also name the host would make the feature depend on the optional `cf_hostname` column being filled in per router, and it would silently do nothing wherever that is blank. When the panel *does* name a host it must agree with the config — a mismatch is a different server, and the hint is refused. |
| 3 | `DnsResolver` + `FLAG_NO_CACHE_LOOKUP` | API 29+. Skips the device cache, still trusts the network's resolver. |
| 4 | Plain `getAllByName` | Last resort; a possibly-stale address beats refusing to connect. |

**Where DoH is blocked** — which is the case in the country this is deployed to — waiting the
full timeout on every lookup would turn the fix into a 2.5s tax on every reconnect. After two
consecutive failures DoH is still *probed* but no longer *waited on*, and a single success
restores it, so the block lifting or the user changing network recovers by itself.

The watchdog now invalidates the cached resolution before each restart (cause 2 above) and
logs when an endpoint's address actually changes. It also **backs off instead of giving up**:
the old five-attempt cap left a tunnel dead forever, worst case when the failure was a moved
server whose address later propagated.

Diagnosing a field report: `adb logcat -s Portway/EndpointResolver` names the winning source
for every lookup, and says explicitly whether DoH was blocked or merely returned nothing.

## Update safety and protection visibility (P6, P5)

**Updated-while-connected notice.** Android 16 can wedge the network stack when a VPN app is
replaced with its VPN active: the tunnel returns looking connected but passes nothing, and
only a reboot clears it. Open at Google since around September 2025. It hits this fork harder
than a Play Store app because the APK is installed by hand, plausibly mid-session, and there
is no in-app updater to disconnect first (upstream's was removed — see above). Nothing can
prevent it, so the app explains it instead: `PackageReplacedReceiver` records an update that
landed while a tunnel was running, and Connect shows a dismissable card **only** when that
coincides with a tunnel that is up and has never handshaked. Either signal alone does not
qualify — an update alone would cry wolf every time.

One trap worth remembering: the notice's grace period is measured from when the notice was
armed, **not** from the tunnel's connect time. The stalled-tunnel watchdog restarts a
non-handshaking tunnel every half-minute or so and each restart resets that timestamp, so a
"has been up this long without handshaking" test against it can never come true on exactly the
tunnels the notice is about. That bug was real and only surfaced on device.

**Protection section in Settings.** Always-on VPN, the lockdown kill switch, and background
restrictions were invisible in the app; users assume they have a kill switch and a tunnel that
survives a reboot, and by default they have neither (the boot receiver does nothing without
the kernel backend). Two rows now sit under *Protection*:

- *Always-on VPN and kill switch* — informational, tapping opens Android's VPN settings.
  **It deliberately does not report on/off.** There is no API that answers "is always-on
  configured for this app": `VpnService.isAlwaysOn()` reports whether the *current session* was
  started by the always-on mechanism, which is false for any hand-started tunnel — verified on
  a device with always-on and lockdown genuinely enabled, where it still read false — and the
  backend wrapper throws outright when no tunnel is up. Showing either as "Off" would tell
  users they are unprotected exactly when they are checking whether they are.
- *Background restrictions* — this one **is** reliable, since
  `isIgnoringBatteryOptimizations` is a real query independent of connection state. Tapping
  requests the exemption. Requesting it directly is only acceptable because this build ships
  outside Google Play; a Play-bound fork would have to open the settings screen instead.

## Device matrix

The redesign's Phase 9 was approved but never executed, so every screen had been checked on
exactly one phone configuration. Results:

| Configuration | Result |
|---|---|
| Phone, default | Baseline, no issues |
| **1.3× font scale** | Passes. Text wraps, nothing truncated, controls stay aligned on both Connect and Settings. |
| **RTL (fa-IR)** | Passes. Title, list rows, nav order and FAB all mirror. `supportsRtl="true"` is set and no layout uses absolute left/right — everything is start/end. |
| **Tablet (sw600dp)** | Two-pane engages, but Connect and Settings were squeezed into the 40% master pane with 60% blank — **fixed**, see below. |
| **Android TV** | Launches, renders tunnels with stats, inherits the palette, no crash. |
| **TalkBack labels** | Three unlabelled controls found and **fixed**, including the app's primary action. |
| **API 24** | Not run on a device. Covered by `lintRelease`'s API-level analysis, which found a **real crash** — see below. |

**Two traps when testing this.** `settings put global debug.force_rtl 1` is silently ignored on
current Android — it produced a byte-identical screenshot and a false pass. Use
`cmd locale set-app-locales <pkg> --locales fa-IR` instead. And clearing that override needs
`--locales ""`; passing `--locales en-US` leaves the app rendering RTL, which then reads as a
mirroring bug that does not exist.

**Tablet fix.** On `sw600dp` every destination is committed into `list_fragment` (weight 2)
while `detail_container` (weight 3) is reserved for tunnel detail. Connect and Settings are
whole screens, not master panes, so they rendered in the left third with two thirds blank.
`MainActivity.applyPaneVisibility()` now collapses the detail pane for any destination except
Tunnels, and the weights give the space back on return.

**API 24 crash, found by lint rather than by a device.** `Pinger.icmp` used
`Process.waitFor(timeout, unit)` and `destroyForcibly()`. Both are API 26 and this module's
minSdk is 24, so on Android 7.0/7.1 every ping would have thrown `NoSuchMethodError`. Replaced
with a poll on `exitValue()` (which throws `IllegalThreadStateException` while the process is
still running — the API-24-safe way to test for completion) plus `destroy()`, which has existed
since API 1. Verified afterwards that the ICMP burst still runs and still falls through to the
TCP probes.

Lint also flags `QuickTilePreference` as requiring API 33 where `preferences.xml` references
it. Reviewed and safe: `@RequiresApi` has no runtime effect, the class touches API 33 only
inside `onClick`, and `SettingsFragment` removes the preference below Tiramisu immediately
after inflating it, so it can never be clicked there. Left as-is.

**Accessibility fix.** The hero connect button is a custom card with an icon inside and no
text, so TalkBack announced an unlabelled button — for the app's primary action. It now
carries a state-aware description set from `render()` ("Connect *name*" / "Disconnect *name*" /
"Connecting"), because the only useful thing to announce is what the tap will do. The
add-tunnel and share-log FABs were also unlabelled and now are not, and the decorative app
icon in the per-app list is explicitly `@null`. The Connect screen's live regions were already
in place from the redesign.

## Performance pass (P7)

**Settings no longer reads the disk on the main thread per row (A9).** Upstream answered every
synchronous `PreferenceDataStore` getter with `runBlocking { dataStore.data.first() }`, and
AndroidX calls those from the main thread while inflating the screen — once per visible row.
A collector now mirrors the latest `Preferences` into a process-wide snapshot and the getters
read that; writes update it optimistically so a read straight after a put cannot see the
pre-write value. One blocking read can still happen, to seed a cold process, instead of one per
row. Verified: Settings renders correctly and a toggle survives leaving and returning.

**The startup theme read was measured and deliberately left alone (A10).** It blocks the main
thread for **8.6–16.5 ms** of a ~900 ms cold start. The alternative is mirroring the value into
SharedPreferences, which risks a theme flash or an activity recreation whenever the two
desync — a visible regression in exchange for ~12 ms. Left blocking so the first frame is
already the right theme. The number is recorded here so the question does not get reopened on
intuition.

**The watchdog backs off when nobody is looking (C4).** It polled every 10s for as long as any
tunnel was up, running a statistics round-trip across the JNI boundary each time, all day, for
a tunnel that is usually healthy. `Application` now tracks started activities as a cheap
foreground signal — `lifecycle-process` is not on the classpath and counting activities needs
no new dependency — and the interval becomes 60s while backgrounded. A stall is still caught:
the stale-handshake threshold is three minutes, `NetworkMonitor` reports a network change
immediately, and `WatchdogAlarm` covers Doze. Measured on device: 2 restart cycles in 80s
foregrounded versus 1 backgrounded.

**The refetching getters are now labelled (D6).** `ObservableTunnel.statistics` and `.config`
launch a background fetch as a side effect of being *read*. That is deliberate for databinding
but unsafe in a loop or a RecyclerView row, and nothing said so. Every current caller already
uses the async variants; the warnings exist so that stays true.

Not done in this pass: **B5**, an app-level lock. The biometric gate still covers only private-key
reveal and zip export, which matches upstream's intent.

## In-app updater

The APK is hand-distributed, so the app checks the panel for a newer build and installs it
itself. `util/UpdateChecker.kt` + `util/UpdateInstaller.kt`; **not** upstream's updater, which
stays deleted — it throws from `Application.onCreate` on any package outside `com.wireguard.`
and fetches WireGuard-signed APKs that could never install over this build.

**The point is not convenience.** Android 16 can corrupt the network stack when a VPN app is
replaced while its tunnel is live, leaving a tunnel that looks connected and carries nothing
until a reboot. A hand-installed APK cannot avoid that. Owning the install moment means the
tunnel goes **down first** — verified on device: `Taking <tunnel> down before installing`, tun
interfaces 4 → 3, and only then does Android's confirmation appear.

Flow: check on start (throttled to 6h) → card on Connect → tap Update → tunnels down →
download to cache → SHA-256 → `PackageInstaller` session → Android's own confirmation → cache
cleared whatever the outcome.

**Where trust actually comes from.** Android refuses any update not signed with our release
key, so a substituted APK cannot replace Portway however it was obtained — the keystore is the
boundary, not this code. The SHA-256 catches truncated or corrupted downloads, which would
otherwise surface as a baffling "app not installed" from the system installer.

**The update must come from the panel's own origin** — same scheme, host and port as the panel
URL. A first cut required `https://`, which was worse in both directions: it broke the LAN
panels served over http that this app deliberately supports, and it still allowed a manifest to
point the install at any host on the internet. Whoever controls the manifest already controls
the app's idea of the panel; restricting the download to that same origin means a compromised
manifest cannot redirect the install somewhere new.

`min_supported_version_code` in the manifest makes an update non-dismissable below that
floor — the "Later" button is removed rather than disabled — so a client with a broken contract
can actually be retired.

Panel contract (`GET /api/app/latest`, `Cache-Control: no-store`, must not be edge-cached):

```json
{ "version_code": 520, "version_name": "1.0.20260401",
  "url": "https://panel.example.com/<direct apk link>",
  "sha256": "<64 hex of that file>", "notes": "optional, may be null",
  "min_supported_version_code": 500 }
```

Until that endpoint exists the checker logs `unavailable` and no card is ever shown — verified
against the live panel. `REQUEST_INSTALL_PACKAGES` is back in the manifest (restricted on Play;
this build does not ship there), and the user must allow "install unknown apps" once — the card
sends them straight to that screen rather than leaving them to find it.

Verified on device: optional card with notes; checksum mismatch refuses and leaves the version
untouched; the good path installs and clears its cache; a forced update shows no Later button.

## Plan-expiry warning

`util/ExpiryNotifier.kt` warns before a plan runs out, at **3 days and every whole day after**
(`WARN_AT_DAYS = 3`, inclusive — the three-day mark is the one with enough time left to act on).

**Why it has its own alarm.** The account card already shows days remaining, but only to
someone who opens the app, and the person who most needs the warning is the one who has not
opened it in a week. It deliberately does not reuse the watchdog's alarm either: that is armed
only while a tunnel is *up*, and an account near expiry often has its tunnel already down —
exactly the population that alarm would miss. So this runs its own check twice a day, armed
regardless of tunnel state, and also on every successful account fetch in the foreground.

**De-duplicated on the day count, not a timestamp.** The stored marker is `<tunnel>:<days>`, so
a user hears once at three days, once at two, once at one and once on the day it lapses, and
never twice for the same number however often the account is refetched. A renewal pushes the
count above the threshold, which clears the marker so the next expiry warns from scratch.
Verified on device: warned at 1, silent on a repeat of 1, marker cleared at 10, warned again
at 0.

This is the app's **only** notification, which is why asking for `POST_NOTIFICATIONS` is
justified at all — Android already posts its own notice for an active VpnService and
duplicating that was never worth a prompt. The permission is requested the first time an
account actually loads, not at first launch: before there is anything to notify about the
prompt is noise, and a denial there is sticky. Without the permission nothing breaks; the
account card remains the fallback.

## Versioning and cutting a release

Use `./release.sh {patch|minor|major}`. It bumps both numbers, builds, **reads the version back
out of the APK** rather than trusting `gradle.properties`, refuses to stage on a mismatch, and
prints the sha256. That check exists because the two drifted once: the panel advertised 520
while the published file was a 519 build, which put every client in a permanent "update
available" loop that installing could never clear.

**Version name** is `major.minor.patch`, chosen by what the release does for a user:

| Bump | When | Example |
|---|---|---|
| patch | Fixes only; nothing new to notice | a crash fix, a layout correction |
| minor | A new capability or visible behaviour change | expiry warnings, the in-app updater |
| major | Changes the deal: breaks the panel contract, needs relearning, or needs the user to act | a redesign, a new required permission |

**Version code** goes up by one on every published build, always, regardless of the name. It is
the only value the updater compares; the name is display-only (confirmed with the panel — it
orders by `version_code` and looks up downloads by it, never by name). Two builds may therefore
share a name, though after 1.1.0 they should not.

The current release is **1.1.0 (520)**. Everything before it used upstream's date-style name
`1.0.20260315`, which is why 519 and 520 briefly shared a name.

**Never set version or hash by hand on the panel.** It derives both from the uploaded APK, and
since commit 242d5af it refuses to publish an override that disagrees with the parsed file. The
derived value is the only one that cannot drift.

## Distribution and updates

There is no Play Store auto-update. Host `ui-release.apk` on the mikrotik-manager domain over
HTTPS and link it from the peer page as the fallback next to the QR code. Users must allow
"install unknown apps" once for their browser.

Upstream's in-app updater has been **removed** (`ui/.../updater/`, its manifest receiver, and the
`REQUEST_INSTALL_PACKAGES` permission). Two reasons: it contains a deliberate anti-fork tripwire
that throws `RuntimeException("Too much code got copy and pasted")` from `Application.onCreate`
whenever the package name does not start with `com.wireguard.` — a rebranded build crashes on
launch until it is dealt with — and upstream's own comment asks forks to delete the file rather
than the check. It would also have fetched WireGuard-signed APKs from their host, which this fork
can neither verify nor install over itself. Updates are therefore fully manual.

To take upstream security fixes:

```bash
cd ~/dev/wireguard-app/wireguard-android
git fetch origin && git rebase origin/master   # the fork's diff is small on purpose
./gradlew assembleRelease
```

Bump `wireguardVersionCode` in `gradle.properties` for every release you publish — Android refuses
to install an APK whose versionCode is not higher than the installed one.

## Licensing / trademark

Apache-2.0 permits this fork without publishing source. Keep `COPYING` and the upstream copyright
headers, and keep changed files marked as modified (done in the files listed above). The
"WireGuard" name and logo are registered trademarks: the app name, launcher icon, TV banner and
the user-visible strings that named the app have all been replaced. Optionally notify
wireguard-trademark-usage@zx2c4.com.

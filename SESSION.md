# One config, one device at a time

A WireGuard config is one private key, which is **one peer** on the router. Connect it on two
devices and both claim the same interface address. The router sends each reply to whichever
device spoke last, so both connections flap, and the operator gets a support ticket that looks
like a server fault.

The app now stops a second device from connecting while the config is live on another one. The
panel flags accounts that are being shared anyway.

Client: `ui/src/main/java/com/wireguard/android/util/SessionGuard.kt`
Panel: `mikrotik-manager` commit `12ee96b` (built and tested; **deploy pending**)

## Decisions (made by the operator, 2026-09-13)

- **Fail-open.** No panel, a timeout (3s), a 5xx, or a 429 rate limit: connect anyway. A panel
  outage must never become a VPN outage.
- **Takeover, not a hard block.** The warning offers **Use here instead**. The device that loses
  the session disconnects itself on its next heartbeat. Without this, a phone left connected at
  home would lock the account.
- **Importing on several devices is fine.** Only being online at the same time is the problem.

## Why a device id, not the panel's existing `online`

One key cannot say *which* device is online. If the app gated on `online`, a user who disconnects
and reconnects the **same** phone would be refused by their own recent handshake for minutes. So
each install generates a random UUID (not hardware-derived; a reinstall is a new device) and
**claims** the session. The panel refuses only when a *different* device holds a session that
is still live.

## The limit to know

This governs **Portway only**. The `.conf` contains the private key, so it can be imported into
the official WireGuard app, which has no such check. The panel's router-side detection is what
catches those users; the app gate is the friendly front door.

## Contract

All requests are `POST` with a JSON body. They carry the same identity as `/api/peer/info`
(`pubkey`, plus `address` for legacy lookup) and `device_id`. Unknown peer → `404`, which the app
treats as allow.

| Endpoint | When | Extra fields | Response |
|---|---|---|---|
| `/api/peer/device/register` | on import; once per app start for every config | `device_name`, `app_version`, `os_version` | `200 {ok}` |
| `/api/peer/session/claim` | before a **user** connect | `device_name`, `app_version`, `os_version`, `takeover` | `200 {granted:true}` · `409 {granted:false, other_device_name, other_since}` |
| `/api/peer/session/heartbeat` | every 60s while up | `device_name`, `app_version`, health (below) | `200 {active:true}` · `200 {active:false, superseded_by_device_name}` |
| `/api/peer/session/release` | on a **user** disconnect | `reason`, `restarts` | `200 {ok}` |

A session is **live** when its heartbeat is within 600s **and** the router saw a handshake within
180s. The panel reads handshakes from a snapshot up to 60s old, so a dead device frees the
account within about 240s. The client hard-codes none of these numbers; it trusts the `409`.

The same `device_id` re-claiming is always granted. `takeover:true` is always granted, except
when rate-limited. A heartbeat with no session, while nobody else holds a live one, is re-granted.

Rate limits are per account: claim 10/min, takeover 3 per 10 min, heartbeat 3/min per device.
Over a limit → `429`, which the client treats as allow.

`os_version` ("13 (33)") and the name/version on the heartbeat exist for the panel's Portway Apps
page: they let it show what a fleet is running, and keep a phone that stays connected for weeks from
going stale. Only configs this panel claims ever send any of it — see the gate above.

Support fields ride the same requests, for the questions that otherwise need a conversation:

| Field | Where | Meaning |
|---|---|---|
| `notifications` | all three | permitted **right now**, not ever-granted: with them off the user never sees a takeover or superseded notice, so this check looks broken while working |
| `battery_unrestricted` | all three | Android may not stop the app in the background |
| `always_on`, `lockdown` | heartbeat | only readable from a running VpnService, and only on Android 10+, so **absent elsewhere — absent never means false** |
| `last_disconnect_reason` | register, claim | how the previous session ended: `user`, `replaced`, `handshake_timeout`, `superseded`, `update`, `system`, `killed`, `unknown` |
| `last_disconnect_at` | register, claim | epoch millis; absent when the reason is `unknown` |

`unknown` is sent as a value rather than by omitting the field: a teardown the app could not
attribute is itself information. `killed` cannot be observed as it happens — a process Android stops
writes nothing — so it is inferred at the next start from the persisted running-tunnels set, which a
clean disconnect empties. See `util/DisconnectReasons.kt`.

`release` carries the same `reason`, so the panel can close a session with its cause at the moment
it ends rather than waiting for the next claim — which, for the session that ended badly, may never
come.

## Up is not connected

A tunnel is UP the moment the interface exists. That is the app asking for a connection, not the
server answering one, and every way a connection fails after that point leaves the tunnel sitting
in UP with no handshake. Reporting only "up" therefore showed a device as connected while it was
reaching nobody — an operator with a green row and a user insisting nothing works, which is the
worst place to start a support conversation.

Every heartbeat now carries what the tunnel is actually doing. **`link_state` is the field to
read; only `handshaking` means traffic is passing.**

| Field | Meaning |
|---|---|
| `link_state` | `handshaking` (handshake inside 180s — healthy) · `connecting` (up, no handshake yet, too early to judge) · `stale` (handshaked once, not within 180s: it worked and stopped) · `no_handshake` (past the window having never handshaked: it never reached the server). Those four only: a tunnel found down is skipped, not reported |
| `handshake_age` | seconds since the latest handshake. **Absent when there has never been one** — which is what separates `no_handshake` from `stale` |
| `connected_for` | seconds since this tunnel came up. Resets on every watchdog restart, so it is not a measure of how long anything has been wrong |
| `silent_for` | seconds not handshaking, **spanning restarts**. Present only when `link_state` is not `handshaking`. This is the number that says how long it has been broken |
| `rx_bytes`, `tx_bytes` | since the tunnel came up. `rx_bytes: 0` with a rising `tx_bytes` is a server answering nothing at all |
| `restarts` | watchdog restarts in this session. High with a healthy link is a tunnel that keeps breaking and recovering — a fault no single snapshot shows |
| `transport` | `wifi`, `cellular`, `ethernet`, `bluetooth`, `other`, `none`. Best-effort: with both radios up it can name the wrong one |

The thresholds are WireGuard's own 180s cutoff, shared with the decay bar and the watchdog
(`HandshakeDecayView.HANDSHAKE_LIMIT`), so the screen, the restarts and the report cannot disagree.

Why `silent_for` rather than `connected_for`: against a server that answers nothing, the watchdog
restarts the tunnel every ~30s, so its up-time never grows and it looks permanently like a
connection that simply has not finished yet. That is how a dead tunnel reported `connecting`
indefinitely. `silent_for` is the watchdog's own clock, started at its first verdict of "not
handshaking" and kept across the restarts that follow.

**What the panel does with it** (`mikrotik-manager` `339e70a`): "connected" keys on
`link_state = handshaking`, never on a heartbeat existing. A claim records the device and leaves
the row unconfirmed until the first heartbeat carries a verdict. A "Not getting through" tile and
filter collect the up-but-not-handshaking devices, with `silent_for` beside each. The link fields
are written as a set including nulls — a recovered tunnel must lose its old `silent_for` rather
than keep it — while a heartbeat carrying no `link_state` at all leaves them untouched.

**The one-device check was never at risk from this**, from the other side: a session counts as
live only when the device heartbeats *and* the router reports a handshake for that peer within
180s. A holder that reaches nothing produces no router-side handshake, so it is not live and the
second device is granted. That holds for the official WireGuard app too, because the test is the
router's word rather than any app's.

**Rollout:** 533 and older send no `link_state` and render "up · unconfirmed", excluded from the
connected count. Until the next release reaches customers, "Connected now" reads low on purpose —
those devices were up, and whether they reached anything is unknown.

The app says the same thing to the user: past the window with nothing from the peer, the Connect
screen reads *"Not reaching the server"* instead of *"Protected"*.

## Who is gated

Every state change goes through `TunnelManager.setTunnelState`, which takes an explicit
`SessionGuard.Gate`:

| Gate | Callers | Claim | Refused? | Release on down |
|---|---|---|---|---|
| `USER` (default) | Connect, detail, list, TV, quick tile, toggle shortcut, remote-control broadcasts | yes | yes | yes |
| `ADVISORY` | boot restore, Android always-on | yes | **never**; a conflict is a notification | yes |
| `NONE` | watchdog restart, updater | no | no | no |

- **On screen** (Connect, detail, list, TV): a dialog naming the other device, with **Use here
  instead**.
- **Headless** (quick tile, toggle shortcut, remote-control broadcasts): a notification with the
  same action.
- **Superseded**, i.e. told by a heartbeat that another device took over: disconnect and notify.
  **Under always-on it only notifies.** Disconnecting there makes Android restart the VPN and
  loop, and in lockdown mode every bounce cuts the user's internet.

The release is not tied to the call that asked for a disconnect. It happens in
`TunnelManager.onBackendStateChange`, which sees **every** teardown the backend performs:
an explicit disconnect, deleting a config, the system tearing the VPN down, and — the case
that motivated it — the backend taking config A down on its own when config B connects.
`NONE` operations mark their tunnel for their duration so that report is ignored.

`ADVISORY` exists because blocking always-on is worse than a conflict. `NONE` exists because
releasing during a 700ms watchdog restart would hand the session to anyone who asked in that
window.

## Security

v1 trusts `pubkey` + `address`, the same as `/api/peer/info`. The new risk is that anyone who
knows a user's **public** key could send `takeover:true` and kick them off. The panel limits
takeovers per account and audit-logs every claim, takeover, denial and release.

The upgrade path, if it becomes necessary, is proof of private-key possession without sending
the key. The panel would publish a static X25519 public key; the client sends
`HMAC-SHA256(X25519(peer_private, panel_public), timestamp + body)`; the panel derives the same
secret from `X25519(panel_private, peer_public)`. WireGuard keys are X25519 already. The hook is
`SessionGuard.sign()`, a no-op today and called on every request.

## Server-side detection (panel, feeds the bot)

- **S1 — endpoint switching** (catches every client). WireGuard moves the peer's endpoint to
  the source of each authenticated packet. Two devices on one key therefore make the endpoint
  switch back and forth between them, whereas one phone moving from Wi-Fi to mobile data changes
  it once. S1 needs *sustained* interleaving between the same address:port values within 10
  minutes, so a single roam, or a home → away → home trip, does not flag.
- **S2 — Portway devices.** Two or more devices heartbeating within 10 minutes, or a takeover of
  a live holder.

The operator gets one notification per flag. A flag clears after 30 quiet minutes.

## Testing notes

- **A full-tunnel config (`0.0.0.0/0`) on the emulator black-holes every panel call.** The
  emulator never completes a handshake, so traffic routed into that tunnel goes nowhere. To test
  heartbeats, use a throwaway config whose `AllowedIPs` don't cover the panel, e.g.
  `192.0.2.0/24` with a TEST-NET endpoint.
- **Point Panel URL at a mock at `http://10.0.2.2:PORT`** (cleartext is allowed). Clearing the
  field afterwards now falls back to the built-in panel; before 2026-09-13 an empty field
  disabled the panel entirely.
- **Always-on only applies at boot.** `settings put secure always_on_vpn_app` followed by
  `adb reboot` works. Android re-persists the setting, so switch it off in
  Settings → Network → VPN, not with `settings delete`.
- **Android 13+ needs `POST_NOTIFICATIONS` granted** or every notice is silently skipped. That is
  by design, but easy to mistake for a bug.

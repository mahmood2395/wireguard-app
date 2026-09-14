# Portway config-import deep link

The contract between the `mikrotik-manager` peer page and the Portway Android app.

## URL format

```
portway://import?c=<base64url of the .conf>[&name=<url-encoded tunnel name>]
```

| Part | Required | Notes |
|------|----------|-------|
| scheme `portway` | yes | Set by the `portwayImportScheme` gradle property; manifest and `BuildConfig.IMPORT_SCHEME` both read it, so they cannot drift. |
| host `import` | yes | Anything else is ignored by the app. |
| `c` | yes | The **entire** `.conf` file, base64-encoded. base64url (`-_`) or standard (`+/`) both decode; padding optional. Max 64 KiB decoded. |
| `name` | no | Prefills the tunnel-name field in the confirmation dialog. Trimmed; blank is treated as absent. |

The app decodes `c`, requires valid UTF-8 containing `[Interface]`, parses it with the stock
WireGuard config parser, then shows the same naming dialog the QR-code import uses. Nothing is
written until the user taps **Create tunnel** — the link cannot silently install a tunnel.

On any failure the user sees *"That link did not contain a valid configuration"*; the reason
(`MALFORMED_BASE64`, `NOT_A_CONFIG`, `PAYLOAD_TOO_LARGE`, …) goes to logcat only — the config
itself is never logged.

## Generating the link (PHP / `PublicPeerController`)

```php
// $conf is the same string already rendered into the QR code.
$payload = rtrim(strtr(base64_encode($conf), '+/', '-_'), '=');
$importUrl = 'portway://import?c=' . $payload
           . '&name=' . rawurlencode($peer->name);
```

In `peer.blade.php`:

```blade
<a href="{{ $importUrl }}" class="btn btn-primary">Import to Portway</a>
```

## Fallbacks are not optional

A custom scheme **fails silently** when the app is not installed — Android simply does nothing, or
Chrome shows a dismissible error. Keep the existing QR code and `.conf` download visible on the
page rather than hiding them behind the button. This fork registers no `.conf` file handler and no
HTTPS App Link, so the button is the only in-app path.

Note the private key travels inside the URL. That is the same exposure as the QR code the page
already renders, but it means the link will appear in browser history and in any logs that record
full URLs — treat it as single-use and keep the page's existing token expiry.

## Testing without the web app

```bash
source ~/dev/wireguard-app/env.sh
CONF=$(printf '[Interface]\nPrivateKey = <key>\nAddress = 10.0.0.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = <pub>\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25\n')
PAYLOAD=$(printf '%s' "$CONF" | base64 | tr '+/' '-_' | tr -d '=\n')
adb shell am start -a android.intent.action.VIEW -d "\"portway://import?c=$PAYLOAD&name=test-peer\""
```

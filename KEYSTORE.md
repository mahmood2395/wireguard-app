# The signing key — read this before you distribute anything

`keystore/portway-release.jks` (alias `portway`, RSA-4096, valid to 2054) is the single
irreplaceable artefact in this project.

Android identifies an app by **package name + signing key**. If you lose this key you cannot ship
an update to anyone who already installed Portway: their device will refuse the new APK as a
different app. The only recovery is asking every user to uninstall — losing their tunnels — and
reinstall. There is no reset, no support channel, no Play Store key recovery here, because this
app is self-distributed.

## What to back up

Both of these, together, to somewhere that is not this laptop:

1. `keystore/portway-release.jks`
2. The password, from `wireguard-android/keystore.properties` (`storePassword` / `keyPassword`)

A password manager entry with the `.jks` attached is the simplest correct answer. A second copy on
an encrypted drive or private repo is the usual belt-and-braces. Do **not** commit either to a
repo that could become public: `.gitignore` already excludes `keystore.properties`, but the `.jks`
lives outside the fork and is not covered by that.

## Verify a backup actually restores

Worth doing once, now, rather than discovering a truncated file in two years:

```bash
source ~/dev/wireguard-app/env.sh
keytool -list -v -keystore /path/to/your/backup.jks -storepass '<password>' | grep SHA256
```

The fingerprint must read:

```
EE:63:79:85:53:25:48:A3:3F:38:5E:47:BB:78:F7:56:BF:0F:48:E7:B6:4E:E4:C1:6A:49:5B:33:F4:96:7F:DD
```

Same fingerprint = the backup can sign updates. Different or an error = the backup is useless, fix
it now.

## Checking what a released APK was signed with

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs dist/Portway-*.apk
```

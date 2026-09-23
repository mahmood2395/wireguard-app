#!/usr/bin/env bash
#
# Portway release helper.
#
# Exists because the version code and the APK drifted apart once already: the panel advertised
# 520 while the published file was a 519 build, which put every client in a permanent
# "update available" loop that installing could never clear. The fix is not vigilance, it is
# doing the bump and the build as one step and reading the version back out of the artifact.
#
#   ./release.sh patch    fixes only, nothing new to notice          1.1.0 -> 1.1.1
#   ./release.sh minor    new capability or visible behaviour change 1.1.1 -> 1.2.0
#   ./release.sh major    breaks the panel contract, or needs the user to relearn/act
#
# The version CODE always goes up by one regardless — it is the only thing the updater compares.
set -euo pipefail

BUMP="${1:-}"
case "$BUMP" in
  patch|minor|major) ;;
  *) echo "usage: $0 {patch|minor|major}" >&2; exit 2 ;;
esac

# The release key's fingerprint, from KEYSTORE.md. Changing this line is changing which key
# users can be updated from, which is not a thing to do to fix a failing build.
EXPECTED_SIGNER=ee637985532548a33f385e47bb78f756bf0f48e7b64ee4c16a495b33f4967fdd

ROOT="$(cd "$(dirname "$0")" && pwd)"
PROPS="$ROOT/wireguard-android/gradle.properties"
source "$ROOT/env.sh"

CODE=$(grep '^wireguardVersionCode=' "$PROPS" | cut -d= -f2)
NAME=$(grep '^wireguardVersionName=' "$PROPS" | cut -d= -f2)
IFS=. read -r MAJ MIN PAT <<< "$NAME"

if [[ ! "$MAJ$MIN$PAT" =~ ^[0-9]+$ ]]; then
  echo "versionName '$NAME' is not major.minor.patch; fix it by hand once, then use this script" >&2
  exit 1
fi

case "$BUMP" in
  patch) PAT=$((PAT + 1)) ;;
  minor) MIN=$((MIN + 1)); PAT=0 ;;
  major) MAJ=$((MAJ + 1)); MIN=0; PAT=0 ;;
esac
NEW_NAME="$MAJ.$MIN.$PAT"
NEW_CODE=$((CODE + 1))

echo "  $NAME ($CODE)  ->  $NEW_NAME ($NEW_CODE)"
sed -i '' "s/^wireguardVersionCode=.*/wireguardVersionCode=$NEW_CODE/" "$PROPS"
sed -i '' "s/^wireguardVersionName=.*/wireguardVersionName=$NEW_NAME/" "$PROPS"

cd "$ROOT/wireguard-android"
./gradlew :ui:assembleRelease

APK="$ROOT/wireguard-android/ui/build/outputs/apk/release/ui-release.apk"
# Read the version back out of the artifact rather than trusting the properties file: this is
# the exact check that would have caught the 519/520 mismatch before it reached anyone.
# Captured in two steps on purpose: `aapt2 ... | grep -m1` makes grep exit at the first match,
# aapt2 takes SIGPIPE, and under `set -o pipefail` that killed this script silently right after
# the build — properties bumped, nothing staged, no error printed.
BADGING_ALL=$("$ANDROID_HOME"/build-tools/36.0.0/aapt2 dump badging "$APK")
BADGING=$(printf '%s\n' "$BADGING_ALL" | grep '^package:' | head -1)
BUILT_CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<< "$BADGING")
BUILT_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<< "$BADGING")

if [[ "$BUILT_CODE" != "$NEW_CODE" || "$BUILT_NAME" != "$NEW_NAME" ]]; then
  echo "MISMATCH: built $BUILT_NAME ($BUILT_CODE), expected $NEW_NAME ($NEW_CODE) — do not publish" >&2
  exit 1
fi

# The panel URL is not in the repository (see BUILD.md), so a build on a machine without
# portwayPanelUrl in ~/.gradle/gradle.properties bakes in an empty one: an APK that can never
# reach the panel, so it shows no account info and, worse, never learns of an update. Refuse it.
BUILDCONFIG=$(find "$ROOT/wireguard-android/ui/build/generated" -name BuildConfig.java -path '*release*' | head -1)
if [[ -z "$BUILDCONFIG" ]] || grep -q 'PANEL_URL = "";' "$BUILDCONFIG"; then
  echo "NO PANEL URL: set portwayPanelUrl in ~/.gradle/gradle.properties (or -PportwayPanelUrl=...) — do not publish" >&2
  exit 1
fi

# The signing key, checked rather than assumed. Every other failure here costs a release; this
# one costs the user their configs — Android identifies an app by package + key, so an APK signed
# with the wrong one cannot install over an existing Portway at all. The customer's only way out
# is to uninstall, which takes their tunnels with it. Cheap to check, unrecoverable to get wrong.
SIGNER=$("$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs "$APK" 2>/dev/null |
  sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | tr 'A-F' 'a-f')
if [[ "$SIGNER" != "$EXPECTED_SIGNER" ]]; then
  echo "WRONG SIGNING KEY: got '${SIGNER:-none}', expected $EXPECTED_SIGNER — do not publish" >&2
  echo "An APK signed with another key cannot update an installed Portway; see KEYSTORE.md." >&2
  exit 1
fi

DEST="$ROOT/dist/Portway-$NEW_NAME-$NEW_CODE.apk"
mkdir -p "$ROOT/dist"
rm -f "$ROOT"/dist/Portway-*.apk
cp "$APK" "$DEST"

echo
echo "  built    $BUILT_NAME ($BUILT_CODE)  — verified from the APK, not the properties file"
echo "  apk      $DEST"
echo "  sha256   $(shasum -a 256 "$DEST" | cut -d' ' -f1)"
echo "  signer   $SIGNER  — matches the key every released build has used"
echo
echo "  Upload this file on the panel. Let it derive version_code and sha256 from the APK —"
echo "  never set them by hand, that is what caused the update loop."
echo "  Keep min_supported_version_code at or below the version users already run."

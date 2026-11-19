#!/bin/bash
# Build the release APK and sign it with your own key.
#   SIDEKEYS_KEYSTORE   path to your keystore (.jks or .p12)
#   SIDEKEYS_KEY_ALIAS  key alias inside it
# apksigner asks for the password interactively; nothing is stored.
set -euo pipefail

source "$(dirname "$0")/env.sh"

ks="${SIDEKEYS_KEYSTORE:-}"
alias="${SIDEKEYS_KEY_ALIAS:-}"
if [ -z "$ks" ] || [ -z "$alias" ]; then
    echo "set SIDEKEYS_KEYSTORE and SIDEKEYS_KEY_ALIAS (see README, Signing)" >&2
    exit 1
fi
[ -f "$ks" ] || { echo "keystore not found: $ks" >&2; exit 1; }

"$root/scripts/build.sh"

version=$(grep -m1 -E '^\s*versionName\s*=' "$root/app/build.gradle.kts" | sed -E 's/.*"(.*)".*/\1/')
mkdir -p "$root/dist"
out="$root/dist/sidekeys-$version.apk"

"$bt/apksigner" sign --ks "$ks" --ks-key-alias "$alias" --out "$out" "$root/build/manual/app-unsigned.apk"
rm -f "$out.idsig"
"$bt/apksigner" verify --print-certs "$out" | grep -m2 -E 'DN|SHA-256'
sha256sum "$out"

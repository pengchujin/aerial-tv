#!/usr/bin/env bash
# Fetches the optional proxy core for the built-in VPN / proxy (docs: app/src/main/assets/proxy/NOTICE.txt).
#
# Downloads the official mihomo release asset, checks its SHA-256 (the compressed asset and the
# extracted executable, both pinned below), and installs it as
# app/src/main/jniLibs/armeabi-v7a/libmihomo.so. That file is git-ignored: Aerial builds and runs
# without it (the VPN button then says the core is unavailable), and no release APK carries it
# unless the person building it ran this script. Any APK that does is covered by the GPLv3.
#
# The official linux-armv7 build (static, with_gvisor), not android-armv7: the Android build's TUN
# reads /data/system/packages.xml (sing-tun packages_android.go), which an ordinary app cannot
# read, so its tun listener fails. The linux build takes the VpnService fd without reading it.
#
# Needs curl, gunzip and sha256sum (or shasum on macOS). A proxy is used the way curl uses one:
# set https_proxy / ALL_PROXY in the environment.
set -euo pipefail

VERSION=1.19.33
ASSET_SHA256=c8a8f9bcaab914269fbdca7569e4a9f024cc667ecf12eb43e8ccdc01b3731604
BINARY_SHA256=e9eaf8ad3467ca980af75abab56969ed7167b4d7a0dd63cd4a112004e4a39fcc
URL="https://github.com/MetaCubeX/mihomo/releases/download/v${VERSION}/mihomo-linux-armv7-v${VERSION}.gz"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$root/app/src/main/jniLibs/armeabi-v7a/libmihomo.so"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -d' ' -f1
    else echo "need sha256sum or shasum" >&2; return 1
    fi
}

temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
gz="$temp/mihomo-linux-armv7-v${VERSION}.gz"
part="$temp/libmihomo.so"

curl --fail --location --retry 2 --max-time 180 --output "$gz" "$URL" \
    || { echo "Official core download failed" >&2; exit 1; }
[ "$(sha256_of "$gz")" = "$ASSET_SHA256" ] \
    || { echo "Compressed core checksum mismatch" >&2; exit 1; }
gunzip -c "$gz" > "$part"
[ "$(sha256_of "$part")" = "$BINARY_SHA256" ] \
    || { echo "Extracted core checksum mismatch" >&2; exit 1; }

mkdir -p "$(dirname "$output")"
mv "$part" "$output"
chmod 644 "$output"
echo "Verified official linux-armv7 mihomo ${VERSION}: ${output}"

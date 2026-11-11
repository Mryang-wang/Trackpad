#!/bin/bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$source_root/../.." && pwd)"
toolchain="$repo_root/Build/BluetoothTrackpadToolchain"
output="$repo_root/Build/BluetoothTrackpad"
platform_jar="${ANDROID_PLATFORM_JAR:-$toolchain/android-35/android.jar}"
android_tools="${ANDROID_BUILD_TOOLS:-$toolchain/android-15}"
app="$output/Trackpad Host.app"
work="$output/.work"

mkdir -p "$app/Contents/MacOS" "$work"
cp "$source_root/macos/Info.plist" "$app/Contents/Info.plist"
xcrun swiftc -parse-as-library -O -target "$(uname -m)-apple-macosx13.0" \
    "$source_root/macos/TrackpadHost.swift" -o "$app/Contents/MacOS/TrackpadHost"
signing="$HOME/Library/Application Support/T1 Microphone/Signing"
keychain="$signing/T1Controller.keychain-db"
certificate="$signing/T1Controller.pem"
password_file="$signing/keychain-password"
if [[ -f "$keychain" && -s "$certificate" && -s "$password_file" ]]; then
    (
        trap 'security lock-keychain "$keychain" >/dev/null 2>&1 || true' EXIT
        security unlock-keychain -p "$(<"$password_file")" "$keychain"
        hash="$(openssl x509 -in "$certificate" -noout -fingerprint -sha1 | tr -d : | cut -d= -f2)"
        printf 'designated => identifier "local.remotevox.trackpadtest.host" and certificate leaf = H"%s"\n' "$hash" \
            > "$work/host.req"
        codesign --force --sign "$hash" --keychain "$keychain" --timestamp=none \
            --requirements "$work/host.req" "$app"
    )
else
    codesign --force --sign - "$app"
fi
codesign --verify --deep --strict "$app"

if [[ ! -f "$platform_jar" || ! -x "$android_tools/aapt2" ]]; then
    printf '%s\n' "Set ANDROID_PLATFORM_JAR to Android API 35 android.jar and ANDROID_BUILD_TOOLS to Build-Tools 35." >&2
    exit 1
fi

classes="$work/classes.jar"
javac -encoding UTF-8 --release 8 -Xlint:all -classpath "$platform_jar" -d "$work/classes" "$source_root"/android/*.java
jar cf "$classes" -C "$work/classes" .
"$android_tools/d8" --lib "$platform_jar" --min-api 23 --output "$work" "$classes"
"$android_tools/aapt2" link -I "$platform_jar" --manifest "$source_root/android/AndroidManifest.xml" \
    --version-code 9 --version-name 0.1.5 -o "$work/unsigned.apk"
zip -q -0 -j "$work/unsigned.apk" "$work/classes.dex"
"$android_tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

key="$work/trackpad-test.jks"
if [[ ! -f "$key" ]]; then
    keytool -genkeypair -keystore "$key" -storepass android -keypass android \
        -alias trackpad-test -dname "CN=Local Trackpad Test" -keyalg RSA -keysize 2048 -validity 3650
fi
apk="$output/Trackpad-Test.apk"
"$android_tools/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$apk" "$work/aligned.apk"
"$android_tools/apksigner" verify --verbose "$apk"
printf 'Mac: %s\nAPK: %s\n' "$app" "$apk"

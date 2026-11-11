#!/bin/bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")" && pwd)"
repo_root="$(cd "$source_root/.." && pwd)"
toolchain="$repo_root/Build/BluetoothTrackpadToolchain"
output="$repo_root/Build"
platform_jar="${ANDROID_PLATFORM_JAR:-$toolchain/android-35/android.jar}"
android_tools="${ANDROID_BUILD_TOOLS:-$toolchain/android-15}"
app="$output/Trackpad Console Host.app"
work="$output/.work"

mkdir -p "$work"
if [[ "${1:-}" != "--android-only" ]]; then
    identity_file="$source_root/macos/SigningIdentity.sha1"
    if [[ ! -f "$identity_file" ]]; then
        printf '%s\n' 'Run ./setup-signing.sh once to create the fixed local signing identity.' >&2
        exit 1
    fi
    signing_identity="$(cat "$identity_file")"
    if [[ ! "$signing_identity" =~ ^[0-9A-Fa-f]{40}$ ]]; then
        printf '%s\n' 'Invalid local signing identity fingerprint.' >&2
        exit 1
    fi
    mkdir -p "$app/Contents/MacOS"
    cp "$source_root/macos/Info.plist" "$app/Contents/Info.plist"
    xcrun swiftc -parse-as-library -O -target "$(uname -m)-apple-macosx13.0" \
        "$source_root"/macos/*.swift -o "$app/Contents/MacOS/TrackpadHost"
    codesign --force --sign "$signing_identity" --timestamp=none "$app"
    codesign --verify --deep --strict "$app"
    printf 'Mac: %s\n' "$app"
fi
if [[ "${1:-}" == "--mac-only" ]]; then
    exit 0
fi

if [[ ! -f "$platform_jar" || ! -x "$android_tools/aapt2" ]]; then
    printf '%s\n' "Set ANDROID_PLATFORM_JAR to Android API 35 android.jar and ANDROID_BUILD_TOOLS to Build-Tools 35." >&2
    exit 1
fi

classes="$work/classes.jar"
javac -encoding UTF-8 --release 8 -Xlint:all -classpath "$platform_jar" -d "$work/classes" "$source_root"/android/*.java
jar cf "$classes" -C "$work/classes" .
"$android_tools/d8" --lib "$platform_jar" --min-api 23 --output "$work" "$classes"
"$android_tools/aapt2" link -I "$platform_jar" --manifest "$source_root/android/AndroidManifest.xml" \
    --version-code 11 --version-name 0.3.7 -o "$work/unsigned.apk"
zip -q -0 -j "$work/unsigned.apk" "$work/classes.dex"
"$android_tools/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"

key="$work/trackpad-test.jks"
if [[ ! -f "$key" ]]; then
    keytool -genkeypair -keystore "$key" -storepass android -keypass android \
        -alias trackpad-test -dname "CN=Local Trackpad Test" -keyalg RSA -keysize 2048 -validity 3650
fi
apk="$output/Trackpad-Console-0.3.7.apk"
"$android_tools/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android --out "$apk" "$work/aligned.apk"
"$android_tools/apksigner" verify --verbose "$apk"
printf 'APK: %s\n' "$apk"

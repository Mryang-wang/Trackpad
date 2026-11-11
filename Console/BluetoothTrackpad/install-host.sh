#!/bin/bash
set -euo pipefail

source_root="$(cd "$(dirname "$0")" && pwd)"
build_app="$source_root/../Build/Trackpad Console Host.app"
installed_app="$HOME/Applications/Trackpad 控制台接收端.app"
identity="$(cat "$source_root/macos/SigningIdentity.sha1")"
if [[ ! "$identity" =~ ^[0-9A-Fa-f]{40}$ ]]; then
    printf '%s\n' 'Invalid signing identity fingerprint.' >&2
    exit 1
fi
codesign --verify --deep --strict \
    -R="identifier \"local.trackpad.console.host\" and certificate leaf = H\"$identity\"" "$build_app"
mkdir -p "$HOME/Applications"
stage="$(mktemp -d "$HOME/Applications/.trackpad-install.XXXXXX")"
trap 'rm -rf "$stage"' EXIT
ditto "$build_app" "$stage/Trackpad 控制台接收端.app"
codesign --verify --deep --strict "$stage/Trackpad 控制台接收端.app"
swift - <<'SWIFT'
import AppKit
for app in NSRunningApplication.runningApplications(withBundleIdentifier: "local.trackpad.console.host") {
    guard app.terminate() else { fputs("Receiver could not quit.\n", stderr); exit(1) }
    let deadline = Date().addingTimeInterval(3)
    while !app.isTerminated && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    guard app.isTerminated else { fputs("Receiver did not exit.\n", stderr); exit(1) }
}
let installed = URL(fileURLWithPath: NSHomeDirectory() + "/Applications/Trackpad 控制台接收端.app")
if FileManager.default.fileExists(atPath: installed.path) {
    var trash: NSURL?
    try FileManager.default.trashItem(at: installed, resultingItemURL: &trash)
    if let trashURL = trash as URL? {
        let unregister = Process()
        unregister.executableURL = URL(fileURLWithPath: "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister")
        unregister.arguments = ["-u", trashURL.path]
        unregister.standardOutput = FileHandle.nullDevice
        unregister.standardError = FileHandle.nullDevice
        try unregister.run()
        unregister.waitUntilExit()
    }
}
SWIFT
mv "$stage/Trackpad 控制台接收端.app" "$installed_app"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$installed_app"
login_service="gui/$(id -u)/local.trackpad.console.host.login"
if launchctl print "$login_service" >/dev/null 2>&1; then
    launchctl kickstart "$login_service"
else
    open -g "$installed_app"
fi
printf 'Installed: %s\n' "$installed_app"

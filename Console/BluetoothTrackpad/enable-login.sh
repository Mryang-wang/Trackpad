#!/bin/bash
set -euo pipefail

installed_app="$HOME/Applications/Trackpad 控制台接收端.app"
service="gui/$(id -u)/local.trackpad.console.host.login"
agent="$HOME/Library/LaunchAgents/local.trackpad.console.host.login.plist"
if [[ ! -x "$installed_app/Contents/MacOS/TrackpadHost" ]]; then
    printf '%s\n' 'Install the Mac receiver before enabling login startup.' >&2
    exit 1
fi
python3 - "$installed_app" "$agent" <<'PYAGENT'
import os
import plistlib
import sys
from pathlib import Path
app, destination = map(Path, sys.argv[1:])
destination.parent.mkdir(parents=True, exist_ok=True)
job = {
    "Label": "local.trackpad.console.host.login",
    "ProgramArguments": [str(app / "Contents/MacOS/TrackpadHost"), "--background"],
    "RunAtLoad": True,
    "KeepAlive": False,
    "LimitLoadToSessionType": "Aqua",
    "ProcessType": "Interactive",
    "AssociatedBundleIdentifiers": ["local.trackpad.console.host"],
}
temporary = destination.with_suffix(".plist.tmp")
with temporary.open("wb") as stream:
    plistlib.dump(job, stream)
os.chmod(temporary, 0o644)
os.replace(temporary, destination)
PYAGENT
if launchctl print "$service" >/dev/null 2>&1; then
    launchctl bootout "$service"
fi
swift - <<'SWIFT'
import AppKit
for app in NSRunningApplication.runningApplications(withBundleIdentifier: "local.trackpad.console.host") {
    guard app.terminate() else { exit(1) }
    let deadline = Date().addingTimeInterval(3)
    while !app.isTerminated && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.1)) }
    guard app.isTerminated else { exit(1) }
}
SWIFT
launchctl enable "$service"
launchctl bootstrap "gui/$(id -u)" "$agent"
printf 'Enabled login startup: %s\n' "$agent"

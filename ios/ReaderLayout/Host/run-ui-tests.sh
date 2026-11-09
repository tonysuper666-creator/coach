#!/bin/bash
# Run from the repository root through tools/dev/heavy --emulator.
set -euo pipefail
device=${1:?Pass an available iOS simulator UDID}
state=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; target=sys.argv[1]; devices=json.load(sys.stdin)["devices"]; print(next(d["state"] for group in devices.values() for d in group if d["udid"] == target))' "$device")
if [[ "$state" == Shutdown ]]; then
  xcrun simctl boot "$device"
  trap 'xcrun simctl shutdown "$device"' EXIT
fi
xcrun simctl bootstatus "$device" -b
xcodebuild -quiet -project ios/ReaderLayout/Host/ReaderLayoutHost.xcodeproj \
  -scheme ReaderLayoutHost -destination "platform=iOS Simulator,id=$device" \
  -derivedDataPath ios/ReaderLayout/.derivedData -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO test

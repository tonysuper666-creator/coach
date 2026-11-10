#!/bin/bash
# Run from the repository root through tools/dev/heavy --emulator.
set -euo pipefail
device=${1:?Pass an available iOS simulator UDID}
shift
state=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; target=sys.argv[1]; devices=json.load(sys.stdin)["devices"]; print(next(d["state"] for group in devices.values() for d in group if d["udid"] == target))' "$device")
if [[ "$state" == Shutdown ]]; then
  xcrun simctl boot "$device"
  trap 'xcrun simctl shutdown "$device"' EXIT
fi
if ! boot_output=$(xcrun simctl bootstatus "$device" -b 2>&1); then
  printf '%s\n' "$boot_output"
  exit 1
fi
printf '%s\n' "$boot_output"
# simctl can return success even when the terminal boot status reports failure.
if [[ "$boot_output" == *"Data Migration Failed"* ]]; then
  exit 1
fi
xcodebuild -quiet -project ios/ReaderLayout/Host/ReaderLayoutHost.xcodeproj \
  -scheme ReaderLayoutHost -destination "platform=iOS Simulator,id=$device" \
  -derivedDataPath ios/ReaderLayout/.derivedData -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO test "$@"

#!/bin/bash
# Run from the repository root through tools/dev/heavy --emulator.
set -euo pipefail
exec python3 "$(dirname "$0")/run_ui_tests.py" "$@"

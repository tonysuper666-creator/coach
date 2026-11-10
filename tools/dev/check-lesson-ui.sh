#!/bin/bash
# Mac-only lesson behavior verification. Preserve failures and collect evidence even on a red suite.
set -u
set -o pipefail
if [ "$(uname -s)" != Darwin ]; then
    echo 'Lesson UI tests must run on Mac; no tests started.' >&2
    exit 64
fi
cd "$(dirname "$0")/../.." || exit 64
mkdir -p build
tools/dev/heavy --local ./gradlew :shared:ui:jvmTest \
    --tests com.coach.ui.MainlineScreenTest \
    --tests com.coach.ui.ReviewEntryTest \
    --tests com.coach.ui.LadderFlowTest --rerun-tasks 2>&1 | tee build/ton1447-ui.log
test_status=${PIPESTATUS[0]}
python3 tools/dev/collect-lesson-evidence.py --output build/ton1447-ui-evidence.zip > build/ton1447-ui-manifest.json
evidence_status=$?
echo "LESSON_UI_EXIT=$test_status EVIDENCE_EXIT=$evidence_status"
echo 'Evidence: build/ton1447-ui-evidence.zip; build/ton1447-ui-manifest.json; build/ton1447-ui.log'
if [ "$test_status" -ne 0 ]; then exit "$test_status"; fi
exit "$evidence_status"

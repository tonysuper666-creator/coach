#!/bin/bash
set -eu
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
exec tools/dev/heavy ./gradlew :shared:domain:jvmTest :shared:client:assemble :server:store:test :server:app:test --max-workers=2 --console=plain "$@"

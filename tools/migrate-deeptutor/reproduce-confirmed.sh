#!/usr/bin/env bash
# Run from the repository root. Inputs must be private captured copies, never live data.
set -euo pipefail
if [[ $# != 6 ]]; then
  echo 'Usage: bash tools/migrate-deeptutor/reproduce-confirmed.sh SNAPSHOT BASELINE_DB SEED PROPOSAL CONFIRMATION NEW_ROOT' >&2
  exit 2
fi
snapshot=$1
baseline=$2
seed=$3
proposal=$4
confirmation=$5
rehearsal_root=$6
python3 - "$snapshot" "$rehearsal_root" "$confirmation" <<'PY'
import hashlib, json, pathlib, shutil, sys
source, dest, decision = map(pathlib.Path, sys.argv[1:])
assert not dest.exists(), 'NEW_ROOT must not exist'
assert not dest.resolve().is_relative_to(source.resolve()), 'Output must be outside snapshot'
dest.mkdir(parents=True, mode=0o700)
def inventory(p):
    return {str(f.relative_to(p)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in sorted(p.rglob('*')) if f.is_file()}
before = inventory(source)
(dest/'source-before-private.json').write_text(json.dumps(before, indent=2))
shutil.copytree(source, dest/'snapshot')
assert inventory(dest/'snapshot') == before
shutil.copyfile(decision, dest/'confirmation.json')
print('Captured private copy; source files:', len(before))
PY
tools/dev/heavy tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor rehearse \
  --snapshot "$rehearsal_root/snapshot" --coach-db "$baseline" --seed-manifest "$seed" \
  --proposal "$proposal" --confirmation "$rehearsal_root/confirmation.json" --out "$rehearsal_root/rehearsal" \
  > "$rehearsal_root/rehearsal.log"
python3 tools/migrate-deeptutor/verify-import.py --snapshot "$rehearsal_root/snapshot" \
  --seed "$seed" --proposal "$proposal" --confirmation "$rehearsal_root/confirmation.json" \
  --out "$rehearsal_root/rehearsal" > "$rehearsal_root/verify.json"
cmp "$rehearsal_root/rehearsal/first-state.json" "$rehearsal_root/rehearsal/second-state.json"
cmp "$rehearsal_root/rehearsal/before-state.json" "$rehearsal_root/rehearsal/rollback-state.json"
cmp "$rehearsal_root/rehearsal/before.sqlite" "$rehearsal_root/rehearsal/rollback.sqlite"
python3 - "$snapshot" "$rehearsal_root" <<'PY'
import hashlib, json, pathlib, sys
source, dest = map(pathlib.Path, sys.argv[1:])
def inventory(p):
    return {str(f.relative_to(p)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in sorted(p.rglob('*')) if f.is_file()}
before = json.loads((dest/'source-before-private.json').read_text())
after = inventory(source)
(dest/'source-after-private.json').write_text(json.dumps(after, indent=2))
assert before == after, 'Captured source changed'
assert before == inventory(dest/'snapshot'), 'Snapshot copy changed'
report = json.loads((dest/'rehearsal/rehearsal.json').read_text())
assert report['second'] == dict(events_added=0, records_inserted=0, records_revised=0, records_unchanged=521)
print(json.dumps(dict(source_files_unchanged=len(before), first=report['first'], second=report['second'],
    state_sha256=report['state_sha256'], projection_sha256=report['projection_sha256'],
    rollback_sqlite_byte_identical=True), indent=2))
PY

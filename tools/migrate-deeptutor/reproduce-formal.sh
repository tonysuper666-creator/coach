#!/usr/bin/env bash
# Capture current Coach read-only; all importer/rollback writes target NEW_ROOT only.
set -euo pipefail
if [[ $# != 7 ]]; then
  echo 'Usage: bash tools/migrate-deeptutor/reproduce-formal.sh CURRENT_DATA SNAPSHOT SEED PROPOSAL CONFIRMATION CONFIRMATION_SHA NEW_ROOT' >&2
  exit 2
fi
current=$1 snapshot=$2 seed=$3 proposal=$4 confirmation=$5 decision_sha=$6 rehearsal_root=$7
export COACH_MIGRATE_LAUNCHER="$PWD/tools/migrate-deeptutor/build/install/migrate-deeptutor/bin/migrate-deeptutor"
python3 - "$current" "$snapshot" "$confirmation" "$decision_sha" "$rehearsal_root" <<'PY'
import hashlib, json, os, pathlib, shutil, sqlite3, sys
current, snapshot, confirmation = (pathlib.Path(s).resolve() for s in sys.argv[1:4])
expected = sys.argv[4]
root = pathlib.Path(sys.argv[5]).resolve()
assert not root.exists(), 'NEW_ROOT must not exist'
for p in (current, snapshot, confirmation, root):
    assert not p.is_relative_to(pathlib.Path.home()/'DeepTutor'), 'Never access DeepTutor runtime'
    assert not any((d/'.git').exists() for d in (p, *p.parents)), 'Private data must stay outside repositories'
for p in (current, snapshot):
    assert not root.is_relative_to(p) and not p.is_relative_to(root), 'Separate source and output'
assert hashlib.sha256(confirmation.read_bytes()).hexdigest() == expected
os.umask(0o077)
root.mkdir(parents=True, mode=0o700)
def inventory(p):
    result = {}
    for f in sorted(p.rglob('*')):
        assert not f.is_symlink(), 'No symbolic links in captured materials'
        if f.is_file():
            result[str(f.relative_to(p))] = hashlib.sha256(f.read_bytes()).hexdigest()
    return result
before = inventory(snapshot)
(root/'source-before-private.json').write_text(json.dumps(before, ensure_ascii=False, indent=2))
shutil.copytree(snapshot, root/'snapshot')
shutil.copyfile(confirmation, root/'confirmation.json')
data = root/'data'
data.mkdir(mode=0o700)
# SQLite's online backup includes committed WAL pages. Never cp an active main file.
with sqlite3.connect((current/'coach.sqlite').as_uri()+'?mode=ro', uri=True) as source:
    with sqlite3.connect(data/'coach.sqlite') as dest:
        source.backup(dest)
        assert dest.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
assert not (current/'blobs').is_symlink()
assert all(f.is_file() and not f.is_symlink() for f in (current/'blobs').iterdir())
shutil.copytree(current/'blobs', data/'blobs', symlinks=False)
assert inventory(snapshot) == before == inventory(root/'snapshot')
print('Captured current Coach online backup and private source:', len(before), 'files')
PY
tools/dev/heavy "$COACH_MIGRATE_LAUNCHER" apply-confirmed \
  --database "$rehearsal_root/data" --backup "$rehearsal_root/backup" --snapshot "$rehearsal_root/snapshot" \
  --seed-manifest "$seed" --proposal "$proposal" --confirmation "$rehearsal_root/confirmation.json" \
  --confirmation-sha256 "$decision_sha" > "$rehearsal_root/apply.log"
# Exercise the actual public rollback CLI against an independent post-import copy.
python3 - "$rehearsal_root" <<'PY'
import pathlib, shutil, sqlite3, sys
root = pathlib.Path(sys.argv[1])
copy = root/'rollback-copy'
copy.mkdir(mode=0o700)
with sqlite3.connect((root/'data/coach.sqlite').resolve().as_uri()+'?mode=ro', uri=True) as source:
    with sqlite3.connect(copy/'coach.sqlite') as dest:
        source.backup(dest)
shutil.copytree(root/'data/blobs', copy/'blobs')
PY
tools/dev/heavy "$COACH_MIGRATE_LAUNCHER" rollback-confirmed --database "$rehearsal_root/rollback-copy" \
  --backup "$rehearsal_root/backup" > "$rehearsal_root/rollback.json"
cmp "$rehearsal_root/backup/coach.sqlite" "$rehearsal_root/rollback-copy/coach.sqlite"
python3 tools/migrate-deeptutor/verify-formal.py --root "$rehearsal_root" --proposal "$proposal" > "$rehearsal_root/independent-verification.json"
python3 - "$snapshot" "$rehearsal_root" <<'PY'
import hashlib, json, pathlib, sys
source, root = map(pathlib.Path, sys.argv[1:])
def inventory(p):
    return {str(f.relative_to(p)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in sorted(p.rglob('*')) if f.is_file()}
before = json.loads((root/'source-before-private.json').read_text())
assert before == inventory(source) == inventory(root/'snapshot')
assert inventory(root/'backup/blobs') == inventory(root/'rollback-copy/blobs')
report = json.loads((root/'backup/result.json').read_text())
report.update(source_files_unchanged=len(before), rollback=json.loads((root/'rollback.json').read_text()),
              production_written=False, activated=False)
(root/'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
PY

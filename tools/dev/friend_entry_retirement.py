#!/usr/bin/env python3
"""Prepare/verify a private retirement dry-run bundle. Never mutates entries."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile

STUB = b"raise SystemExit('Retired service entry; use the reviewed durable controller')\n"


def require(value):
    if not value:
        raise ValueError('retirement gate failed')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def regular(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid())
    return info


def private_json(path):
    info = regular(path)
    require(stat.S_IMODE(info.st_mode) == 0o600)
    return json.loads(Path(path).read_bytes())


def entry(root, relative):
    require(isinstance(relative, str))
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and parts.as_posix() == relative
            and '..' not in parts.parts and len(parts.parts) > 0)
    # Releases, live controller/pair, databases and general service files need
    # separate reviewed procedures. Only explicit historical Python is eligible.
    require((parts.parts[0].startswith('upgrade-')
             or parts.parts[0].startswith('rollback-')) and parts.suffix == '.py')
    path = root / relative
    info = regular(path)
    return path, info


def private_write(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def prepare(root, selection, output):
    root, output = Path(root), Path(output)
    require(root.is_absolute() and root.resolve(strict=True) == root and root.is_dir())
    require(output.is_absolute() and output.parent.resolve(strict=True) == output.parent
            and not output.exists() and not output.is_symlink()
            and root not in output.parents)
    selected = private_json(selection)
    require(isinstance(selected, list) and selected)
    records, payloads = [], {}
    for row in selected:
        require(isinstance(row, dict) and set(row) == {'relative', 'sha256'})
        path, info = entry(root, row['relative'])
        data = path.read_bytes()
        require(sha(data) == row['sha256'] and row['relative'] not in payloads)
        records.append(dict(row, device=info.st_dev, inode=info.st_ino,
                            mode=stat.S_IMODE(info.st_mode)))
        payloads[row['relative']] = data
    output.mkdir(mode=0o700)
    archive = output / 'originals.zip'
    private_write(archive, b'')
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(payloads.items()):
            bundle.writestr(name, data)
    private_write(output / 'refusal.txt', STUB)
    info = root.stat()
    plan = dict(version=1, dryRun=True, root=str(root), device=info.st_dev,
                inode=info.st_ino, files=records, archiveSha256=sha(archive.read_bytes()),
                refusalSha256=sha(STUB))
    encoded = (json.dumps(plan, sort_keys=True, indent=2) + '\n').encode()
    private_write(output / 'plan.json', encoded)
    # Re-read all source identities and bytes after packaging. Drift is failure,
    # even if the bundle was partly written; no source file was ever changed.
    return verify(output / 'plan.json', sha(encoded))


def verify(path, expected):
    path = Path(path)
    plan = private_json(path)
    require(sha(path.read_bytes()) == expected
            and set(plan) == {'version', 'dryRun', 'root', 'device', 'inode', 'files',
                              'archiveSha256', 'refusalSha256'}
            and plan['version'] == 1 and plan['dryRun'] is True)
    root = Path(plan['root'])
    require(root.is_absolute() and root.resolve(strict=True) == root)
    info = root.stat()
    require((info.st_dev, info.st_ino) == (plan['device'], plan['inode']))
    archive = path.parent / 'originals.zip'
    refusal = path.parent / 'refusal.txt'
    for payload in (archive, refusal):
        require(stat.S_IMODE(regular(payload).st_mode) == 0o600)
    require(sha(archive.read_bytes()) == plan['archiveSha256']
            and refusal.read_bytes() == STUB and sha(STUB) == plan['refusalSha256'])
    require(isinstance(plan['files'], list) and plan['files'])
    names = [r['relative'] for r in plan['files']]
    require(len(names) == len(set(names)))
    with zipfile.ZipFile(archive) as bundle:
        require(sorted(bundle.namelist()) == sorted(names))
        for row in plan['files']:
            require(set(row) == {'relative', 'sha256', 'device', 'inode', 'mode'})
            source, info = entry(root, row['relative'])
            require((info.st_dev, info.st_ino, stat.S_IMODE(info.st_mode))
                    == (row['device'], row['inode'], row['mode']))
            require(sha(source.read_bytes()) == row['sha256']
                    and sha(bundle.read(row['relative'])) == row['sha256'])
    return {'files': len(names), 'production_mutations': 0, 'plan_sha256': expected}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    build = commands.add_parser('prepare')
    for option in ('root', 'selection', 'output'):
        build.add_argument('--' + option, required=True)
    check = commands.add_parser('verify')
    check.add_argument('--plan', required=True)
    check.add_argument('--expected-sha256', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            counts = prepare(args.root, args.selection, args.output)
        else:
            counts = verify(args.plan, args.expected_sha256)
        print('RETIREMENT_DRY_RUN=' + json.dumps(counts, sort_keys=True))
    except Exception:
        # Source files may contain private addresses, identities and credentials.
        print('RETIREMENT_DRY_RUN=FAIL', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

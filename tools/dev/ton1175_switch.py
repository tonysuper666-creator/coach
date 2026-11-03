#!/usr/bin/env python3
"""TON-1175 pointer transaction. No action writes unless --apply is present.

The service controller must accept `stop` and `start`; it must operate only on
its owned instance. Configuration and idle evidence are private trusted inputs.
"""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time


class GateError(Exception):
    pass


def require(value, message):
    if not value:
        raise GateError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def atomic(path, data, mode=0o600):
    path = Path(path)
    fd, name = tempfile.mkstemp(prefix='.ton1175-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def file_hash(path):
    return digest(Path(path).read_bytes())


def tree_hash(root):
    root = Path(root)
    require(root.is_dir() and not root.is_symlink(), 'candidate release missing or symlink')
    h = hashlib.sha256()
    for path in sorted(root.rglob('*')):
        relative = str(path.relative_to(root)).encode()
        if path.is_symlink():
            # Venvs commonly link to their system interpreter. Bind the link itself.
            kind, value = b'L', os.readlink(path).encode()
        elif path.is_file():
            kind, value = b'F', file_hash(path).encode() + str(stat.S_IMODE(path.stat().st_mode)).encode()
        elif path.is_dir():
            kind, value = b'D', b''
        else:
            raise GateError('unsupported candidate file type')
        h.update(kind + len(relative).to_bytes(8, 'big') + relative + len(value).to_bytes(8, 'big') + value)
    return h.hexdigest()


def load(path):
    return json.loads(Path(path).read_text())


ACCEPTANCE_SECONDS = 300


def run(argv, timeout=60):
    require(isinstance(argv, list) and argv and all(isinstance(v, str) for v in argv), 'invalid command')
    try:
        require(timeout > 0, 'acceptance deadline expired')
        # A timed out verifier must not leave a child writing evidence later.
        child = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            result = child.wait(timeout=timeout)
        except BaseException:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            raise
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GateError('controller or verifier could not run') from exc
    require(result == 0, 'controller or verifier returned nonzero')


def command(c, action):
    run(c['controller'] + [action])


def get_time(value):
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(parsed.tzinfo is not None, 'idle timestamp missing timezone')
        return parsed.timestamp()
    except (ValueError, AttributeError) as exc:
        raise GateError('invalid timestamp') from exc


def check_idle(c, old_head, stopped=False, coverage_seal=None):
    """Require recent measured evidence, an independent live identity verifier, and log coverage.

    verifier is a trusted executable, not a user-supplied result flag; it must
    independently validate the recording, process identity and installation.
    """
    p = load(c['idleEvidence'])
    now = time.time()
    start, end = get_time(p['windowStart']), get_time(p['windowEnd'])
    verified = float(p['verifiedAtEpoch'])
    require(p['sourceHead'] == old_head and p['clientSocketReads'] == 0
            and p['latestClientRead'] is None, 'idle proof reports client activity or wrong head')
    require(end - start >= 900 and start <= end <= verified <= now + 2
            and 0 <= now - verified <= 120 and now - end <= 120,
            'idle proof missing 15 minutes or is stale/future')
    require(int(p['pid']) > 0 and Path(p['recording']).is_file(), 'missing process or recording')
    # The verifier must fail closed if it cannot check the JFR, same PID and
    # identity, recording coverage and unchanged installation. No output leaks.
    if not stopped:
        run(c['idleVerifier'] + [c['idleEvidence'], c['installation']])
    logs = c['accessLogs']
    require(isinstance(logs, list) and logs, 'access log coverage missing')
    for name in logs:
        path = Path(name)
        require(path.is_file(), 'access log missing')
        for line in path.read_text().splitlines():
            row = json.loads(line)
            at = get_time(row['at'])
            require(at <= now + 2, 'access log has future entry')
            if row.get('protocol') == 1:
                # The trusted coverage verifier reconciles these metadata-only journal rows.
                continue
            if at > end:
                require(isinstance(row.get('route'), str), 'access log route missing')
                require(row['route'] == '/v1/hello', 'client route after idle window')
    # A separate verifier must check log list covers the entire post-window
    # interval, including rotation; it is invoked again after stopping.
    require(not stopped or 'coverage' not in p or coverage_seal, 'shutdown coverage seal missing')
    run(c['logVerifier'] + (['--closed-seal', coverage_seal] if coverage_seal else []) + [c['idleEvidence']] + logs)


def coverage_guard(c):
    if 'coverage' not in load(c['idleEvidence']):
        # Legacy/custom protocols remain subject to their mandatory logVerifier.
        # The shipped verifier rejects them; they gain no coverage=true bypass.
        return contextlib.nullcontext(None)
    try:
        from .activity_coverage import stop_guard
    except ImportError:
        from activity_coverage import stop_guard
    return stop_guard(c['idleEvidence'], c['accessLogs'])


def load_state(c):
    ops = Path(c['stateDir'])
    require(ops.is_dir() and stat.S_IMODE(ops.stat().st_mode) == 0o700, 'private state missing')
    state = load(ops / 'state.json')
    for name in ('old.plist', 'new.plist', 'old.json', 'new.json'):
        path = ops / name
        require(path.is_file() and stat.S_IMODE(path.stat().st_mode) == 0o600
                and file_hash(path) == state['files'][name], 'recovery material missing or changed')
    require(state['configHash'] == digest(json.dumps(c, sort_keys=True).encode()), 'configuration changed')
    return ops, state


def set_journal(ops, value):
    atomic(ops / 'journal.json', json.dumps({'phase': value}).encode())


def current(c, ops):
    pairs = ((c['plist'], 'old.plist', 'new.plist'), (c['installation'], 'old.json', 'new.json'))
    result = []
    for target, old, new in pairs:
        data = Path(target).read_bytes()
        result.append('old' if data == (ops / old).read_bytes() else
                      'new' if data == (ops / new).read_bytes() else 'unknown')
    return result


def restore(c, ops, state):
    # Journal stays recoverable if the process or host fails between operations.
    set_journal(ops, 'recovering')
    try:
        command(c, 'stop')
        for target, source, mode in ((c['plist'], 'old.plist', state['plistMode']),
                                      (c['installation'], 'old.json', state['installationMode'])):
            atomic(target, (ops / source).read_bytes(), mode)
        command(c, 'start')
        set_journal(ops, 'rolled_back')
    except BaseException:
        set_journal(ops, 'recovery_required')
        raise


def prepare(c, apply):
    payload, destination = Path(c['payload']), Path(c['release'])
    if destination.exists():
        require(tree_hash(destination) == c['candidateTreeSha256'], 'existing release differs; never overwrite')
        print('release already prepared; no action')
        return
    require(destination.is_absolute() and payload.is_absolute() and destination.parent.is_dir(),
            'payload and release must be absolute; release parent must exist')
    require(payload.resolve() != destination.resolve() and payload.resolve() not in destination.resolve().parents,
            'release must be outside payload')
    require(tree_hash(payload) == c['candidateTreeSha256'], 'prepared payload hash changed')
    if not apply:
        print('prepare dry-run: payload hash verified; no files written')
        return
    # Do not stage a partially copied tree under the final release name.
    temp = Path(tempfile.mkdtemp(prefix='.ton1175-release-', dir=destination.parent))
    try:
        shutil.copytree(payload, temp, dirs_exist_ok=True, symlinks=True)
        require(tree_hash(temp) == c['candidateTreeSha256'], 'copied payload hash mismatch')
        require(not destination.exists(), 'release appeared during copy')
        os.rename(temp, destination)
    finally:
        if temp.exists():
            shutil.rmtree(temp)
    print('release prepared; dependencies and provenance must be verified before stage')


def stage(c, apply):
    ops, release = Path(c['stateDir']), Path(c['release'])
    if ops.exists():
        load_state(c)
        print('already staged; recovery material retained')
        return
    for name in ('acceptanceSnapshot', 'acceptanceVerifier'):
        argv = c.get(name)
        require(isinstance(argv, list) and argv and all(isinstance(v, str) for v in argv),
                'five-minute acceptance command missing: ' + name)
    old = Path(c['installation']).read_bytes()
    old_plist = Path(c['plist']).read_bytes()
    info, plist = json.loads(old), plistlib.loads(old_plist)
    require(digest(old) == c['expectedInstallationSha256'] and info['sourceHead'] == c['expectedSourceHead'],
            'old installation head/hash changed')
    require(Path(info['root']).is_dir(), 'old release missing')
    require(c['candidateHead'] != info['sourceHead'] and len(c['candidateHead']) == 40, 'invalid candidate head')
    for rel in ('app/bin/app', 'tools/bridge/.venv/bin/python', 'tools/mathsvc/src/server.mjs'):
        require((release / rel).is_file(), 'candidate dependency missing: ' + rel)
    require((release / 'tools/mathsvc/node_modules').is_dir(), 'candidate npm dependencies missing')
    require(file_hash(release / 'source-head.txt') == c['sourceHeadFileSha256']
            and (release / 'source-head.txt').read_text().strip() == c['candidateHead'], 'candidate provenance mismatch')
    candidate_hash = tree_hash(release)
    require(candidate_hash == c['candidateTreeSha256'], 'candidate release hash changed')
    # This must check build provenance and the relocated dependency environment;
    # file presence alone is not evidence that Python and npm dependencies work.
    run(c['candidateVerifier'] + [str(release), c['candidateHead'], c['plist']])
    newinfo = dict(info, root=str(release), sourceHead=c['candidateHead'])
    newplist = dict(plist)
    newplist['ProgramArguments'] = [str(release / 'app/bin/app')]
    newplist['WorkingDirectory'] = str(release)
    env = dict(newplist['EnvironmentVariables'])
    env.update(COACH_SERVICE_ROOT=str(release), COACH_PYTHON=str(release / 'tools/bridge/.venv/bin/python'),
               COACH_MATHSVC=str(release / 'tools/mathsvc/src/server.mjs'))
    newplist['EnvironmentVariables'] = env
    files = {'old.plist': old_plist, 'old.json': old,
             'new.plist': plistlib.dumps(newplist), 'new.json': json.dumps(newinfo, indent=2).encode()}
    state = {'version': 2, 'configHash': digest(json.dumps(c, sort_keys=True).encode()), 'candidateTreeSha256': candidate_hash,
             'files': {name: digest(value) for name, value in files.items()},
             'plistMode': stat.S_IMODE(Path(c['plist']).stat().st_mode),
             'installationMode': stat.S_IMODE(Path(c['installation']).stat().st_mode)}
    if not apply:
        print('stage dry-run: gates passed; no files written')
        return
    ops.mkdir(mode=0o700)
    try:
        for name, value in files.items():
            atomic(ops / name, value)
        atomic(ops / 'state.json', json.dumps(state, indent=2).encode())
        atomic(ops / 'lock', b'')
        set_journal(ops, 'staged')
    except BaseException:
        # Never remove a partially-created directory automatically: operator can inspect it.
        raise
    print('stage complete: old pointers retained privately; service untouched')


def operate(c, action, apply):
    ops, state = load_state(c)
    lock = open(ops / 'lock', 'rb')
    with lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        phase = load(ops / 'journal.json')['phase']
        pointers = current(c, ops)
        require('unknown' not in pointers, 'unknown pointers; manual intervention required')
        if action == 'switch':
            require(state.get('version') == 2, 'legacy stage lacks acceptance contract; create a new stage')
            if phase == 'switched' and pointers == ['new', 'new']:
                print('already switched; no action')
                return
            require(phase in ('staged', 'rolled_back') and pointers == ['old', 'old'],
                    'incomplete transaction; use recover')
            require(tree_hash(c['release']) == state['candidateTreeSha256'], 'candidate release changed')
            run(c['candidateVerifier'] + [c['release'], c['candidateHead'], c['plist']])
            check_idle(c, c['expectedSourceHead'])
            if not apply:
                print('switch dry-run: gates passed; no stop or pointer changes')
                return
            # Snapshot original identities and data high-water marks before stopping.
            baseline = ops / 'acceptance-baseline.json'
            baseline.unlink(missing_ok=True)
            run(c['acceptanceSnapshot'] + [str(baseline)])
            require(baseline.is_file() and not baseline.is_symlink()
                    and stat.S_IMODE(baseline.stat().st_mode) == 0o600,
                    'private acceptance baseline missing')
            with coverage_guard(c) as seal:
                set_journal(ops, 'switching')
                try:
                    command(c, 'stop')
                    require(current(c, ops) == ['old', 'old'], 'pointers changed while stopping')
                    check_idle(c, c['expectedSourceHead'], stopped=True, coverage_seal=seal)
                    atomic(c['plist'], (ops / 'new.plist').read_bytes(), state['plistMode'])
                    atomic(c['installation'], (ops / 'new.json').read_bytes(), state['installationMode'])
                    # The startup itself spends the same five-minute budget.
                    started = time.time()
                    deadline = time.monotonic() + ACCEPTANCE_SECONDS
                    run(c['controller'] + ['start'], timeout=min(60, deadline - time.monotonic()))
                    set_journal(ops, 'validating')
                    run(c['acceptanceVerifier'] + [str(baseline), str(started),
                                                   str(started + ACCEPTANCE_SECONDS)],
                        timeout=deadline - time.monotonic())
                    require(time.monotonic() <= deadline, 'acceptance deadline expired')
                    require(current(c, ops) == ['new', 'new'], 'pointers changed during acceptance')
                    set_journal(ops, 'switched')
                    print('candidate accepted within five-minute transaction')
                except BaseException as original:
                    try:
                        restore(c, ops, state)
                    except BaseException as rollback_error:
                        raise GateError('switch failed and recovery failed; run recover --apply after diagnosing controller') from rollback_error
                    raise GateError('switch failed; old pointers restored and old service started') from original
        else:
            if phase == 'rolled_back' and pointers == ['old', 'old']:
                print('already rolled back; no action')
                return
            require(phase in ('switching', 'validating', 'switched', 'recovering', 'recovery_required'),
                    'no switch transaction to recover')
            if not apply:
                print('rollback dry-run: no stop or pointer changes')
                return
            try:
                restore(c, ops, state)
            except BaseException as error:
                raise GateError('rollback failed; inspect controller then run recover --config CONFIG --apply') from error
            print('old pointers restored and old service started; current database retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'stage', 'switch', 'rollback', 'recover'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise GateError('transaction interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGHUP, interrupted)
    try:
        c = load(args.config)
        if args.action == 'prepare':
            prepare(c, args.apply)
        elif args.action == 'stage':
            stage(c, args.apply)
        else:
            operate(c, args.action, args.apply)
    except GateError as error:
        print('ERROR:', str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError):
        print('ERROR: configuration or recovery evidence unreadable', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

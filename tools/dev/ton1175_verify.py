#!/usr/bin/env python3
"""Read-only TON-1175 gates, plus explicit private build/snapshot evidence writers.

No defaults point at a service, database, token or production port. Legacy access
logs cannot prove idle; coverage v1 requires process-owned counters and a held
admission fence through shutdown, in addition to the independent JFR gate.
"""
import argparse
import contextlib
import ctypes
import fcntl
import http.client
import json
import os
from pathlib import Path
import plistlib
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit

try:
    from .ton1175_switch import GateError, atomic, digest, file_hash, get_time, load, require, tree_hash
except ImportError:
    from ton1175_switch import GateError, atomic, digest, file_hash, get_time, load, require, tree_hash

HEALTH_LOOP_OLD_HEAD = '8d3608f5f32bb690f36284e71ca55ceec9e39bbf'


def release_links(release):
    root = Path(release).resolve()
    python = root / 'tools/bridge/.venv/bin/python'
    for path in root.rglob('*'):
        if path.is_symlink() and root not in path.resolve().parents:
            require(path.parent == python.parent and re.fullmatch(r'python(?:3(?:\.[0-9]+)?)?', path.name)
                    and path.resolve() == python.resolve(), 'unbound external release symlink')
    require(os.access(root / 'app/bin/app', os.X_OK), 'candidate app is not executable')


def captured(argv, **kwargs):
    # Only closed-schema machine output is consumed, never replayed on failure.
    result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=60, **kwargs)
    require(result.returncode == 0, 'dependency or evidence command failed')
    return result.stdout


def dependencies(release, node):
    release = Path(release).resolve()
    python = release / 'tools/bridge/.venv/bin/python'
    requirements = release / 'tools/bridge/requirements.txt'
    pins = {}
    for line in requirements.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            match = re.fullmatch(r'([A-Za-z_][\w-]*)==([\w.]+)', line)
            require(match is not None, 'unrecognized Python dependency pin')
            pins[match[1]] = match[2]
    require(pins, 'Python dependency pins missing')
    script = '''import importlib, importlib.metadata, json, pathlib, sys
root=pathlib.Path(sys.argv[1]); pins=json.loads(sys.argv[2])
assert pathlib.Path(sys.prefix).resolve()==root.resolve()
for name, version in pins.items():
 assert importlib.metadata.version(name)==version
 module=importlib.import_module(name.replace('-', '_'))
 assert root.resolve() in pathlib.Path(module.__file__).resolve().parents
'''
    captured([str(python), '-I', '-B', '-c', script, str(python.parent.parent), json.dumps(pins)])
    # Check the interpreter's target as well as the venv's symlink text.
    external = {str(python.resolve()): file_hash(python.resolve()),
                str(Path(node).resolve()): file_hash(Path(node).resolve())}
    major = int(captured([node, '-p', 'process.versions.node.split(".")[0]']).strip())
    require(major >= 22, 'Node 22 or newer required')
    # Actually load MathJax and render, rather than testing directory presence.
    with subprocess.Popen([node, str(release / 'tools/mathsvc/src/server.mjs')],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as child:
        try:
            output, _ = child.communicate(b'{"id":"gate","tex":"x^2"}\n', timeout=15)
        except BaseException:
            child.kill()
            child.wait()
            raise
        require(child.returncode == 0, 'math service failed')
    rows = [json.loads(line) for line in output.splitlines()]
    require(len(rows) == 1 and rows[0].get('id') == 'gate'
            and '<svg' in rows[0].get('svg', '') and 'error' not in rows[0],
            'math service did not render')
    return external


def attest(checkout, release, node, java, output, usage_old_head=None, usage_allow_nontemporary=False):
    """Build before the outage, bind final relocated tree to a clean source head.

    The output and its SHA must be reviewed/pinned outside the candidate tree.
    Verification does not rebuild or install anything in the outage.
    """
    checkout, release = Path(checkout).resolve(), Path(release).resolve()
    if usage_old_head is not None:
        # Production evidence writes require an explicit, separately authorized opt-in.
        roots = (Path(tempfile.gettempdir()).resolve(), Path('/tmp').resolve())
        require(usage_allow_nontemporary or all(any(root in path.parents for root in roots)
                for path in (release, Path(output).resolve())),
                'schema evidence defaults to temporary release and manifest paths')
    else:
        require(not usage_allow_nontemporary, 'schema old head required for non-temporary opt-in')
    require(not captured(['git', '-C', str(checkout), 'status', '--porcelain']).strip(),
            'build checkout must be clean')
    head = captured(['git', '-C', str(checkout), 'rev-parse', 'HEAD']).decode().strip()
    subprocess.run([str(checkout / 'tools/dev/heavy'), './gradlew', ':server:app:installDist', '--offline'],
                   cwd=checkout, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    require(not captured(['git', '-C', str(checkout), 'status', '--porcelain']).strip(),
            'build changed checkout')
    require(tree_hash(checkout / 'server/app/build/install/app') == tree_hash(release / 'app'),
            'release app does not match this clean build')
    tracked = captured(['git', '-C', str(checkout), 'ls-files', '-z', 'tools/bridge', 'tools/mathsvc',
                        'tools/dev/service-child.py'])
    for name in tracked.decode().split('\0'):
        if name:
            require((release / name).is_file() and file_hash(checkout / name) == file_hash(release / name),
                    'release dependency sources do not match build checkout')
    require((release / 'source-head.txt').read_text().strip() == head, 'release source head mismatch')
    external = dependencies(release, node)
    release_links(release)
    require(captured([java, '--version']).startswith((b'openjdk 17.', b'java 17.')), 'JDK 17 required')
    external[str(Path(java).resolve())] = file_hash(Path(java).resolve())
    manifest = {'version': 1, 'sourceHead': head, 'root': str(release), 'node': str(Path(node).resolve()),
                'java': str(Path(java).resolve()),
                'treeSha256': tree_hash(release), 'externalExecutables': external}
    if usage_old_head is not None:
        if not __package__:
            sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
        from tools.dev.ton1193_usage_schema import attest_revisions
        manifest['usageObservationSchema'] = attest_revisions(checkout, usage_old_head, head)
    require(release not in Path(output).resolve().parents, 'manifest must be outside release')
    require(not Path(output).exists(), 'never overwrite provenance evidence')
    atomic(output, json.dumps(manifest, sort_keys=True).encode())
    print('build evidence sha256=' + file_hash(output))


def candidate(manifest_path, expected_hash, release, head, plist_path):
    require(file_hash(manifest_path) == expected_hash, 'build evidence changed')
    proof = load(manifest_path)
    require(proof['version'] == 1 and proof['sourceHead'] == head
            and proof['root'] == str(Path(release).resolve()), 'build provenance mismatch')
    require(tree_hash(release) == proof['treeSha256'], 'release differs from attested clean build')
    release_links(release)
    require(all(file_hash(name) == value for name, value in proof['externalExecutables'].items()),
            'external interpreter changed')
    env = plistlib.loads(Path(plist_path).read_bytes())['EnvironmentVariables']
    require(str(Path(env['COACH_NODE']).resolve()) == proof['node']
            and str((Path(env['JAVA_HOME']) / 'bin/java').resolve()) == proof['java'],
            'service runtime does not match attested interpreters')
    external = dependencies(release, proof['node'])
    external[proof['java']] = file_hash(proof['java'])
    require(external == proof['externalExecutables'],
            'relocated dependency environment changed')


def logs(evidence, paths, closed_seal=None):
    require(paths and all(Path(name).is_file() for name in paths), 'access logs missing')
    try:
        from .activity_coverage import verify
    except ImportError:
        from activity_coverage import verify
    verify(evidence, paths, stopped=closed_seal is not None, seal=closed_seal)


def darwin_lock_owner(path, pid):
    """Query the kernel, without taking/releasing another process's lock.

    Darwin lsof can report a blank lock field for Java FileChannel locks.
    Darwin's sys/fcntl.h defines this native flock layout; F_GETLK returns the
    conflicting owner's PID and exact range. Other platforms keep the lsof gate.
    """
    require(sys.platform == 'darwin', 'unsupported kernel lock query')
    class Flock(ctypes.Structure):
        _fields_ = [('start', ctypes.c_int64), ('length', ctypes.c_int64),
                    ('pid', ctypes.c_int32), ('kind', ctypes.c_short), ('whence', ctypes.c_short)]
    require(ctypes.sizeof(Flock) == 24, 'unsupported Darwin flock ABI')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        query = Flock(0, 0, 0, fcntl.F_WRLCK, os.SEEK_SET)
        held = Flock.from_buffer_copy(fcntl.fcntl(fd, fcntl.F_GETLK, bytes(query)))
        after = os.stat(path, follow_symlinks=False)
        require((before.st_dev, before.st_ino) == (after.st_dev, after.st_ino), 'server lock inode changed')
        require(held.pid == int(pid) and held.kind == fcntl.F_WRLCK
                and held.start == 0 and held.length == 0 and held.whence == os.SEEK_SET,
                'recorded JVM does not exclusively own whole-file server lock')
    finally:
        os.close(fd)


@contextlib.contextmanager
def staged_bridge_port(config_path, installation, expected_config_hash):
    """Bind the pin to the same staged, current old plist and installation.

    No value from idle evidence participates. The private stage and both
    pointer files are rechecked after the JFR inspection.
    """
    if not __package__:
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.dev.ton1193_transaction import _read_file, load_bound_state
    from tools.dev.ton1193_trust import directory_snapshot
    config_path = Path(config_path)
    config_parent = directory_snapshot(config_path.parent)
    config_bytes, config_inode = _read_file(config_path, config_parent, 0o600)
    config = json.loads(config_bytes)
    require(digest(json.dumps(config, sort_keys=True).encode()) == expected_config_hash,
            'stage config differs from active transaction')
    require(str(installation) == config['installation'], 'stage installation differs')
    state_id = directory_snapshot(config['stateDir'])
    state, state_hash = load_bound_state(config, state_id)
    parents = {key: directory_snapshot(Path(config[key]).parent) for key in ('plist', 'installation')}
    pointers = {}
    for key, extension, mode in (('plist', 'plist', 'plistMode'),
                                  ('installation', 'json', 'installationMode')):
        pointers[key] = _read_file(config[key], parents[key], state[mode])
        require(digest(pointers[key][0]) == state['files']['old.' + extension],
                'stage pointer is not the bound old side')
    require(json.loads(pointers['installation'][0]).get('sourceHead')
            == config.get('expectedSourceHead') == HEALTH_LOOP_OLD_HEAD,
            'unreviewed managed health loop source')
    env = plistlib.loads(pointers['plist'][0])['EnvironmentVariables']
    pin = config['adapterEnvPins']['BRIDGE_PORT']
    require(isinstance(pin, str) and re.fullmatch(r'[1-9][0-9]{0,4}', pin)
            and int(pin) <= 65535 and env['BRIDGE_PORT'] == pin
            and env['COACH_MANAGE_SERVICES'] == config['adapterEnvPins']['COACH_MANAGE_SERVICES'] == '1',
            'managed bridge pin differs')
    yield int(pin)
    require(_read_file(config_path, config_parent, 0o600) == (config_bytes, config_inode),
            'stage config changed during inspection')
    load_bound_state(config, state_id, state_hash)
    for key, mode in (('plist', 'plistMode'), ('installation', 'installationMode')):
        require(_read_file(config[key], parents[key], state[mode]) == pointers[key],
                'stage pointer changed during inspection')


def idle(evidence, installation, java, jcmd, lsof, stage_config=None, stage_config_sha256=None):
    require(bool(stage_config) == bool(stage_config_sha256), 'stage config and transaction digest required together')
    p, info = load(evidence), load(installation)
    pid = str(int(p['pid']))
    require(int(pid) > 0 and info['sourceHead'] == p['sourceHead']
            and file_hash(installation) == p['installationSha256'], 'JFR installation mismatch')
    start, end = get_time(p['windowStart']), get_time(p['windowEnd'])
    now = time.time()
    require(end-start >= 900 and start <= end <= now and now-end <= 120,
            'JFR window is short, stale or future')
    recording = Path(p['recording'])
    require(file_hash(recording) == p['recordingSha256'], 'JFR recording changed')

    def live_identity():
        ps = captured(['/bin/ps', '-p', pid, '-o', 'lstart=', '-o', 'command=']).strip()
        require(digest(ps) == p['processIdentitySha256'], 'recorded JVM process identity changed')
        require(str(Path(info['root']) / 'app/lib').encode() in ps
                and b'com.coach.server.MainKt' in ps, 'live JVM is not the installed server')
        # Merely having the file open is insufficient. Query Darwin's kernel
        # for an exact whole-file write-lock owner; retain lsof on other hosts.
        lock = Path(info['dataDir']) / 'server.lock'
        if sys.platform == 'darwin':
            darwin_lock_owner(lock, pid)
        else:
            lines = captured([lsof, '-nP', '-F', 'pl', '--', str(lock)]).decode().splitlines()
            owners, owner = [], None
            for line in lines:
                if line.startswith('p'):
                    owner = line[1:]
                elif line == 'lW':
                    owners.append(owner)
            require(owners == [pid], 'recorded JVM does not exclusively own server lock')
        return ps

    before = live_identity()
    inspector = Path(__file__).with_name('Ton1175Jfr.java')
    bridge = staged_bridge_port(stage_config, installation, stage_config_sha256) if stage_config else contextlib.nullcontext(None)
    with bridge as port:
        command = [java, str(inspector), str(recording), p['windowStart'], p['windowEnd'], pid]
        if port is not None:
            command.append(str(port))
        measured = json.loads(captured(command))
        require(measured['pid'] == int(pid) and measured['rejectReason'] == 'NONE' and measured['clientReads'] == 0
                and measured['socketReads'] == measured['attributedHealthReads'],
                'JFR contains client socket activity')
    # Uptime binds the live PID to the recorded JVM start, not just a PID which
    # might have been reused since the recording. No jcmd output is disclosed.
    uptime = captured([jcmd, pid, 'VM.uptime']).decode()
    match = re.search(r'(?m)^([0-9]+(?:\.[0-9]+)?) s\s*$', uptime)
    require(match is not None and abs(time.time()-float(match[1])-measured['jvmStartEpochMillis']/1000) < 1,
            'recording belongs to a different JVM lifetime')
    require(live_identity() == before and file_hash(installation) == p['installationSha256']
            and file_hash(recording) == p['recordingSha256'], 'identity changed during verification')


@contextlib.contextmanager
def database(path):
    db = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True, timeout=1)
    try:
        db.execute('PRAGMA query_only=ON')
        db.execute('BEGIN')
        yield db
    finally:
        db.close()


def identity(db, device):
    row = db.execute('SELECT token_sha256 FROM devices WHERE device_id=? AND revoked_at IS NULL',
                     (device,)).fetchone()
    require(row is not None, 'original paired device missing or revoked')
    return row[0]


def token(c):
    return Path(c['desktopTokenFile']).read_text().strip()


def snapshot(config, output):
    c = load(config)
    require(c['phoneDevice'] != c['desktopDevice'], 'phone must be a separate original device')
    with database(c['database']) as db:
        require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'database integrity failed')
        epoch = db.execute('SELECT epoch FROM ledger_meta WHERE singleton=1').fetchone()[0]
        desktop, phone = identity(db, c['desktopDevice']), identity(db, c['phoneDevice'])
        require(digest(token(c).encode()) == desktop, 'original desktop token does not match database')
        seq = db.execute('SELECT coalesce(max(seq),0) FROM events').fetchone()[0]
    logs = []
    require(c['accessLogs'], 'acceptance access logs missing')
    for name in c['accessLogs']:
        path = Path(name)
        data, st = path.read_bytes(), path.stat()
        require(not data or data.endswith(b'\n'), 'incomplete pre-switch log row')
        logs.append({'path': name, 'device': st.st_dev, 'inode': st.st_ino,
                     'offset': len(data), 'prefixSha256': digest(data)})
    proof = {'version': 1, 'configHash': file_hash(config), 'createdAt': time.time(), 'epoch': epoch,
             'seq': seq, 'desktopSha256': desktop, 'phoneSha256': phone, 'logs': logs}
    atomic(output, json.dumps(proof).encode())


def hello(c, epoch, remaining):
    url = urlsplit(c['helloUrl'])
    require(url.scheme == 'http' and url.hostname in ('127.0.0.1', '::1')
            and url.path == '/v1/hello' and not url.query and not url.fragment
            and url.username is None and url.password is None and url.port is not None,
            'hello must be an explicit loopback /v1/hello URL')
    connection = http.client.HTTPConnection(url.hostname, url.port, timeout=min(3, remaining))
    try:
        connection.request('GET', '/v1/hello', headers={'Authorization': 'Bearer ' + token(c),
                                                     'X-Coach-Device': c['desktopDevice']})
        response = connection.getresponse()
        require(response.status == 200, 'original desktop authentication failed')
        require(json.loads(response.read(65536))['epoch'] == epoch, 'hello epoch changed')
    finally:
        connection.close()


def positive_evidence(c, baseline, started, deadline):
    """Only fetch IDs/counts; never read learning content or raw answers into Python."""
    phone_seen = False
    for old in baseline['logs']:
        path = Path(old['path'])
        st = path.stat()
        require((st.st_dev, st.st_ino) == (old['device'], old['inode'])
                and st.st_size >= old['offset'], 'acceptance log rotated or truncated')
        with path.open('rb') as stream:
            require(digest(stream.read(old['offset'])) == old['prefixSha256'], 'log prefix changed')
            data = stream.read()
        # An in-flight partial final line is pending, not evidence.
        for line in data.split(b'\n')[:-1]:
            row = json.loads(line)
            at = get_time(row['at'])
            require(at <= time.time() + 2, 'future access row')
            if started <= at <= deadline and row.get('device') == c['phoneDevice']:
                if (row.get('status') == 200 and row.get('method') in ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD')
                        and row.get('route') not in (None, '/v1/pair', '/unmatched')):
                    phone_seen = True
    with database(c['database']) as db:
        require(db.execute('SELECT epoch FROM ledger_meta WHERE singleton=1').fetchone()[0]
                == baseline['epoch'], 'database epoch changed')
        require(identity(db, c['desktopDevice']) == baseline['desktopSha256']
                and identity(db, c['phoneDevice']) == baseline['phoneSha256']
                and digest(token(c).encode()) == baseline['desktopSha256'], 'original credentials changed')
        # Completion alone is not a lecture: require the tutor's matching delivery event.
        lecture = db.execute('''SELECT count(*) FROM events e JOIN messages m
            ON m.message_id=json_extract(e.payload,'$.msgId') AND m.thread_id=e.thread_id
            WHERE e.seq>? AND e.recorded_at BETWEEN ? AND ? AND e.course_id=? AND e.thread_id=?
            AND e.type='explanation_delivered' AND m.status='complete'
            AND coalesce(m.role,'assistant')='assistant' AND length(trim(m.content_md))>0
            AND m.created_at>=?''', (baseline['seq'], int(started*1000), int(deadline*1000),
                                    c['course'], c['thread'], int(started*1000))).fetchone()[0] > 0
        # Three distinct posed/answered/graded attempts in ONE new group and target thread.
        groups = db.execute('''WITH fresh AS (SELECT * FROM events WHERE seq>?
            AND recorded_at BETWEEN ? AND ? AND course_id=?)
            SELECT json_extract(p.payload,'$.groupId'),count(DISTINCT json_extract(p.payload,'$.attemptId'))
            FROM fresh p JOIN fresh a ON a.type='answer_submitted'
              AND json_extract(a.payload,'$.attemptId')=json_extract(p.payload,'$.attemptId') AND a.seq>p.seq
            JOIN fresh g ON g.type='attempt_graded'
              AND json_extract(g.payload,'$.attemptId')=json_extract(p.payload,'$.attemptId') AND g.seq>a.seq
            WHERE p.type='question_posed' AND p.thread_id=?
            AND a.device_id IN (?,?) AND json_extract(p.payload,'$.groupId')<>''
            GROUP BY json_extract(p.payload,'$.groupId')''',
            (baseline['seq'], int(started*1000), int(deadline*1000), c['course'], c['thread'],
             c['desktopDevice'], c['phoneDevice'])).fetchall()
        exercise = any(count >= 3 for _, count in groups)
    return phone_seen and lecture and exercise


def accept(config, baseline_path, started, deadline):
    c, baseline = load(config), load(baseline_path)
    now = time.time()
    require(baseline['version'] == 1 and baseline['configHash'] == file_hash(config),
            'acceptance baseline/config changed')
    require(baseline['createdAt'] <= started <= now <= deadline
            and started - baseline['createdAt'] <= 120 and 0 < deadline-started <= 300,
            'acceptance window invalid or baseline stale')
    monotonic_deadline = time.monotonic() + deadline - now
    # Active traffic is limited to the authorized original desktop hello. Phone,
    # lecture and exercises are observed passively; never pair or call a tutor.
    hello(c, baseline['epoch'], monotonic_deadline - time.monotonic())
    while True:
        require(time.monotonic() < monotonic_deadline, 'required acceptance evidence timed out')
        if positive_evidence(c, baseline, started, deadline):
            hello(c, baseline['epoch'], monotonic_deadline - time.monotonic())
            require(time.monotonic() < monotonic_deadline, 'required acceptance evidence timed out')
            return
        time.sleep(min(1, max(0, monotonic_deadline - time.monotonic())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    build = sub.add_parser('attest')
    for name in ('checkout', 'release', 'node', 'java', 'output'):
        build.add_argument('--' + name, required=True)
    build.add_argument('--usage-old-head', help='full old Git commit for usage schema proof')
    build.add_argument('--usage-allow-nontemporary', action='store_true',
                       help='explicitly allow schema evidence outside temporary roots')
    check = sub.add_parser('candidate')
    check.add_argument('--manifest', required=True)
    check.add_argument('--sha256', required=True)
    check.add_argument('release')
    check.add_argument('head')
    check.add_argument('plist')
    coverage = sub.add_parser('logs')
    coverage.add_argument('--closed-seal')
    coverage.add_argument('evidence')
    coverage.add_argument('paths', nargs='+')
    recording = sub.add_parser('idle')
    for name in ('java', 'jcmd', 'lsof'):
        recording.add_argument('--' + name, required=True)
    recording.add_argument('--stage-config')
    recording.add_argument('--stage-config-sha256')
    recording.add_argument('evidence')
    recording.add_argument('installation')
    for name in ('snapshot', 'accept'):
        p = sub.add_parser(name)
        p.add_argument('--config', required=True)
        p.add_argument('baseline')
        if name == 'accept':
            p.add_argument('started', type=float)
            p.add_argument('deadline', type=float)
    args = parser.parse_args()
    try:
        if args.action == 'attest':
            attest(args.checkout, args.release, args.node, args.java, args.output,
                   args.usage_old_head, args.usage_allow_nontemporary)
        elif args.action == 'candidate':
            candidate(args.manifest, args.sha256, args.release, args.head, args.plist)
        elif args.action == 'logs':
            logs(args.evidence, args.paths, args.closed_seal)
        elif args.action == 'idle':
            idle(args.evidence, args.installation, args.java, args.jcmd, args.lsof, args.stage_config,
                 args.stage_config_sha256)
        elif args.action == 'snapshot':
            snapshot(args.config, args.baseline)
        else:
            accept(args.config, args.baseline, args.started, args.deadline)
    except GateError as error:
        print('ERROR:', error, file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error, subprocess.SubprocessError,
            http.client.HTTPException):
        # Do not print exception messages containing tokens, rows, URLs or content.
        print('ERROR: evidence unreadable or dependency unavailable', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

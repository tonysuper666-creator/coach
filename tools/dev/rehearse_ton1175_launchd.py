#!/usr/bin/env python3
"""Opt-in real JVM/launchd rehearsal. Takes 15 minutes plus 5-minute timeout.

No production configuration, data, credentials or bridge is read. The acceptance
hook tests isolated authentication and a real ledger commit, not tutor learning.
"""
import argparse
import concurrent.futures
import datetime as dt
import json
import os
from pathlib import Path
import plistlib
import signal
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import traceback
import uuid

try:
    from .activity_coverage import Control, healthy, observed
    from .ton1175_launchd import Adapter, FORBIDDEN_PORTS
    from .ton1175_switch import atomic, digest, file_hash, load, require, tree_hash
except ImportError:
    from activity_coverage import Control, healthy, observed
    from ton1175_launchd import Adapter, FORBIDDEN_PORTS
    from ton1175_switch import atomic, digest, file_hash, load, require, tree_hash

HERE = Path(__file__).resolve()
REPO = HERE.parents[2]
CASES = ('normal', 'before_seal', 'held_seal', 'stopped', 'pointers',
         'validating', 'acceptance_timeout', 'recovery_timeout')


def run(argv, timeout=90):
    result = subprocess.run(list(map(str, argv)), stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=timeout)
    require(result.returncode == 0, 'isolated rehearsal subprocess failed: ' + Path(str(argv[0])).name)
    return result.stdout


def write(path, value):
    atomic(path, json.dumps(value, indent=2).encode())


def port():
    while True:
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            value = listener.getsockname()[1]
        if value not in FORBIDDEN_PORTS:
            return value


def snapshot(root):
    adapter = Adapter(root / 'adapter.json')
    adapter.material()
    with sqlite3.connect(root / 'data/coach.sqlite') as db:
        return dict(epoch=db.execute('SELECT epoch FROM ledger_meta').fetchone()[0],
                    seq=db.execute('SELECT coalesce(max(seq),0) FROM events').fetchone()[0],
                    tokenSha256=file_hash(root / 'data/device-token'))


def hang(root, checkpoint):
    # Both this workload and its ignoring descendant must be removed by the
    # guardian group. A late marker detects cleanup that only reaps the leader.
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = subprocess.Popen([sys.executable, '-c',
        'import pathlib,signal,sys,time\n'
        'signal.signal(signal.SIGTERM,signal.SIG_IGN)\n'
        'while True:\n pathlib.Path(sys.argv[1]).touch();time.sleep(.1)\n',
        str(root / 'heartbeat')], stdin=subprocess.DEVNULL)
    write(root / 'fault.json', dict(checkpoint=checkpoint, pid=os.getpid(), child=child.pid))
    while True:
        time.sleep(1)


def hook(action, root, args):
    root = root.resolve()
    adapter = Adapter(root / 'adapter.json')
    c = load(root / 'rehearsal.json')
    phase = load(root / 'ops/journal.json')['phase'] if (root / 'ops/journal.json').exists() else None
    if action == 'bootstrap_race':
        original = adapter.command
        def command(argv):
            result = original(argv)
            if argv[:2] == ['/bin/launchctl', 'bootstrap'] and result.returncode == 0:
                write(root / 'bootstrap-created.json', dict(controller=os.getpid()))
                os.kill(os.getpid(), signal.SIGSTOP)
            return result
        adapter.command = command
        adapter.start()
    elif action == 'candidate':
        release, head, _ = args
        proof = load(root / 'build.json')
        require(str(Path(release).resolve()) == str(root / 'candidate') and head == c['head']
                and tree_hash(Path(release) / 'app') == proof['appTreeSha256'], 'isolated JVM artifact changed')
    elif action == 'idle':
        try:
            from .ton1175_verify import idle
        except ImportError:
            from ton1175_verify import idle
        idle(*args, c['java'], c['jcmd'], '/usr/sbin/lsof')
        if c['case'] == 'before_seal':
            hang(root, 'before_seal')
    elif action == 'snapshot':
        write(Path(args[0]), snapshot(root))
    elif action == 'accept':
        baseline, started, deadline = args
        require(float(deadline)-float(started) == 300 and time.time() < float(deadline),
                'real five-minute acceptance reserve changed')
        before, now = load(baseline), snapshot(root)
        require(adapter.ready(adapter.material()[0]), 'isolated candidate authenticated hello failed')
        require(now['epoch'] == before['epoch'] and now['tokenSha256'] == before['tokenSha256'],
                'isolated identity changed')
        # Commit through the actual authenticated command endpoint. No fake DB,
        # direct SQL insertion, model/bridge call or injected application clock.
        import http.client
        payload = json.dumps(dict(type='save_note', courseId='ton1175-isolated',
            clientTime=int(time.time()*1000), payload=dict(content='isolated rehearsal note')),
            separators=(',', ':'))
        command = dict(deviceId=adapter.label, commandId='ton1175-candidate-write',
                       payloadBytes=payload, payloadSha256=digest(payload.encode()))
        connection = http.client.HTTPConnection('127.0.0.1', adapter.port, timeout=3)
        try:
            connection.request('POST', '/v1/commands', body=json.dumps(command), headers={
                'Content-Type': 'application/json', 'X-Coach-Device': adapter.label,
                'Authorization': 'Bearer ' + (root / 'data/device-token').read_text().strip()})
            response = connection.getresponse()
            receipt = json.loads(response.read())
            require(response.status == 200 and receipt['status'] == 'OK' and receipt['eventSeqs'],
                    'candidate ledger commit failed')
            write(root / 'committed.json', dict(seq=receipt['eventSeqs'][0]))
        finally:
            connection.close()
        if c['case'] in ('validating', 'acceptance_timeout', 'recovery_timeout'):
            hang(root, 'validating')
    elif action == 'controller':
        operation = args[0]
        first = not (root / 'fault-consumed').exists()
        if operation == 'stop' and phase == 'switching' and first:
            if c['case'] == 'held_seal':
                (root / 'fault-consumed').touch()
                hang(root, 'held_seal')
            if c['case'] == 'stopped':
                adapter.stop()
                (root / 'fault-consumed').touch()
                hang(root, 'stopped')
        if operation == 'stop' and phase == 'recovering' and c['case'] == 'held_seal':
            # Worker death must release its real seal before a restart can mask
            # it. Prove same PID, healthy counters and HTTP 200 before bootout.
            with Control(root / 'state/activity.sock') as control:
                point = control.ask('snapshot')
            healthy(point)
            require(not point.get('seal') and point['pid'] == c['oldPid'], 'EOF did not release seal')
            require(adapter.ready(adapter.material()[0]), 'old JVM remained fenced')
            write(root / 'seal-released.json', dict(pid=point['pid'], http=200, seal=None))
        if operation == 'stop' and phase == 'recovering' and c['case'] == 'recovery_timeout' \
                and not (root / 'repair').exists():
            hang(root, 'recovery')
        if operation == 'start' and phase == 'switching' and first:
            if c['case'] == 'pointers':
                (root / 'fault-consumed').touch()
                hang(root, 'pointers')
            if c['case'] == 'startup_failure':
                (root / 'fault-consumed').touch()
                p, _ = adapter.material()
                p['EnvironmentVariables']['JAVA_OPTS'] += ' -XX:TON1175InvalidOption'
                failed = root / 'failed-start.plist'
                atomic(failed, plistlib.dumps(p))
                adapter.plist = failed
                adapter.timeout = 3
                try:
                    adapter.start()
                finally:
                    job = adapter.command(['/bin/launchctl', 'print', adapter.target])
                    require(b'last exit code = 1' in job.stdout, 'startup failure was not observed by launchd')
                    write(root / 'startup-failure.json', dict(launchdExitCode=1))
                raise RuntimeError('invalid JVM option unexpectedly succeeded')
        getattr(adapter, operation)()


class Rehearsal:
    def __init__(self, case, java_home, head, old_head, app_hash):
        self.root = Path(tempfile.mkdtemp(prefix='ton1175-', dir='/tmp')).resolve()
        os.chmod(self.root, 0o700)
        self.case, self.process = case, None
        self.audits = []
        self.config = self.root / 'switch.json'
        self.java, self.jcmd = java_home / 'bin/java', java_home / 'bin/jcmd'
        self.head, self.old_head = head, old_head
        self.app_hash = app_hash

    def setup(self):
        root = self.root
        for name in ('home', 'data', 'logs', 'state', 'packs'):
            (root / name).mkdir(mode=0o700)
        for name, head in (('old', self.old_head), ('candidate', self.head)):
            release = root / name
            shutil.copytree(REPO / 'server/app/build/install/app', release / 'app')
            (release / 'source-head.txt').write_text(head + '\n')
        # Unmanaged JVMs deliberately do not start Python or MathJax. These
        # inert paths satisfy the generic switcher's release shape only; they
        # are NOT dependency provenance evidence for a managed release.
        release = root / 'candidate'
        for name in ('tools/bridge/.venv/bin/python', 'tools/mathsvc/src/server.mjs'):
            path = release / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unused: unmanaged isolated JVM rehearsal\n')
        (release / 'tools/mathsvc/node_modules').mkdir()
        write(root / 'build.json', dict(appTreeSha256=self.app_hash))
        label = 'local.coach.ton1175.test.' + uuid.uuid4().hex
        service_port, bridge_port = port(), port()
        while bridge_port == service_port:
            bridge_port = port()
        env = dict(HOME=str(root / 'home'), JAVA_HOME=str(self.java.parent.parent),
            JAVA_OPTS=f'-Xmx128m -XX:ActiveProcessorCount=2 -Duser.home={root / "home"}',
            COACH_DATA_DIR=str(root / 'data'), COACH_TOKEN_FILE=str(root / 'data/device-token'),
            COACH_LOG_DIR=str(root / 'logs'), COACH_STATE_DIR=str(root / 'state'),
            COACH_PACKS_DIR=str(root / 'packs'), COACH_ACTIVITY_SOCKET=str(root / 'state/activity.sock'),
            COACH_TAIL_HOST='', COACH_MANAGE_SERVICES='0', COACH_TRAFFIC='test',
            COACH_SOURCE_LOOKUP='false', COACH_DEVICE_ID=label, COACH_PORT=str(service_port),
            COACH_BRIDGE_URL=f'http://127.0.0.1:{bridge_port}')
        atomic(root / 'service.plist', plistlib.dumps(dict(Label=label, RunAtLoad=True,
            KeepAlive=False, ProgramArguments=[str(root / 'old/app/bin/app')],
            WorkingDirectory=str(root / 'old'), EnvironmentVariables=env,
            StandardOutPath=str(root / 'stdout.log'), StandardErrorPath=str(root / 'stderr.log'))))
        write(root / 'installation.json', dict(root=str(root / 'old'), sourceHead=self.old_head,
                                               dataDir=str(root / 'data')))
        write(root / 'adapter.json', dict(root=str(root), label=label, domain=f'gui/{os.getuid()}',
            port=service_port, plist=str(root / 'service.plist'), installation=str(root / 'installation.json')))
        self.adapter = Adapter(root / 'adapter.json')
        self.adapter.start()
        self.old_pid = load(self.adapter.owner)['pid']
        write(root / 'rehearsal.json', dict(case=self.case, head=self.head, oldPid=self.old_pid,
                                           java=str(self.java), jcmd=str(self.jcmd)))
        settings = '<configuration version="2.0" label="TON1175 isolated">'
        for event in ('SocketRead', 'ActiveSetting', 'DataLoss', 'CPULoad', 'JVMInformation'):
            settings += f'<event name="jdk.{event}"><setting name="enabled">true</setting>'
            if event == 'SocketRead':
                settings += '<setting name="threshold">0 ns</setting>'
            if event == 'CPULoad':
                settings += '<setting name="period">1 s</setting>'
            settings += '</event>'
        settings += '</configuration>'
        atomic(root / 'idle.jfc', settings.encode())
        run([self.jcmd, self.old_pid, 'JFR.start', 'name=ton1175', f'settings={root / "idle.jfc"}'])
        self.heartbeat_after()  # observe a real sample before opening the window
        with Control(root / 'state/activity.sock') as control:
            self.start = control.ask('snapshot')
        healthy(self.start)
        # JFR timestamps and Python wall time can differ slightly. Keep our
        # own control reads strictly outside the observed window; coverage
        # snapshots still bracket both guards, so ingress there also rejects.
        time.sleep(2)
        self.window_start = time.time()
        self.monotonic_start = time.monotonic()
        self.identity = digest(run(['/bin/ps', '-p', self.old_pid, '-o', 'lstart=', '-o', 'command=']).strip())

    def heartbeat_after(self, after=None):
        """Wait for recorded coverage, rather than assuming JFR startup latency."""
        recording = self.root / 'heartbeat.jfr'
        deadline = time.monotonic()+15
        while time.monotonic() < deadline:
            run([self.jcmd, self.old_pid, 'JFR.dump', 'name=ton1175', f'filename={recording}'])
            os.chmod(recording, 0o600)
            data = json.loads(run([self.java.parent / 'jfr', 'print', '--json',
                                   '--events', 'jdk.CPULoad', recording]))
            beats = [dt.datetime.fromisoformat(e['values']['startTime'].replace('Z', '+00:00')).timestamp()
                     for e in data['recording']['events']]
            if beats and (after is None or max(beats) >= after):
                return recording
            time.sleep(.25)
        raise RuntimeError('real JFR heartbeat boundary deadline expired')

    def evidence(self):
        require(time.monotonic()-self.monotonic_start >= 903, 'real observation still incomplete')
        end = time.time()
        time.sleep(2)
        with Control(self.root / 'state/activity.sock') as control:
            finish = control.ask('snapshot')
        recording = self.root / 'idle.jfr'
        os.replace(self.heartbeat_after(end), recording)
        stamp = lambda value: dt.datetime.fromtimestamp(value, dt.timezone.utc).isoformat()
        proof = dict(windowStart=stamp(self.window_start), windowEnd=stamp(end),
            verifiedAtEpoch=time.time(), sourceHead=self.old_head, pid=self.old_pid,
            recording=str(recording), recordingSha256=file_hash(recording),
            processIdentitySha256=self.identity, installationSha256=file_hash(self.root / 'installation.json'),
            clientSocketReads=0, latestClientRead=None, coverage=dict(protocol=1,
                socket=str(self.root / 'state/activity.sock'), start=self.start, end=finish))
        observed(proof)
        write(self.root / 'idle.json', proof)
        return end-self.window_start, (finish['monoNs']-self.start['monoNs'])/1e9

    def stage(self):
        root = self.root
        helper = lambda action: [sys.executable, str(HERE), '--hook', action, '--root', str(root)]
        c = dict(stateDir=str(root / 'ops'), release=str(root / 'candidate'), payload=str(root / 'candidate'),
            installation=str(root / 'installation.json'), plist=str(root / 'service.plist'),
            expectedInstallationSha256=file_hash(root / 'installation.json'), expectedSourceHead=self.old_head,
            candidateHead=self.head, sourceHeadFileSha256=file_hash(root / 'candidate/source-head.txt'),
            candidateTreeSha256=tree_hash(root / 'candidate'), controller=helper('controller'),
            idleEvidence=str(root / 'idle.json'), accessLogs=list(map(str, (root / 'logs').glob('activity-*.log'))),
            candidateVerifier=helper('candidate'), idleVerifier=helper('idle'),
            logVerifier=[sys.executable, str(HERE.with_name('ton1175_verify.py')), 'logs'],
            acceptanceSnapshot=helper('snapshot'), acceptanceVerifier=helper('accept'))
        write(self.config, c)
        run([sys.executable, HERE.with_name('ton1175_switch.py'), 'stage', '--config', self.config, '--apply'])

    def launch(self, action='switch', record='supervision'):
        # Only transaction/grace/recovery are shortened. Real admission/JFR
        # clocks and the 300-second acceptance reserve are never injected.
        recovery = 6 if self.case == 'recovery_timeout' and record == 'supervision' else 60
        code = ('from tools.dev.ton1175_supervise import main,Budgets\n'
                f'raise SystemExit(main(budgets=Budgets(35,2,{recovery},300)))')
        self.process = subprocess.Popen([sys.executable, '-c', code, action, '--config', str(self.config),
            '--record-dir', str(self.root / record), '--apply'], cwd=REPO, stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def wait(self, predicate, seconds=90):
        deadline = time.monotonic()+seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            require(self.process is None or self.process.poll() is None, 'supervisor exited before fault checkpoint')
            time.sleep(.03)
        raise RuntimeError('real fault checkpoint deadline expired')

    def audit(self, record='supervision'):
        events = [json.loads(line) for line in (self.root / record / 'events.jsonl').read_text().splitlines()]
        registered = [e['pgid'] for e in events if e['event'] == 'command_registered']
        require(registered == [e['pgid'] for e in events if e['event'] == 'command_reaped'],
                'owned command group was not reaped')
        rows = run(['/bin/ps', '-axo', 'pgid=,stat=']).decode().splitlines()
        require(not any(int(row.split()[0]) in registered and not row.split()[1].startswith('Z')
                        for row in rows), 'command workload survived group cleanup')
        require(load(self.adapter.owner)['pgid'] not in registered, 'service shared a command process group')
        self.audits.append(dict(record=record, events=events))
        return events

    def exercise(self):
        seconds, mono = self.evidence()
        self.stage()
        self.launch()
        if self.case in ('before_seal', 'held_seal', 'stopped', 'pointers', 'validating', 'recovery_timeout'):
            self.wait(lambda: (self.root / 'fault.json').exists())
            fault = load(self.root / 'fault.json')
            owner = load(self.root / 'supervision/owner.json')
            pid = owner['workerPid']
            require(pid and int(run(['/bin/ps', '-p', pid, '-o', 'ppid='])) == self.process.pid,
                    'worker is not the live supervisor child')
            # The supervisor still owns this live, unreaped child. Never replay
            # its audit PID after exit; this signal is only the test injection.
            os.kill(pid, signal.SIGSTOP if self.case == 'before_seal' else signal.SIGKILL)
        code = self.process.wait(timeout=380)
        self.process = None
        events = self.audit()
        if self.case == 'normal':
            require(code == 0 and load(self.root / 'ops/journal.json')['phase'] == 'switched', 'switch did not commit')
            self.launch('rollback', 'manual-rollback')
            require(self.process.wait(timeout=90) == 0, 'manual rollback failed')
            self.process = None
            self.audit('manual-rollback')
        elif self.case == 'before_seal':
            require(code == 124 and load(self.adapter.owner)['pid'] == self.old_pid, 'pre-seal timeout disturbed JVM')
        elif self.case == 'recovery_timeout':
            require(code == 2 and events[-1]['outcome'] == 'recovery_required', 'missing failed recovery evidence')
            require(load(self.root / 'ops/journal.json')['phase'] in ('recovering', 'recovery_required'),
                    'failed recovery journal is misleading')
            (self.root / 'repair').touch()
            self.launch('recover', 'retry-recovery')
            require(self.process.wait(timeout=90) == 0, 'explicit recovery retry failed')
            self.process = None
            self.audit('retry-recovery')
        else:
            require(code in (1, 124), 'fault did not return a recovered failure')
        phase = load(self.root / 'ops/journal.json')['phase']
        require(phase == ('staged' if self.case == 'before_seal' else 'rolled_back'), 'wrong final journal')
        require(load(self.root / 'installation.json')['sourceHead'] == self.old_head
                and self.adapter.ready(self.adapter.material()[0]), 'old service unavailable')
        committed = (self.root / 'committed.json').exists()
        if committed:
            with sqlite3.connect(self.root / 'data/coach.sqlite') as db:
                require(db.execute('SELECT count(*) FROM events WHERE seq=? AND type=?',
                    (load(self.root / 'committed.json')['seq'], 'note_saved')).fetchone()[0] == 1,
                    'committed candidate ledger row was lost')
        if self.case == 'held_seal':
            require(load(self.root / 'seal-released.json')['pid'] == self.old_pid, 'missing same-JVM release proof')
        acceptance = [e['at'] for e in events if e['event'] == 'acceptance_budget']
        termination = [e['at'] for e in events if e['event'] == 'worker_terminate']
        reserve_elapsed = termination[0]-acceptance[0] if acceptance and termination else None
        if self.case == 'acceptance_timeout':
            require(reserve_elapsed is not None and reserve_elapsed >= 299,
                    'real acceptance reserve was shortened')
        return dict(case=self.case, exitCode=code, finalPhase=phase, oldHello=200,
            observedSeconds=seconds, coverageMonotonicSeconds=mono, jfrVerified=True,
            sameJvmSealRelease=self.case == 'held_seal', committedLedgerRowRetained=committed,
            serviceParent=1, commandGroupsReaped=sum(e['event']=='command_reaped' for e in events),
            acceptanceReserveElapsedSeconds=reserve_elapsed,
            recoveryRequiredObserved=self.case == 'recovery_timeout',
            idleProof=load(self.root / 'idle.json'), supervisionAudit=self.audits)

    def cleanup(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            self.process.wait(timeout=200)
        if hasattr(self, 'adapter'):
            self.adapter.stop()
            require(self.adapter.job() is None, 'test label survived cleanup')
        shutil.rmtree(self.root)
        require(not self.root.exists(), 'test plist/root survived cleanup')


def bootstrap_race(java_home, head, old_head, app_hash):
    """Kill the live launcher caller after bootstrap but before PID registration."""
    job = Rehearsal('bootstrap_race', java_home, head, old_head, app_hash)
    controller = None
    try:
        job.setup()
        job.adapter.stop()
        controller = subprocess.Popen([sys.executable, str(HERE), '--hook', 'bootstrap_race',
            '--root', str(job.root)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL)
        deadline = time.monotonic()+30
        while not (job.root / 'bootstrap-created.json').exists():
            require(controller.poll() is None and time.monotonic() < deadline, 'bootstrap race checkpoint missing')
            time.sleep(.03)
        require(load(job.root / 'bootstrap-created.json')['controller'] == controller.pid
                and not job.adapter.owner.exists(), 'controller already registered job')
        controller.kill()  # exact live unreaped child, never an audit PID
        controller.wait(timeout=5)
        controller = None
        deadline = time.monotonic()+20
        while True:
            pid = job.adapter.job()
            if pid:
                try:
                    if job.adapter.identity(pid):
                        break
                except Exception:
                    pass
            require(time.monotonic() < deadline, 'unregistered real JVM did not start')
            time.sleep(.1)
        job.adapter.stop()
        require(job.adapter.job() is None, 'bootstrap race left a loaded job')
        return dict(controllerKilledBeforeRegistration=True, realJvmStarted=True,
                    exactIntentJobRemoved=True, cleanupComplete=True)
    finally:
        if controller is not None and controller.poll() is None:
            controller.kill()
            controller.wait(timeout=5)
        job.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--hook', choices=('candidate', 'idle', 'snapshot', 'accept', 'controller', 'bootstrap_race'))
    parser.add_argument('--root', type=Path)
    parser.add_argument('--cases', nargs='+', choices=CASES + ('startup_failure',), default=list(CASES) + ['startup_failure'])
    args, rest = parser.parse_known_args()
    if args.hook:
        require(args.root is not None, 'isolated hook root missing')
        try:
            hook(args.hook, args.root, rest)
        except Exception as error:
            # Private diagnostics have a fixed type/action schema and no child
            # output, exception text, payload, credentials or process argv.
            frames = traceback.extract_tb(error.__traceback__)
            frame = frames[-2] if frames[-1].name == 'require' and len(frames) > 1 else frames[-1]
            write(args.root / 'hook-error.json', dict(action=args.hook, error=type(error).__name__,
                source=Path(frame.filename).name, line=frame.lineno))
            raise
        return
    require(not rest, 'unexpected rehearsal arguments')
    if not args.apply:
        print('dry-run: no jobs or files created; --apply --output FILE builds and rehearses isolated JVMs')
        return
    require(sys.platform == 'darwin' and args.output and not args.output.exists(), 'new output on macOS required')
    java_home = Path(run(['/usr/libexec/java_home', '-v', '17']).decode().strip())
    environment = dict(os.environ, JAVA_HOME=str(java_home))
    subprocess.run([str(REPO / 'tools/dev/heavy'), './gradlew', ':server:app:installDist', '--offline', '--console=plain'],
                   cwd=REPO, env=environment, check=True)
    head = run(['git', '-C', REPO, 'rev-parse', 'HEAD']).decode().strip()
    old_head = run(['git', '-C', REPO, 'rev-parse', 'origin/main']).decode().strip()
    require(head != old_head, 'distinct old/candidate source heads required')
    # Scope is tools/dev: both releases legitimately use this same JVM build.
    require(not run(['git', '-C', REPO, 'diff', old_head, head, '--', 'server', 'shared']), 'JVM source changed; build old separately')
    app_hash = tree_hash(REPO / 'server/app/build/install/app')
    creation = bootstrap_race(java_home, head, old_head, app_hash)
    jobs, results, failures = [], [], []
    try:
        for case in dict.fromkeys(args.cases):
            job = Rehearsal(case, java_home, head, old_head, app_hash)
            jobs.append(job)
            job.setup()
            print('observing real JVM:', case, flush=True)
        while max(903-(time.monotonic()-j.monotonic_start) for j in jobs) > 0:
            remaining = max(903-(time.monotonic()-j.monotonic_start) for j in jobs)
            print('real observation remaining seconds:', round(remaining), flush=True)
            time.sleep(min(30, remaining))
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(jobs)) as pool:
            pending = {pool.submit(j.exercise): j for j in jobs}
            while pending:
                done, _ = concurrent.futures.wait(pending, timeout=30, return_when=concurrent.futures.FIRST_COMPLETED)
                for future in done:
                    job = pending.pop(future)
                    try:
                        results.append(future.result())
                        print('fault passed:', job.case, flush=True)
                    except Exception as error:
                        # Diagnostic type/checkpoint only; never service output.
                        failure = dict(case=job.case, error=type(error).__name__, check=str(error))
                        for name in ('hook-error.json', 'ops/journal.json', 'supervision/owner.json'):
                            if (job.root / name).is_file():
                                value = load(job.root / name)
                                failure[name] = value if name != 'supervision/owner.json' else value['last']
                        if (job.root / 'idle.jfr').is_file():
                            # Fixed-schema metadata only, no addresses, stack
                            # traces, request bodies or arbitrary thread names.
                            data = json.loads(run([job.java.parent / 'jfr', 'print', '--json',
                                '--events', 'jdk.SocketRead', job.root / 'idle.jfr']))
                            failure['socketReads'] = [dict(start=e['values']['startTime'],
                                duration=e['values']['duration'], bytes=e['values']['bytesRead'],
                                control=e['values']['eventThread']['javaName'] == 'coach-activity-control')
                                for e in data['recording']['events'] if e['values']['bytesRead'] > 0]
                        failures.append(failure)
                        print('fault failed:', job.case, type(error).__name__, flush=True)
                if pending:
                    print('waiting for bounded scenarios:', ','.join(j.case for j in pending.values()), flush=True)
    except Exception as error:
        failures.append(dict(stage='setup_or_observation', error=type(error).__name__))
        raise
    finally:
        for job in jobs:
            try:
                job.cleanup()
            except Exception as error:
                failures.append(dict(case=job.case, cleanup=type(error).__name__,
                    privateRoot=str(job.root), target=job.adapter.target if hasattr(job, 'adapter') else None))
        write(args.output, dict(result='pass' if not failures else 'fail', sourceHead=head, oldHead=old_head,
            appTreeSha256=app_hash, cases=sorted(results, key=lambda r:r['case']), failures=failures,
            bootstrapRace=creation,
            cleanupComplete=all(not j.root.exists() for j in jobs), productionValidated=False,
            scope='unmanaged isolated JVM/launchd; synthetic identity and note; no tutor/dependency gate'))
    require(not failures, 'isolated rehearsal failed; inspect redacted output')


if __name__ == '__main__':
    main()

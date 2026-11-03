#!/usr/bin/env python3
"""Controller for disposable TON-1175 launchd jobs; never a production controller."""
import argparse
import http.client
import json
import os
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import sys
import time
from urllib.parse import urlsplit

try:
    from .ton1175_switch import GateError, atomic, digest, load, require
except ImportError:
    from ton1175_switch import GateError, atomic, digest, load, require

FORBIDDEN_PORTS = {8790, 8791, 18791, 3790}


def private(path, directory=False):
    node = path.lstat()
    require(not path.is_symlink() and node.st_uid == os.getuid()
            and stat.S_IMODE(node.st_mode) == (0o700 if directory else 0o600),
            'private owned test material required')


class Adapter:
    def __init__(self, config):
        self.config = Path(config).absolute()
        private(self.config)
        c = load(self.config)
        self.root = Path(c['root']).resolve()
        require(self.root.parent == Path('/tmp').resolve()
                and re.fullmatch(r'ton1175-[A-Za-z0-9_-]+', self.root.name),
                'disposable /tmp/ton1175-* root required')
        private(self.root, True)
        self.inside(self.config)
        self.label = c['label']
        require(re.fullmatch(r'local\.coach\.ton1175\.test\.[a-f0-9]{32}', self.label),
                'isolated random test label required')
        self.domain = c['domain']
        require(self.domain in (f'gui/{os.getuid()}', f'user/{os.getuid()}'), 'own user domain required')
        self.target = self.domain + '/' + self.label
        self.plist, self.installation = self.inside(c['plist']), self.inside(c['installation'])
        self.port = int(c['port'])
        require(1024 <= self.port <= 65535 and self.port not in FORBIDDEN_PORTS, 'unsafe test port')
        self.timeout = float(c.get('timeout', 20))
        require(0 < self.timeout <= 30, 'bounded controller timeout required')
        self.owner = self.root / 'launchd-owner.json'

    def inside(self, value):
        path = Path(value).absolute()
        require(self.root in path.resolve().parents and not path.is_symlink(), 'path escapes disposable root')
        return path

    def material(self):
        private(self.plist); private(self.installation)
        p, info = plistlib.loads(self.plist.read_bytes()), load(self.installation)
        release = self.inside(info['root'])
        require(p['Label'] == self.label and p.get('RunAtLoad') is True
                and p.get('KeepAlive') is False, 'unexpected test job policy')
        require(p['ProgramArguments'] == [str(release / 'app/bin/app')]
                and Path(p['WorkingDirectory']).resolve() == release, 'job differs from installed test release')
        self.inside(p['StandardOutPath']); self.inside(p['StandardErrorPath'])
        e = p['EnvironmentVariables']
        for key in ('HOME', 'COACH_DATA_DIR', 'COACH_TOKEN_FILE', 'COACH_LOG_DIR',
                    'COACH_STATE_DIR', 'COACH_PACKS_DIR', 'COACH_ACTIVITY_SOCKET'):
            self.inside(e[key])
        require(int(e['COACH_PORT']) == self.port and e['COACH_TAIL_HOST'] == ''
                and e['COACH_MANAGE_SERVICES'] == '0' and e['COACH_TRAFFIC'] == 'test'
                and e['COACH_SOURCE_LOOKUP'] == 'false'
                and e['COACH_DEVICE_ID'] == self.label, 'unsafe service environment')
        bridge = urlsplit(e['COACH_BRIDGE_URL'])
        require(bridge.scheme == 'http' and bridge.hostname == '127.0.0.1'
                and bridge.port not in FORBIDDEN_PORTS and bridge.port != self.port
                and bridge.port is not None and bridge.port >= 1024, 'unsafe test bridge')
        require(f'-Duser.home={e["HOME"]}' in e['JAVA_OPTS'].split(), 'isolated JVM home required')
        return p, info

    @staticmethod
    def command(argv):
        return subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, timeout=10)

    def job(self):
        result = self.command(['/bin/launchctl', 'print', self.target])
        if result.returncode == 113:  # ESRCH: the exact job is absent.
            return None
        require(result.returncode == 0, 'cannot inspect test launchd job')
        match = re.search(rb'^\s*pid = ([0-9]+)\s*$', result.stdout, re.M)
        return int(match[1]) if match else 0

    def identity(self, pid):
        require(pid > 1, 'unsafe job PID')
        # Never infer daemon ownership from an executable name or an old PID file.
        daemon = self.command(['multica', 'daemon', 'status', '--output', 'json'])
        require(daemon.returncode == 0 and int(json.loads(daemon.stdout)['pid']) != pid,
                'test job PID must differ from Multica daemon')
        result = self.command(['/bin/ps', '-p', str(pid), '-o', 'lstart=', '-o', 'command='])
        if result.returncode != 0:
            return None
        require(str(self.root).encode() in result.stdout and b'/app/lib/' in result.stdout
                and b'com.coach.server.MainKt' in result.stdout, 'unexpected test service process')
        parent = self.command(['/bin/ps', '-p', str(pid), '-o', 'ppid='])
        require(parent.returncode == 0 and int(parent.stdout) == 1
                and os.getpgid(pid) != os.getpgrp(), 'service is not isolated under launchd')
        return digest(result.stdout.strip())

    def remember(self, pid):
        identity = self.identity(pid)
        require(identity is not None, 'test JVM disappeared')
        value = dict(label=self.label, pid=pid, identity=identity, pgid=os.getpgid(pid), ppid=1)
        atomic(self.owner, json.dumps(value).encode())
        return value

    def ready(self, p):
        token = Path(p['EnvironmentVariables']['COACH_TOKEN_FILE'])
        if not token.is_file():
            return False
        private(token)
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=1)
        try:
            connection.request('GET', '/v1/hello', headers={
                'Authorization': 'Bearer ' + token.read_text().strip(),
                'X-Coach-Device': self.label})
            response = connection.getresponse()
            payload = response.read(65536)
            return response.status == 200 and isinstance(json.loads(payload), dict)
        except (OSError, ValueError, http.client.HTTPException):
            return False
        finally:
            connection.close()

    def start(self):
        p, _ = self.material()
        pid = self.job()
        if pid:
            self.remember(pid)
            require(self.ready(p), 'loaded test job is not ready')
            return
        require(pid is None, 'test job already loaded without a live JVM; stop it first')
        result = self.command(['/bin/launchctl', 'bootstrap', self.domain, str(self.plist)])
        require(result.returncode == 0, 'test job bootstrap failed')
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            pid = self.job()
            if pid:
                # The launcher can briefly precede its exec(java). Only retain
                # a checked JVM identity; bootout still owns this exact label.
                try:
                    self.remember(pid)
                except GateError:
                    time.sleep(0.1)
                    continue
                if self.ready(p):
                    return
            time.sleep(0.1)
        raise GateError('test JVM readiness deadline expired; exact label retained for stop/recover')

    def stop(self):
        # Recovery must also work between the two atomic pointer writes. The
        # configured random label is authoritative here, not either pointer.
        pid = self.job()
        if pid is None:
            return
        identity = self.identity(pid) if pid else None
        if identity is not None:
            private(self.owner)
            owner = load(self.owner)
            require(owner['label'] == self.label and owner['pid'] == pid
                    and owner['identity'] == identity, 'test service identity changed')
        require(self.command(['/bin/launchctl', 'bootout', self.target]).returncode == 0,
                'test job bootout failed')
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            # Observe only. Never signal a PID that may have been reused.
            old = self.command(['/bin/ps', '-p', str(pid), '-o', 'lstart=', '-o', 'command=']) if pid else None
            exited = old is None or old.returncode != 0 or digest(old.stdout.strip()) != identity
            if self.job() is None and exited:
                return
            time.sleep(0.1)
        raise GateError('test job removal/exit deadline expired')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('action', choices=('start', 'stop', 'check'))
    args = parser.parse_args()
    try:
        adapter = Adapter(args.config)
        if args.action == 'check':
            adapter.material()
        else:
            getattr(adapter, args.action)()
        return 0
    except Exception:
        print('isolated launchd controller refused or failed; inspect private test state', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())

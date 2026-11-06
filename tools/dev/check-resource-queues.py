#!/usr/bin/env python3
"""Isolated queue tests. Never uses a real heavy lock, platform, or device."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

HEAVY = Path(__file__).resolve().with_name('heavy')
parser = argparse.ArgumentParser()
parser.add_argument('--testphone-dir', type=Path, default=Path.home() / 'Coach/testphone')
args = parser.parse_args()
PHONE = args.testphone_dir.resolve()


class ResourceQueues(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='coach-resource-test-')
        self.root = Path(self.tmp.name)
        self.processes = []
        self.env = dict(os.environ, COACH_HEAVY_LOCK=str(self.root / 'lock'),
                        COACH_HEAVY_BUILD_LANES='1', COACH_HEAVY_LANES_FILE=str(self.root / 'lanes'))

    def tearDown(self):
        for proc in self.processes:
            if proc.poll() is None:
                proc.terminate()
        for proc in self.processes:
            try:
                proc.communicate(timeout=7)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.communicate()
        self.tmp.cleanup()

    def wait_for(self, predicate, seconds=25):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if predicate():
                return
            time.sleep(.03)
        self.fail('timed out waiting for isolated queue state')

    def launch(self, name, *flags):
        command = f'echo {name} >> "$1"'
        proc = subprocess.Popen([str(HEAVY), *flags, 'bash', '-c', command, '_', str(self.root / 'order')],
                                env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(proc)
        return proc

    def test_heavy_release_first_then_original_fifo(self):
        for emulator in (False, True):
            with self.subTest(emulator=emulator):
                lane = self.root / ('lock' if emulator else 'lock.2')
                lane.mkdir()
                (lane / 'owner').write_text(f'PID={os.getpid()} command=fixture\n')
                flags = ['--emulator'] if emulator else []
                pool = self.root / ('lock.waiters/emulator' if emulator else 'lock.waiters/build')
                procs = []
                for index, name in enumerate(('one', 'two', 'three', 'release'), 1):
                    procs.append(self.launch(name, *flags, *(['--priority', 'release'] if name == 'release' else [])))
                    self.wait_for(lambda: len(list(pool.glob('*'))) == index)
                    time.sleep(.04)
                (lane / 'owner').unlink()
                lane.rmdir()
                for proc in procs:
                    out, err = proc.communicate(timeout=30)
                    self.assertEqual(proc.returncode, 0, err)
                self.assertEqual((self.root / 'order').read_text().splitlines(), ['release', 'one', 'two', 'three'])
                self.assertFalse(lane.exists())
                self.assertEqual(list(pool.iterdir()), [])
                (self.root / 'order').unlink()

    def test_heavy_cancelled_release_does_not_block_builds(self):
        lane = self.root / 'lock.2'
        lane.mkdir()
        (lane / 'owner').write_text(f'PID={os.getpid()} command=fixture\n')
        release = self.launch('cancelled', '--priority', 'release')
        pool = self.root / 'lock.waiters/build'
        self.wait_for(lambda: len(list(pool.glob('*'))) == 1)
        release.send_signal(signal.SIGTERM)
        release.communicate(timeout=7)
        self.assertEqual(release.returncode, 130)
        normal = self.launch('normal')
        self.wait_for(lambda: len(list(pool.glob('*'))) == 1)
        (lane / 'owner').unlink()
        lane.rmdir()
        normal.communicate(timeout=10)
        self.assertEqual(normal.returncode, 0)
        self.assertEqual((self.root / 'order').read_text().strip(), 'normal')

    def test_heavy_fifo_uses_subsecond_arrival_not_pid_order(self):
        lane = self.root / 'lock.2'
        lane.mkdir()
        (lane / 'owner').write_text(f'PID={os.getpid()} command=fixture\n')
        pool = self.root / 'lock.waiters/build'
        procs = []
        # PID allocation can precede ticket publication (or wrap around).
        # Model publication order independently, within one wall-clock second.
        epoch = int(time.time()) * 1_000_000_000
        for i, name in enumerate(('three', 'two', 'one'), 1):
            proc = self.launch(name)
            procs.append(proc)
            self.wait_for(lambda: len(list(pool.glob('*'))) == i)
            ticket = next(pool.glob(f'{proc.pid}.*'))
            arrival = epoch + (4-i) * 100_000_000
            os.utime(ticket, ns=(arrival, arrival))
        (lane / 'owner').unlink()
        lane.rmdir()
        for proc in procs:
            out, err = proc.communicate(timeout=25)
            self.assertEqual(proc.returncode, 0, err)
        self.assertEqual((self.root / 'order').read_text().splitlines(), ['one', 'two', 'three'])

    def test_legacy_emulator_holder_does_not_block_release_build(self):
        # TON-1241/1253: a live old lane-1 hold must be preserved, while
        # current build invocations use lane 2 without waiting for that hold.
        lane = self.root / 'lock'
        lane.mkdir()
        owner = f'PID={os.getpid()} command=../ton1253-hold.sh\n'
        (lane / 'owner').write_text(owner)
        emulator = self.launch('emulator', '--emulator', '--priority', 'release')
        self.wait_for(lambda: len(list((self.root / 'lock.waiters/emulator').glob('*'))) == 1)
        self.env['COACH_HEAVY_PRIORITY'] = 'release'
        build = self.launch('release-build')
        build.communicate(timeout=10)
        self.assertEqual(build.returncode, 0)
        self.assertIsNone(emulator.poll())
        self.assertEqual((lane / 'owner').read_text(), owner)
        self.assertEqual((self.root / 'order').read_text().strip(), 'release-build')

    def phone(self):
        self.phone_dir = self.root / 'phone'
        self.phone_dir.mkdir()
        for filename in ('wait.sh', 'watch.sh', 'lease.sh', 'queue.py'):
            shutil.copy2(PHONE / filename, self.phone_dir / filename)
        self.wait_dir = self.phone_dir / 'wait.d'
        self.wait_dir.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env.update(COACH_TESTPHONE_DIR=str(self.phone_dir), COACH_WAIT_DIR=str(self.wait_dir),
                        PATH=str(self.bin) + os.pathsep + os.environ['PATH'], FAKE_ROOT=str(self.root),
                        COACH_ADB=str(self.bin / 'adb'))
        (self.bin / 'adb').write_text('#!/bin/bash\nexit 0\n')
        (self.phone_dir / 'probe.sh').write_text('#!/bin/bash\necho "available 空闲=5分钟"\nexit 0\n')
        (self.bin / 'multica').write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
root=pathlib.Path(os.environ['FAKE_ROOT']); a=sys.argv[1:]
with (root/'calls').open('a') as f: f.write(json.dumps(a)+'\\n')
if a[:2]==['issue','get']:
    if (root/'offline').exists(): sys.exit(1)
    print(json.dumps({'status': (root/'status').read_text() if (root/'status').exists() else 'in_progress'}))
elif a[:3]==['issue','comment','list']: print('[]')
elif a[:3]==['issue','comment','add']:
    assert pathlib.Path(a[a.index('--content-file')+1]).parent==pathlib.Path.cwd()
    with (root/'comments').open('a') as f: f.write(pathlib.Path(a[a.index('--content-file')+1]).read_text())
    print('{}')
elif a[:3]==['issue','wakeup','get']: print('{"enabled":true}')
elif a[:3]==['issue','wakeup','create']: print('{"id":"wake-new"}')
elif a[:3]==['issue','wakeup','list']: print('[{"id":"wake-new","enabled":true,"instruction":"【测试机值守唤醒】","created_at":"now","next_fire_at":"later"}]')
else: print('{}')
''')
        for path in (self.bin / 'adb', self.bin / 'multica', self.phone_dir / 'probe.sh'):
            path.chmod(0o755)

    def run_phone(self, script, *params, expected=0):
        cmd = ['python3', '-I'] if script == 'queue.py' else ['bash']
        result = subprocess.run([*cmd, str(self.phone_dir / script), *params], env=self.env,
                                cwd=self.root, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def register(self, card, since, priority=None):
        (self.wait_dir / card).write_text(f'card={card}\nwakeup=w-{card}\nsince={since}\nuntil=later\n' + (f'priority={priority}\n' if priority else ''))

    def test_phone_release_first_existing_entries_preserved_and_add(self):
        self.phone()
        for i in range(1, 4):
            self.register(f'TON-{i}', i)
        self.register('TON-4', 4, 'release')
        self.run_phone('watch.sh')
        self.assertFalse((self.wait_dir / 'TON-4').exists())
        self.assertEqual((self.phone_dir / 'lease.d/info').read_text().splitlines()[0], 'card=TON-4')
        self.assertTrue(all((self.wait_dir / f'TON-{i}').exists() for i in range(1,4)))
        self.run_phone('lease.sh', 'release', 'TON-4')
        self.run_phone('watch.sh')
        self.assertIn('card=TON-1', (self.phone_dir / 'lease.d/info').read_text())
        self.run_phone('wait.sh', 'add', 'TON-3', '--priority', 'release')
        text = (self.wait_dir / 'TON-3').read_text()
        self.assertIn('since=3\n', text)
        self.assertIn('priority=release\n', text)
        listing = self.run_phone('wait.sh', 'list').stdout
        self.assertTrue(listing.startswith('TON-3 [release]'), listing)

    def test_five_grants_block_skip_deduplicate_status_and_unknown(self):
        self.phone()
        (self.phone_dir / 'watch.log').write_text('11-06T01:00:00Z 已触发 TON-1（唤醒 old），租约代领 30 分钟\n' * 5)
        self.register('TON-1', 1, 'release')
        self.register('TON-2', 2)
        self.run_phone('watch.sh')
        self.assertIn('card=TON-2', (self.phone_dir / 'lease.d/info').read_text())
        self.assertTrue((self.wait_dir / 'TON-1').exists())
        self.assertIn('拿过 5 次租约未过，先查根因再排队', (self.root / 'comments').read_text())
        self.run_phone('queue.py', 'check', 'TON-1', expected=1)
        self.assertEqual(len((self.root / 'comments').read_text().splitlines()), 1)
        # A log rotation must not erase imported history.
        (self.phone_dir / 'watch.log').write_text('')
        self.run_phone('queue.py', 'check', 'TON-1', expected=1)
        for state in ('in_review', 'done'):
            (self.root / 'status').write_text(state)
            self.run_phone('queue.py', 'check', 'TON-1')
        (self.root / 'offline').touch()
        self.run_phone('queue.py', 'check', 'TON-1', expected=1)
        self.assertEqual(len((self.root / 'comments').read_text().splitlines()), 1)

    def test_direct_grants_count_renewals_do_not_and_active_lease_retained(self):
        self.phone()
        for _ in range(5):
            self.run_phone('lease.sh', 'acquire', 'TON-1')
            self.run_phone('lease.sh', 'acquire', 'TON-1')
            self.run_phone('lease.sh', 'renew', 'TON-1')
            self.run_phone('lease.sh', 'release', 'TON-1')
        self.assertEqual(json.loads((self.phone_dir / 'lease-history.json').read_text())['counts']['TON-1'], 5)
        self.run_phone('lease.sh', 'acquire', 'TON-2')
        # TON-1146: a long renewed live lease remains intact even when a
        # release card arrives. Priority only applies to the next grant.
        self.run_phone('lease.sh', 'renew', 'TON-2', '90')
        original = (self.phone_dir / 'lease.d/info').read_text()
        self.register('TON-3', 1, 'release')
        self.run_phone('watch.sh')
        self.assertEqual((self.phone_dir / 'lease.d/info').read_text(), original)
        self.assertFalse((self.root / 'comments').exists())


unittest.main(argv=['check-resource-queues.py'], verbosity=2)

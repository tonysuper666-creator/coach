"""Real OS-process faults; all service/pointer/JFR inputs are temporary fixtures."""
import fcntl
import json
import os
from pathlib import Path
import signal
import sqlite3
import stat
import subprocess
import sys
import time
import unittest

from . import ton1175_supervise as supervise
from . import test_ton1175_switch as fixtures


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.f = fixtures.SwitchTest(methodName='runTest')
        self.f.setUp()
        self.addCleanup(self.f.doCleanups)
        self.record = self.f.root / 'supervision'

    def launch(self, action='switch', apply=True, prefix='', budgets=(3, 2, 3, 2)):
        code = ('import sys,os,signal\nfrom tools.dev.ton1175_supervise import main,Budgets,Supervisor\n'
                + prefix + '\nsys.exit(main(budgets=Budgets(*' + repr(budgets) + ')))')
        child = subprocess.Popen([sys.executable, '-c', code, action, '--config', str(self.f.config_path),
                                  '--record-dir', str(self.record)] + (['--apply'] if apply else []),
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if child.poll() is None:
                child.terminate()
            child.communicate(timeout=12)
        self.addCleanup(cleanup)
        return child

    def finish(self, child, expected):
        out, err = child.communicate(timeout=18)
        self.assertEqual(child.returncode, expected, (out, err))
        events = [json.loads(line) for line in (self.record / 'events.jsonl').read_text().splitlines()]
        registered = [e['pgid'] for e in events if e['event'] == 'command_registered']
        reaped = [e['pgid'] for e in events if e['event'] == 'command_reaped']
        self.assertEqual(registered, reaped, events)
        # Observe all owned process groups, including descendants whose direct
        # parent died. A reaped guardian alone would not prove group cleanup.
        groups = set(registered)
        deadline = time.monotonic() + 2
        while True:
            rows = subprocess.check_output(['ps', '-axo', 'pgid=,stat='], text=True).splitlines()
            live = [row for row in rows if int(row.split()[0]) in groups and not row.split()[1].startswith('Z')]
            if not live or time.monotonic() >= deadline:
                break
            time.sleep(.02)
        self.assertFalse(live, 'owned command processes survived: '+repr(live))
        owner = json.loads((self.record / 'owner.json').read_text())
        self.assertIsNone(owner['commandPgid'])
        self.assertIsNone(owner['workerPid'])
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o700)
        for path in self.record.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        return events

    def wait_for(self, predicate, child):
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline and child.poll() is None:
            if predicate():
                return
            time.sleep(.01)
        self.fail('fault checkpoint not reached')

    def phase(self):
        return json.loads((self.f.ops / 'journal.json').read_text())['phase']

    def hang_verifier(self, name='reject-acceptance', ignore=False):
        self.f.verifier.write_text('''import os,pathlib,signal,sqlite3,subprocess,sys,time
p=pathlib.Path(sys.argv[1]);r=p.parent
if p.name == %r:
 %s
 with sqlite3.connect(r/'new-data.sqlite') as db:
  db.execute('CREATE TABLE IF NOT EXISTS ledger(value TEXT)')
  db.execute("INSERT INTO ledger VALUES ('during candidate')")
 subprocess.Popen([sys.executable,'-c',"import signal,time,pathlib;signal.signal(signal.SIGTERM,signal.SIG_IGN);time.sleep(1.5)\\nwhile True: pathlib.Path("+repr(str(r/'late-write'))+").touch();time.sleep(.1)"])
 (r/'fault-ready').touch()
 time.sleep(90)
''' % (name, 'signal.signal(signal.SIGTERM,signal.SIG_IGN)' if ignore else 'pass'))

    def assert_recovered(self):
        self.f.assert_old()
        self.assertEqual(self.phase(), 'rolled_back')
        self.assertEqual(self.f.running.read_text(), 'b' * 40)

    def test_normal_dry_run_switch_and_rollback(self):
        self.f.stage()
        self.finish(self.launch(apply=False), 0)
        self.f.assert_old()
        self.assertFalse((self.f.root / 'calls').exists())
        self.record = self.f.root / 'switch-record'
        events = self.finish(self.launch(), 0)
        self.assertEqual(self.phase(), 'switched')
        self.assertTrue(any(e['event'] == 'acceptance_budget' for e in events))
        self.f.database.write_text('new write retained')
        self.record = self.f.root / 'rollback-record'
        self.finish(self.launch('rollback'), 0)
        self.assert_recovered()
        self.assertEqual(self.f.database.read_text(), 'new write retained')
        self.assertEqual(supervise.Budgets().acceptance, 300)
        self.assertGreater(supervise.Budgets().transaction, 300)

    def test_unreaped_command_requires_manual_recovery_without_racing_it(self):
        # Architect's reproduction: really reap the child, but simulate waitpid
        # timing out after SIGKILL in the stopped-service window.
        prefix = '''import subprocess,json,pathlib
_wait=subprocess.Popen.wait
def wait(self,timeout=None):
 ops=pathlib.Path(json.loads(pathlib.Path(sys.argv[sys.argv.index('--config')+1]).read_text())['stateDir'])
 if timeout==5 and '_guardian' in self.args and json.loads((ops/'journal.json').read_text())['phase'] in ('switching','validating'):
  _wait(self)
  raise subprocess.TimeoutExpired(self.args,timeout)
 return _wait(self,timeout)
subprocess.Popen.wait=wait
'''
        self.f.stage()
        child = self.launch(prefix=prefix, budgets=(6, 3, 4, 300))
        out, err = child.communicate(timeout=30)
        self.assertEqual(child.returncode, 2, (out, err))
        events = [json.loads(line) for line in (self.record / 'events.jsonl').read_text().splitlines()]
        self.assertEqual(self.phase(), 'switching')
        self.assertEqual(self.f.running.read_text(), 'stopped')
        self.assertTrue(any(e['event'] == 'reap_timeout' and e['kind'] == 'command' for e in events))
        self.assertFalse(any(e['event'] == 'recovery_started' for e in events))
        owner = json.loads((self.record / 'owner.json').read_text())
        self.assertEqual(owner['last']['outcome'], 'recovery_required')
        self.assertIsNotNone(owner['commandPgid'])
        self.assertIsNone(owner['workerPid'])

    def test_unexpected_supervisor_exception_returns_recovery_required(self):
        child = self.launch(prefix="Supervisor.execute=lambda *args: 1/0")
        out, err = child.communicate(timeout=10)
        self.assertEqual(child.returncode, 2, (out, err))

    def test_unreaped_worker_requires_manual_recovery(self):
        self.hang_verifier(ignore=True)
        self.f.stage()
        prefix = '''import subprocess
_wait=subprocess.Popen.wait
def wait(self,timeout=None):
 if timeout==5 and any(str(arg).endswith('ton1175_switch.py') for arg in self.args):
  _wait(self)
  raise subprocess.TimeoutExpired(self.args,timeout)
 return _wait(self,timeout)
subprocess.Popen.wait=wait
'''
        child = self.launch(prefix=prefix, budgets=(1, 1, 3, 1))
        self.wait_for(lambda: (self.f.root / 'fault-ready').exists(), child)
        os.kill(json.loads((self.record / 'owner.json').read_text())['workerPid'], signal.SIGSTOP)
        out, err = child.communicate(timeout=15)
        self.assertEqual(child.returncode, 2, (out, err))
        events = [json.loads(line) for line in (self.record / 'events.jsonl').read_text().splitlines()]
        self.assertTrue(any(e['event'] == 'reap_timeout' and e['kind'] == 'worker' for e in events))
        self.assertFalse(any(e['event'] == 'recovery_started' for e in events))
        owner = json.loads((self.record / 'owner.json').read_text())
        self.assertEqual(owner['last']['outcome'], 'recovery_required')
        self.assertIsNotNone(owner['workerPid'])

    def test_pre_seal_hang_is_bounded_without_service_changes(self):
        self.hang_verifier('reject-idle', ignore=True)
        self.f.stage()
        self.finish(self.launch(budgets=(1, 1, 3, 1)), 124)
        self.f.assert_old()
        self.assertEqual(self.phase(), 'staged')
        self.assertFalse((self.f.root / 'calls').exists())

    def test_transaction_lock_wait_is_bounded(self):
        self.f.stage()
        with (self.f.ops / 'lock').open('rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.finish(self.launch(budgets=(.4, 1, 3, 1)), 124)
        self.f.assert_old()
        self.assertEqual(self.phase(), 'staged')

    def test_partial_pointer_recovery_hang_retries_without_data_restore(self):
        self.f.controller.write_text(self.f.controller.read_text() + '''
if action=='start' and not (r/'once').exists():
 import time
 (r/'once').touch();time.sleep(90)
''')
        self.f.stage()
        (self.f.ops / 'journal.json').write_text(json.dumps({'phase': 'switching'}))
        self.f.plist.write_bytes((self.f.ops / 'new.plist').read_bytes())
        self.f.running.write_text('stopped')
        self.f.database.write_text('committed after partial switch')
        self.finish(self.launch('recover', budgets=(1, 1, 3, 1)), 124)
        self.assert_recovered()
        self.assertEqual(self.f.database.read_text(), 'committed after partial switch')

    def test_acceptance_reserve_extends_near_expired_transaction_budget(self):
        self.f.controller.write_text(self.f.controller.read_text() + '''
if action=='stop':
 import time
 time.sleep(.6)
''')
        self.f.verifier.write_text('''import pathlib,sys,time
if pathlib.Path(sys.argv[1]).name=='reject-acceptance': time.sleep(.7)
''')
        self.f.stage()
        # Total execution exceeds one second, but acceptance has its full
        # separate reserve. Production uses the same path with 300 seconds.
        self.finish(self.launch(budgets=(1.5, 2, 3, 2)), 0)
        self.assertEqual(self.phase(), 'switched')

    def test_supervisor_term_cancels_command_and_rolls_back(self):
        self.hang_verifier(ignore=True)
        self.f.stage()
        child = self.launch()
        self.wait_for(lambda: (self.f.root / 'fault-ready').exists(), child)
        child.terminate()
        self.finish(child, 124)
        self.assert_recovered()

    def test_timeout_cancels_term_ignoring_descendants_preserves_new_data(self):
        self.hang_verifier(ignore=True)
        self.f.stage()
        events = self.finish(self.launch(), 124)
        self.assert_recovered()
        self.assertTrue(any(e['event'] == 'worker_terminate' for e in events))
        with sqlite3.connect(self.f.root / 'new-data.sqlite') as db:
            self.assertEqual(db.execute('SELECT value FROM ledger').fetchall(), [('during candidate',)])
        (self.f.root / 'late-write').unlink(missing_ok=True)
        time.sleep(.3)
        self.assertFalse((self.f.root / 'late-write').exists())

    def test_killed_worker_during_acceptance_recovers_and_leaves_service_group(self):
        self.hang_verifier(ignore=True)
        self.f.stage()
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(90)'], start_new_session=True)
        self.addCleanup(lambda: (unrelated.terminate(), unrelated.wait(timeout=5)))
        child = self.launch()
        self.wait_for(lambda: (self.f.root / 'fault-ready').exists(), child)
        owner = json.loads((self.record / 'owner.json').read_text())
        self.assertEqual(self.phase(), 'validating')
        os.kill(owner['workerPid'], signal.SIGKILL)
        events = self.finish(child, 1)
        self.assert_recovered()
        self.assertIsNone(unrelated.poll(), 'service/foreign session must survive command cleanup')
        self.assertTrue(any(e['event'] == 'recovery_started' for e in events))
        time.sleep(1.6)
        self.assertFalse((self.f.root / 'late-write').exists())

    def test_registration_race_is_cleaned_before_recovery(self):
        self.f.stage()
        # Kill at the durable registration boundary, before command execution is
        # released. This is a real worker death, not a mocked process API.
        prefix = ("original=Supervisor.event\n"
                  "def fault(self,event,**kw):\n"
                  " original(self,event,**kw)\n"
                  " if event=='command_registered' and not getattr(self,'injected',False):\n"
                  "  self.injected=True;os.kill(self.worker.pid,signal.SIGKILL)\n"
                  "Supervisor.event=fault\n")
        self.finish(self.launch(prefix=prefix), 1)
        self.f.assert_old()
        self.assertEqual(self.phase(), 'staged')

    def test_force_kill_after_stopped_stage_recovers(self):
        original = self.f.controller.read_text()
        self.f.controller.write_text(original + '''
if action=='stop' and not (r/'once').exists():
 import signal,time
 (r/'once').touch();(r/'fault-ready').touch()
 signal.signal(signal.SIGTERM,signal.SIG_IGN)
 time.sleep(90)
''')
        self.f.stage()
        child = self.launch()
        self.wait_for(lambda: (self.f.root / 'fault-ready').exists(), child)
        os.kill(json.loads((self.record / 'owner.json').read_text())['workerPid'], signal.SIGSTOP)
        events = self.finish(child, 124)
        self.assert_recovered()
        self.assertTrue(any(e['event'] == 'worker_kill' for e in events))

    def test_recovery_timeout_has_explicit_recover_record_and_can_retry(self):
        self.hang_verifier(ignore=True)
        self.f.controller.write_text(self.f.controller.read_text() + '''
if action=='start' and (r/'block-recovery').exists():
 import time
 time.sleep(90)
''')
        self.f.stage()
        child = self.launch()
        self.wait_for(lambda: (self.f.root / 'fault-ready').exists(), child)
        (self.f.root / 'block-recovery').touch()
        os.kill(json.loads((self.record / 'owner.json').read_text())['workerPid'], signal.SIGKILL)
        self.finish(child, 2)
        owner = json.loads((self.record / 'owner.json').read_text())
        self.assertEqual(owner['last']['outcome'], 'recovery_required')
        self.assertIn('--apply', owner['recover'])
        self.assertIn(self.phase(), ('recovering', 'recovery_required'))
        self.f.assert_old()
        (self.f.root / 'block-recovery').unlink()
        self.record = self.f.root / 'retry-record'
        self.finish(self.launch('recover'), 0)
        self.assert_recovered()


if __name__ == '__main__':
    unittest.main()

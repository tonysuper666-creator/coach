"""Offline policy/JFR fixtures; no service, socket, fcntl shim or user data."""
import copy
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from tools.dev import ton1175_idle as idle


def stamp(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def process(seconds, command='java com.coach.server.MainKt'):
    return (dt.datetime.fromtimestamp(seconds, dt.timezone.utc).strftime('%a %b %d %H:%M:%S %Y')
            + ' ' + command).encode()


def health_recording(start):
    rows = []
    for poll in range(90):
        for offset, size in ((0, 150), (0.01, 95)):
            at = start + poll * 10 + 0.5 + offset
            rows.append(dict(start=stamp(at), end=stamp(at + 0.001), bytes=size,
                             thread=20, bridgePeer=True, healthOrigin=True))
    return dict(socketReads=len(rows), reads=rows)


class HealthReadsTest(unittest.TestCase):
    def setUp(self):
        self.start = 1794268800
        self.end = self.start + 900
        self.rows = health_recording(self.start)['reads']

    def check(self):
        return idle.health_reads(dict(socketReads=len(self.rows), reads=self.rows), self.start, self.end)

    def test_only_complete_regular_health_polling_passes(self):
        self.assertEqual(self.check(), 90)

    def test_tcp_fragmentation_can_vary_but_exchange_total_cannot(self):
        self.rows[40]['bytes'], self.rows[41]['bytes'] = 200, 45
        self.assertEqual(self.check(), 90)
        self.rows[41]['bytes'] += 1
        with self.assertRaisesRegex(ValueError, 'size changed'):
            self.check()

    def test_client_unix_unknown_peer_and_wrong_call_site_rejected(self):
        for field in ('bridgePeer', 'healthOrigin'):
            with self.subTest(field=field):
                self.rows[40][field] = False
                with self.assertRaisesRegex(ValueError, 'non-health'):
                    self.check()
                self.rows[40][field] = True
        self.rows.append(dict(self.rows[40], bridgePeer=False))
        with self.assertRaises(ValueError):
            self.check()

    def test_missing_poll_or_beginning_or_tail_coverage_rejected(self):
        original = self.rows
        for rows in ([], original[:40] + original[42:], original[4:], original[:-4]):
            with self.subTest(size=len(rows)):
                self.rows = rows
                with self.assertRaises(ValueError):
                    self.check()
        self.rows = original

    def test_fast_slow_or_extra_poll_rejected(self):
        original = copy.deepcopy(self.rows)
        for offset in (-2, 2, 5):
            self.rows = copy.deepcopy(original)
            for row in self.rows[40:42]:
                row['start'] = stamp(idle.instant(row['start']) + offset)
                row['end'] = stamp(idle.instant(row['end']) + offset)
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, 'cadence'):
                self.check()
        self.rows = original + [dict(original[40], start=stamp(self.start + 205.5),
                                     end=stamp(self.start + 205.501))]
        with self.assertRaises(ValueError):
            self.check()

    def test_ambiguous_thread_overlap_slow_and_boundary_reads_rejected(self):
        original = copy.deepcopy(self.rows)
        for replacement in (dict(thread=21), dict(start=original[40]['start']),
                            dict(end=stamp(self.start + 202)), dict(bytes=0), dict(thread=0)):
            self.rows = copy.deepcopy(original)
            self.rows[41].update(replacement)
            with self.subTest(change=replacement), self.assertRaises(ValueError):
                self.check()
        for at, until in ((self.start - 0.1, self.start + 0.1), (self.end - 0.1, self.end + 0.1)):
            self.rows = original + [dict(original[0], start=stamp(at), end=stamp(until))]
            with self.assertRaises(ValueError):
                self.check()

    def test_missing_metadata_or_forged_total_never_becomes_an_exclusion(self):
        with self.assertRaises(ValueError):
            idle.health_reads(dict(socketReads=0, reads=self.rows), self.start, self.end)
        for field in list(self.rows[0]):
            row = self.rows[0].pop(field)
            with self.subTest(field=field), self.assertRaises((ValueError, KeyError)):
                self.check()
            self.rows[0][field] = row


class LifetimeTest(unittest.TestCase):
    def test_sleep_offset_does_not_change_kernel_lifetime(self):
        started = 1794260000
        jvm_start = started + 0.125
        now, sleep = started + 20000, 11313
        old_uptime = now - jvm_start - sleep
        self.assertGreater(abs(now - old_uptime - jvm_start), 10000)
        idle.lifetime(process(started), stamp(jvm_start))

    def test_same_pid_different_lifetime_rejected_at_original_tolerance(self):
        for offset in (-11313, -1, 1, 11313):
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, 'different JVM lifetime'):
                idle.lifetime(process(1794260000), stamp(1794260000 + offset))

    def test_missing_kernel_start_or_timezone_rejected(self):
        with self.assertRaises(ValueError):
            idle.lifetime(b'java com.coach.server.MainKt', stamp(1794260000))
        with self.assertRaises(ValueError):
            idle.instant('2026-11-10T00:00:00')


class BridgeIdentityTest(unittest.TestCase):
    def setUp(self):
        self.start = 1794268800
        self.now = self.start + 910
        self.state = dict(pid=300, guardPid=301, ownerPid=1802, healthy=True,
                          checkedAt=int(self.now * 1000))
        self.bridge = process(self.start - 1000, 'python /release/tools/bridge/subscription_bridge.py')
        self.guard = process(self.start - 1001,
            'python /release/tools/dev/service-child.py /run/bridge-1802.pid /release/tools/bridge/run.sh')

    def check(self, **kwargs):
        args = dict(state=self.state, owner=1802, bridge=self.bridge, guard=self.guard,
                    bridge_parent=301, guard_parent=1802, root='/release', run_dir='/run',
                    window_start=self.start, now=self.now)
        args.update(kwargs)
        return idle.bridge_identity(**args)

    def test_installed_child_ancestry_and_observed_identity(self):
        old = self.check()
        self.assertEqual(old, self.check())
        self.state['pid'] += 2
        self.assertNotEqual(old, self.check())

    def test_wrong_owner_unhealthy_stale_future_or_invalid_pid_rejected(self):
        original = dict(self.state)
        for field, value in (('ownerPid', 999), ('healthy', False), ('pid', 0), ('pid', True),
                             ('guardPid', 1802), ('checkedAt', (self.now - 16) * 1000),
                             ('checkedAt', (self.now + 1) * 1000)):
            self.state = dict(original, **{field: value})
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.check()

    def test_foreign_ancestry_command_or_restarted_child_rejected(self):
        for change in (dict(bridge_parent=999), dict(guard_parent=999), dict(root='/foreign'),
                       dict(run_dir='/foreign'), dict(bridge=process(self.start + 1,
                            'python /release/tools/bridge/subscription_bridge.py')),
                       dict(bridge=process(self.start - 999, 'python /foreign/subscription_bridge.py'))):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.check(**change)


class InspectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        java_home = os.environ.get('JAVA_HOME')
        cls.java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        if not cls.java or not javac:
            raise RuntimeError('JDK required for real offline JFR fixtures')
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        source = Path(os.environ.get('TON1460_INSPECTOR', Path(__file__).with_name('Ton1175Jfr.java')))
        # Synthetic event fields avoid binding even a local production port.
        child = cls.root / 'ChildServices$supervise$1.java'
        child.write_text('''package com.coach.server;
import jdk.jfr.*;
public class ChildServices$supervise$1 {
 @Name("fixture.SocketRead") public static class Read extends Event {
  public String address; public int port; public long bytesRead;
 }
 public static void client(String address,int port) {
  Read e=new Read();e.address=address;e.port=port;e.bytesRead=95;e.commit();
 }
 public static void invokeSuspend(String address,int port) {client(address,port);}
}''', encoding='utf-8')
        harness = cls.root / 'Ton1460Fixture.java'
        harness.write_text('''import jdk.jfr.*;import jdk.jfr.consumer.*;
import java.nio.file.*;import java.time.*;import java.util.*;
import com.coach.server.ChildServices$supervise$1;
public class Ton1460Fixture {
 public static void main(String[] args)throws Exception {
  Path path=Path.of(args[0]);
  try(Recording r=new Recording()) {
   r.enable("fixture.SocketRead").withStackTrace();r.start();
   ChildServices$supervise$1.invokeSuspend("127.0.0.1",8791);
   ChildServices$supervise$1.invokeSuspend("127.0.0.1",8790);
   ChildServices$supervise$1.invokeSuspend("::1",8791);
   ChildServices$supervise$1.invokeSuspend("unix",0);
   ChildServices$supervise$1.client("127.0.0.1",8791);
   r.enable("fixture.SocketRead").withoutStackTrace();
   ChildServices$supervise$1.invokeSuspend("127.0.0.1",8791);
   r.stop();r.dump(path);
  }
  var inspect=Class.forName("Ton1175Jfr").getDeclaredMethod("socketRead",RecordedEvent.class);
  inspect.setAccessible(true);List<String> rows=new ArrayList<>();
  try(RecordingFile file=new RecordingFile(path)) {
   while(file.hasMoreEvents()) {var e=file.readEvent();
    if(e.getEventType().getName().equals("fixture.SocketRead")) rows.add((String)inspect.invoke(null,e));
   }
  }
  System.out.println("["+String.join(",",rows)+"]");
 }
}''', encoding='utf-8')
        main = cls.root / 'MainKt.java'
        main.write_text('''package com.coach.server;
import jdk.jfr.*;import java.time.*;import java.nio.file.*;import java.net.*;
public class MainKt {
 public static void main(String[] args)throws Exception {
  try(Recording r=new Recording()) {
   String mode=System.getenv().getOrDefault("GATE_JFR_MODE","normal");
   r.enable("jdk.JVMInformation").withPeriod(Duration.ofMillis(20));
   if(!mode.equals("missingHeartbeat")) r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(20));
   r.enable("jdk.ActiveSetting");r.enable("jdk.DataLoss");
   r.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
   r.start();Thread.sleep(1500);Instant start=Instant.now();
   Thread.sleep(150);Instant quiet=Instant.now();
   if(mode.equals("threshold")) r.enable("jdk.SocketRead").withThreshold(Duration.ofMillis(1));
   if(mode.equals("disabled")) r.disable("jdk.SocketRead");
   try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
       Socket c=new Socket(InetAddress.getLoopbackAddress(),s.getLocalPort());Socket a=s.accept()) {
    c.getOutputStream().write(1);a.getInputStream().read();
   }
   Instant busy=Instant.now();Thread.sleep(1500);r.stop();r.dump(Path.of(System.getenv("GATE_JFR_OUTPUT")));
   System.out.printf("{\\"pid\\":%d,\\"start\\":\\"%s\\",\\"quiet\\":\\"%s\\",\\"busy\\":\\"%s\\"}%n",
    ProcessHandle.current().pid(),start,quiet,busy);
  }
 }
}''', encoding='utf-8')
        result = subprocess.run([javac, '-d', str(cls.root), str(source), str(child), str(harness), str(main)],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_java(self, *args, **kwargs):
        return subprocess.run([self.java, '-cp', str(self.root), *map(str, args)],
                              capture_output=True, text=True, timeout=60, **kwargs)

    def test_real_jfr_fields_bind_ipv4_port_and_actual_call_stack(self):
        result = self.run_java('Ton1460Fixture', self.root / 'synthetic.jfr')
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(result.stdout)
        self.assertEqual([(row['bridgePeer'], row['healthOrigin']) for row in rows],
                         [(True, True), (False, True), (False, True), (False, True),
                          (True, False), (True, False)])
        self.assertTrue(all(row['bytes'] == 95 and row['thread'] > 0 for row in rows))

    def test_original_real_socket_recording_still_counts_clients_and_rejects_wrong_pid(self):
        path = self.root / 'socket.jfr'
        fixture = self.run_java('com.coach.server.MainKt', env=dict(os.environ, GATE_JFR_OUTPUT=str(path)))
        self.assertEqual(fixture.returncode, 0, fixture.stderr)
        proof = json.loads(fixture.stdout)
        def inspect(end, pid):
            return self.run_java('Ton1175Jfr', path, proof['start'], end, pid)
        quiet = inspect(proof['quiet'], proof['pid'])
        self.assertEqual(quiet.returncode, 0, quiet.stderr)
        self.assertEqual(json.loads(quiet.stdout)['socketReads'], 0)
        busy = inspect(proof['busy'], proof['pid'])
        self.assertEqual(busy.returncode, 0, busy.stderr)
        measured = json.loads(busy.stdout)
        self.assertGreater(measured['socketReads'], 0)
        self.assertEqual(measured['socketReads'], len(measured['reads']))
        self.assertFalse(measured['reads'][0]['healthOrigin'])
        with self.assertRaises(ValueError):
            idle.health_reads(measured, idle.instant(proof['start']), idle.instant(proof['busy']))
        self.assertNotEqual(inspect(proof['busy'], proof['pid'] + 1).returncode, 0)

    def test_real_recordings_with_missing_heartbeat_disabled_or_threshold_changes_rejected(self):
        for mode in ('missingHeartbeat', 'disabled', 'threshold'):
            path = self.root / (mode + '.jfr')
            fixture = self.run_java('com.coach.server.MainKt',
                env=dict(os.environ, GATE_JFR_OUTPUT=str(path), GATE_JFR_MODE=mode))
            self.assertEqual(fixture.returncode, 0, fixture.stderr)
            proof = json.loads(fixture.stdout)
            with self.subTest(mode=mode):
                rejected = self.run_java('Ton1175Jfr', path, proof['start'], proof['busy'], proof['pid'])
                self.assertEqual(rejected.returncode, 1, rejected.stdout)
                self.assertIn('JFR evidence rejected', rejected.stderr)


if __name__ == '__main__':
    unittest.main()

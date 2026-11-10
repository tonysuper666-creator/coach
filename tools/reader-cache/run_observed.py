"""Mac foreground runner: owns the synthetic service, test process and memory sampler until all finish."""

import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid

from fixture_server import Fixture
from summarize import summarize
from correlate_transport import correlate
from route_control import summarize_routes
from packet_capture import PacketCapture
from android_observer import MODES, observe_android, audit_transport_stage
from queue_readiness import analyze_queue_watch
from host_socket_boundary import SocketBoundaryRelay, analyze_boundary
from host_probe import preflight as host_probe_preflight

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.coach.readermeasurement"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=["desktop", "android"], required=True)
    parser.add_argument("--suite", choices=["performance", "behavior", "transport", "route", "lifecycle"], default="performance")
    parser.add_argument("--transport-mode", choices=["legacy", "nodelay", "keepalive"], default="legacy",
                        help="Diagnostic fixture mode; keep legacy for historical comparisons")
    parser.add_argument("--packet-interface", help="Optional Mac capture interface; only the owned synthetic transport endpoint is captured")
    observers=parser.add_mutually_exclusive_group()
    observers.add_argument('--no-transport-memory-sampler',action='store_true',
                        help='Android transport/route diagnostic only: omit repeated run-as/dumpsys sampling')
    observers.add_argument('--transport-observer',choices=MODES,
                        help='Android transport/route only: select repeated stage reads, meminfo, both (default), or neither')
    parser.add_argument('--socket-queue-watch',action='store_true',
                        help='Android route with observer none only: concurrent same-socket available() observations')
    parser.add_argument('--socket-scheduling-trace',action='store_true',
                        help='Android plain route/observer none only: own a dedicated read-thread scheduling trace')
    parser.add_argument('--socket-syscalls',action='store_true',
                        help='Add syscall entry/exit to the scheduling trace on the existing userdebug emulator only')
    parser.add_argument('--host-clock-boundaries',action='store_true',
                        help='Route/syscall diagnostic only: bracket fixture-host epoch reads near I/O boundaries')
    parser.add_argument('--single-socket-boundary',action='store_true',
                        help='Route/observer none only: ONE nonce through an explicit instrumented host relay (changed path)')
    parser.add_argument('--socket-recording-budget-us',type=int,default=1000,
                        help='Single-boundary local observed recording budget; not a complete overhead/error bound')
    parser.add_argument("--output", type=Path, required=True, help="New directory in this checkout")
    parser.add_argument("--host", default="127.0.0.1", help="For Android: the existing configured coach.tailHost IPv4")
    parser.add_argument("--renderer", default="pdftoppm")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--aapt2")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--perfetto", action="store_true", help="Collect Android FrameTimeline and the app's fixed trace markers")
    args = parser.parse_args()
    if (args.no_transport_memory_sampler or args.transport_observer is not None) and (args.platform != 'android' or args.suite not in ('transport','route')):
        parser.error('memory sampler control is limited to Android transport/route; UI/lifecycle memory sampling stays required')
    observer_mode=args.transport_observer or ('none' if args.no_transport_memory_sampler else 'both')
    if args.single_socket_boundary and (args.platform!='android' or args.suite!='route' or observer_mode!='none' or
            args.socket_queue_watch or args.perfetto or args.packet_interface or args.host_clock_boundaries):
        parser.error('single socket boundary requires Android legacy route/observer none; separate from UI/queue/packet flags; fixture clocks are included')
    if args.socket_recording_budget_us<=0 or (args.socket_recording_budget_us!=1000 and not args.single_socket_boundary):
        parser.error('positive recording budget applies only to single socket boundary')
    if args.socket_queue_watch and (args.platform!='android' or args.suite!='route' or observer_mode!='none'):
        parser.error('socket queue observation requires Android route and explicit observer none')
    if args.socket_scheduling_trace and (args.platform!='android' or args.suite!='route' or observer_mode!='none' or args.socket_queue_watch or args.perfetto):
        parser.error('socket scheduling trace requires Android plain route/observer none; separate from queue watch and FrameTimeline')
    if args.socket_syscalls and not args.socket_scheduling_trace:
        parser.error('socket syscalls requires the isolated socket scheduling trace')
    if args.host_clock_boundaries and not args.socket_syscalls:
        parser.error('host clock boundaries require the isolated route/syscall diagnostic')
    capture_perfetto=args.perfetto or args.socket_scheduling_trace
    if args.suite == 'route' and (args.platform != 'android' or args.transport_mode != 'legacy' or args.host in
            (('0.0.0.0','10.0.2.2') if args.single_socket_boundary else ('127.0.0.1','0.0.0.0','10.0.2.2'))):
        parser.error('route control requires Android, legacy transport and a distinct exact configured host IPv4')
    if args.packet_interface and args.suite != "transport":
        parser.error("packet capture is limited to the synthetic transport suite")
    if sys.platform != "darwin":
        parser.error("Native UI, emulator and shared UI tests stay on Mac; use tools/dev/heavy")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", args.host):
        parser.error("--host must be a numeric IPv4")
    out = args.output.resolve()
    out.relative_to(ROOT)  # never write into an unrelated library or account directory
    out.mkdir(parents=True, exist_ok=False)
    if args.platform == "android":
        if not args.serial or not args.serial.startswith("emulator-") or not all((args.aapt2, args.apk, args.test_apk)):
            parser.error("Android needs an explicit emulator serial, aapt2 and both isolated APKs")
        adb = [args.adb, "-s", args.serial]
        if subprocess.check_output(adb + ["shell", "getprop", "ro.kernel.qemu"], text=True).strip() != "1":
            raise ValueError("Only an emulator is permitted")
        if args.socket_syscalls:
            syscall_build_type=subprocess.check_output(adb+['shell','getprop','ro.build.type'],text=True).strip()
            if syscall_build_type!='userdebug':
                raise ValueError('Syscall tracing requires the existing userdebug image; no image or permission change is permitted')
        for apk, expected in ((args.apk, PACKAGE), (args.test_apk, PACKAGE + ".test")):
            badging = subprocess.check_output([args.aapt2, "dump", "badging", str(apk)], text=True)
            if not re.search(r"^package: name='" + re.escape(expected) + "'", badging):
                raise ValueError("Refusing to install an APK outside the isolated measurement package")
            subprocess.run(adb + ["install", "-r", str(apk)], check=True, stdout=subprocess.DEVNULL)
    run_id = uuid.uuid4().hex
    if args.single_socket_boundary:
        # Current rights only: never sudo, attach to an SDK process or change permissions.
        preflight=host_probe_preflight()
        preflight['runId']=run_id
        (out/'host-probe-preflight.json').write_text(json.dumps(preflight,indent=2))
    identity = dict(platform=args.platform, suite=args.suite, fixtureTransport=args.transport_mode, runId=run_id, sourceSha=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    captureBoundary="draw/submission and verified pixel upper bound; first physical present unobserved")
    identity["sourceDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    identity['memorySamplerEnabled']=observer_mode!='none'
    identity['memorySamplerStarted']=False
    identity['memorySamplerJoined']=False
    identity['socketQueueWatchRequested']=args.socket_queue_watch
    identity['socketSchedulingTraceRequested']=args.socket_scheduling_trace
    identity['socketSyscallsRequested']=args.socket_syscalls
    identity['hostClockBoundariesRequested']=args.host_clock_boundaries
    identity['singleSocketBoundaryRequested']=args.single_socket_boundary
    if args.socket_syscalls: identity['syscallBuildType']=syscall_build_type
    observer_counts=dict(stage=0,meminfo=0)
    observer_file='stage-samples.jsonl' if args.platform=='android' and observer_mode=='stage' else 'memory.jsonl'
    if args.platform=='android':
        identity['androidObserver']=dict(mode=observer_mode,stageEnabled=observer_mode in ('both','stage'),
            meminfoEnabled=observer_mode in ('both','meminfo'),commandCounts=observer_counts,
            samplesFile=observer_file if observer_mode!='none' else None,waitAfterCycleSeconds=.2)
    if args.platform == "android":
        identity["apkSha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        identity["testApkSha256"] = hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
    (out / "run.json").write_text(json.dumps(identity, indent=2))
    stop = threading.Event()
    sampling_errors = []

    def sample():
        try:
            with (out / observer_file).open("w") as log:
                while not stop.is_set():
                    row = dict(hostMonotonicNs=time.monotonic_ns(), stage="test_process_running")
                    if args.platform == "desktop":
                        stage_file = out / "stage.json"
                        if stage_file.exists():
                            try:
                                stage = json.loads(stage_file.read_text())
                                if stage.get("runId") == run_id:
                                    row["stage"] = stage["stage"]
                            except (ValueError, KeyError):
                                pass
                        pid_file = out / "identity.json"
                        if pid_file.exists():
                            try:
                                pid = int(json.loads(pid_file.read_text())["pid"])
                            except (ValueError, KeyError):
                                stop.wait(.2)
                                continue
                            result = subprocess.run(["ps", "-o", "pid=,rss=", "-p", str(pid)], capture_output=True, text=True)
                            row.update(pid=pid, rssKiB=result.stdout.strip(), exit=result.returncode)
                    else:
                        row=observe_android(adb,PACKAGE,run_id,observer_mode,
                            expected_suite=args.suite if args.suite in ('transport','route') else None)
                        for kind,span in row['operations'].items():
                            observer_counts[kind]+=1
                            if 'error' in span: sampling_errors.append(span['error'])
                    log.write(json.dumps(row) + "\n")
                    log.flush()
                    if sampling_errors: break
                    stop.wait(.2)
        except Exception as error:
            sampling_errors.append(type(error).__name__)

    # Existing isolated service adapters implement the original constructor. Default
    # performance/behavior runs keep that contract; controls apply only when requested.
    fixture_options = {}
    if args.transport_mode != "legacy":
        fixture_options["transport"] = args.transport_mode
    if args.suite == "lifecycle":
        fixture_options["lifecycle"] = True
    if args.suite == 'route' and not args.single_socket_boundary:
        fixture_options['loopback_control'] = True
    if args.host_clock_boundaries: fixture_options['host_clock']=True
    if args.single_socket_boundary:
        fixture_options.update(single_boundary=True,host_clock=True)
    with ExitStack() as scope:
        fixture=scope.enter_context(Fixture(out / 'fixture', '127.0.0.1' if args.single_socket_boundary else args.host,
                                           renderer=args.renderer, **fixture_options))
        relay=None
        measurement_port=fixture.port
        if args.single_socket_boundary:
            relay=scope.enter_context(SocketBoundaryRelay(args.host,fixture.port,run_id+'-boundary-1',
                                                        args.socket_recording_budget_us*1000))
            measurement_port=relay.port
        identity["materialSha256"] = fixture.material_sha256
        identity["serverTransferTimingsObserved"] = hasattr(fixture, "transfers")
        if args.suite == 'route':
            identity['routeEndpoints']=dict(configured=args.host,hostLoopbackAlias='10.0.2.2',
                hostListeners=[args.host,'127.0.0.1'],port=measurement_port,productionNetworkPolicyChanged=False)
            if relay is not None:
                identity['routeEndpoints'].update(hostListeners=[args.host],backendHost='127.0.0.1',backendPort=fixture.port,
                                                 path='single-synthetic-relay; not historical direct route')
        (out / "run.json").write_text(json.dumps(identity, indent=2))
        packet_capture=PacketCapture(out,args.packet_interface,args.host,fixture.port)
        def save_http_records():
            (out / "requests.json").write_text(json.dumps(fixture.requests, indent=2))
            (out / "transfers.json").write_text(json.dumps(getattr(fixture, "transfers", []), indent=2))
        sampler = None if observer_mode=='none' else threading.Thread(target=sample)
        perfetto = None
        perfetto_log = None
        try:
            if sampler is not None:
                sampler.start()
                identity['memorySamplerStarted']=True
            packet_capture.start()
            if args.platform == "android" and capture_perfetto:
                remote_trace = f"/data/misc/perfetto-traces/reader-page-{time.time_ns()}.perfetto-trace"
                perfetto_log = (out / "perfetto.log").open("wb")
                perfetto = subprocess.Popen(adb + ["shell", "perfetto", "--txt", "-c", "-", "-o", remote_trace],
                                            stdin=subprocess.PIPE, stdout=perfetto_log, stderr=subprocess.STDOUT)
                config_name=('socket-syscalls.pbtxt' if args.socket_syscalls else 'socket-scheduling.pbtxt') if args.socket_scheduling_trace else 'frametimeline.pbtxt'
                identity['perfettoConfig']=config_name
                perfetto.stdin.write((Path(__file__).with_name(config_name)).read_bytes())
                perfetto.stdin.close()
            env = dict(os.environ, COACH_READER_FIXTURE_HOST=args.host, COACH_READER_FIXTURE_PORT=str(fixture.port),
                       COACH_READER_OBSERVED_OUTPUT=str(out), COACH_READER_RUN_ID=run_id)
            with (out / "test.log").open("w") as log:
                if args.platform == "desktop":
                    # The caller holds tools/dev/heavy --local; do not nest the same lock.
                    test_class = "com.coach.ui." + dict(performance="ReaderPageObservedTest", behavior="ReaderPageCachePathTest",
                        transport="ReaderImageTransportTest", lifecycle="ReaderPageLifetimeTest")[args.suite]
                    result = subprocess.run(["./gradlew", ":shared:ui:jvmTest", "--tests", test_class,
                                             "--rerun-tasks"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
                else:
                    test_class = "com.coach.android." + dict(performance="ReaderPageObservedSmoke", behavior="ReaderPageCachePathSmoke",
                        transport="ReaderImageTransportSmoke", route="ReaderImageTransportSmoke", lifecycle="ReaderPageLifetimeSmoke")[args.suite]
                    route_args=['-e','readerRouteControl','host-loopback'] if args.suite=='route' else []
                    if args.single_socket_boundary: route_args+=['-e','readerSocketBoundary','single-relay']
                    if args.socket_queue_watch: route_args+=['-e','readerQueueWatch','available']
                    if args.socket_scheduling_trace: route_args+=['-e','readerSocketSchedule','enabled']
                    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                        test_class, "-e", "readerPort", str(measurement_port), "-e", "readerRunId", run_id,
                        *route_args, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
                    # adb may exit 0 even if AndroidJUnitRunner reports a failing test.
                    captures = [("reader-observed.json", "trace.json")]
                    if args.suite == "performance":
                        captures.append(("reader-closed.json", "closed.json"))
                        captures.append(("reader-before-close.json", "before-close.json"))
                    if args.suite in ("transport", "route"):
                        captures.append(("reader-transport.json", "transport.json"))
                    if args.suite == "lifecycle":
                        captures.append(("reader-lifecycle-cases.json", "lifecycle-cases.json"))
                        for stage in ("baseline_idle", "book_a_ready", "three_pages_ready", "after_close", "closed_decode_paused", "final_closed", "after_gc_request"):
                            captures.append((f"reader-lifetime-{stage}.json", f"lifetime-{stage}.json"))
                    for remote, local in captures:
                        with (out / local).open("wb") as capture:
                            subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "cache/" + remote], stdout=capture, check=True)
            identity["commandExit"] = result.returncode
            save_http_records()
            if args.platform == "android" and "OK (1 test)" not in (out / "test.log").read_text():
                raise RuntimeError("Android instrumentation failed; inspect test.log")
            if result.returncode:
                raise RuntimeError("test command failed; inspect test.log")
            trace = json.loads((out / "trace.json").read_text())
            identity["measurementPid"] = trace["process"]["pid"]
            if args.suite in ("performance", "behavior"):
                expected = {"input_next": 2, "input_citation": 1} if args.suite == "behavior" else None
                summary = summarize(trace, expected)
            elif args.suite in ("transport", "route"):
                probes = json.loads((out / "transport.json").read_text())
                if args.single_socket_boundary and probes.get('boundaryControl')!='single-relay':
                    raise RuntimeError('Single boundary instrumentation missing; rebuild the isolated test APK')
                summary = dict(suite="transport", samples=len(probes.get("samples", [])),
                               urlControlSamples=len(probes.get("urlConnectionControl", [])),
                               socketControlSamples=len(probes.get("socketControl", [])),
                               uiPerformanceVerified=False, physicalMemoryBudgetVerified=False)
                association=correlate(probes,getattr(fixture,"transfers",[]))
                (out/'transport-association.json').write_text(json.dumps(association,indent=2))
                if not association['complete']:
                    raise RuntimeError('Probe request/response association incomplete; inspect transport-association.json')
                summary['requestResponseAssociationComplete']=True
                if args.suite == 'route':
                    if relay is None:
                        summary=summarize_routes(association,args.host,fixture.port)
                        (out/'route-comparison.json').write_text(json.dumps(summary,indent=2))
                    else:
                        if not relay.done.wait(timeout=3):
                            raise RuntimeError('Single nonce relay did not complete; preserve host-socket-events.json')
                        relay.close()
                        relay_report=relay.report()
                        (out/'host-socket-events.json').write_text(json.dumps(relay_report,indent=2))
                        summary=analyze_boundary(association,relay_report,run_id,args.host,measurement_port,fixture.port)
                        (out/'host-socket-boundary.json').write_text(json.dumps(summary,indent=2))
                    if not summary['complete']:
                        raise RuntimeError('Route/boundary control incomplete; inspect diagnostic JSON')
                summary['memorySamplerEnabled']=observer_mode!='none'
                summary['androidObserverMode']=observer_mode
                if args.socket_scheduling_trace:
                    if any(row['client'].get('readTraceLabel')!='ReaderSocket/first-read/'+row['probeId'] for row in association['samples']):
                        raise RuntimeError('Requested read trace labels missing; rebuild isolated instrumentation')
                    summary['schedulingEvidenceVerified']=False
                if args.socket_queue_watch:
                    queue=analyze_queue_watch(association)
                    (out/'queue-watch.json').write_text(json.dumps(queue,indent=2))
                    if not queue['complete']:
                        raise RuntimeError('Requested queue observation is incomplete; inspect queue-watch.json')
            else:
                cases = json.loads((out / "lifecycle-cases.json").read_text())["cases"]
                required = {"book_a", "switch_book_b", "restore_book_a", "version_changed", "rendering_changed", "closed", "cancelled_decode", "reopened"}
                complete = {row["case"] for row in cases if row["passed"]} == required
                if not complete:
                    raise RuntimeError("Native lifecycle cases incomplete")
                summary = dict(suite="lifecycle", cases=cases, complete=complete, physicalMemoryBudgetVerified=False, gpuReleaseVerified=False)
            (out / "summary.json").write_text(json.dumps(summary, indent=2))
            if args.platform == "desktop":
                xml = ROOT / f"shared/ui/build/test-results/jvmTest/TEST-{test_class}.xml"
                shutil.copy2(xml, out / "test.xml")
        finally:
            if relay is not None:
                relay.close()
                (out/'host-socket-events.json').write_text(json.dumps(relay.report(),indent=2))
            stop.set()
            if identity['memorySamplerStarted']:
                sampler.join()
                identity['memorySamplerJoined']=True
            try:
                packet_capture.stop()
            except Exception as error:
                process=packet_capture.process
                if process is not None and process.poll() is None:
                    process.kill();process.wait()
                packet_capture.report.update(observed=False,stopError=type(error).__name__)
            identity['packetCapture']=packet_capture.report
            if perfetto is not None:
                try:
                    identity["perfettoExit"] = perfetto.wait(timeout=45)
                    if identity["perfettoExit"] == 0:
                        trace_name=('socket-syscalls.perfetto-trace' if args.socket_syscalls else 'socket-scheduling.perfetto-trace') if args.socket_scheduling_trace else 'frames.perfetto-trace'
                        subprocess.run(adb + ["pull", remote_trace, str(out / trace_name)],
                                       check=True, stdout=perfetto_log, stderr=subprocess.STDOUT)
                except subprocess.TimeoutExpired:
                    perfetto.kill()
                    perfetto.wait()
                    identity["perfettoExit"] = "timeout"
                finally:
                    perfetto_log.close()
            identity["memorySamplerErrors"] = sampling_errors
            save_http_records()
            (out / "run.json").write_text(json.dumps(identity, indent=2))
    if sampling_errors:
        raise RuntimeError("memory sampler failed; keep the measurement incomplete")
    if args.platform=='android' and args.suite in ('transport','route'):
        requested=observer_mode in ('both','stage')
        rows=[json.loads(line) for line in (out/observer_file).read_text().splitlines()] if requested else []
        audit=audit_transport_stage(rows,requested,args.suite,identity['measurementPid'])
        (out/'stage-audit.json').write_text(json.dumps(audit,indent=2))
        if requested and not audit['verified']:
            raise RuntimeError('Current transport run/suite/PID active stage was not observed; inspect stage-audit.json')
    if args.packet_interface and (not identity['packetCapture']['observed'] or identity['packetCapture'].get('decodeExit')!=0):
        raise RuntimeError('Requested packet boundary capture is incomplete; inspect run.json and wire-capture.log')
    if capture_perfetto and identity.get("perfettoExit") != 0:
        raise RuntimeError("Requested Perfetto capture failed; keep scheduling/presentation evidence incomplete")
    print("Collected synthetic-only trace; first-present and native/GPU memory budget remain unverified.")


if __name__ == "__main__":
    main()

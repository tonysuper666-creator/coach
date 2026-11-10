"""Mac foreground runner: owns the synthetic service, test process and memory sampler until all finish."""

import argparse
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
    if args.socket_queue_watch and (args.platform!='android' or args.suite!='route' or observer_mode!='none'):
        parser.error('socket queue observation requires Android route and explicit observer none')
    if args.suite == 'route' and (args.platform != 'android' or args.transport_mode != 'legacy' or args.host in ('127.0.0.1','0.0.0.0','10.0.2.2')):
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
        for apk, expected in ((args.apk, PACKAGE), (args.test_apk, PACKAGE + ".test")):
            badging = subprocess.check_output([args.aapt2, "dump", "badging", str(apk)], text=True)
            if not re.search(r"^package: name='" + re.escape(expected) + "'", badging):
                raise ValueError("Refusing to install an APK outside the isolated measurement package")
            subprocess.run(adb + ["install", "-r", str(apk)], check=True, stdout=subprocess.DEVNULL)
    run_id = uuid.uuid4().hex
    identity = dict(platform=args.platform, suite=args.suite, fixtureTransport=args.transport_mode, runId=run_id, sourceSha=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    captureBoundary="draw/submission and verified pixel upper bound; first physical present unobserved")
    identity["sourceDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    identity['memorySamplerEnabled']=observer_mode!='none'
    identity['memorySamplerStarted']=False
    identity['memorySamplerJoined']=False
    identity['socketQueueWatchRequested']=args.socket_queue_watch
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
    if args.suite == 'route':
        fixture_options['loopback_control'] = True
    with Fixture(out / "fixture", args.host, renderer=args.renderer, **fixture_options) as fixture:
        identity["materialSha256"] = fixture.material_sha256
        identity["serverTransferTimingsObserved"] = hasattr(fixture, "transfers")
        if args.suite == 'route':
            identity['routeEndpoints']=dict(configured=args.host,hostLoopbackAlias='10.0.2.2',
                hostListeners=[args.host,'127.0.0.1'],port=fixture.port,productionNetworkPolicyChanged=False)
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
            if args.platform == "android" and args.perfetto:
                remote_trace = f"/data/misc/perfetto-traces/reader-page-{time.time_ns()}.perfetto-trace"
                perfetto_log = (out / "perfetto.log").open("wb")
                perfetto = subprocess.Popen(adb + ["shell", "perfetto", "--txt", "-c", "-", "-o", remote_trace],
                                            stdin=subprocess.PIPE, stdout=perfetto_log, stderr=subprocess.STDOUT)
                perfetto.stdin.write((Path(__file__).with_name("frametimeline.pbtxt")).read_bytes())
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
                    if args.socket_queue_watch: route_args+=['-e','readerQueueWatch','available']
                    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                        test_class, "-e", "readerPort", str(fixture.port), "-e", "readerRunId", run_id,
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
                    summary=summarize_routes(association,args.host,fixture.port)
                    (out/'route-comparison.json').write_text(json.dumps(summary,indent=2))
                    if not summary['complete']:
                        raise RuntimeError('Route control incomplete; inspect route-comparison.json')
                summary['memorySamplerEnabled']=observer_mode!='none'
                summary['androidObserverMode']=observer_mode
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
                        subprocess.run(adb + ["pull", remote_trace, str(out / "frames.perfetto-trace")],
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
    if args.perfetto and identity.get("perfettoExit") != 0:
        raise RuntimeError("FrameTimeline capture failed; keep first-presentation evidence incomplete")
    print("Collected synthetic-only trace; first-present and native/GPU memory budget remain unverified.")


if __name__ == "__main__":
    main()

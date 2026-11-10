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

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.coach.readermeasurement"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=["desktop", "android"], required=True)
    parser.add_argument("--suite", choices=["performance", "behavior"], default="performance")
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
    identity = dict(platform=args.platform, suite=args.suite, runId=run_id, sourceSha=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    captureBoundary="draw/submission and verified pixel upper bound; first physical present unobserved")
    identity["sourceDirty"] = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
    if args.platform == "android":
        identity["apkSha256"] = hashlib.sha256(args.apk.read_bytes()).hexdigest()
        identity["testApkSha256"] = hashlib.sha256(args.test_apk.read_bytes()).hexdigest()
    (out / "run.json").write_text(json.dumps(identity, indent=2))
    stop = threading.Event()
    sampling_errors = []

    def sample():
        try:
            with (out / "memory.jsonl").open("w") as log:
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
                        stage_result = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "cache/reader-stage.json"], capture_output=True, text=True)
                        if stage_result.returncode == 0:
                            try:
                                stage = json.loads(stage_result.stdout)
                                if stage.get("runId") == run_id:
                                    row["stage"] = stage["stage"]
                            except (ValueError, KeyError):
                                pass
                        result = subprocess.run(adb + ["shell", "dumpsys", "meminfo", "-s", PACKAGE], capture_output=True, text=True)
                        row.update(meminfo=result.stdout, exit=result.returncode)
                    log.write(json.dumps(row) + "\n")
                    log.flush()
                    stop.wait(.2)
        except Exception as error:
            sampling_errors.append(type(error).__name__)

    with Fixture(out / "fixture", args.host, renderer=args.renderer) as fixture:
        identity["materialSha256"] = fixture.material_sha256
        (out / "run.json").write_text(json.dumps(identity, indent=2))
        sampler = threading.Thread(target=sample)
        sampler.start()
        perfetto = None
        perfetto_log = None
        try:
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
                    test_class = "com.coach.ui.ReaderPageObservedTest" if args.suite == "performance" else "com.coach.ui.ReaderPageCachePathTest"
                    result = subprocess.run(["./gradlew", ":shared:ui:jvmTest", "--tests", test_class,
                                             "--rerun-tasks"], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
                else:
                    test_class = "com.coach.android.ReaderPageObservedSmoke" if args.suite == "performance" else "com.coach.android.ReaderPageCachePathSmoke"
                    result = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                        test_class, "-e", "readerPort", str(fixture.port), "-e", "readerRunId", run_id,
                        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], stdout=log, stderr=subprocess.STDOUT)
                    # adb may exit 0 even if AndroidJUnitRunner reports a failing test.
                    captures = [("reader-observed.json", "trace.json")]
                    if args.suite == "performance":
                        captures.append(("reader-closed.json", "closed.json"))
                        captures.append(("reader-before-close.json", "before-close.json"))
                    for remote, local in captures:
                        with (out / local).open("wb") as capture:
                            subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "cache/" + remote], stdout=capture, check=True)
            identity["commandExit"] = result.returncode
            if args.platform == "android" and "OK (1 test)" not in (out / "test.log").read_text():
                raise RuntimeError("Android instrumentation failed; inspect test.log")
            if result.returncode:
                raise RuntimeError("test command failed; inspect test.log")
            trace = json.loads((out / "trace.json").read_text())
            identity["measurementPid"] = trace["process"]["pid"]
            expected = {"input_next": 1, "input_citation": 1} if args.suite == "behavior" else None
            (out / "summary.json").write_text(json.dumps(summarize(trace, expected), indent=2))
            if args.platform == "desktop":
                xml = ROOT / f"shared/ui/build/test-results/jvmTest/TEST-{test_class}.xml"
                shutil.copy2(xml, out / "test.xml")
            (out / "requests.json").write_text(json.dumps(fixture.requests, indent=2))
        finally:
            stop.set()
            sampler.join()
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
            (out / "run.json").write_text(json.dumps(identity, indent=2))
    if sampling_errors:
        raise RuntimeError("memory sampler failed; keep the measurement incomplete")
    if args.perfetto and identity.get("perfettoExit") != 0:
        raise RuntimeError("FrameTimeline capture failed; keep first-presentation evidence incomplete")
    print("Collected synthetic-only trace; first-present and native/GPU memory budget remain unverified.")


if __name__ == "__main__":
    main()

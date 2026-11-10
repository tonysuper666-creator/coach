#!/usr/bin/env python3
"""Bound a single simulator UI-test run and retain its diagnostic log."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]


def main():
    if len(sys.argv) < 2:
        print("Pass an available iOS simulator UDID", file=sys.stderr)
        return 64
    device = sys.argv[1]
    try:
        timeout = float(os.environ.get("READER_UI_TIMEOUT_SECONDS", "600"))
        if not 0 < timeout <= 3600:
            raise ValueError
    except ValueError:
        print("READER_UI_TIMEOUT_SECONDS must be > 0 and <= 3600", file=sys.stderr)
        return 64
    deadline = time.monotonic() + timeout
    directory = Path(os.environ.get("READER_UI_LOG_DIR", ROOT / "ios/ReaderLayout/.derivedData/ui-test-runs"))
    directory = directory / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True)
    log_path = directory / "run.log"
    result_path = directory / "result.xcresult"
    print(f"UI run: timeout={timeout:g}s device={device} log={log_path} result={result_path}", flush=True)
    owned = False
    active = None
    interrupted = None

    def stop_child():
        nonlocal active
        if active is None or active.poll() is not None:
            return
        try:
            os.killpg(active.pid, signal.SIGTERM)
            active.wait(timeout=2)
        except subprocess.TimeoutExpired:
            os.killpg(active.pid, signal.SIGKILL)
            active.wait(timeout=2)
        except ProcessLookupError:
            pass

    def handle_signal(signum, _frame):
        nonlocal interrupted
        interrupted = signum
        raise InterruptedError(f"received signal {signum}")

    for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, handle_signal)

    with log_path.open("w", encoding="utf-8") as log:
        def run(label, args, limit=None):
            nonlocal active
            remaining = deadline - time.monotonic() if limit is None else limit
            if remaining <= 0:
                raise TimeoutError(label)
            log.write(f"\n[{label}] start\n")
            log.flush()
            start = time.monotonic()
            active = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                code = active.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                stop_child()
                raise TimeoutError(label)
            finally:
                log.write(f"[{label}] elapsed={time.monotonic() - start:.1f}s\n")
                log.flush()
            active = None
            log.write(f"[{label}] exit={code}\n")
            log.flush()
            return code if code >= 0 else 128 - code

        def state():
            # The command's captured output is also retained in the run log.
            before = log.tell()
            code = run("device-list", ["xcrun", "simctl", "list", "devices", "available", "-j"])
            if code:
                raise ChildFailure(code, "simctl list failed")
            with log_path.open(encoding="utf-8") as reader:
                reader.seek(before)
                output = reader.read()
            start = output.find("{")
            try:
                data, _ = json.JSONDecoder().raw_decode(output[start:])
                return next(d["state"] for group in data["devices"].values() for d in group if d["udid"] == device)
            except (ValueError, KeyError, StopIteration):
                raise ChildFailure(1, "device missing or invalid simctl JSON")

        try:
            initial = state()
            if initial == "Shutdown":
                # It was not running before this invocation; cleanup even if boot hangs.
                owned = True
                code = run("boot", ["xcrun", "simctl", "boot", device])
                if code:
                    raise ChildFailure(code, "simctl boot failed")
            elif initial != "Booted":
                raise ChildFailure(1, f"unexpected initial simulator state: {initial}")
            before = log.tell()
            code = run("bootstatus", ["xcrun", "simctl", "bootstatus", device, "-b"])
            if code:
                raise ChildFailure(code, "simctl bootstatus failed")
            with log_path.open(encoding="utf-8") as reader:
                reader.seek(before)
                boot_output = reader.read()
            if re.search(r"\b(failed|failure|error)\b", boot_output, re.IGNORECASE):
                raise ChildFailure(1, "bootstatus reported failure despite exit 0")
            terminal = re.findall(r"Status=(\d+),\s*isTerminal=YES", boot_output)
            if any(value != "4294967295" for value in terminal):
                raise ChildFailure(1, f"abnormal terminal boot status: {terminal}")
            if state() != "Booted":
                raise ChildFailure(1, "simulator is not Booted after bootstatus")
            code = run("xcodebuild", ["xcodebuild", "-quiet", "-project", "ios/ReaderLayout/Host/ReaderLayoutHost.xcodeproj",
                "-scheme", "ReaderLayoutHost", "-destination", f"platform=iOS Simulator,id={device}",
                "-derivedDataPath", "ios/ReaderLayout/.derivedData", "-resultBundlePath", str(result_path),
                "-parallel-testing-enabled", "NO", "CODE_SIGNING_ALLOWED=NO", "test", *sys.argv[2:]])
        except TimeoutError as error:
            code = 124
            print(f"UI run timed out during {error} after {time.monotonic() - (deadline - timeout):.1f}s", file=sys.stderr)
        except ChildFailure as error:
            code = error.code
            print(str(error), file=sys.stderr)
        except InterruptedError as error:
            code = 128 + (interrupted or signal.SIGTERM)
            print(str(error), file=sys.stderr)
        except OSError as error:
            code = 127 if isinstance(error, FileNotFoundError) else 126
            print(f"unable to start command: {error}", file=sys.stderr)
        finally:
            stop_child()
            # Do not touch an already-booted simulator. Shutdown is independently
            # capped to avoid letting cleanup turn a bounded run into a hang.
            if owned:
                try:
                    cleanup_code = run("shutdown", ["xcrun", "simctl", "shutdown", device], limit=10)
                    if cleanup_code:
                        print(f"simulator shutdown failed: exit {cleanup_code}", file=sys.stderr)
                        if code == 0:
                            code = cleanup_code
                except TimeoutError:
                    print("simulator shutdown timed out after 10s", file=sys.stderr)
                    if code == 0:
                        code = 124
    print(f"UI run exit={code} log={log_path} result={result_path}", flush=True)
    return code


class ChildFailure(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


if __name__ == "__main__":
    sys.exit(main())

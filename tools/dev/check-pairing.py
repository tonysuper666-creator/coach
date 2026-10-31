#!/usr/bin/env python3
"""Foreground pairing acceptance; owns an isolated server, emulator and synthetic data.
Build APKs + :server:app:installDist first. Never reads the production Coach DB.
"""
import json
import os
import re
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(value("coach.sdkDir")).expanduser()
ADB = SDK / "platform-tools/adb"
SERIAL = "emulator-5586"
BINARY = ROOT / "server/app/build/install/app/bin/app"


def run(args, **kwargs):
    result = subprocess.run(list(map(str, args)), check=True, text=True,
                            capture_output=True, timeout=kwargs.pop("timeout", 60), **kwargs)
    return result.stdout


def wait_until(check, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.5)
    raise TimeoutError("Isolated pairing acceptance timed out")


with tempfile.TemporaryDirectory(prefix="coach-pairing-") as tmp:
    work = Path(tmp)
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    env = dict(os.environ, COACH_DATA_DIR=str(work / "data"),
               COACH_TOKEN_FILE=str(work / "desktop-token"), COACH_DEVICE_ID="desktop", COACH_PORT=str(port))
    # The real allowlisted interface is required; no global cleartext exemption or adb reverse.
    env["COACH_TAIL_HOST"] = value("coach.tailHost")
    devices = run([ADB, "devices"])
    if SERIAL in devices:
        raise RuntimeError("Acceptance emulator port is already owned; refusing to reuse it")
    emulator = server = None
    with (work / "server.log").open("w") as server_log, (work / "emulator.log").open("w") as emulator_log:
        try:
            server = subprocess.Popen([BINARY], env=env, stdout=server_log, stderr=subprocess.STDOUT)

            def server_ready():
                if server.poll() is not None:
                    raise RuntimeError("Isolated server exited")
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/hello", timeout=1)
                except urllib.error.HTTPError as error:
                    return error.code == 401
                except urllib.error.URLError:
                    pass
                return False

            wait_until(server_ready, 30)
            assert (work / "desktop-token").stat().st_mode & 0o777 == 0o600
            emulator = subprocess.Popen([SDK / "emulator/emulator", "-avd", "sable-api35", "-port", "5586",
                                         "-read-only", "-no-snapshot-save", "-no-boot-anim", "-no-audio",
                                         "-no-window", "-gpu", "host", "-cores", "2"],
                                        env=dict(os.environ, ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK)),
                                        stdout=emulator_log, stderr=subprocess.STDOUT)

            def booted():
                if emulator.poll() is not None:
                    raise RuntimeError("Acceptance emulator exited")
                result = subprocess.run([str(ADB), "-s", SERIAL, "shell", "getprop", "sys.boot_completed"],
                                        capture_output=True, text=True, timeout=5)
                return result.stdout.strip() == "1"

            wait_until(booted, 180)
            for apk in ["androidApp/build/outputs/apk/debug/androidApp-debug.apk",
                        "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"]:
                run([ADB, "-s", SERIAL, "install", "-r", ROOT / apk])
            code = re.search(r"Pairing code: (\d{6})", run([BINARY, "pair-code"], env=env)).group(1)
            print("CLI pairing-code=6 digits token-file=0600", flush=True)

            def instrumentation(test, extra=()):
                output = run([ADB, "-s", SERIAL, "shell", "am", "instrument", "-w", "-e", "class", test,
                              "-e", "coachPort", port, *extra, "com.coach.android.test/androidx.test.runner.AndroidJUnitRunner"], timeout=120)
                if "OK (1 test)" not in output:
                    raise RuntimeError(output)

            test = "com.coach.android.PairingKeystoreSmoke#pairPersistAndReconnect"
            instrumentation(test, ["-e", "phase", "pair", "-e", "pairingCode", code])
            # New instrumentation process: reconstruct the Keystore instance and use HTTP + WS again.
            run([ADB, "-s", SERIAL, "shell", "am", "force-stop", "com.coach.android"])
            instrumentation(test, ["-e", "phase", "reconnect"])
            instrumentation("com.coach.android.PairingKeystoreSmoke#sharedClientsRespectAndroidCleartextAllowlist")
            instrumentation("com.coach.android.NetworkSecuritySmoke#testOtherCleartextBlocked")
            devices = json.loads(run([BINARY, "devices"], env=env))
            phone = next(device for device in devices if device["name"] == "synthetic emulator")
            run([BINARY, "revoke", phone["deviceId"]], env=env)
            instrumentation(test, ["-e", "phase", "revoked"])
            with sqlite3.connect(work / "data/coach.sqlite") as db:
                assert db.execute("SELECT count(*) FROM ai_calls").fetchone()[0] == 0
                assert db.execute("SELECT revoked_at FROM devices WHERE device_id=?", (phone["deviceId"],)).fetchone()[0] is not None
            print("ANDROID pair=pass fresh-process=pass HTTP+WS=pass Keystore=pass no-plaintext=pass", flush=True)
            print("ANDROID non-allowlisted HTTP+WS=blocked CIO-policy=blocked CLI-revoke=pass revokedHTTP=401 ai_calls=0", flush=True)
        finally:
            for process in [emulator, server]:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()

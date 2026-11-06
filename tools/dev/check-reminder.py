#!/usr/bin/env python3
"""Foreground reminder acceptance. Invoke via tools/dev/heavy --emulator.

Owns a read-only-mode SQLite backup, an isolated server and a read-only AVD instance.
Never connects to production ports or invokes AI. Private copied data stays untracked.
Build the suffixed APKs and :server:app:installDist before invoking this runner.
"""
import json
import os
import re
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(value("coach.sdkDir")).expanduser()
ADB = SDK / "platform-tools/adb"
PACKAGE = "com.coach.android.ton1293"
SERIAL = "emulator-5594"
AVD = "coach_1128_c70_r"
OUT = ROOT / ".reminder-evidence"
BINARY = ROOT / "server/app/build/install/app/bin/app"


def run(args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, capture_output=True,
                          timeout=kwargs.pop("timeout", 60), **kwargs)


def adb(*args, **kwargs):
    return run([ADB, "-s", SERIAL, *args], **kwargs).stdout.decode()


def wait(check, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except subprocess.CalledProcessError:
            # adb briefly returns nonzero while booting, and pidof does so after process death.
            pass
        time.sleep(0.5)
    raise TimeoutError("Reminder acceptance condition timed out")


def instrument(phase, *extra):
    output = adb("shell", "am", "instrument", "-w", "-e", "class", "com.coach.android.ReminderSmoke#phase",
                 "-e", "phase", phase, "-e", "coachPort", str(port), *extra,
                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=120)
    (OUT / (phase + ".txt")).write_text(output)
    if "OK (1 test)" not in output:
        raise AssertionError(output)
    print(f"instrumentation {phase}: exit=0 OK (1 test)", flush=True)


if OUT.exists():
    raise RuntimeError("Evidence directory exists: preserve or remove it explicitly before a new run")
OUT.mkdir(mode=0o700)
data = OUT / "data"
data.mkdir(mode=0o700)
source = Path.home() / "Coach/data/coach.sqlite"
with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as production:
    with sqlite3.connect(data / "coach.sqlite") as copied:
        production.backup(copied)
        event_count = copied.execute("SELECT count(*) FROM events").fetchone()[0]
        # In-flight production operations must never resume in the acceptance copy.
        copied.execute("DELETE FROM jobs")
        copied.execute("DELETE FROM turn_leases")
        copied.execute("DELETE FROM pairing_code")
        ai_before = copied.execute("SELECT count(*) FROM ai_calls").fetchone()[0]
with socket.socket() as reservation:
    reservation.bind(("127.0.0.1", 0))
    port = reservation.getsockname()[1]
assert port not in (18791, 8791, 8790)
env = dict(os.environ, COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(OUT / "test-token"),
           COACH_DEVICE_ID="ton1293-host", COACH_PORT=str(port), COACH_TAIL_HOST=value("coach.tailHost"),
           COACH_BRIDGE_URL="http://127.0.0.1:1", COACH_TRAFFIC="test", COACH_SOURCE_LOOKUP="false")
if SERIAL in run([ADB, "devices"]).stdout.decode():
    raise RuntimeError("Emulator serial already owned; refusing to reuse it")
server = emulator = None
try:
    with (OUT / "server-private.log").open("w") as log:
        server = subprocess.Popen([BINARY], env=env, stdout=log, stderr=subprocess.STDOUT)

    def server_ready():
        if server.poll() is not None:
            raise RuntimeError("Isolated server exited; inspect private log locally")
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/hello", timeout=1)
        except urllib.error.HTTPError as error:
            return error.code == 401
        except urllib.error.URLError:
            return False
        return False

    wait(server_ready, 40)
    with (OUT / "emulator.log").open("w") as log:
        emulator = subprocess.Popen([SDK / "emulator/emulator", "-avd", AVD, "-port", "5594", "-read-only",
                                     "-no-snapshot", "-no-boot-anim", "-no-audio", "-no-window", "-gpu", "host", "-cores", "2"],
                                    env=dict(env, ANDROID_HOME=str(SDK)), stdout=log, stderr=subprocess.STDOUT)

    def booted():
        if emulator.poll() is not None:
            raise RuntimeError("Acceptance emulator exited")
        return adb("shell", "getprop", "sys.boot_completed").strip() == "1"

    wait(booted, 180)
    wait(lambda: "NC_OK" in adb("shell", f"nc -z -w 2 {env['COACH_TAIL_HOST']} {port} && echo NC_OK"), 60)
    adb("shell", "settings", "put", "system", "system_locales", "en-US")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "600000")
    adb("shell", "input", "keyevent", "KEYCODE_MENU")
    for apk in ["androidApp/build/outputs/apk/debug/androidApp-debug.apk",
                "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"]:
        adb("install", "-r", ROOT / apk)
    # First enable really requests Android permission; a denied toggle must not prompt again.
    adb("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "pm", "clear-permission-flags", PACKAGE, "android.permission.POST_NOTIFICATIONS", "user-set", "user-fixed")
    code = re.search(r"Pairing code: (\d{6})", run([BINARY, "pair-code"], env=env).stdout.decode()).group(1)
    instrument("prepare", "-e", "pairingCode", code)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    instrument("schedule")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "kill", PACKAGE)
    def process_dead():
        result = subprocess.run([str(ADB), "-s", SERIAL, "shell", "pidof", PACKAGE], capture_output=True, timeout=10)
        return result.returncode == 1 and not result.stdout.strip()
    wait(process_dead, 20)
    print("process killed after scheduling; waiting for real AlarmManager delivery", flush=True)
    wait(lambda: "due=" in adb("logcat", "-d", "-s", "CoachReminder:I") and
         "notified" in adb("logcat", "-d", "-s", "CoachReminder:I"), 240)
    instrument("tap")
    for name in ("notification.png", "review.png"):
        image = run([ADB, "-s", SERIAL, "exec-out", "run-as", PACKAGE, "cat", "cache/" + name]).stdout
        (OUT / name).write_bytes(image)
    alarm_before = adb("shell", "dumpsys", "alarm")
    assert PACKAGE in alarm_before and "REVIEW_REMINDER" in alarm_before
    adb("reboot")
    time.sleep(2)
    run([ADB, "-s", SERIAL, "wait-for-device"], timeout=120)
    wait(booted, 120)
    wait(lambda: "REVIEW_REMINDER" in adb("shell", "dumpsys", "alarm"), 30)
    alarms = adb("shell", "dumpsys", "alarm")
    # Preserve just our package's alarm excerpt, not unrelated device state.
    lines = alarms.splitlines()
    selected = set()
    for i, line in enumerate(lines):
        if PACKAGE in line:
            selected.update(range(max(0, i - 2), min(len(lines), i + 5)))
    (OUT / "alarm-after-boot.txt").write_text("\n".join(lines[i] for i in sorted(selected)) + "\n")
    instrument("restored")
    alarms = adb("shell", "dumpsys", "alarm")
    # Alarm history can retain the action; active PendingIntent must be gone.
    assert not any("RTC_WAKEUP" in line and PACKAGE in line for line in alarms.splitlines())
    server.terminate(); server.wait(timeout=20)
    instrument("zero")
    with sqlite3.connect(data / "coach.sqlite") as db:
        assert db.execute("SELECT count(*) FROM ai_calls").fetchone()[0] == ai_before
    reminder_log = adb("logcat", "-d", "-s", "CoachReminder:I", "CoachReminderTest:I")
    (OUT / "reminder-log.txt").write_text(reminder_log)
    (OUT / "result.json").write_text(json.dumps(dict(avd=AVD, package=PACKAGE, serverPort=port,
        backupEvents=event_count, productionOpened="mode=ro + SQLite backup", aiCallsAdded=0,
        phases=["permission denial", "two-minute alarm", "process death", "notification tap", "reboot", "disable", "zero due"],
        exitCode=0), indent=2) + "\n")
    print(f"PASS backupEvents={event_count} port={port} aiCallsAdded=0 exit=0", flush=True)
finally:
    for process in (emulator, server):
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()

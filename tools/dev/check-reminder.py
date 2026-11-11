#!/usr/bin/env python3
"""Foreground reminder acceptance. Invoke via tools/dev/heavy --emulator.

Runs against an explicit private synthetic data directory, never the installed Coach tree or a production backup. Seeds one synthetic due
review for the APK's course, then owns an isolated server and a read-only AVD instance.
Never connects to production ports or invokes AI. The copied data, credentials and
server log live in a temporary directory that is deleted afterwards; only content-free
reminder evidence is written to --out. Build the suffixed APKs (-Pcoach.course must
match --course) and :server:app:installDist before invoking this runner.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from zoneinfo import ZoneInfo
from local_config import value

ROOT = Path(__file__).resolve().parents[2]
SDK = Path(value("coach.sdkDir")).expanduser()
ADB = SDK / "platform-tools/adb"
AVD = "coach_1128_c70_r"
BINARY = ROOT / "server/app/build/install/app/bin/app"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--private-data-dir", required=True, type=Path, help="synthetic private data copy outside the installed Coach tree")
parser.add_argument("--application-id", required=True, help="isolated TON-specific APK package")
parser.add_argument("--course", default="ton1291-synthetic-review", help="course compiled into the APK (-Pcoach.course)")
parser.add_argument("--out", required=True, type=Path, help="new directory for content-free evidence")
cli = parser.parse_args()
assert re.fullmatch(r"com\.coach\.android\.ton[0-9]+", cli.application_id), "Use an isolated TON-specific APK package"
PACKAGE = cli.application_id
OUT = cli.out.resolve()
source_data = cli.private_data_dir.resolve()
assert not source_data.is_relative_to((Path.home() / "Coach").resolve()), "Refusing installed Coach tree"
assert (source_data / "coach.sqlite").is_file() and not (source_data / "coach.sqlite").is_symlink()
assert BINARY.is_file(), "Build installDist through tools/dev/heavy first"


def free_emulator_port():
    def available(port):
        try:
            with socket.socket() as connection:
                connection.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False
    # Select only local emulator ports; do not enumerate connected phones.
    return next(p for p in range(5588, 5680, 2) if available(p) and available(p + 1))


EMULATOR_PORT = free_emulator_port()
SERIAL = f"emulator-{EMULATOR_PORT}"


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


def save_reminder_log():
    before_boot = OUT / "reminder-log-before-boot.txt"
    prefix = before_boot.read_text() if before_boot.exists() else ""
    current = adb("logcat", "-d", "-s", "CoachReminder:I", "CoachReminderTest:I")
    (OUT / "reminder-log.txt").write_text(prefix + current)


def seed_due_review(db, course):
    """One synthetic objective that the exam-compressed schedule makes due today (as check-review-copy.py)."""
    kp = course + "-kp"
    now = int(time.time() * 1000)
    local = datetime.datetime.fromtimestamp(now / 1000, ZoneInfo("Europe/London"))
    date = local.date() - datetime.timedelta(days=int(local.hour < 4))
    day = (date - datetime.date(1970, 1, 1)).days
    assert db.execute("select count(*) from events where course_id=?", (course,)).fetchone()[0] == 0, "Fixture ID collision"
    seq = db.execute("select coalesce(max(seq),0) from events").fetchone()[0]
    facts = [
        ("outline_revised", {"course": {"tz": "Europe/London"}, "ops": [{"kpId": kp, "title": "Synthetic review objective", "conceptIds": ["synthetic"]}]}),
        ("legacy_passed", {"kpId": kp}),
        ("legacy_schedule", {"kpId": kp, "dueDay": day + 12, "interval": 15}),
        ("exam_set", {"examDay": day + 7}),
    ]
    for i, (kind, payload) in enumerate(facts, 1):
        event = dict(seq=seq + i, id=f"{course}-r{i}", courseId=course, type=kind, schema=1, recordedAt=now, occurredAt=now,
                     deviceId="desktop", actor="SYSTEM", effectIndex=0, idemKey=f"{course}-r{i}", payload=payload)
        db.execute("insert into events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (event["seq"], event["id"], course, kind, 1, now, now, "desktop", "SYSTEM", 0, event["idemKey"], json.dumps(payload), json.dumps(event)))
    return len(facts)


os.umask(0o077)
if OUT.exists():
    raise RuntimeError("Evidence directory exists: preserve or remove it explicitly before a new run")
OUT.mkdir(mode=0o700, parents=True)
private = Path(tempfile.mkdtemp(prefix="coach-reminder-"))
data = private / "data"
data.mkdir(mode=0o700)
with sqlite3.connect((source_data / "coach.sqlite").as_uri() + "?mode=ro", uri=True) as fixture:
    with sqlite3.connect(data / "coach.sqlite") as copied:
        fixture.backup(copied)
        assert copied.execute("pragma integrity_check").fetchone()[0] == "ok"
        # In-flight operations must never resume in the acceptance copy.
        copied.execute("DELETE FROM jobs")
        copied.execute("DELETE FROM turn_leases")
        copied.execute("DELETE FROM pairing_code")
        seeded = seed_due_review(copied, cli.course)
        event_count = copied.execute("SELECT count(*) FROM events").fetchone()[0]
        ai_before = copied.execute("SELECT count(*) FROM ai_calls").fetchone()[0]
with socket.socket() as reservation:
    reservation.bind(("127.0.0.1", 0))
    port = reservation.getsockname()[1]
assert port not in (18791, 8791, 8790, 3790)
for name in ("home", "tmp", "packs"):
    (private / name).mkdir(mode=0o700)
env = {k: v for k, v in os.environ.items() if not k.startswith(("COACH_", "BRIDGE_"))
       and k not in ("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
env.update(COACH_DATA_DIR=str(data), COACH_TOKEN_FILE=str(private / "test-token"),
           COACH_DEVICE_ID=PACKAGE.rsplit(".", 1)[1] + "-host", COACH_PORT=str(port), COACH_TAIL_HOST=value("coach.tailHost"),
           COACH_LOG_DIR=str(private / "logs"), COACH_STATE_DIR=str(private / "state"), COACH_RUN_DIR=str(private / "run"),
           COACH_PACKS_DIR=str(private / "packs"), COACH_ACTIVITY_SOCKET=str(private / "activity.sock"), COACH_MANAGE_SERVICES="0",
           JAVA_OPTS=f"-Duser.home={private}/home -Djava.io.tmpdir={private}/tmp",
           COACH_BRIDGE_URL="http://127.0.0.1:1", COACH_TRAFFIC="test", COACH_SOURCE_LOOKUP="false")
if SERIAL in run([ADB, "devices"]).stdout.decode():
    raise RuntimeError("Emulator serial already owned; refusing to reuse it")
server = emulator = None
passed = False
try:
    with (private / "server-private.log").open("w") as log:
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
        emulator = subprocess.Popen([SDK / "emulator/emulator", "-avd", AVD, "-port", str(EMULATOR_PORT), "-read-only",
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
    display = [adb("shell", "wm", "size").strip(), adb("shell", "wm", "density").strip()]
    for apk in ["androidApp/build/outputs/apk/debug/androidApp-debug.apk",
                "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"]:
        adb("install", "-r", ROOT / apk)
    # Only our suffixed test package: make first-enable permission checks repeatable.
    adb("shell", "pm", "clear", PACKAGE)
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
    for name in ("notification.png", "review.png", "permission-denied.png"):
        image = run([ADB, "-s", SERIAL, "exec-out", "run-as", PACKAGE, "cat", "cache/" + name]).stdout
        (OUT / name).write_bytes(image)
    alarm_before = adb("shell", "dumpsys", "alarm")
    assert PACKAGE in alarm_before and "REVIEW_REMINDER" in alarm_before
    # Use the platform's timezone command, not a fabricated broadcast. The receiver must
    # move the pending reminder to the selected wall-clock time in the new zone.
    previous_zone = adb("shell", "getprop", "persist.sys.timezone").strip()
    changed_zone = "America/New_York" if previous_zone != "America/New_York" else "Asia/Tokyo"
    adb("shell", "cmd", "alarm", "set-timezone", changed_zone)
    time.sleep(2)
    instrument("timezone", "-e", "expectedZone", changed_zone)
    # Reboot clears logcat; retain the permission, real-delivery and timezone evidence.
    (OUT / "reminder-log-before-boot.txt").write_text(adb("logcat", "-d", "-s", "CoachReminder:I", "CoachReminderTest:I"))
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
    save_reminder_log()
    (OUT / "result.json").write_text(json.dumps(dict(avd=AVD, package=PACKAGE, serverPort=port, display=display,
        fixture="synthetic private copy", seededEvents=seeded, fixtureEvents=event_count, productionConnections=0, aiCallsAdded=0,
        phases=["permission denial", "two-minute alarm", "process death", "notification tap", "timezone change", "reboot", "disable", "zero due"],
        exitCode=0), indent=2) + "\n")
    print(f"PASS fixture=synthetic seededEvents={seeded} port={port} aiCallsAdded=0 exit=0", flush=True)
    passed = True
finally:
    # Preserve only content-free reminder diagnostics on failures as well. The private
    # server log and copied ledger remain local and must never be attached to the issue.
    if emulator is not None and emulator.poll() is None:
        try:
            save_reminder_log()
            if not passed:
                # What the synthetic screen showed when a phase failed; the accessibility dump with
                # bounds is in reminder-log.txt. The foldable AVD reports several displays, so
                # screencap prefixes a text warning to the PNG on stdout; keep only the image.
                shot = run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"]).stdout
                (OUT / "failure.png").write_bytes(shot[max(shot.find(b"\x89PNG"), 0):])
                # In-app screenshots the instrumentation took before it failed (synthetic UI only).
                for name in ("notification.png", "review.png", "permission-denied.png", "timeout.png"):
                    image = subprocess.run([str(ADB), "-s", SERIAL, "exec-out", "run-as", PACKAGE, "cat", "cache/" + name],
                                           capture_output=True, timeout=30).stdout
                    if image.startswith(b"\x89PNG"):
                        (OUT / name).write_bytes(image)
        except (subprocess.SubprocessError, OSError):
            pass
    uninstall_failed = False
    if emulator is not None and emulator.poll() is None:
        # The isolated APKs must not survive the smoke, including assertion failures.
        for package in (PACKAGE + ".test", PACKAGE):
            try:
                removed = subprocess.run([str(ADB), "-s", SERIAL, "uninstall", package], capture_output=True, timeout=30)
                uninstall_failed |= removed.returncode != 0
                print(json.dumps(dict(package=package, uninstallExitCode=removed.returncode)), flush=True)
            except subprocess.TimeoutExpired:
                uninstall_failed = True
                print(json.dumps(dict(package=package, uninstallTimedOut=True)), flush=True)
    for process in (emulator, server):
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
    shutil.rmtree(private, ignore_errors=True)
    print(json.dumps(dict(ownedEmulatorReaped=emulator is None or emulator.poll() is not None,
                          privateCopyRemoved=not private.exists())), flush=True)
    assert not uninstall_failed, "Failed to uninstall isolated reminder APKs"

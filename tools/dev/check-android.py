import os, subprocess, time, signal, urllib.request
from pathlib import Path
from local_config import tail_host, value

tail = tail_host()
r = Path(__file__).resolve().parents[2]
out = r / ".s0-evidence"
out.mkdir(exist_ok=True)
os.chdir(out)
sdk = Path(value("coach.sdkDir")).expanduser()
adb = str(sdk / "platform-tools/adb")
ts = str(Path(value("coach.tailscaleCli")).expanduser())
serial = "emulator-5568"
procs = []
logs = []
serve_owned = False


def run(args, **kwargs):
    print("+", " ".join(map(str, args)), flush=True)
    return subprocess.run(
        list(map(str, args)), check=True, timeout=kwargs.pop("timeout", 30), **kwargs
    )


def server(name, env):
    f = open(name + ".log", "w")
    logs.append(f)
    p = subprocess.Popen(
        [str(r / "server/app/build/install/app/bin/app")],
        env=dict(os.environ, **env),
        stdout=f,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    procs.append(p)
    port = env.get("COACH_PORT", "18791")
    for _ in range(60):
        try:
            with urllib.request.urlopen(
                "http://127.0.0.1:" + port + "/v1/hello", timeout=1
            ) as response:
                assert response.read() == b"Hello from Coach"
            return p
        except OSError:
            time.sleep(0.2)
    raise RuntimeError("server not ready")


try:
    existing = subprocess.run(
        [adb, "-s", serial, "get-state"], capture_output=True, text=True, timeout=5
    )
    if existing.returncode == 0:
        raise RuntimeError(
            serial + " is already in use; will not stop an existing emulator"
        )
    server("server-android", {})
    if os.environ.get("COACH_VERIFY_PEER"):
        run(
            [
                "ssh",
                "-oBatchMode=yes",
                "-oConnectTimeout=8",
                os.environ["COACH_VERIFY_PEER"],
                f"curl -fsS --connect-timeout 5 --max-time 10 http://{tail}:18791/v1/hello",
            ],
            timeout=20,
        )
        print("\nNormal tailnet listener reachable from peer", flush=True)
    current = run([ts, "serve", "status"], capture_output=True, text=True).stdout
    print("serve status before:", repr(current), flush=True)
    if (
        os.environ.get("COACH_VERIFY_PEER")
        and not os.environ.get("COACH_SKIP_SERVE")
        and (not current.strip() or current.strip() == "No serve config")
    ):
        server("server-serve", {"COACH_PORT": "18795", "COACH_TAIL_HOST": "192.0.2.1"})
        # The task explicitly authorizes this one temporary TCP mapping.
        run([ts, "serve", "--bg", "--tcp=18793", "tcp://127.0.0.1:18795"], timeout=20)
        serve_owned = True
        run(
            [
                "ssh",
                "-oBatchMode=yes",
                "-oConnectTimeout=8",
                os.environ["COACH_VERIFY_PEER"],
                f"curl -fsS --connect-timeout 5 --max-time 10 http://{tail}:18793/v1/hello",
            ],
            timeout=20,
        )
        print(
            "\nTCP serve forwards to loopback-only Ktor from another tailnet peer",
            flush=True,
        )
        run([ts, "serve", "--tcp=18793", "off"])
        serve_owned = False
        run([ts, "serve", "status"])
    else:
        print(
            "SKIP TCP serve experiment: existing configuration or COACH_SKIP_SERVE was set",
            flush=True,
        )
    ef = open("emulator.log", "w")
    logs.append(ef)
    emu = subprocess.Popen(
        [
            str(r / "tools/dev/heavy"),
            str(sdk / "emulator/emulator"),
            "-avd",
            "sable-api35",
            "-port",
            "5568",
            "-no-snapshot-save",
            "-no-boot-anim",
            "-no-audio",
            "-no-window",
            "-gpu",
            "host",
            "-cores",
            "2",
        ],
        env=dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk)),
        stdout=ef,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    procs.append(emu)
    for _ in range(180):
        if emu.poll() is not None:
            raise RuntimeError("emulator exited " + str(emu.returncode))
        check = subprocess.run(
            [adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
            capture_output=True,
            text=True,
            timeout=5,
        )
        if check.stdout.strip() == "1":
            break
        time.sleep(1)
    else:
        raise RuntimeError("emulator boot timeout")
    run([adb, "-s", serial, "emu", "avd", "name"])
    run(
        [
            adb,
            "-s",
            serial,
            "install",
            "-r",
            r / "androidApp/build/outputs/apk/debug/androidApp-debug.apk",
        ],
        timeout=90,
    )
    run(
        [
            adb,
            "-s",
            serial,
            "install",
            "-r",
            r
            / "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk",
        ],
        timeout=90,
    )
    run(
        [
            adb,
            "-s",
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.coach.android/.MainActivity",
        ]
    )
    time.sleep(3)
    with open("android-app.png", "wb") as screenshot:
        run([adb, "-s", serial, "exec-out", "screencap", "-p"], stdout=screenshot)
    result = run(
        [
            adb,
            "-s",
            serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "com.coach.android.test/androidx.test.runner.AndroidJUnitRunner",
        ],
        capture_output=True,
        text=True,
        timeout=90,
    )
    print(result.stdout, flush=True)
    assert "OK (3 tests)" in result.stdout
    run([adb, "-s", serial, "logcat", "-d", "-s", "CoachSmoke:I", "*:S"])
    run(
        [
            adb,
            "-s",
            serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            "com.coach.android/.MainActivity",
        ]
    )
    run([adb, "-s", serial, "emu", "kill"])
    emu.wait(timeout=20)
    print("Android runtime + HTTP/WS security verification passed", flush=True)
finally:
    if serve_owned:
        subprocess.run([ts, "serve", "--tcp=18793", "off"], timeout=20)
    for p in reversed(procs):
        if p.poll() is None:
            os.killpg(p.pid, signal.SIGTERM)
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait()
    for f in logs:
        f.close()

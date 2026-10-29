import os, subprocess, tempfile, time, signal
from pathlib import Path

heavy = str(Path(__file__).resolve().with_name("heavy"))
with tempfile.TemporaryDirectory(prefix="coach-heavy-") as tmp:
    env = dict(os.environ, COACH_HEAVY_LOCK=tmp + "/lock")
    first = subprocess.Popen(
        [heavy, "bash", "-c", "echo running; sleep 2; exit 7"],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert first.stdout.readline().strip() == "running"
    assert Path(tmp + "/lock/owner").exists()
    second = subprocess.Popen(
        [heavy, "bash", "-c", "ps -o ni= -p $$"],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert first.wait() == 7
    out, err = second.communicate(timeout=10)
    assert second.returncode == 0 and int(out.strip()) >= 15
    assert "waiting;" in err and "PID=" in err
    assert not Path(tmp + "/lock").exists()
    print(
        "heavy: contention waits with owner; command exit=7 propagated; child nice>=15; exit cleanup OK"
    )
    process = subprocess.Popen([heavy, "sleep", "30"], env=env)
    for _ in range(100):
        if Path(tmp + "/lock/owner").exists():
            break
        time.sleep(0.01)
    process.send_signal(signal.SIGTERM)
    assert process.wait(timeout=5) == 130
    assert not Path(tmp + "/lock").exists()
    print("heavy: SIGTERM cleanup OK")

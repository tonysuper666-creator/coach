import subprocess, os, time, urllib.request, signal, socket, json
from urllib.parse import urlparse
from pathlib import Path
from local_config import tail_host

tail = tail_host()
r = Path(__file__).resolve().parents[2]
logroot = r / ".s0-evidence"
logroot.mkdir(exist_ok=True)
server = r / "server/app/build/install/app/bin/app"
procs = []
handles = []
data_by_port = {}


def start(name, env):
    f = open(logroot / (name + ".log"), "w")
    handles.append(f)
    data = logroot / (name + "-data")
    env = dict(env, COACH_DATA_DIR=str(data))
    data_by_port[int(env["COACH_PORT"])] = data
    p = subprocess.Popen(
        [str(server)],
        env=dict(os.environ, **env),
        stdout=f,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    procs.append(p)
    return p


def get(url):
    token = (data_by_port[urlparse(url).port] / "device-token").read_text().strip()
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token, "X-Coach-Device": "desktop"})
    with urllib.request.urlopen(request, timeout=2) as res:
        return res.read().decode()


def ready(url):
    for _ in range(50):
        try:
            body = get(url)
            assert json.loads(body)["epoch"]
            print(url, body, flush=True)
            return
        except (OSError, AssertionError, ValueError):
            time.sleep(0.2)
    raise RuntimeError("not ready " + url)


try:
    p = start(
        "server-unavailable", {"COACH_PORT": "18792", "COACH_TAIL_HOST": "192.0.2.1"}
    )
    ready("http://127.0.0.1:18792/v1/hello")
    time.sleep(3)
    assert p.poll() is None
    print("unavailable tail address: process alive, loopback available", flush=True)
    blocker = socket.socket()
    blocker.bind((tail, 18794))
    blocker.listen()
    p = start("server-rebind", {"COACH_PORT": "18794"})
    ready("http://127.0.0.1:18794/v1/hello")
    time.sleep(2)
    blocker.close()
    ready(f"http://{tail}:18794/v1/hello")
    print("tail listener recovers after occupied port released", flush=True)
finally:
    for p in procs:
        if p.poll() is None:
            os.killpg(p.pid, signal.SIGTERM)
    for p in procs:
        try:
            p.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            p.wait()
    for f in handles:
        f.close()
    for name in ["server-unavailable", "server-rebind"]:
        assert "Failed to destroy" not in (logroot / (name + ".log")).read_text()

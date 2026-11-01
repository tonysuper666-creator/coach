"""Controlled performance runs for the render spike: Android (JankStats + frame gaps) and Mac (frame gaps).

Run the whole thing under the heavy lock:  tools/dev/heavy python3 tools/render/perf.py
Refuses to start while the machine is busy (1-minute load average above PERF_MAX_LOAD1, default 8, on 18 cores), waits up
to PERF_WAIT_SECONDS (default 480) for it to quiet down, and records for every single run: load averages before/after, the
busiest processes, how many processes burn >50% CPU, other emulators/Gradle daemons, and whether the desktop window was
showing/focused. Nothing is dropped: every run, cold-start stalls included, goes to perf/<timestamp>/runs.json.
PERF_RUNS (default 3) runs per mode. Several cards can measure side by side: PERF_EMU_PORT (emulator port, default 5574), PERF_PACKAGE, PERF_APK, PERF_SKIP_MAC=1
(Android only); PERF_APKS="before=a.apk,after=b.apk" runs several builds of the same package interleaved so machine drift hits them alike. The replay screen sits behind
the pairing gate: build with -Pcoach.port=<test port> and pair the emulator with a NON-production test server first, never port 18791. A run that starts while the load gate is open is still marked with its own readings.
"""
import json, os, re, signal, subprocess, sys, time
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render')))
out = root / 'perf' / time.strftime('%Y%m%d-%H%M%S'); out.mkdir(parents=True)
max_load = float(os.environ.get('PERF_MAX_LOAD1', '8')); wait_s = int(os.environ.get('PERF_WAIT_SECONDS', '480')); runs_n = int(os.environ.get('PERF_RUNS', '3'))
emu_port = os.environ.get('PERF_EMU_PORT', '5574'); skip_mac = os.environ.get('PERF_SKIP_MAC') == '1'  # own port/package/APK so two cards can measure side by side (TON-1142)
sdk = Path(os.environ['ANDROID_HOME']); adb = str(sdk / 'platform-tools/adb'); serial = f'emulator-{emu_port}'; package = os.environ.get('PERF_PACKAGE', 'com.coach.android')
apk = os.environ.get('PERF_APK', str(repo / 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'))
# PERF_APKS="before=a.apk,after=b.apk" measures several builds of the same package interleaved (reinstall keeps pairing and data), so slow drift of the machine hits all of them alike.
builds = [tuple(x.split('=', 1)) for x in os.environ['PERF_APKS'].split(',')] if os.environ.get('PERF_APKS') else [('', apk)]
records = []

def sh(args, **kw): return subprocess.run([str(a) for a in args], capture_output=True, text=True, timeout=kw.pop('timeout', 30), **kw).stdout
def snapshot():
    ps = sh(['ps', '-Ao', 'pcpu,pid,comm', '-r']).splitlines()[1:9]
    rows = [(float(l.split(None, 2)[0]), l.split(None, 2)[2][-70:]) for l in ps if l.strip()]
    return {'load': [round(x, 2) for x in os.getloadavg()], 'busy_over_50pct': sum(1 for c, _ in rows if c >= 50),
            'top': [f'{c:.0f}% {n}' for c, n in rows[:5]],
            'other_emulators': len([p for p in sh(['pgrep', '-f', 'qemu-system|emulator/emulator']).split() if p]),
            'gradle_daemons': len([p for p in sh(['pgrep', '-f', 'GradleDaemon']).split() if p])}
def wait_quiet(limit):
    end = time.time() + limit
    while os.getloadavg()[0] >= max_load:
        if time.time() > end: return False
        time.sleep(15)
    return True
def save(): (out / 'runs.json').write_text(json.dumps(records, indent=1, ensure_ascii=False))
def start_run(platform, mode):
    if not wait_quiet(wait_s if not records else 180):
        print(json.dumps({'aborted': 'machine busy', 'snapshot': snapshot(), 'completed_runs': len(records)})); save(); sys.exit(3 if not records else 4)
    return {'platform': platform, 'mode': mode, 'before': snapshot(), 't0': time.strftime('%H:%M:%S')}
def finish_run(r, **fields):
    r['after'] = snapshot(); r.update(fields); records.append(r); save(); print(json.dumps({k: r[k] for k in ('platform', 'mode', 'before', 'summary') if k in r}, ensure_ascii=False)[:600])

# ---------------- Android
def device(*a, **kw): return sh([adb, '-s', serial, *a], **kw)
proc = None
try:
    if not wait_quiet(wait_s):
        print(json.dumps({'aborted': 'machine busy', 'snapshot': snapshot()})); sys.exit(3)
    # Start the emulator only once the machine is quiet, so it is not part of the load that the gate looks at.
    proc = subprocess.Popen(['nice', '-n', '15', str(sdk / 'emulator/emulator'), '-avd', 'sable-api35', '-port', emu_port, '-no-snapshot-save', '-no-boot-anim', '-no-audio', '-no-window', '-gpu', 'host', '-cores', '2'], stdout=(out / 'emulator.log').open('w'), stderr=subprocess.STDOUT, start_new_session=True)
    for _ in range(180):
        if device('shell', 'getprop', 'sys.boot_completed').strip() == '1': break
        time.sleep(1)
    else: raise RuntimeError('emulator boot timeout')
    device('install', '-r', builds[0][1], timeout=90)
    device('push', root / 'replay.json', '/data/local/tmp/coach-render-replay.json'); device('shell', 'run-as', package, 'mkdir', '-p', 'files', 'shared_prefs')
    device('shell', 'run-as', package, 'cp', '/data/local/tmp/coach-render-replay.json', 'files/replay.json')
    device('shell', 'wm', 'density', '420'); device('shell', 'wm', 'size', '1248x1972')
    for i in range(runs_n):
        for label, build in builds:
            if len(builds) > 1: device('install', '-r', build, timeout=90)
            for name, mode in (('pinned', 1), ('scrolled-away', 2)):
                r = start_run('android', name); r['build'] = label; device('shell', 'am', 'force-stop', package); device('logcat', '-c')
                device('shell', 'am', 'start', '-W', '-n', package + '/com.coach.android.MainActivity', '--ei', 'autoplay', str(mode), '--el', 'runId', str(time.time_ns()))
                logs = ''
                for _ in range(400):
                    logs = device('logcat', '-d', '-s', 'CoachRender:I', 'AndroidRuntime:E', '*:S')
                    if 'SUMMARY mode=' in logs or 'FATAL EXCEPTION' in logs: break
                    time.sleep(.5)
                device('shell', 'input', 'keyevent', 'KEYCODE_HOME'); time.sleep(.7)
                logs = device('logcat', '-d', '-s', 'CoachRender:I', 'AndroidRuntime:E', '*:S')
                summary = [l.split('CoachRender: ', 1)[1] for l in logs.splitlines() if 'SUMMARY mode=' in l]
                jank = [l.split('CoachRender: ', 1)[1] for l in logs.splitlines() if 'JANK frames' in l]
                finish_run(r, index=i + 1, build=label, summary=summary[-1] if summary else 'MISSING', jankstats=jank[-1] if jank else 'MISSING', fatal='FATAL EXCEPTION' in logs)
    device('shell', 'wm', 'size', 'reset'); device('shell', 'wm', 'density', 'reset')
finally:
    if proc is not None:
        subprocess.run([adb, '-s', serial, 'emu', 'kill'], capture_output=True, timeout=15)
        try: proc.wait(timeout=30)
        except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGTERM)

# ---------------- Mac desktop (window 840x568dp, replay all 20 answers at 30 chars / 50 ms)
if skip_mac: print(json.dumps({'runs': len(records), 'out': str(out)})); sys.exit(0)
app = repo / 'desktopApp/build/compose/binaries/main/app/Coach.app/Contents/MacOS/Coach'
for i in range(runs_n):
    for name, mode in (('pinned', 1), ('scrolled-away', 2)):
        r = start_run('mac', name); log = out / f'mac-{name}-{i + 1}.log'
        env = dict(os.environ, COACH_AUTOPLAY=str(mode), COACH_RENDER_STATE=str(out / f'state-{name}-{i + 1}.json'),
                   JAVA_TOOL_OPTIONS=f'-Dcoach.summaryCapturePath={out}/mac-{name}-{i + 1}.png')
        with log.open('w') as f: subprocess.run(['nice', '-n', '15', str(app)], env=env, stdout=f, stderr=subprocess.STDOUT, timeout=420)
        text = log.read_text(); summary = [l for l in text.splitlines() if l.startswith('SUMMARY')]
        front = sh(['osascript', '-e', 'tell application "System Events" to get name of first process whose frontmost is true'], timeout=10).strip() or 'unavailable'
        finish_run(r, index=i + 1, summary=summary[-1] if summary else 'MISSING', window=[l for l in text.splitlines() if l.startswith('COACH window')], frontmost_after=front)
print(json.dumps({'runs': len(records), 'out': str(out)}))

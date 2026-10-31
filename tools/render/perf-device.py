"""Streaming-render performance on a real Android device (JankStats + frame gaps), TON-1100.

Unlike perf.py this starts no emulator and has no Mac load gate: the frames are drawn on the phone, so the Mac's load does not
enter the measurement. The Mac load and the phone's own state (battery temperature, busiest processes, thermal status) are
recorded for every run instead, and nothing is dropped: every run goes to perf-device/<timestamp>/runs.json.

The phone must already have an APK that is paired with a NON-production server (build with -Pcoach.port=NNNN); the replay
screen sits behind the pairing gate. Never pair a test device with the production port.

  COACH_DEVICE=host:port  adb serial (required)      PERF_RUNS=3   runs per mode      ADB=path/to/adb      PERF_PACKAGE=<installed package, e.g. a -PcoachApplicationId build>
"""
import json, os, re, subprocess, sys, time
from pathlib import Path

root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render')))
out = root / 'perf-device' / time.strftime('%Y%m%d-%H%M%S'); out.mkdir(parents=True)
adb = os.environ.get('ADB', '/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
serial = os.environ['COACH_DEVICE']; runs_n = int(os.environ.get('PERF_RUNS', '3')); package = os.environ.get('PERF_PACKAGE', 'com.coach.android')
min_frames = 900
sys.path.insert(0, str(Path(__file__).resolve().parent)); import devicecheck
installed_port = devicecheck.require_test_port(adb, serial, package)
records = []

def device(*a, timeout=30): return subprocess.run([adb, '-s', serial, *[str(x) for x in a]], capture_output=True, text=True, timeout=timeout).stdout
def shell(cmd, timeout=30): return device('shell', cmd, timeout=timeout)
def snapshot():
    cpu = [l.strip() for l in shell('dumpsys cpuinfo | head -8').splitlines() if '%' in l][:5]
    battery = dict(re.findall(r'(level|temperature): (\d+)', shell('dumpsys battery')))
    thermal = re.findall(r'Thermal Status: (\d+)', shell('dumpsys thermalservice | head -20'))
    return {'mac_load': [round(x, 2) for x in os.getloadavg()], 'battery_level': battery.get('level'), 'battery_temp_tenth_c': battery.get('temperature'),
            'thermal_status': thermal[0] if thermal else None, 'phone_top': cpu}
def save(): (out / 'runs.json').write_text(json.dumps(records, indent=1, ensure_ascii=False))

info = {'installed_port': installed_port, 'package': package, 'model': shell('getprop ro.product.model').strip(), 'android': shell('getprop ro.build.version.release').strip(), 'size': shell('wm size').strip(),
        'density': shell('wm density').strip(), 'refresh': re.findall(r'renderFrameRate (\d+\.?\d*)', device('shell', 'dumpsys display'))[:1]}
print(json.dumps(info, ensure_ascii=False))
device('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'); device('shell', 'wm', 'dismiss-keyguard')
for i in range(runs_n):
    for name, mode in (('pinned', 1), ('scrolled-away', 2)):
        r = {'platform': 'android-device', 'mode': name, 'index': i + 1, 'device': info, 'before': snapshot(), 't0': time.strftime('%H:%M:%S')}
        device('shell', 'am', 'force-stop', package); device('logcat', '-c')
        device('shell', 'am', 'start', '-W', '-n', package + '/com.coach.android.MainActivity', '--ei', 'autoplay', str(mode), '--el', 'runId', str(time.time_ns()))
        logs = ''
        for _ in range(900):
            logs = device('logcat', '-d', '-s', 'CoachRender:I', 'AndroidRuntime:E', '*:S')
            if 'SUMMARY mode=' in logs or 'FATAL EXCEPTION' in logs: break
            time.sleep(.5)
        device('shell', 'input', 'keyevent', 'KEYCODE_HOME'); time.sleep(.8)
        logs = device('logcat', '-d', '-s', 'CoachRender:I', 'AndroidRuntime:E', '*:S')
        summary = [l.split('CoachRender: ', 1)[1] for l in logs.splitlines() if 'SUMMARY mode=' in l]
        jank = [l.split('CoachRender: ', 1)[1] for l in logs.splitlines() if 'JANK frames' in l]
        m = re.search(r'JANK frames=(\d+) jank=(\d+) percent=([\d.]+)', jank[-1]) if jank else None
        r.update(after=snapshot(), summary=summary[-1] if summary else 'MISSING', jankstats=jank[-1] if jank else 'MISSING',
                 frames=int(m.group(1)) if m else None, jank_frames=int(m.group(2)) if m else None, jank_percent=float(m.group(3)) if m else None,
                 enough_frames=bool(m and int(m.group(1)) >= min_frames), fatal='FATAL EXCEPTION' in logs)
        records.append(r); save()
        print(json.dumps({k: r[k] for k in ('mode', 'index', 'frames', 'jank_frames', 'jank_percent', 'enough_frames', 'fatal')}), flush=True)
print(json.dumps({'runs': len(records), 'out': str(out)}))

"""Foreground, isolated emulator replay; owns and cleans up only the emulator it starts."""
import os, subprocess, time, signal, json, xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

repo = Path(__file__).resolve().parents[2]
root = Path(os.environ.get('COACH_RENDER_CORPUS', str(Path.home() / 'Coach/corpus/render')))
out = root / 'android'; out.mkdir(exist_ok=True)
sdk = Path(os.environ['ANDROID_HOME'])
adb = str(sdk / 'platform-tools/adb'); serial = 'emulator-5572'; package = 'com.coach.android'

def run(args, **kw):
    return subprocess.run([str(a) for a in args], check=True, timeout=kw.pop('timeout',30), **kw)
def device(*args, **kw): return run([adb,'-s',serial,*args],**kw)
def shell(*args, **kw): return device('shell',*args,**kw)
def text(*args): return device(*args,capture_output=True,text=True).stdout

def snapshot():
    xml = text('exec-out','run-as',package,'cat','shared_prefs/render-replay.xml')
    return json.loads(ET.fromstring(xml).find("string[@name='state']").text)
def launch(page=None,extra=()):
    args=['am','start','-W','-n',package+'/.MainActivity',*extra]
    if page is not None: args += ['--ei','chinesePage',str(page)]
    shell(*args,capture_output=True)
def home(): shell('input','keyevent','KEYCODE_HOME'); time.sleep(.5)
def capture(name):
    with (out/name).open('wb') as f: device('exec-out','screencap','-p',stdout=f)

def button(label):
    shell('uiautomator','dump','/sdcard/coach-render-ui.xml',capture_output=True)
    import re
    xml=ET.fromstring(text('exec-out','cat','/sdcard/coach-render-ui.xml'))
    for n in xml.iter('node'):
        if n.get('text')==label:
            a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
            shell('input','tap',str((a+c)//2),str((b+d)//2));return
    raise RuntimeError('Button missing: '+label)

existing=subprocess.run([adb,'-s',serial,'get-state'],capture_output=True,timeout=5)
if existing.returncode==0: raise RuntimeError(serial+' already in use')
log=(out/'emulator.log').open('w')
proc=subprocess.Popen([str(repo/'tools/dev/heavy'),str(sdk/'emulator/emulator'),'-avd','sable-api35','-port','5572','-no-snapshot-save','-no-boot-anim','-no-audio','-no-window','-gpu','host','-cores','2'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
record=None
try:
    for _ in range(120):
        if proc.poll() is not None: raise RuntimeError('Emulator exited')
        boot=subprocess.run([adb,'-s',serial,'shell','getprop','sys.boot_completed'],capture_output=True,text=True,timeout=5)
        if boot.stdout.strip()=='1':break
        time.sleep(1)
    else:raise RuntimeError('Boot timeout')
    device('install','-r',repo/'androidApp/build/outputs/apk/debug/androidApp-debug.apk',capture_output=True,timeout=60)
    device('push',root/'replay.json','/data/local/tmp/coach-render-replay.json',capture_output=True)
    shell('run-as',package,'mkdir','-p','files','shared_prefs')
    shell('run-as',package,'cp','/data/local/tmp/coach-render-replay.json','files/replay.json')
    state={'draft':'draft x^2','selection':9,'option':'B','cursor':0,'running':True,'scrollIndex':0,'scrollOffset':0,'message':0}
    seed=out/'seed.xml';seed.write_text('<?xml version="1.0" encoding="utf-8"?><map><string name="state">'+escape(json.dumps(state))+'</string></map>')
    device('push',seed,'/data/local/tmp/coach-render-seed.xml',capture_output=True)
    shell('run-as',package,'cp','/data/local/tmp/coach-render-seed.xml','shared_prefs/render-replay.xml')
    shell('wm','density','420')
    shell('wm','size','1248x1972')
    device('logcat','-c')
    launch()
    for _ in range(60):
        logs=text('logcat','-d','-s','CoachRender:I','AndroidRuntime:E','*:S')
        if 'FATAL EXCEPTION' in logs: raise RuntimeError(logs)
        if 'READY messages=20' in logs: break
        time.sleep(.5)
    else: raise RuntimeError('UI readiness timeout: '+logs)
    time.sleep(1.5)
    capture('outer-stream.png');home();before=snapshot()
    assert before['draft']==state['draft'] and before['option']=='B' and before['cursor']>0,before
    # Record a real running screen through the resize sequence; collect before exit.
    record=subprocess.Popen([adb,'-s',serial,'shell','screenrecord','--size','720x1280','--time-limit','18','/sdcard/coach-render.mp4'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    results=[]
    for name,size in [('outer','1248x1972'),('inner','1848x2448'),('fold-landscape','2448x1848'),('pad-landscape','2520x1680')]:
        shell('wm','size',size);launch();time.sleep(1.5);capture(name+'.png');home();s=snapshot()
        assert s['draft']==before['draft'] and s['option']==before['option'] and s['cursor']>=before['cursor'],s
        results.append({'shape':name,'size':size,'state':s});before=s
    # Real background kill: verify process is gone, then reload the same private state.
    home();before=snapshot()
    shell('am','kill',package);time.sleep(.5)
    pid=text('shell','pidof',package) if subprocess.run([adb,'-s',serial,'shell','pidof',package],capture_output=True).returncode==0 else ''
    assert not pid.strip(),'am kill did not reclaim process'
    launch();time.sleep(.7);home();after=snapshot()
    for k in ['draft','option','message']: assert before[k]==after[k],(k,before,after)
    assert after['cursor']>=before['cursor']
    capture('process-restored.png')
    if record is not None:
        record.communicate(timeout=25);assert record.returncode==0
        device('pull','/sdcard/coach-render.mp4',out/'resize-replay.mp4',capture_output=True)
        record=None
    # All fourteen CJK formulas, two per actual Android screenshot.
    for page in range(7):
        home();shell('am','force-stop',package);launch(page);time.sleep(.8);capture(f'chinese-{page+1}.png')
    home()
    # Full replay of the 20 staged answers: pinned to bottom, then scrolled away from the tail (wm size is still Pad landscape).
    shell('wm','size','1248x1972')
    runs={}
    for name,mode in [('pinned',1),('scrolled-away',2)]:
        shell('am','force-stop',package);device('logcat','-c');launch(extra=['--ei','autoplay',str(mode)])
        for _ in range(400):
            logs=text('logcat','-d','-s','CoachRender:I','AndroidRuntime:E','*:S')
            if 'FATAL EXCEPTION' in logs: raise RuntimeError(logs)
            if 'SUMMARY mode=' in logs: break
            time.sleep(.5)
        else: raise RuntimeError('autoplay timeout: '+logs[-2000:])
        capture(f'replay-{name}.png');home();time.sleep(.5)
        logs=text('logcat','-d','-s','CoachRender:I','AndroidRuntime:E','*:S')
        runs[name]={'summary':[l.split('CoachRender: ',1)[1] for l in logs.splitlines() if 'SUMMARY mode=' in l][-1],
                    'jank':[l.split('CoachRender: ',1)[1] for l in logs.splitlines() if 'JANK frames' in l][-1]}
        (out/f'runtime-{name}.log').write_text(logs)
    # Inline baseline sheet: digits in text and formula must share a baseline.
    shell('am','force-stop',package);launch(extra=['--ez','baseline','true']);time.sleep(1.5);capture('baseline.png');home()
    shell('wm','size','reset');shell('wm','density','reset')
    logs=text('logcat','-d','-s','CoachRender:I','AndroidRuntime:E','*:S')
    (out/'runtime.log').write_text(logs)
    assert 'FATAL EXCEPTION' not in logs,logs
    summary={'forms':results,'beforeKill':before,'afterKill':after,'processReclaimed':True,'chineseScreenshots':7,'chineseFormulas':14,'replayRuns':runs,'ai_calls':0}
    (out/'summary.json').write_text(json.dumps(summary,indent=2))
    print(json.dumps({'forms':len(results),'processReclaimed':True,'chineseFormulas':14,'statePreserved':True}))
finally:
    if proc.poll() is None:
        try: (out/'runtime.log').write_text(text('logcat','-d','-s','CoachRender:I','AndroidRuntime:E','*:S'))
        except Exception: pass
    if record is not None:
        try:record.communicate(timeout=25)
        except subprocess.TimeoutExpired:record.terminate();record.wait(timeout=5)
    if proc.poll() is None:
        subprocess.run([adb,'-s',serial,'emu','kill'],capture_output=True,timeout=10)
        try:proc.wait(timeout=20)
        except subprocess.TimeoutExpired:os.killpg(proc.pid,signal.SIGTERM);proc.wait(timeout=10)
    log.close()

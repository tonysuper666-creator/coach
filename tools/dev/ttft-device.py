"""Real-device first-token time (TON-1100): restarts Coach on the phone, sends one ask per run through the real UI and reads the CoachTtft log line.

The phone must be paired with a NON-production test server (own port and data directory) whose bridge is the real one. Env: COACH_DEVICE=host:port, COACH_KP=<kp id>, PERF_PACKAGE (default com.coach.android).
Results append to ~/Coach/corpus/render/ttft-1100.jsonl; rerunning resumes after the lines already there.
"""
import json, os, re, subprocess, sys, time
A = os.environ.get('ADB', '/opt/homebrew/share/android-commandlinetools/platform-tools/adb'); S = os.environ['COACH_DEVICE']; P = os.environ.get('PERF_PACKAGE', 'com.coach.android')
KP = os.environ['COACH_KP']  # a knowledge point id of the test server's course
out = os.path.expanduser('~/Coach/corpus/render/ttft-1100.jsonl')
Q = ['用一句话说说为什么医疗市场有信息不对称', '什么是 adverse selection？举个保险的例子', 'moral hazard 和 adverse selection 的区别是什么', 'Arrow 1963 的核心论点是什么', '为什么说医疗有 uncertainty', '竞争市场有效率需要哪些条件', 'normative 和 positive 的区别，各举一例', '医疗支出占 GDP 的比例为什么上升', '外部性在医疗里有什么例子', '用两句话解释道德风险', '保险公司为什么担心逆向选择', '为什么医疗需求缺乏弹性', '第三方支付会怎样影响价格敏感度', 'deductible 是什么', '解释一下 experience good', '为什么病人比医生更不了解治疗', '什么是 supplier-induced demand', '医疗市场里 agency 问题指什么', '为什么政府要介入医疗保险', '风险厌恶如何推动人们买保险', '用一句话概括这一节的重点', '这一节最容易混淆的地方是什么']
def adb(*a, t=30): return subprocess.run([A, '-s', S, *a], capture_output=True, text=True, timeout=t).stdout
def send_xy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
    x = adb('shell', 'cat', '/sdcard/u.xml')
    for n in re.findall(r'<node [^>]*>', x):
        if 'text="发送"' in n:
            b = list(map(int, re.findall(r'\d+', re.search(r'bounds="([^"]*)"', n).group(1)))); return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    return None
done = sum(1 for _ in open(out)) if os.path.exists(out) else 0
for i, q in enumerate(Q):
    if i < done: continue
    adb('shell', 'am', 'force-stop', P); adb('logcat', '-c')
    adb('shell', 'am', 'start', '-n', P + '/com.coach.android.CoachActivity', '--es', 'openKp', KP, '--es', 'draft', q)
    for _ in range(30):
        if 'connected=true' in adb('logcat', '-d', '-s', 'CoachConn:I'): break
        time.sleep(1)
    time.sleep(2)
    xy = send_xy()
    if not xy: print(i, 'no send button'); continue
    t0 = time.time(); adb('shell', 'input', 'tap', str(xy[0]), str(xy[1]))
    line = None
    for _ in range(60):
        l = [x for x in adb('logcat', '-d', '-s', 'CoachTtft:I').splitlines() if 'CoachTtft' in x]
        if l: line = l[-1]; break
        time.sleep(.5)
    rec = {'i': i + 1, 'q': q, 'wall_s': round(time.time() - t0, 2), 'sample': json.loads(line.split('CoachTtft: ', 1)[1]) if line else None}
    open(out, 'a').write(json.dumps(rec, ensure_ascii=False) + '\n'); print(i + 1, rec['sample'] and (rec['sample']['kind'], rec['sample']['ms'], rec['sample']['model'], rec['sample']['fallback']), flush=True)
    time.sleep(12)  # let the answer finish before the next restart

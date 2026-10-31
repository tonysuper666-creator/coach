#!/usr/bin/env python3
"""Run via tools/dev/heavy after assembling both APKs and :server:app:installDist.
Owns two disposable emulator sessions and an isolated synthetic server. No AI calls.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import re
from local_config import value

ROOT=Path(__file__).resolve().parents[2]
SDK=Path(value('coach.sdkDir')).expanduser()
ADB=SDK/'platform-tools/adb'
OUT=Path(os.environ['COACH_OFFLINE_EVIDENCE']).resolve()
if any((p/'.git').exists() for p in [OUT,*OUT.parents]):
    raise RuntimeError('Evidence must be outside the repository')
OUT.mkdir(parents=True,exist_ok=True)
DATA=OUT/'synthetic-server'
if (DATA/'coach.sqlite').exists():
    raise RuntimeError('Use a fresh evidence directory for an independent run')
SERIALS=['emulator-5590','emulator-5592']
COURSE=value('coach.course')
HOST=value('coach.tailHost')
TOKEN=secrets.token_urlsafe(32)
processes=[]
logs=[]
server=None


def run(args,timeout=60,binary=False):
    return subprocess.check_output(list(map(str,args)),timeout=timeout,text=not binary,stderr=subprocess.STDOUT)


def wait_until(check,timeout):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        if check(): return
        time.sleep(.5)
    raise TimeoutError('Isolated offline acceptance timed out')


with socket.socket() as sock:
    sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
env=dict(os.environ,COACH_DATA_DIR=str(DATA),COACH_TOKEN_FILE=str(DATA/'desktop-token'),
         COACH_PORT=str(port),COACH_TAIL_HOST=HOST)


def start_server():
    global server
    log=(OUT/'server.log').open('a');logs.append(log)
    server=subprocess.Popen([ROOT/'server/app/build/install/app/bin/app'],env=env,stdout=log,stderr=subprocess.STDOUT)
    processes.append(server)
    def ready():
        if server.poll() is not None: raise RuntimeError('Isolated server exited')
        try: urllib.request.urlopen(f'http://127.0.0.1:{port}/v1/hello',timeout=1)
        except urllib.error.HTTPError as err: return err.code==401
        except OSError: return False
    wait_until(ready,30)


def stop_server():
    server.terminate();server.wait(timeout=20)


def instrument(serial,phase):
    run([ADB,'-s',serial,'shell','am','force-stop','com.coach.android'])
    report=run([ADB,'-s',serial,'shell','am','instrument','-w','-e','class',
        'com.coach.android.OfflinePackSmoke#exercisePhase','-e','phase',phase,
        '-e','coachPort',port,'-e','coachDevice','offline-'+serial,'-e','coachToken',TOKEN,
        'com.coach.android.test/androidx.test.runner.AndroidJUnitRunner'],timeout=120)
    (OUT/f'{serial}-{phase}.txt').write_text(report)
    if 'OK (1 test)' not in report: raise RuntimeError('Instrumentation failed: '+serial+' '+phase+'\n'+report)
    print(serial,phase,'OK',flush=True)


try:
    start_server();stop_server()
    # Seed only our stopped, temporary server database. All content is synthetic.
    with sqlite3.connect(DATA/'coach.sqlite') as db:
        for serial in SERIALS:
            db.execute('INSERT INTO devices(device_id,token_sha256,created_at) VALUES(?,?,?)',
                ('offline-'+serial,hashlib.sha256(TOKEN.encode()).hexdigest(),int(time.time()*1000)))
        now=int(time.time()*1000)
        for seq,kind,payload in [(1,'outline_revised',{'ops':[{'kpId':'offline-learning','title':'Synthetic learning'}, {'kpId':'offline-review','title':'Synthetic review'}]}),
                                 (2,'kp_opened',{'kpId':'offline-learning','title':'Synthetic learning'})]:
            event=dict(seq=seq,id=f'synthetic-{seq}',courseId=COURSE,type=kind,schema=1,recordedAt=now,occurredAt=now,
                       deviceId='server',actor='SYSTEM',idemKey=f'synthetic-{seq}',payload=payload)
            db.execute('INSERT INTO events(seq,id,course_id,type,schema,recorded_at,occurred_at,device_id,actor,effect_index,idem_key,payload,event_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (seq,event['id'],COURSE,kind,1,now,now,'server','SYSTEM',0,event['idemKey'],json.dumps(payload),json.dumps(event)))
        options=[dict(optionId=f'o{i}',text=f'Synthetic option {i}') for i in range(1,5)]
        for i in range(30):
            stem=f'Synthetic offline exercise {i}'
            texts=[stem.lower()]+sorted(x['text'].lower() for x in options)
            fp=hashlib.sha256(''.join(f'{len(t)}:{t}' for t in texts).encode()).hexdigest()
            db.execute("INSERT INTO questions VALUES(?,?,'SINGLE_CHOICE',?,?,'COACH',?,'VERIFIED',NULL)",(f'q{i}',COURSE,stem,json.dumps(options),fp))
            db.execute("INSERT INTO question_kp VALUES(?,'offline-review',1)",(f'q{i}',))
            db.execute('INSERT INTO answer_keys(question_id,key_json) VALUES(?,?)',(f'q{i}',json.dumps({'correctOptionId':f'o{i%4+1}'})))
    start_server()
    devices=run([ADB,'devices'])
    for serial in SERIALS:
        if serial in devices: raise RuntimeError('Emulator already owned: '+serial)
        log=(OUT/f'{serial}.log').open('w');logs.append(log)
        emulator=subprocess.Popen([SDK/'emulator/emulator','-avd','sable-api35','-port',serial.split('-')[1],
            '-read-only','-no-snapshot-save','-no-boot-anim','-no-audio','-no-window','-gpu','host','-cores','2'],
            env=dict(os.environ,ANDROID_HOME=str(SDK),ANDROID_SDK_ROOT=str(SDK)),stdout=log,stderr=subprocess.STDOUT)
        processes.append(emulator)
        def booted():
            if emulator.poll() is not None: raise RuntimeError('Emulator exited')
            try: return run([ADB,'-s',serial,'shell','getprop','sys.boot_completed'],timeout=5).strip()=='1'
            except subprocess.CalledProcessError: return False
        wait_until(booted,180)
        # Let Android's default network settle before opening authenticated connections.
        time.sleep(8)
        for apk in ['androidApp/build/outputs/apk/debug/androidApp-debug.apk','androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk']:
            run([ADB,'-s',serial,'install','-r',ROOT/apk])
        run([ADB,'-s',serial,'logcat','-c'])
        instrument(serial,'prepare')
    stop_server()
    instrument(SERIALS[0],'ui')
    (OUT/'offline-ready.png').write_bytes(run([ADB,'-s',SERIALS[0],'exec-out','run-as','com.coach.android','cat','cache/offline-ready.png'],binary=True))
    for serial in SERIALS:
        instrument(serial,'offline')
    # Show the real cached app with Mac unavailable. No production preferences or database are used.
    serial=SERIALS[0]
    run([ADB,'-s',serial,'shell','am','start','-n','com.coach.android/.CoachActivity','--es','coachUrl',f'http://{HOST}:{port}'])
    time.sleep(3)
    def tap(text):
        run([ADB,'-s',serial,'shell','uiautomator','dump','/sdcard/offline-ui.xml'])
        tree=ET.fromstring(run([ADB,'-s',serial,'shell','cat','/sdcard/offline-ui.xml']))
        node=next(n for n in tree.iter('node') if n.attrib.get('text')==text)
        x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
        run([ADB,'-s',serial,'shell','input','tap',(x1+x2)//2,(y1+y2)//2])
        time.sleep(.5)
    tap('到期复习')
    tap('查看上组结果')
    (OUT/'offline-result.png').write_bytes(run([ADB,'-s',serial,'exec-out','screencap','-p'],binary=True))
    run([ADB,'-s',serial,'shell','am','force-stop','com.coach.android'])
    start_server()
    for serial in SERIALS:
        instrument(serial,'reconcile')
    ids=[]
    evidence=[]
    for serial in SERIALS:
        report=run([ADB,'-s',serial,'logcat','-d','-s','CoachOffline:I','*:S'])
        (OUT/f'{serial}-metrics.txt').write_text(report)
        ids.append(set(re.search(r'IDS=([^\r\n]+)',report).group(1).split(',')))
        evidence.extend(line for line in report.splitlines() if 'OFFLINE ' in line or 'RECONCILE ' in line or 'UI tapToCardMs=' in line)
    assert len(ids[0])==10 and len(ids[1])==10 and not ids[0]&ids[1]
    with sqlite3.connect(DATA/'coach.sqlite') as db:
        answers=db.execute("SELECT count(*),count(DISTINCT json_extract(payload,'$.attemptId')) FROM events WHERE type='answer_submitted'").fetchone()
        assert answers==(20,20),answers
        assert db.execute('SELECT coalesce(sum(physical_calls),0) FROM ai_calls').fetchone()[0]==0
    evidence.append('TWO_EMULATORS downloaded=10+10 overlap=0 answers=20 uniqueFirstAnswers=20 lost=0 duplicates=0 ai_calls=0')
    (OUT/'evidence.txt').write_text('\n'.join(evidence)+'\n')
    print('\n'.join(evidence),flush=True)
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=20)
            except subprocess.TimeoutExpired: process.kill();process.wait()
    for log in logs: log.close()

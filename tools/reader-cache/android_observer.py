"""Independently selected Android diagnostic observations in host time only."""
import json
import subprocess
import time

MODES=('both','stage','meminfo','none')


def observe_android(adb,package,run_id,mode,run=None):
    if mode not in MODES or mode=='none':
        raise ValueError('an enabled Android observer mode is required')
    run=subprocess.run if run is None else run
    row=dict(hostMonotonicNs=time.monotonic_ns(),stage='test_process_running',observerMode=mode,operations={})

    def invoke(kind,command):
        span=dict(invokedNs=time.monotonic_ns())
        row['operations'][kind]=span
        try:
            result=run(adb+command,capture_output=True,text=True)
            span['exit']=result.returncode
            return result
        except Exception as error:
            span['error']=type(error).__name__
            return None
        finally:
            span['returnedNs']=time.monotonic_ns()

    if mode in ('both','stage'):
        result=invoke('stage',['exec-out','run-as',package,'cat','cache/reader-stage.json'])
        row['stageRunMatched']=False
        if result is None: return row
        if result.returncode==0:
            try:
                stage=json.loads(result.stdout)
                if stage.get('runId')==run_id:
                    row['stage']=stage['stage'];row['stageRunMatched']=True
            except (ValueError,KeyError,AttributeError):
                pass
    if mode in ('both','meminfo'):
        result=invoke('meminfo',['shell','dumpsys','meminfo','-s',package])
        if result is not None: row.update(meminfo=result.stdout,exit=result.returncode)
    return row

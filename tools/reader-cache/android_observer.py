"""Independently selected Android diagnostic observations in host time only."""
import json
import hashlib
import subprocess
import time

MODES=('both','stage','meminfo','none')


def observe_android(adb,package,run_id,mode,run=None,expected_suite=None):
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
        row['stageReadStatus']='command_error' if result is None else 'command_failed'
        if result is None: return row
        if result.returncode==0:
            row['stageReadStatus']='malformed'
            try:
                stage=json.loads(result.stdout)
                if isinstance(stage.get('runId'),str):
                    row['stageObservedRunHash']=hashlib.sha256(stage['runId'].encode()).hexdigest()
                if stage.get('runId')!=run_id:
                    row['stageReadStatus']='stale_run'
                elif not isinstance(stage.get('stage'),str) or not stage['stage']:
                    row['stageReadStatus']='invalid_phase'
                elif expected_suite is not None and stage.get('suite')!=expected_suite:
                    row['stageReadStatus']='wrong_suite'
                elif expected_suite is not None and (type(stage.get('pid')) is not int or stage['pid']<=0):
                    row['stageReadStatus']='invalid_pid'
                else:
                    row.update(stage=stage['stage'],stageRunMatched=True,stageReadStatus='matched')
                    if expected_suite is not None: row.update(stageSuite=stage['suite'],stagePid=stage['pid'])
            except (ValueError,AttributeError):
                pass
    if mode in ('both','meminfo'):
        result=invoke('meminfo',['shell','dumpsys','meminfo','-s',package])
        if result is not None: row.update(meminfo=result.stdout,exit=result.returncode)
    return row


def audit_transport_stage(rows,requested,suite,pid):
    statuses={};matched=0;active=0
    for row in rows:
        if 'stage' not in row.get('operations',{}): continue
        status=row.get('stageReadStatus','unclassified')
        statuses[status]=statuses.get(status,0)+1
        valid=row.get('stageRunMatched') is True and row.get('stageSuite')==suite and row.get('stagePid')==pid
        matched+=int(valid)
        active+=int(valid and row.get('stage')=='transport_running')
    return dict(requested=requested,verified=requested and active>0,matchedRows=matched,activeRows=active,
                statuses=statuses,scope='Stage identity only; not per-request timing, display, or memory-budget evidence.')

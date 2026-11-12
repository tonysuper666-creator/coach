from copy import deepcopy
from pathlib import Path
import sqlite3
import unittest

from read_syscall_analysis import summarize_syscalls


class ReadSyscallsTest(unittest.TestCase):
    def data(self):
        connection=sqlite3.connect(':memory:');connection.row_factory=sqlite3.Row
        connection.executescript('''
          CREATE TABLE slice(id,name,ts,dur,track_id,arg_set_id);
          CREATE TABLE thread_track(id,utid); CREATE TABLE thread(utid,tid,upid); CREATE TABLE process(upid,pid);
          CREATE TABLE args(arg_set_id,key,flat_key,value_type,int_value,string_value,real_value,display_value);
          INSERT INTO slice VALUES(5,'ReaderSocket/first-read/unit',100,100,7,0);
          INSERT INTO slice VALUES(6,'sys_ppoll',110,80,7,6);
          INSERT INTO slice VALUES(7,'sys_recvfrom',190,8,7,7);
          INSERT INTO slice VALUES(8,'sys_write',199,5,7,8);
          INSERT INTO thread_track VALUES(7,2); INSERT INTO thread VALUES(2,11,3); INSERT INTO process VALUES(3,321);
          INSERT INTO args VALUES(6,'ret','ret','int',1,NULL,NULL,'1');
          INSERT INTO args VALUES(6,'args[0]','args','uint',444,NULL,NULL,'444');
          INSERT INTO args VALUES(7,'ret','ret','int',1,NULL,NULL,'1');
          INSERT INTO args VALUES(7,'args[0]','args','uint',55,NULL,NULL,'55');
        ''')
        try:
            def query(name):
                sql=Path(__file__).with_name(name).read_text().replace('{{PID}}','321')
                return [dict(row) for row in connection.execute(sql)]
            calls=query('read_syscalls.sql');args=query('read_syscall_args.sql')
        finally:connection.close()
        read=dict(probeId='unit',marker=dict(slice_id=5,label='ReaderSocket/first-read/unit',ts=100,dur=100,utid=2,tid=11,pid=321),
            errors=[],stateDurationNs={'S':80,'Running':20},segments=[dict(ts=110,end=190,state='S'),dict(ts=190,end=200,state='Running')])
        return dict(complete=True,samples=[read]),calls,args

    def test_sql_exports_preserve_raw_args_and_overlay_sleep_without_fd_inference(self):
        scheduling,calls,args=self.data();report=summarize_syscalls(scheduling,calls,[])
        self.assertTrue(report['complete'])
        self.assertEqual(80,report['samples'][0]['calls'][0]['sleepSDNs'])
        self.assertEqual(0,report['samples'][0]['unattributedSleepSDNs'])
        self.assertEqual('sys_write',report['samples'][0]['boundaryCalls'][0]['name'])
        self.assertTrue(any(row['key']=='args[0]' and row['int_value']==444 for row in args))
        self.assertFalse(report['socketFdVerified']);self.assertFalse(report['kernelReceiveObserved'])

    def test_missing_exit_wrong_identity_or_duplicate_calls_are_rejected(self):
        scheduling,calls,_=self.data()
        for changed in ([],[dict(calls[0],ret=None)]+calls[1:],[dict(calls[0],tid=99)]+calls[1:],
                        calls+calls[:1],calls+[dict(calls[0],read_slice_id=999)],
                        [dict(calls[0],syscall_slice_id=None)]+calls[1:]):
            self.assertFalse(summarize_syscalls(scheduling,changed,[])['complete'])

    def test_unattributed_sleep_is_reported_without_claiming_complete_attribution(self):
        scheduling,calls,_=self.data();scheduling=deepcopy(scheduling)
        scheduling['samples'][0]['segments'][0]['ts']=100;scheduling['samples'][0]['stateDurationNs']['S']=90
        report=summarize_syscalls(scheduling,calls,[])
        self.assertTrue(report['complete']);self.assertFalse(report['samples'][0]['sleepAttributionComplete'])
        self.assertEqual(10,report['samples'][0]['unattributedSleepSDNs'])

    def test_unfinished_calls_or_bad_capture_quality_are_not_verified(self):
        scheduling,calls,_=self.data()
        self.assertFalse(summarize_syscalls(scheduling,[dict(calls[0],dur=-1)]+calls[1:],[])['complete'])
        for severity in ('error','data_loss'):
            self.assertFalse(summarize_syscalls(scheduling,calls,[dict(severity=severity,value=1)])['complete'])

    def test_signal_restoration_stays_unpaired_and_does_not_clear_strict_failure(self):
        scheduling,calls,_=self.data()
        signal=dict(calls[0],syscall_slice_id=9,name='sys_rt_sigreturn',ts=198,dur=0,ret=None)
        report=summarize_syscalls(scheduling,calls+[signal],[])
        self.assertFalse(report['complete'])
        row=report['samples'][0]
        self.assertEqual(0,row['unattributedSleepSDNs'])
        self.assertFalse(row['sleepAttributionComplete'])
        self.assertIn('unfinished_or_cross_boundary_syscall',row['errors'])
        self.assertEqual([dict(syscallSliceId=9,kind='unpaired_signal_restoration',ordinaryReturnVerified=False)],row['boundaryNotes'])
        self.assertIn(signal,row['boundaryCalls'])


if __name__=='__main__': unittest.main()

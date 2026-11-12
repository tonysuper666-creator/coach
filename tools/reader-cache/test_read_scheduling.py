from pathlib import Path
import sqlite3
import unittest

from read_scheduling import summarize_scheduling


class ReadSchedulingTest(unittest.TestCase):
    def data(self):
        connection=sqlite3.connect(':memory:');connection.row_factory=sqlite3.Row
        connection.executescript('''
            CREATE TABLE slice(id,name,ts,dur,track_id);
            CREATE TABLE thread_track(id,utid);
            CREATE TABLE thread(utid,tid,upid);
            CREATE TABLE process(upid,pid);
            CREATE TABLE thread_state(id,utid,ts,dur,state,io_wait,blocked_function,waker_utid);
            CREATE TABLE stats(name,severity,source,value);
            INSERT INTO slice VALUES(5,'ReaderSocket/first-read/unit',100,100,7);
            INSERT INTO thread_track VALUES(7,2);
            INSERT INTO thread VALUES(2,11,3);
            INSERT INTO process VALUES(3,321);
            INSERT INTO thread_state VALUES(1,2,90,60,'Running',NULL,NULL,NULL);
            INSERT INTO thread_state VALUES(2,2,150,20,'R',NULL,NULL,NULL);
            INSERT INTO thread_state VALUES(3,2,170,50,'S',NULL,NULL,NULL);
            INSERT INTO stats VALUES('unit_counter','info','trace',10);
        ''')
        try:
            def query(name):
                sql=Path(__file__).with_name(name).read_text().replace('{{PID}}','321')
                return [dict(row) for row in connection.execute(sql)]
            slices=query('read_slices.sql');states=query('read_thread_states.sql');quality=query('read_trace_quality.sql')
        finally: connection.close()
        association=dict(complete=True,errors=[],samples=[dict(probeId='unit',client=dict(readTraceLabel='ReaderSocket/first-read/unit',
            readTid=11,readRequestedNs=1000,firstByteNs=1100))])
        return association,slices,states,quality

    def test_sql_exports_clip_complete_states_in_the_trace_clock(self):
        association,slices,states,quality=self.data()
        report=summarize_scheduling(association,321,slices,states,quality)
        self.assertTrue(report['complete'])
        self.assertEqual(dict(Running=50,R=20,S=30),report['samples'][0]['stateDurationNs'])
        self.assertEqual(20,report['samples'][0]['runnableNs'])
        self.assertFalse(report['kernelReceiveObserved']);self.assertFalse(report['networkCauseVerified'])

    def test_missing_ambiguous_or_wrong_thread_markers_are_rejected(self):
        association,slices,states,quality=self.data()
        for changed in ([],slices+slices,[dict(slices[0],tid=99)],[dict(slices[0],dur=-1)]):
            self.assertFalse(summarize_scheduling(association,321,changed,states,quality)['complete'])

    def test_state_gaps_overlaps_and_unfinished_states_are_rejected(self):
        association,slices,states,quality=self.data()
        for changed in (states[:-1],states+states[:1],[dict(states[0],dur=-1)]+states[1:]):
            self.assertFalse(summarize_scheduling(association,321,slices,changed,quality)['complete'])

    def test_quality_errors_and_data_loss_are_retained_and_fail_verification(self):
        association,slices,states,quality=self.data()
        for severity in ('error','data_loss'):
            rows=quality+[dict(name='unit_error',severity=severity,source='trace',value=1)]
            report=summarize_scheduling(association,321,slices,states,rows)
            self.assertFalse(report['complete']);self.assertEqual(rows,report['quality'])


if __name__=='__main__': unittest.main()

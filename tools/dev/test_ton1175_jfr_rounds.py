"""Round bounds and closed diagnostic output; fixtures are not idle proof."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class RoundsTest(unittest.TestCase):
    def test_round_boundaries(self):
        source = r'''
import java.time.*;
import java.util.*;
class RoundCases {
 static Ton1175Jfr.HealthEvent e(long ms,long thread,long bytes) {
   return new Ton1175Jfr.HealthEvent(Instant.EPOCH.plusMillis(ms),thread,bytes);
 }
 static void check(String expected,long window,Ton1175Jfr.HealthEvent... events) {
   var counts=new Ton1175Jfr.Counts();
   String actual=Ton1175Jfr.rounds(new ArrayList<>(List.of(events)),window*1000000000L,counts);
   if(!expected.equals(actual)) throw new AssertionError(expected+" != "+actual);
 }
 public static void main(String[] a) {
   check("NONE",30,e(0,1,204),e(250,1,204),e(10000,1,204));
   check("ROUND_READ_LIMIT",30,e(0,1,100),e(250,1,100),e(500,1,100),e(750,1,100),e(1000,1,100));
   check("ROUND_READ_LIMIT",30,e(0,1,10),e(1,1,10),e(2,1,10),e(3,1,10),e(4,1,10));
   check("ROUND_BYTES_LIMIT",30,e(0,1,600),e(1,1,425));
   check("ROUND_THREADS",30,e(0,1,100),e(1,2,100));
   check("ROUND_GAP",30,e(0,1,100),e(8999,1,100));
   check("ROUND_COUNT",1,e(0,1,100),e(10000,1,100),e(20000,1,100),e(30000,1,100));
   var c=new Ton1175Jfr.Counts();
   Ton1175Jfr.rounds(new ArrayList<>(List.of(e(0,1,204),e(250,1,204),e(10000,1,100))),30000000000L,c);
   if(c.healthRounds!=2 || c.maxReadsPerRound!=2 || c.maxBytesPerRound!=408 || c.minRoundGapMillis!=10000)
     throw new AssertionError();
 }
}'''
        inspector = Path(__file__).with_name('Ton1175Jfr.java')
        java_bin = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('/usr/bin')
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory) / 'RoundCases.java'
            fixture.write_text(source)
            subprocess.run([str(java_bin / 'javac'), '-d', directory, str(inspector), str(fixture)], check=True)
            subprocess.run([str(java_bin / 'java'), '-cp', directory, 'RoundCases'], check=True)

    def test_rejection_outputs_closed_counts_without_raw_exception(self):
        inspector = Path(__file__).with_name('Ton1175Jfr.java')
        java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else Path('/usr/bin/java')
        result = subprocess.run([str(java), str(inspector), '/private/secret.jfr',
                                 '2026-01-01T00:00:00Z', '2026-01-01T00:01:00Z', '123'],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        data = json.loads(result.stdout)
        self.assertEqual(set(data), {'pid', 'jvmStartEpochMillis', 'socketReads', 'attributedHealthReads',
                                    'clientReads', 'healthRounds', 'maxReadsPerRound', 'maxBytesPerRound',
                                    'minRoundGapMillis', 'rejectReason'})
        self.assertEqual(data.pop('rejectReason'), 'EVIDENCE_INVALID')
        self.assertTrue(all(type(value) is int for value in data.values()))
        self.assertNotIn('secret', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()

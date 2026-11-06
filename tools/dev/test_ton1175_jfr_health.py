"""Safety cases for the unwired JFR health-read classifier."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class HealthReadTest(unittest.TestCase):
    def test_health_and_fail_closed_cases(self):
        inspector = Path(__file__).with_name('Ton1175Jfr.java')
        # Invoke the real Java classifier. The fixture reproduces the observed
        # old-JVM frame; it is not an idle proof or a real-service exercise.
        source = r'''
import java.util.List;
class HealthClassifierCases {
    static void need(boolean value) { if (!value) throw new AssertionError(); }
    static boolean classify(String addr, int port, long bytes, boolean truncated, List<String> frames) {
        return Ton1175Jfr.healthRead(addr, port, bytes, truncated, frames, 33333);
    }
    public static void main(String[] args) {
        var health = List.of("java.net.HttpURLConnection#getResponseCode",
            "com.coach.server.ChildServices$supervise$1#invokeSuspend");
        need(classify("127.0.0.1", 33333, 204, false, health));
        need(!classify("127.0.0.1", 44444, 204, false, health));
        need(!classify("127.0.0.1", 33333, 204, true, health));
        need(!classify("127.0.0.1", 33333, 204, false, List.of(
            "com.coach.server.ChildServices$supervise$1#invokeSuspend",
            "com.coach.ai.BridgeClient#request")));
        need(!classify("127.0.0.1", 33333, 1025, false, health));
        need(!classify("127.0.0.2", 33333, 204, false, health));
        need(!classify("127.0.0.1", 33333, 204, false, List.of("com.coach.ai.BridgeClient#request")));
        need(!classify("127.0.0.1", 33333, 204, false, List.of(
            "com.coach.server.ChildServices$supervise$1#invokeSuspend", "io.ktor.server.routing.Route#handle")));
        need(Ton1175Jfr.healthReadLimit(22_000_000_000L) == 5);
        need(Ton1175Jfr.healthReadLimit(900_000_000_000L) == 92);
    }
}
'''
        with tempfile.TemporaryDirectory() as directory:
            case = Path(directory) / 'HealthClassifierCases.java'
            case.write_text(source)
            java_home = os.environ.get('JAVA_HOME')
            java_bin = Path(java_home) / 'bin' if java_home else Path('/usr/bin')
            subprocess.run([str(java_bin / 'javac'), '-d', directory, str(inspector), str(case)], check=True)
            subprocess.run([str(java_bin / 'java'), '-cp', directory, 'HealthClassifierCases'], check=True)


if __name__ == '__main__':
    unittest.main()

"""Safety cases for the JFR health-read classifier."""
import os
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


class HealthReadTest(unittest.TestCase):
    def test_real_jfr_health_and_client_on_same_bridge_port(self):
        inspector = Path(__file__).with_name('Ton1175Jfr.java')
        java_home = os.environ.get('JAVA_HOME')
        java_bin = Path(java_home) / 'bin' if java_home else Path('/usr/bin')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            main = root / 'MainKt.java'
            child = root / 'ChildServices.java'
            client = root / 'BridgeClient.java'
            child.write_text('''package com.coach.server;
import java.net.*;
public class ChildServices {
 public static class supervise$1 {
  public void invokeSuspend(int port) throws Exception {
   try(Socket s=new Socket("127.0.0.1",port)) { s.getInputStream().read(new byte[204]); }
  }
 }
}''')
            client.write_text('''package com.coach.ai;
import java.net.*;
public class BridgeClient {
 public static void request(int port) throws Exception {
  try(Socket s=new Socket("127.0.0.1",port)) { s.getInputStream().read(new byte[204]); }
 }
}''')
            main.write_text('''package com.coach.server;
import java.net.*;import java.nio.file.*;import java.time.*;import jdk.jfr.*;
public class MainKt {
 static void read(ServerSocket server,boolean health) throws Exception {
  Thread peer=new Thread(()->{ try(Socket s=server.accept()) {
   s.getOutputStream().write(new byte[204]);
  } catch(Exception e) { throw new RuntimeException(e); }});
  peer.start();
  if(health) new ChildServices.supervise$1().invokeSuspend(server.getLocalPort());
  else com.coach.ai.BridgeClient.request(server.getLocalPort());
  peer.join();
 }
 public static void main(String[] args) throws Exception {
  try(ServerSocket server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
      ServerSocket ingress=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
      Recording r=new Recording()) {
   r.enable("jdk.JVMInformation").withPeriod(Duration.ofMillis(20));
   r.enable("jdk.CPULoad").withPeriod(Duration.ofMillis(20));
   r.enable("jdk.ActiveSetting");r.enable("jdk.DataLoss");
   var socket=r.enable("jdk.SocketRead").withThreshold(Duration.ZERO);
   if(Boolean.getBoolean("noStack")) socket.withoutStackTrace(); else socket.withStackTrace();
   r.start();Thread.sleep(1500);Instant start=Instant.now();
   read(server,true);Instant healthEnd=Instant.now();
   read(ingress,false);Instant ingressEnd=Instant.now();
   read(server,false);Instant clientEnd=Instant.now();
   for(int i=0;i<5;i++) read(server,true);
   Instant overEnd=Instant.now();Thread.sleep(1500);r.stop();
   r.dump(Path.of(System.getenv("HEALTH_JFR_OUTPUT")));
   System.out.printf("{\\"pid\\":%d,\\"port\\":%d,\\"start\\":\\"%s\\",\\"healthEnd\\":\\"%s\\",\\"ingressEnd\\":\\"%s\\",\\"clientEnd\\":\\"%s\\",\\"overEnd\\":\\"%s\\"}%n",
    ProcessHandle.current().pid(),server.getLocalPort(),start,healthEnd,ingressEnd,clientEnd,overEnd);
  }
 }
}''')
            subprocess.run([str(java_bin / 'javac'), '-d', directory, str(main), str(child), str(client)], check=True)
            recording = root / 'events.jfr'
            result = subprocess.run([str(java_bin / 'java'), '-cp', directory, 'com.coach.server.MainKt'],
                                    capture_output=True, check=True, text=True,
                                    env=dict(os.environ, HEALTH_JFR_OUTPUT=str(recording)))
            proof = json.loads(result.stdout)

            def inspect(end, port=True):
                cmd = [str(java_bin / 'java'), str(inspector), str(recording), proof['start'], end, str(proof['pid'])]
                if port:
                    cmd.append(str(proof['port']))
                return subprocess.run(cmd, capture_output=True, text=True)

            health = inspect(proof['healthEnd'])
            self.assertEqual(health.returncode, 0, health.stderr)
            measured = json.loads(health.stdout)
            self.assertEqual((measured['socketReads'], measured['attributedHealthReads'], measured['clientReads']),
                             (1, 1, 0))
            ingress = inspect(proof['ingressEnd'])
            self.assertEqual(ingress.returncode, 0, ingress.stderr)
            self.assertEqual(json.loads(ingress.stdout)['clientReads'], 1)
            mixed = inspect(proof['clientEnd'])
            self.assertEqual(mixed.returncode, 0, mixed.stderr)
            self.assertEqual(json.loads(mixed.stdout)['clientReads'], 2)
            strict = inspect(proof['healthEnd'], port=False)
            self.assertEqual(json.loads(strict.stdout)['clientReads'], 1)
            self.assertNotEqual(inspect(proof['overEnd']).returncode, 0)
            no_stack = subprocess.run([str(java_bin / 'java'), '-DnoStack=true', '-cp', directory,
                                       'com.coach.server.MainKt'], capture_output=True, check=True, text=True,
                                      env=dict(os.environ, HEALTH_JFR_OUTPUT=str(recording)))
            proof = json.loads(no_stack.stdout)
            self.assertNotEqual(inspect(proof['healthEnd']).returncode, 0)

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

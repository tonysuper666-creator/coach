using System;
using System.IO;
using System.Threading;

// Separate test executable; these fault/barrier controls are absent from production CLI parsing.
public static class AuthTransactionHarness {
    public static int Main(string[] args) {
        return CoachAuthCommit.Run(args, delegate(string phase) {
            string parent = Path.GetDirectoryName(args[0]);
            if (File.Exists(Path.Combine(parent, "SYNTHETIC-pause-" + phase))) {
                File.WriteAllText(Path.Combine(parent, "SYNTHETIC-ready"), "READY");
                DateTime deadline = DateTime.UtcNow.AddSeconds(3);
                while (!File.Exists(Path.Combine(parent, "SYNTHETIC-release"))) {
                    if (DateTime.UtcNow >= deadline) throw new IOException();
                    Thread.Sleep(5);
                }
            }
            if (File.Exists(Path.Combine(parent, "SYNTHETIC-fail-" + phase))) throw new IOException();
        });
    }
}

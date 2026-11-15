using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;

class SyntheticBookCodex {
    const string Mode = "__SYNTHETIC_MODE__";
    const string Neighbor = @"__SYNTHETIC_NEIGHBOR__";
    const string FixtureRoot = @"__SYNTHETIC_ROOT__";
    static string Home { get { return Environment.GetEnvironmentVariable("CODEX_HOME"); } }
    static void Observe(string name, string value) { File.WriteAllText(Path.Combine(Home, name), value); }
    static string Quote(string value) { return "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "\\r").Replace("\n", "\\n") + "\""; }
    static void Event(string value) { Console.WriteLine(value); Console.Out.Flush(); }
    static int Main(string[] args) {
        if (args.Length == 1 && args[0] == "--synthetic-child") { Thread.Sleep(120000); return 0; }
        string phase = args.SequenceEqual(new[] { "login", "status" }) ? "login" : "exec";
        Observe("adapter-started", "SYNTHETIC_STARTED");
        if (Mode == "environment") Observe(phase + "-environment.json", "{\"has_RUST_LOG\": " + (Environment.GetEnvironmentVariable("RUST_LOG") == null ? "false" : "true") + "}");
        if (phase == "login") { Console.WriteLine("Logged in using " + (Mode == "apikey" ? "an API key" : "ChatGPT")); return 0; }
        if (Directory.EnumerateFileSystemEntries(Environment.CurrentDirectory).Any()) return 71;
        Observe("argv.json", "[" + string.Join(",", args.Select(Quote)) + "]");
        Observe("cwd", Environment.CurrentDirectory);
        string raw = Console.In.ReadToEnd();
        if (raw.Contains("Synthetic A") || raw.Contains("book") || raw.Contains(FixtureRoot) || !raw.Contains("Photosynthesis")) return 72;
        if (Mode == "model") { Console.Error.WriteLine("The model is not supported"); return 3; }
        if (new[] { "timeout", "cancel", "stubborn", "parentexit" }.Contains(Mode)) {
            var child = Process.Start(new ProcessStartInfo(Process.GetCurrentProcess().MainModule.FileName, "--synthetic-child") { UseShellExecute = false, CreateNoWindow = true });
            Observe("pids", Process.GetCurrentProcess().Id + "," + child.Id);
            if (Mode == "parentexit") { Thread.Sleep(500); Environment.Exit(0); }
            Thread.Sleep(120000);
        }
        string suffix = "";
        if (Neighbor.Length != 0) {
            try { File.ReadAllText(Neighbor); return 73; }
            catch (UnauthorizedAccessException) { suffix = " SYNTHETIC_NEIGHBOR_DENIED"; }
        }
        int output = Array.IndexOf(args, "-o");
        if (output < 0) return 74;
        File.WriteAllText(args[output + 1], "{\"answer\":\"Light supplies energy for making stored food." + suffix + "\",\"citations\":[0]}");
        if (Mode == "refresh") File.WriteAllText(Path.Combine(Home, "auth.json"), "{\"SYNTHETIC\":\"REFRESHED_NOT_A_CREDENTIAL\"}");
        if (Mode == "broken-auth") File.WriteAllText(Path.Combine(Home, "auth.json"), "{");
        if (Mode == "empty") return 0;
        if (Mode == "malformed") { Event("not a JSON event"); return 0; }
        Event("{\"type\":\"thread.started\"}");
        Event("{\"type\":\"turn.started\"}");
        Event("{\"type\":\"item.completed\",\"item\":{\"type\":\"reasoning\",\"text\":\"synthetic reasoning\"}}");
        if (Mode.StartsWith("tool-")) Event("{\"type\":\"item.started\",\"item\":{\"type\":" + Quote(Mode.Substring(5)) + "}}");
        Event("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"synthetic answer\"}}");
        if (Mode != "truncated") Event("{\"type\":\"turn.completed\"}");
        if (Mode == "future-event") Event("{\"type\":\"future.tool_started\"}");
        if (Mode == "late-tool") Event("{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\"}}");
        if (Mode == "stderr-collab") Console.Error.WriteLine("collab spawn failed: no rollout found");
        if (Mode == "stderr-router") Console.Error.WriteLine("codex_core::tools::router: tool invocation");
        if (Mode == "stderr-late-collab") Console.Error.WriteLine(new string('x', 32768) + "COLLAB spawn failed");
        if (Mode == "stderr-split-router") Console.Error.WriteLine(new string('x', 8180) + "codex_core::tools::router");
        if (Mode == "stderr-benign") Console.Error.WriteLine("synthetic harmless diagnostic");
        return 0;
    }
}

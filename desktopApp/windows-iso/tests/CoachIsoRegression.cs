// Compile separately with /define:COACH_ISO_TEST /main:CoachIsoRegression and CoachIso.cs.
// Never ship this assembly. The production helper has neither these hooks nor a bare mode.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using Microsoft.Win32;

public static class CoachIsoTestHooks {
    public static string Target;
    public static int Hits;
    public static bool Fail(string step) {
        if (Target != step) return false;
        Hits++;
        return true;
    }
}

public static class CoachIsoRegression {
    static string root, helper, system;
    static List<object> results = new List<object>();
    static void Check(bool ok, string why) { if (!ok) throw new Exception(why); }
    static string[] Args(string work, string command) {
        return new[] { "--allow-write", work, "--capability", "S-1-15-3-1",
            "--stdout", Path.Combine(work, "stdout.txt"), "--stderr", Path.Combine(work, "stderr.txt"),
            "--cwd", work, "--timeout-ms", "5000", "--", Path.Combine(system, "cmd.exe"), "/d", "/c", command };
    }
    static string NewWork(string name) {
        string work = Path.Combine(root, name); Directory.CreateDirectory(work); return work;
    }
    static bool Alive(int pid) {
        try { using (var p = Process.GetProcessById(pid)) return !p.HasExited; }
        catch (ArgumentException) { return false; }
    }
    static void CheckCleanup(string log, string work, bool expectedFailure = false) {
        foreach (Match m in Regex.Matches(log, @"COACH_ISO_CREATED_SUSPENDED pid=(\d+)"))
            Check(!Alive(int.Parse(m.Groups[1].Value)), "child survived: " + m.Value);
        var profile = Regex.Match(log, @"COACH_ISO_PROFILE ([^\r\n]+)");
        var sid = Regex.Match(log, @"COACH_ISO_SID (S-[^\r\n]+)");
        if (profile.Success) {
            Check(log.Contains("COACH_ISO_PROFILE_DELETED hr=0x0"), "profile delete failed");
            Check(!Directory.Exists(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Packages", profile.Groups[1].Value)), "package remained");
            using (var mappings = Registry.CurrentUser.OpenSubKey(@"Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppContainer\Mappings")) {
                if (mappings != null) foreach (string key in mappings.GetSubKeyNames()) {
                    using (var sub = mappings.OpenSubKey(key))
                        Check((string)sub.GetValue("Moniker") != profile.Groups[1].Value, "profile mapping remained");
                }
            }
        }
        if (sid.Success) {
            var paths = new List<string> { work };
            paths.AddRange(Directory.GetFileSystemEntries(work));
            foreach (string path in paths) {
                FileSystemSecurity acl = Directory.Exists(path) ? (FileSystemSecurity)Directory.GetAccessControl(path) : File.GetAccessControl(path);
                foreach (FileSystemAccessRule rule in acl.GetAccessRules(true, true, typeof(SecurityIdentifier)))
                    Check(rule.IdentityReference.Value != sid.Groups[1].Value, "container ACE remained");
            }
        }
        Check(expectedFailure == log.Contains("cleanup_failed"), "unexpected cleanup failure state");
    }
    static string Invoke(string[] argv, string fault, out int exit) {
        CoachIsoTestHooks.Target = fault; CoachIsoTestHooks.Hits = 0;
        var prior = Console.Error; var log = new StringWriter();
        try { Console.SetError(log); exit = CoachIso.Main(argv); }
        finally { Console.SetError(prior); }
        return log.ToString();
    }
    static void FaultCase(string step, int code) {
        string work = NewWork(step); int exit;
        string log = Invoke(Args(work, "echo EXECUTED> answer.json"), step, out exit);
        Check(CoachIsoTestHooks.Hits == 1, "fault not reached: " + step);
        Check(exit == code, step + " unexpected exit " + exit + " " + log);
        Check(!log.Contains("COACH_ISO_RESUMED"), step + " resumed");
        Check(!File.Exists(Path.Combine(work, "answer.json")), step + " child executed");
        Check(!File.Exists(Path.Combine(work, "stdout.txt")) || new FileInfo(Path.Combine(work, "stdout.txt")).Length == 0, step + " emitted answer");
        CheckCleanup(log, work);
        results.Add(new { test = step, exit, resumed = 0, answers = 0, nakedStarts = 0, residual = 0, log });
    }
    static void RuntimeCase(string step, int code) {
        string work = NewWork(step); int exit;
        // No answer-producing child for post-resume failures. The contract under test here is
        // a nonzero helper result and cleanup, not "the child never ran".
        var argv = Args(work, "ping.exe -n 10 127.0.0.1 >nul");
        if (step == "timeout") argv[11] = "1";
        string log = Invoke(argv, step == "timeout" ? null : step, out exit);
        Check(exit == code, step + " unexpected exit " + exit + " " + log);
        Check(log.Contains("COACH_ISO_RESUMED"), "runtime failure did not resume");
        CheckCleanup(log, work);
        results.Add(new { test = step, exit, residual = 0, log });
    }
    static void CleanupFailureCase(string step) {
        string work = NewWork(step); int exit;
        string log = Invoke(Args(work, "echo SYNTHETIC_ANSWER_REJECTED"), step, out exit);
        Check(CoachIsoTestHooks.Hits == 1, "cleanup fault not reached: " + step);
        Check(exit == 128 && log.Contains("COACH_ISO_CHILD_EXIT 0") && log.Contains("cleanup_failed:" + step), "cleanup failure accepted: " + log);
        Check(new FileInfo(Path.Combine(work, "stdout.txt")).Length == 0, "failed cleanup left an answer");
        // The one-shot failure is recovered, but remains a refusal. This does not claim
        // recovery from a permanent OS failure; that path still exits nonzero.
        CheckCleanup(log, work, true);
        results.Add(new { test = step, exit, childExit = 0, answers = 0, recovered = true, residual = 0, log });
    }
    static string Quote(string arg) {
        if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return arg;
        var quoted = new System.Text.StringBuilder("\""); int slashes = 0;
        foreach (char c in arg) {
            if (c == '\\') { slashes++; continue; }
            quoted.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            quoted.Append(c); slashes = 0;
        }
        quoted.Append('\\', slashes * 2); quoted.Append('"'); return quoted.ToString();
    }
    static string RunProcess(string program, string[] argv, out int exit) {
        var parts = new List<string>(); foreach (string a in argv) parts.Add(Quote(a));
        var start = new ProcessStartInfo(program, string.Join(" ", parts)) {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        using (var p = Process.Start(start)) {
            var stdout = p.StandardOutput.ReadToEndAsync(); var stderr = p.StandardError.ReadToEndAsync();
            Check(p.WaitForExit(15000), "test process timed out");
            exit = p.ExitCode;
            return stdout.Result + stderr.Result;
        }
    }
    static void ProductionRejects(string flag) {
        string work = NewWork("reject-" + results.Count); int exit;
        var args = new List<string>(Args(work, "echo EXECUTED> answer.json")); args.Insert(0, flag);
        string log = RunProcess(helper, args.ToArray(), out exit);
        Check(exit == 120 && log.Contains("bad_arg:" + flag), "production accepted test flag: " + log);
        Check(!log.Contains("COACH_ISO_CREATED") && !log.Contains("COACH_ISO_PROFILE"), "rejected flag had side effects");
        Check(!File.Exists(Path.Combine(work, "answer.json")), "rejected flag ran child");
        results.Add(new { test = "production_rejects_" + flag, exit, resumed = 0, answers = 0, nakedStarts = 0, residual = 0, log });
    }
    static void ProductionRun(string name, string command, bool denied, string sentinel) {
        string work = NewWork(name); int exit;
        string log = RunProcess(helper, Args(work, command), out exit);
        Check(log.Contains("COACH_ISO_VERIFIED") && log.Contains("COACH_ISO_RESUMED"), "no verified launch: " + log);
        string output = File.ReadAllText(Path.Combine(work, "stdout.txt"));
        Check(denied ? exit != 0 && !output.Contains(sentinel) : exit == 0 && output.Contains(sentinel), "access result wrong: " + log);
        CheckCleanup(log, work);
        results.Add(new { test = name, exit, sentinelRead = output.Contains(sentinel), residual = 0, log });
    }
    public static int Main(string[] argv) {
        root = Path.GetFullPath(argv[0]); helper = Path.GetFullPath(argv[1]);
        system = Environment.GetFolderPath(Environment.SpecialFolder.System);
        int exit = 0; string failure = null;
        try {
            string[] faults = { "create_profile", "profile_sid", "acl_grant", "capability_sid",
                "inherit_stdin", "inherit_stdout", "inherit_stderr", "attr_size", "attr_init", "attr_handles", "attr_caps",
                "job_create", "job_limits", "create_process", "open_token", "token_flag", "token_sid", "token_sid_match", "close_token", "job_assign", "resume" };
            foreach (string fault in faults) FaultCase(fault, fault == "acl_grant" ? 122 :
                (fault == "create_profile" || fault == "profile_sid" || fault == "capability_sid") ? 121 :
                fault.StartsWith("token_") || fault == "open_token" ? 124 : fault == "close_token" ? 128 : fault.StartsWith("job_") || fault == "resume" ? 125 : 123);
            RuntimeCase("wait", 127);
            // Use a fast child so GetExitCodeProcess is reached without a long wait.
            string work = NewWork("exit-code"); int childExit;
            string log = Invoke(Args(work, "exit /b 0"), "exit_code", out childExit);
            Check(CoachIsoTestHooks.Hits == 1 && childExit == 127, "exit-code refusal failed"); CheckCleanup(log, work);
            results.Add(new { test = "exit_code", exit = childExit, residual = 0, log });
            RuntimeCase("timeout", 126);
            CleanupFailureCase("revoke_acl");
            CleanupFailureCase("delete_profile");
            ProductionRejects("--no-container-negative-control");
            ProductionRejects("--test-force-verify-mismatch");
            ProductionRejects("--test-fault=job_assign");
            // No test environment variable can turn the release binary into a negative control.
            Environment.SetEnvironmentVariable("COACH_ISO_TEST", "1");
            Environment.SetEnvironmentVariable("COACH_ISO_NO_CONTAINER", "1");
            ProductionRun("allowed", "echo ALLOWED_SEGMENT_7Q2", false, "ALLOWED_SEGMENT_7Q2");
            string library = NewWork("library"), sentinel = "SYNTHETIC_BOOK_X91K";
            string book = Path.Combine(library, "book-full.txt"); File.WriteAllText(book, sentinel);
            ProductionRun("sibling-denied", "type " + book, true, sentinel);
            // Independent bare launch exists only in this test assembly; no helper switch.
            int bareExit; string bare = RunProcess(Path.Combine(system, "cmd.exe"), new[] { "/d", "/c", "type " + book }, out bareExit);
            Check(bareExit == 0 && bare.Contains(sentinel), "negative control did not read synthetic book: exit=" + bareExit + " output=" + bare);
            results.Add(new { test = "independent_negative_control", exit = bareExit, sentinelRead = true });
        } catch (Exception e) { exit = 1; failure = e.ToString(); }
        File.WriteAllText(Path.Combine(root, "result.json"), new JavaScriptSerializer().Serialize(new { exit, failure, cases = results }));
        Console.WriteLine("regression_exit=" + exit + " cases=" + results.Count + (failure == null ? "" : " failure=" + failure));
        return exit;
    }
}

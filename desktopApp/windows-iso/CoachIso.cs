// Prototype: launch a child inside a fresh AppContainer so it can only open files whose ACL names
// this container's SID (plus OS files readable by ALL APPLICATION PACKAGES). Fails closed: if the
// boundary cannot be created and verified on the suspended child, the child is killed before it
// executes a single instruction and the helper exits 120-129.
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32.SafeHandles;

public static class CoachIso {
    const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000, CREATE_SUSPENDED = 0x4, CREATE_NO_WINDOW = 0x08000000, CREATE_UNICODE_ENVIRONMENT = 0x400;
    static readonly IntPtr ATTR_SECURITY_CAPABILITIES = (IntPtr)0x00020009;
    static readonly IntPtr ATTR_HANDLE_LIST = (IntPtr)0x00020002;
    const int STARTF_USESTDHANDLES = 0x100;

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)] static extern int CreateAppContainerProfile(string name, string display, string desc, IntPtr caps, uint count, out IntPtr sid);
    [DllImport("userenv.dll", CharSet = CharSet.Unicode)] static extern int DeleteAppContainerProfile(string name);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool ConvertStringSidToSid(string s, out IntPtr sid);
    [DllImport("advapi32.dll")] static extern IntPtr FreeSid(IntPtr sid);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr p);
    [StructLayout(LayoutKind.Sequential)] struct SID_AND_ATTRIBUTES { public IntPtr Sid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] struct SECURITY_CAPABILITIES { public IntPtr AppContainerSid; public IntPtr Capabilities; public uint CapabilityCount; public uint Reserved; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct STARTUPINFO { public int cb; public string lpReserved, lpDesktop, lpTitle; public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags; public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError; }
    [StructLayout(LayoutKind.Sequential)] struct STARTUPINFOEX { public STARTUPINFO StartupInfo; public IntPtr lpAttributeList; }
    [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attr, IntPtr val, IntPtr size, IntPtr prev, IntPtr ret);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CreateProcessW(string app, StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string cwd, ref STARTUPINFOEX si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr h);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateProcess(IntPtr h, uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr h, out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern SafeFileHandle CreateFileW(string n, uint access, uint share, IntPtr sa, uint disp, uint flags, IntPtr t);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr CreateJobObjectW(IntPtr a, string n);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr p);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int cls, byte[] info, int len);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr p, uint access, out IntPtr tok);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr tok, int cls, IntPtr buf, int len, out int ret);
    [DllImport("advapi32.dll")] static extern bool EqualSid(IntPtr a, IntPtr b);

    static int Refuse(int code, string why) { Console.Error.WriteLine("COACH_ISO_REFUSED code=" + code + " reason=" + why); return code; }

    sealed class Refusal : Exception {
        public readonly int Code;
        public Refusal(int code, string why) : base(why) { Code = code; }
    }
    static void Require(bool ok, int code, string step) {
        if (!ok) throw new Refusal(code, step);
    }
    // Fault injection is linked only into the separate regression executable. There is no
    // production argument, environment variable, or mutable state that enables it.
    static bool Fault(string step) {
#if COACH_ISO_TEST
        return CoachIsoTestHooks.Fail(step);
#else
        return false;
#endif
    }

    static string Quote(string a) {
        if (a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return a;
        var sb = new StringBuilder("\"");
        int bs = 0;
        foreach (char c in a) {
            if (c == '\\') { bs++; continue; }
            if (c == '"') { sb.Append('\\', bs * 2 + 1); sb.Append('"'); bs = 0; continue; }
            sb.Append('\\', bs); bs = 0; sb.Append(c);
        }
        sb.Append('\\', bs * 2); sb.Append('"');
        return sb.ToString();
    }

    static void Grant(string path, SecurityIdentifier sid, FileSystemRights rights) {
        if (Directory.Exists(path)) {
            var ds = Directory.GetAccessControl(path);
            ds.AddAccessRule(new FileSystemAccessRule(sid, rights, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            Directory.SetAccessControl(path, ds);
        } else {
            var fs = File.GetAccessControl(path);
            fs.AddAccessRule(new FileSystemAccessRule(sid, rights, AccessControlType.Allow));
            File.SetAccessControl(path, fs);
        }
    }

    static void Revoke(string path, SecurityIdentifier sid) {
        if (Directory.Exists(path)) { var ds = Directory.GetAccessControl(path); ds.PurgeAccessRules(sid); Directory.SetAccessControl(path, ds); }
        else if (File.Exists(path)) { var fs = File.GetAccessControl(path); fs.PurgeAccessRules(sid); File.SetAccessControl(path, fs); }
    }

    static SafeFileHandle Inheritable(FileStream f, string step) {
        var h = f.SafeFileHandle;
        Require(!Fault(step) && SetHandleInformation(h.DangerousGetHandle(), 1, 1), 123, step);
        return h;
    }

    static void DisposeStream(FileStream f, List<string> failures) {
        try { if (f != null) f.Dispose(); }
        catch (Exception) { failures.Add("close_stream"); }
    }

    public static int Main(string[] argv) {
        try { return Launch(argv); }
        catch (Refusal e) { return Refuse(e.Code, e.Message); }
        catch (ArgumentException) { return Refuse(120, "invalid_argument"); }
        catch (IndexOutOfRangeException) { return Refuse(120, "missing_argument"); }
        catch (FormatException) { return Refuse(120, "invalid_number"); }
        catch (OverflowException) { return Refuse(120, "invalid_number"); }
        catch (Exception e) { return Refuse(127, "helper_error " + e.GetType().Name); }
    }

    static int Launch(string[] argv) {
        var read = new List<string>(); var write = new List<string>(); var caps = new List<string>();
        string stdin = null, stdout = null, stderr = null, cwd = null; uint timeoutMs = 600000;
        int i = 0;
        for (; i < argv.Length && argv[i] != "--"; i++) {
            switch (argv[i]) {
                case "--allow-read": read.Add(Path.GetFullPath(argv[++i])); break;
                case "--allow-write": write.Add(Path.GetFullPath(argv[++i])); break;
                case "--capability": caps.Add(argv[++i]); break;
                case "--stdin": stdin = argv[++i]; break;
                case "--stdout": stdout = argv[++i]; break;
                case "--stderr": stderr = argv[++i]; break;
                case "--cwd": cwd = argv[++i]; break;
                case "--timeout-ms": timeoutMs = uint.Parse(argv[++i]); break;
                default: return Refuse(120, "bad_arg:" + argv[i]);
            }
        }
        if (i >= argv.Length - 1 || stdout == null || stderr == null || cwd == null) return Refuse(120, "usage");
        var cmd = new StringBuilder();
        for (int j = i + 1; j < argv.Length; j++) { if (cmd.Length > 0) cmd.Append(' '); cmd.Append(Quote(argv[j])); }

        int swept = Sweep();
        if (swept > 0) Console.Error.WriteLine("COACH_ISO_SWEPT " + swept);
        string name = "coach.book.iso." + System.Diagnostics.Process.GetCurrentProcess().Id + "." + Guid.NewGuid().ToString("N");
        // Only a fixed image inside an allowed read root (or the OS directory) may be launched; the helper's own
        // token maps the image, so an out-of-bounds image must be refused here, before CreateProcess.
        string image = Path.GetFullPath(argv[i + 1]);
        string sys = Environment.GetFolderPath(Environment.SpecialFolder.System) + "\\";
        bool imageOk = image.StartsWith(sys, StringComparison.OrdinalIgnoreCase);
        foreach (var r in read) if (image.StartsWith(r.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) imageOk = true;
        if (!imageOk) return Refuse(120, "image_outside_allowed_roots");
        IntPtr acSid = IntPtr.Zero; SecurityIdentifier sid = null; bool profileCreated = false;
        var capSids = new List<IntPtr>();
        var granted = new List<string>();
        IntPtr attrs = IntPtr.Zero, secCapsPtr = IntPtr.Zero, capArr = IntPtr.Zero, handleArr = IntPtr.Zero, job = IntPtr.Zero;
        bool attrsInitialized = false;
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        FileStream fin = null, fout = null, ferr = null;
        try {
            int hr = Fault("create_profile") ? -1 : CreateAppContainerProfile(name, name, "Coach book run", IntPtr.Zero, 0, out acSid);
            if (hr != 0) return Refuse(121, "create_profile hr=0x" + hr.ToString("x"));
            profileCreated = true;
            Console.Error.WriteLine("COACH_ISO_PROFILE " + name);
            Require(!Fault("profile_sid") && acSid != IntPtr.Zero, 121, "profile_sid");
            sid = new SecurityIdentifier(acSid);
            Console.Error.WriteLine("COACH_ISO_SID " + sid.Value);
            try {
                foreach (var p in read) { granted.Add(p); Require(!Fault("acl_grant"), 122, "acl_grant"); Grant(p, sid, FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize); }
                foreach (var p in write) { granted.Add(p); Require(!Fault("acl_grant"), 122, "acl_grant"); Grant(p, sid, FileSystemRights.Modify | FileSystemRights.Synchronize); }
            } catch (Exception e) { return Refuse(122, "acl_grant " + e.GetType().Name); }
            foreach (var c in caps) {
                IntPtr cs; Require(!Fault("capability_sid"), 121, "capability_sid");
                if (!ConvertStringSidToSid(c, out cs)) return Refuse(121, "capability_sid");
                capSids.Add(cs);
            }
            fin = stdin != null ? new FileStream(stdin, FileMode.Open, FileAccess.Read) : new FileStream(CreateFileW("NUL", 0x80000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero), FileAccess.Read);
            fout = new FileStream(stdout, FileMode.Create, FileAccess.Write, FileShare.Read);
            ferr = new FileStream(stderr, FileMode.Create, FileAccess.Write, FileShare.Read);
            var handles = new[] { Inheritable(fin, "inherit_stdin").DangerousGetHandle(), Inheritable(fout, "inherit_stdout").DangerousGetHandle(), Inheritable(ferr, "inherit_stderr").DangerousGetHandle() };

            IntPtr size = IntPtr.Zero;
            bool probed = InitializeProcThreadAttributeList(IntPtr.Zero, 2, 0, ref size);
            int probeError = Marshal.GetLastWin32Error();
            Require(!Fault("attr_size") && !probed && probeError == 122 /* ERROR_INSUFFICIENT_BUFFER */ && size.ToInt64() > 0, 123, "attr_size");
            attrs = Marshal.AllocHGlobal(size);
            Require(!Fault("attr_init") && InitializeProcThreadAttributeList(attrs, 2, 0, ref size), 123, "attr_init");
            attrsInitialized = true;
            handleArr = Marshal.AllocHGlobal(IntPtr.Size * 3);
            for (int k = 0; k < 3; k++) Marshal.WriteIntPtr(handleArr, k * IntPtr.Size, handles[k]);
            Require(!Fault("attr_handles") && UpdateProcThreadAttribute(attrs, 0, ATTR_HANDLE_LIST, handleArr, (IntPtr)(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero), 123, "attr_handles");
            int saSize = Marshal.SizeOf(typeof(SID_AND_ATTRIBUTES));
            if (capSids.Count > 0) {
                capArr = Marshal.AllocHGlobal(saSize * capSids.Count);
                for (int k = 0; k < capSids.Count; k++) Marshal.StructureToPtr(new SID_AND_ATTRIBUTES { Sid = capSids[k], Attributes = 4 /* SE_GROUP_ENABLED */ }, capArr + k * saSize, false);
            }
            var sc = new SECURITY_CAPABILITIES { AppContainerSid = acSid, Capabilities = capArr, CapabilityCount = (uint)capSids.Count };
            secCapsPtr = Marshal.AllocHGlobal(Marshal.SizeOf(sc));
            Marshal.StructureToPtr(sc, secCapsPtr, false);
            Require(!Fault("attr_caps") && UpdateProcThreadAttribute(attrs, 0, ATTR_SECURITY_CAPABILITIES, secCapsPtr, (IntPtr)Marshal.SizeOf(sc), IntPtr.Zero, IntPtr.Zero), 123, "attr_caps");

            // Establish reclamation before creating a child. A failed Job setup never reaches
            // CreateProcess, and a failed assignment below leaves the child suspended.
            job = Fault("job_create") ? IntPtr.Zero : CreateJobObjectW(IntPtr.Zero, null);
            Require(job != IntPtr.Zero, 125, "job_create");
            var info = new byte[IntPtr.Size == 8 ? 144 : 112];
            BitConverter.GetBytes((uint)0x2000).CopyTo(info, 16); // KILL_ON_JOB_CLOSE; not read isolation.
            Require(!Fault("job_limits") && SetInformationJobObject(job, 9, info, info.Length), 125, "job_limits");
            var si = new STARTUPINFOEX();
            si.StartupInfo.cb = Marshal.SizeOf(si);
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            si.StartupInfo.hStdInput = handles[0]; si.StartupInfo.hStdOutput = handles[1]; si.StartupInfo.hStdError = handles[2];
            si.lpAttributeList = attrs;
            if (Fault("create_process") || !CreateProcessW(image, cmd, IntPtr.Zero, IntPtr.Zero, true, EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW, IntPtr.Zero, cwd, ref si, out pi))
                return Refuse(123, "create_process err=" + Marshal.GetLastWin32Error());
            Console.Error.WriteLine("COACH_ISO_CREATED_SUSPENDED pid=" + pi.dwProcessId);

            // Verify the boundary on the suspended child before it runs: AppContainer flag set and SID ours.
            IntPtr tok = IntPtr.Zero;
            try {
                Require(!Fault("open_token") && OpenProcessToken(pi.hProcess, 0x0008, out tok), 124, "open_token");
                IntPtr buf = Marshal.AllocHGlobal(256); int ret;
                try {
                    Require(!Fault("token_flag") && GetTokenInformation(tok, 29 /* TokenIsAppContainer */, buf, 4, out ret) && ret == 4 && Marshal.ReadInt32(buf) == 1, 124, "token_flag");
                    Require(!Fault("token_sid") && GetTokenInformation(tok, 31 /* TokenAppContainerSid */, buf, 256, out ret) && ret >= IntPtr.Size, 124, "token_sid");
                    Require(!Fault("token_sid_match") && EqualSid(Marshal.ReadIntPtr(buf), acSid), 124, "token_sid_match");
                } finally { Marshal.FreeHGlobal(buf); }
            } finally {
                if (tok != IntPtr.Zero) {
                    bool closed = CloseHandle(tok);
                    Require(closed && !Fault("close_token"), 128, "close_token");
                }
            }
            Console.Error.WriteLine("COACH_ISO_VERIFIED pid=" + pi.dwProcessId + " appcontainer=1");
            Require(!Fault("job_assign") && AssignProcessToJobObject(job, pi.hProcess), 125, "job_assign");
            Require(!Fault("resume"), 125, "resume");
            Require(ResumeThread(pi.hThread) == 1, 125, "resume");
            Console.Error.WriteLine("COACH_ISO_RESUMED pid=" + pi.dwProcessId);
            uint w = Fault("wait") ? 0xffffffff : WaitForSingleObject(pi.hProcess, timeoutMs);
            Require(w == 0, w == 258 ? 126 : 127, w == 258 ? "timeout" : "wait_failed");
            uint code = 0;
            Require(!Fault("exit_code") && GetExitCodeProcess(pi.hProcess, out code) && code != 259 /* STILL_ACTIVE */, 127, "exit_code");
            Console.Error.WriteLine("COACH_ISO_CHILD_EXIT " + code);
            return (int)code;
        } finally {
            // Every refusal after CreateProcess owns a suspended or Job-bound child. Kill and
            // wait even on early returns/exceptions, before removing its ACL/profile.
            var cleanup = new List<string>();
            if (pi.hProcess != IntPtr.Zero && WaitForSingleObject(pi.hProcess, 0) != 0) {
                if (!TerminateProcess(pi.hProcess, 127)) cleanup.Add("terminate_child");
            }
            if (job != IntPtr.Zero && !CloseHandle(job)) cleanup.Add("close_job");
            if (pi.hProcess != IntPtr.Zero && WaitForSingleObject(pi.hProcess, 5000) != 0) cleanup.Add("wait_child");
            if (pi.hThread != IntPtr.Zero && !CloseHandle(pi.hThread)) cleanup.Add("close_thread");
            if (pi.hProcess != IntPtr.Zero && !CloseHandle(pi.hProcess)) cleanup.Add("close_process");
            DisposeStream(fin, cleanup); DisposeStream(fout, cleanup); DisposeStream(ferr, cleanup);
            if (attrs != IntPtr.Zero) { if (attrsInitialized) DeleteProcThreadAttributeList(attrs); Marshal.FreeHGlobal(attrs); }
            if (handleArr != IntPtr.Zero) Marshal.FreeHGlobal(handleArr);
            if (secCapsPtr != IntPtr.Zero) Marshal.FreeHGlobal(secCapsPtr);
            if (capArr != IntPtr.Zero) Marshal.FreeHGlobal(capArr);
            int revoked = 0;
            foreach (var p in granted) {
                try { Require(!Fault("revoke_acl"), 128, "revoke_acl"); Revoke(p, sid); revoked++; }
                catch (Exception) {
                    cleanup.Add("revoke_acl");
                    // Best effort recovery does not turn a failed cleanup into success.
                    try { Revoke(p, sid); revoked++; } catch (Exception) { cleanup.Add("revoke_acl_retry"); }
                }
            }
            if (granted.Count > 0) Console.Error.WriteLine("COACH_ISO_ACL_REVOKED " + revoked);
            if (acSid != IntPtr.Zero) FreeSid(acSid);
            foreach (var cs in capSids) if (LocalFree(cs) != IntPtr.Zero) cleanup.Add("free_capability_sid");
            if (profileCreated) {
                int dh = Fault("delete_profile") ? -1 : DeleteAppContainerProfile(name);
                if (dh != 0) { cleanup.Add("delete_profile"); dh = DeleteAppContainerProfile(name); }
                if (dh != 0) cleanup.Add("delete_profile_retry");
                Console.Error.WriteLine("COACH_ISO_PROFILE_DELETED hr=0x" + dh.ToString("x"));
            }
            if (cleanup.Count > 0 && fout != null) {
                // The helper created this output. Do not leave a successful-looking answer
                // behind when cleanup failed, even if the child itself exited successfully.
                try { using (var rejected = new FileStream(stdout, FileMode.Truncate, FileAccess.Write)) { } }
                catch (Exception) { cleanup.Add("discard_stdout"); }
            }
            Require(cleanup.Count == 0, 128, "cleanup_failed:" + string.Join(",", cleanup));
        }
    }

    // Deletes coach.book.iso.<pid>.* profiles left by a helper that was force-killed (Coach's revoke path kills the tree).
    static int Sweep() {
        int n = 0;
        using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppContainer\Mappings")) {
            if (k == null) return 0;
            foreach (var sidName in k.GetSubKeyNames()) {
                string moniker;
                using (var sk = k.OpenSubKey(sidName)) { moniker = sk == null ? null : sk.GetValue("Moniker") as string; }
                if (moniker == null || !moniker.StartsWith("coach.book.iso.")) continue;
                var parts = moniker.Split('.');
                int pid; bool alive = false;
                if (parts.Length >= 5 && int.TryParse(parts[3], out pid)) { try { alive = !System.Diagnostics.Process.GetProcessById(pid).HasExited; } catch (ArgumentException) { } }
                if (!alive && DeleteAppContainerProfile(moniker) == 0) n++;
            }
        }
        return n;
    }

}

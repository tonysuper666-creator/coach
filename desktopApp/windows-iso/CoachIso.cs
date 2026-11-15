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

    [DllImport("userenv.dll", CharSet = CharSet.Unicode)] static extern int DeriveAppContainerSidFromAppContainerName(string name, out IntPtr sid);
    [DllImport("userenv.dll", CharSet = CharSet.Unicode)] static extern int CreateAppContainerProfile(string name, string display, string desc, IntPtr caps, uint count, out IntPtr sid);
    [DllImport("userenv.dll", CharSet = CharSet.Unicode)] static extern int DeleteAppContainerProfile(string name);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool ConvertStringSidToSid(string s, out IntPtr sid);
    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool ConvertSidToStringSid(IntPtr sid, out string s);
    [DllImport("advapi32.dll")] static extern IntPtr FreeSid(IntPtr sid);
    [StructLayout(LayoutKind.Sequential)] struct SID_AND_ATTRIBUTES { public IntPtr Sid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] struct SECURITY_CAPABILITIES { public IntPtr AppContainerSid; public IntPtr Capabilities; public uint CapabilityCount; public uint Reserved; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] struct STARTUPINFO { public int cb; public string lpReserved, lpDesktop, lpTitle; public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags; public short wShowWindow, cbReserved2; public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError; }
    [StructLayout(LayoutKind.Sequential)] struct STARTUPINFOEX { public STARTUPINFO StartupInfo; public IntPtr lpAttributeList; }
    [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attr, IntPtr val, IntPtr size, IntPtr prev, IntPtr ret);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern bool CreateProcessW(string app, StringBuilder cmd, IntPtr pa, IntPtr ta, bool inherit, uint flags, IntPtr env, string cwd, ref STARTUPINFOEX si, out PROCESS_INFORMATION pi);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr h);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr h, uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h, uint ms);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr h, out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern SafeFileHandle CreateFileW(string n, uint access, uint share, IntPtr sa, uint disp, uint flags, IntPtr t);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetHandleInformation(IntPtr h, uint mask, uint flags);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObjectW(IntPtr a, string n);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr p);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int cls, byte[] info, int len);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr p, uint access, out IntPtr tok);
    [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr tok, int cls, IntPtr buf, int len, out int ret);
    [DllImport("advapi32.dll")] static extern bool EqualSid(IntPtr a, IntPtr b);

    static int Refuse(int code, string why) { Console.Error.WriteLine("COACH_ISO_REFUSED code=" + code + " reason=" + why); return code; }

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

    static SafeFileHandle Inheritable(FileStream f) {
        var h = f.SafeFileHandle;
        SetHandleInformation(h.DangerousGetHandle(), 1, 1);
        return h;
    }

    public static int Main(string[] argv) {
        var read = new List<string>(); var write = new List<string>(); var caps = new List<string>();
        string stdin = null, stdout = null, stderr = null, cwd = null; bool noContainer = false, forceMismatch = false; uint timeoutMs = 600000;
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
                case "--no-container-negative-control": noContainer = true; break;
                case "--test-force-verify-mismatch": forceMismatch = true; break;
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
        bool imageOk = noContainer || image.StartsWith(sys, StringComparison.OrdinalIgnoreCase);
        foreach (var r in read) if (image.StartsWith(r.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) imageOk = true;
        if (!imageOk) return Refuse(120, "image_outside_allowed_roots");
        IntPtr acSid = IntPtr.Zero; SecurityIdentifier sid = null; bool profileCreated = false;
        var capSids = new List<IntPtr>();
        var granted = new List<string>();
        IntPtr attrs = IntPtr.Zero, secCapsPtr = IntPtr.Zero, capArr = IntPtr.Zero, handleArr = IntPtr.Zero;
        PROCESS_INFORMATION pi = new PROCESS_INFORMATION();
        FileStream fin = null, fout = null, ferr = null;
        try {
            if (!noContainer) {
                int hr = CreateAppContainerProfile(name, name, "Coach book run", IntPtr.Zero, 0, out acSid);
                if (hr != 0) return Refuse(121, "create_profile hr=0x" + hr.ToString("x"));
                profileCreated = true;
                Console.Error.WriteLine("COACH_ISO_PROFILE " + name);
                string s; ConvertSidToStringSid(acSid, out s); sid = new SecurityIdentifier(s);
                Console.Error.WriteLine("COACH_ISO_SID " + s);
                try {
                    foreach (var p in read) { Grant(p, sid, FileSystemRights.ReadAndExecute | FileSystemRights.Synchronize); granted.Add(p); }
                    foreach (var p in write) { Grant(p, sid, FileSystemRights.Modify | FileSystemRights.Synchronize); granted.Add(p); }
                } catch (Exception e) { return Refuse(122, "acl_grant " + e.GetType().Name); }
                foreach (var c in caps) {
                    IntPtr cs; if (!ConvertStringSidToSid(c, out cs)) return Refuse(121, "capability_sid " + c);
                    capSids.Add(cs);
                }
            }
            fin = stdin != null ? new FileStream(stdin, FileMode.Open, FileAccess.Read) : new FileStream(CreateFileW("NUL", 0x80000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero), FileAccess.Read);
            fout = new FileStream(stdout, FileMode.Create, FileAccess.Write, FileShare.Read);
            ferr = new FileStream(stderr, FileMode.Create, FileAccess.Write, FileShare.Read);
            var handles = new[] { Inheritable(fin).DangerousGetHandle(), Inheritable(fout).DangerousGetHandle(), Inheritable(ferr).DangerousGetHandle() };

            IntPtr size = IntPtr.Zero;
            InitializeProcThreadAttributeList(IntPtr.Zero, 2, 0, ref size);
            attrs = Marshal.AllocHGlobal(size);
            if (!InitializeProcThreadAttributeList(attrs, 2, 0, ref size)) return Refuse(123, "attr_init");
            handleArr = Marshal.AllocHGlobal(IntPtr.Size * 3);
            for (int k = 0; k < 3; k++) Marshal.WriteIntPtr(handleArr, k * IntPtr.Size, handles[k]);
            if (!UpdateProcThreadAttribute(attrs, 0, ATTR_HANDLE_LIST, handleArr, (IntPtr)(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero)) return Refuse(123, "attr_handles");
            if (!noContainer) {
                int saSize = Marshal.SizeOf(typeof(SID_AND_ATTRIBUTES));
                if (capSids.Count > 0) {
                    capArr = Marshal.AllocHGlobal(saSize * capSids.Count);
                    for (int k = 0; k < capSids.Count; k++) Marshal.StructureToPtr(new SID_AND_ATTRIBUTES { Sid = capSids[k], Attributes = 4 /* SE_GROUP_ENABLED */ }, capArr + k * saSize, false);
                }
                var sc = new SECURITY_CAPABILITIES { AppContainerSid = acSid, Capabilities = capArr, CapabilityCount = (uint)capSids.Count };
                secCapsPtr = Marshal.AllocHGlobal(Marshal.SizeOf(sc));
                Marshal.StructureToPtr(sc, secCapsPtr, false);
                if (!UpdateProcThreadAttribute(attrs, 0, ATTR_SECURITY_CAPABILITIES, secCapsPtr, (IntPtr)Marshal.SizeOf(sc), IntPtr.Zero, IntPtr.Zero)) return Refuse(123, "attr_caps");
            }
            var si = new STARTUPINFOEX();
            si.StartupInfo.cb = Marshal.SizeOf(si);
            si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            si.StartupInfo.hStdInput = handles[0]; si.StartupInfo.hStdOutput = handles[1]; si.StartupInfo.hStdError = handles[2];
            si.lpAttributeList = attrs;
            if (!CreateProcessW(null, cmd, IntPtr.Zero, IntPtr.Zero, true, EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_NO_WINDOW, IntPtr.Zero, cwd, ref si, out pi))
                return Refuse(123, "create_process err=" + Marshal.GetLastWin32Error());

            // Verify the boundary on the suspended child before it runs: AppContainer flag set and SID ours.
            if (!noContainer) {
                IntPtr tok; bool ok = false;
                if (OpenProcessToken(pi.hProcess, 0x0008, out tok)) {
                    IntPtr buf = Marshal.AllocHGlobal(256); int ret;
                    bool isAc = GetTokenInformation(tok, 29 /* TokenIsAppContainer */, buf, 4, out ret) && Marshal.ReadInt32(buf) == 1;
                    bool sameSid = GetTokenInformation(tok, 31 /* TokenAppContainerSid */, buf, 256, out ret) && EqualSid(Marshal.ReadIntPtr(buf), forceMismatch ? capSidsOrSelfMismatch() : acSid);
                    ok = isAc && sameSid;
                    Marshal.FreeHGlobal(buf); CloseHandle(tok);
                }
                if (!ok) { TerminateProcess(pi.hProcess, 125); WaitForSingleObject(pi.hProcess, 5000); return Refuse(124, "verify_failed_child_killed_before_resume pid=" + pi.dwProcessId); }
                Console.Error.WriteLine("COACH_ISO_VERIFIED pid=" + pi.dwProcessId + " appcontainer=1");
            }
            IntPtr job = CreateJobObjectW(IntPtr.Zero, null);
            var info = new byte[IntPtr.Size == 8 ? 144 : 112];
            BitConverter.GetBytes((uint)0x2000).CopyTo(info, IntPtr.Size == 8 ? 16 : 16); // KILL_ON_JOB_CLOSE (reclamation only)
            SetInformationJobObject(job, 9, info, info.Length);
            AssignProcessToJobObject(job, pi.hProcess);
            ResumeThread(pi.hThread);
            uint w = WaitForSingleObject(pi.hProcess, timeoutMs);
            if (w != 0) { TerminateProcess(pi.hProcess, 126); Console.Error.WriteLine("COACH_ISO_TIMEOUT"); }
            uint code; GetExitCodeProcess(pi.hProcess, out code);
            CloseHandle(job);
            Console.Error.WriteLine("COACH_ISO_CHILD_EXIT " + code);
            return (int)code;
        } catch (Exception e) {
            if (pi.hProcess != IntPtr.Zero) TerminateProcess(pi.hProcess, 127);
            return Refuse(127, "helper_error " + e.GetType().Name);
        } finally {
            if (pi.hThread != IntPtr.Zero) CloseHandle(pi.hThread);
            if (pi.hProcess != IntPtr.Zero) CloseHandle(pi.hProcess);
            if (fin != null) fin.Dispose(); if (fout != null) fout.Dispose(); if (ferr != null) ferr.Dispose();
            if (attrs != IntPtr.Zero) { DeleteProcThreadAttributeList(attrs); Marshal.FreeHGlobal(attrs); }
            if (handleArr != IntPtr.Zero) Marshal.FreeHGlobal(handleArr);
            if (secCapsPtr != IntPtr.Zero) Marshal.FreeHGlobal(secCapsPtr);
            if (capArr != IntPtr.Zero) Marshal.FreeHGlobal(capArr);
            foreach (var p in granted) { try { Revoke(p, sid); } catch (Exception e) { Console.Error.WriteLine("COACH_ISO_REVOKE_FAILED " + p + " " + e.GetType().Name); } }
            if (granted.Count > 0) Console.Error.WriteLine("COACH_ISO_ACL_REVOKED " + granted.Count);
            if (acSid != IntPtr.Zero) FreeSid(acSid);
            if (profileCreated) { int dh = DeleteAppContainerProfile(name); Console.Error.WriteLine("COACH_ISO_PROFILE_DELETED hr=0x" + dh.ToString("x")); }
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

    static IntPtr mismatchSid = IntPtr.Zero;
    static IntPtr capSidsOrSelfMismatch() { if (mismatchSid == IntPtr.Zero) DeriveAppContainerSidFromAppContainerName("coach.book.iso.mismatch", out mismatchSid); return mismatchSid; }
}

# A per-invocation Windows job. Only the interactive worker and its descendants
# join it; existing clients and shared build daemons never do.
if (-not ('CoachSmokeJob' -as [type])) {
    Add-Type @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
public static class CoachSmokeJob {
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinimumWorkingSet, MaximumWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong ReadOps, WriteOps, OtherOps, ReadBytes, WriteBytes, OtherBytes;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
        public BasicLimits Basic;
        public IoCounters Io;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateJobObject(IntPtr security, string name);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr OpenJobObject(uint access, bool inherit, string name);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool CloseHandle(IntPtr handle);
    public static IntPtr Create(string name) {
        IntPtr job = CreateJobObject(IntPtr.Zero, name);
        if (job == IntPtr.Zero) throw new Win32Exception();
        var limits = new ExtendedLimits();
        limits.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; no breakaway
        if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(limits))) {
            int error = Marshal.GetLastWin32Error(); CloseHandle(job);
            throw new Win32Exception(error);
        }
        return job;
    }
    public static IntPtr Open(string name) {
        IntPtr job = OpenJobObject(0x0001, false, name); // JOB_OBJECT_ASSIGN_PROCESS
        if (job == IntPtr.Zero) throw new Win32Exception();
        return job;
    }
    public static void Join(IntPtr job) {
        using (var self = Process.GetCurrentProcess()) {
            if (!AssignProcessToJobObject(job, self.Handle)) throw new Win32Exception();
        }
    }
}
'@
}

using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

// Used only by the disposable ARM installer probe. Both the driver and the
// installed launcher must run on the same visible, input-receiving desktop.
public static class ArmSmokeDesktop {
    const uint ReadObjects = 0x1, CreateWindow = 0x2, Switch = 0x100;

    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateDesktop(string name, IntPtr device, IntPtr mode, uint flags, uint access, IntPtr security);
    [DllImport("user32.dll", SetLastError = true)]
    static extern bool SwitchDesktop(IntPtr desktop);
    [DllImport("user32.dll")]
    static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder name, uint bytes, out uint needed);
    [DllImport("user32.dll")] static extern IntPtr GetProcessWindowStation();
    [DllImport("user32.dll")] static extern IntPtr GetThreadDesktop(uint thread);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct Startup {
        public int size;
        public string reserved, desktop, title;
        public uint x, y, width, height, cols, rows, fill, flags;
        public ushort show, reservedSize;
        public IntPtr reservedData, stdin, stdout, stderr;
    }
    [StructLayout(LayoutKind.Sequential)]
    struct Child {
        public IntPtr process, thread;
        public int pid, tid;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateProcess(string exe, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity,
        bool inherit, uint flags, IntPtr environment, string cwd, ref Startup startup, out Child child);

    static Exception Error(string action) {
        int code = Marshal.GetLastWin32Error();
        return new Win32Exception(code, action + " failed (" + code + "): " + new Win32Exception(code).Message);
    }
    static string ObjectName(IntPtr handle) {
        var name = new StringBuilder(256);
        uint needed;
        if (!GetUserObjectInformation(handle, 2, name, 512, out needed)) throw Error("Read desktop name");
        return name.ToString();
    }
    public static Process Start(string exe, string arguments, string desktop) {
        var startup = new Startup { size = Marshal.SizeOf(typeof(Startup)), desktop = @"WinSta0\" + desktop };
        var command = new StringBuilder("\"" + exe + "\" " + arguments);
        Child child;
        if (!CreateProcess(exe, command, IntPtr.Zero, IntPtr.Zero, false, 0x08000000, IntPtr.Zero,
                           Environment.CurrentDirectory, ref startup, out child)) throw Error("Launch on test desktop");
        try {
            var process = Process.GetProcessById(child.pid);
            // Retain a query handle before the child exits. WaitForExit alone
            // opens a temporary wait handle on Windows PowerShell's .NET runtime.
            var handle = process.Handle;
            return process;
        }
        finally { CloseHandle(child.thread); CloseHandle(child.process); }
    }
    public static int Run(string exe, string args, string desktop) {
        var station = ObjectName(GetProcessWindowStation());
        var caller = ObjectName(GetThreadDesktop(GetCurrentThreadId()));
        Console.WriteLine("Runner station={0}, desktop={1}, session={2}", station, caller, Process.GetCurrentProcess().SessionId);
        if (station != "WinSta0" || caller != "Default") throw new InvalidOperationException("Expected the interactive default desktop");
        var original = OpenInputDesktop(0, false, ReadObjects | Switch);
        if (original == IntPtr.Zero) throw Error("Open current input desktop");
        try {
            string input = ObjectName(original);
            Console.WriteLine("Input desktop=" + input);
            if (input != caller) throw new InvalidOperationException("The runner is not on its input desktop");
            var test = CreateDesktop(desktop, IntPtr.Zero, IntPtr.Zero, 0, ReadObjects | CreateWindow | Switch, IntPtr.Zero);
            if (test == IntPtr.Zero) throw Error("Create test desktop");
            try {
                if (!SwitchDesktop(test)) throw Error("Activate test desktop");
                try {
                    using (var worker = Start(exe, args, desktop)) {
                        if (!worker.WaitForExit(12 * 60 * 1000)) {
                            worker.Kill();
                            worker.WaitForExit();
                            throw new TimeoutException("Installed scan exceeded its time limit");
                        }
                        return worker.ExitCode;
                    }
                } finally {
                    if (!SwitchDesktop(original)) throw Error("Restore runner desktop");
                }
            } finally { CloseDesktop(test); }
        } finally { CloseDesktop(original); }
    }
}

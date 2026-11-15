using System;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

// Independent candidate, not installed or called by production transport. No credential output.
public static class CoachAuthCommit {
    const int MaxBytes = 1048576;
    const long UnixEpochFileTime = 116444736000000000L;
    sealed class Refused : Exception {
        public readonly int Code;
        public Refused(int code) { Code = code; }
    }
    [DllImport("KtmW32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateTransaction(IntPtr attrs, IntPtr guid, uint options, uint isolation, uint flags, uint timeout, string description);
    [DllImport("KtmW32.dll", SetLastError=true)] static extern bool CommitTransaction(IntPtr transaction);
    [DllImport("KtmW32.dll", SetLastError=true)] static extern bool RollbackTransaction(IntPtr transaction);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern SafeFileHandle CreateFileTransactedW(string name, uint access, uint share, IntPtr attrs, uint disposition,
        uint flags, IntPtr template, IntPtr transaction, IntPtr miniVersion, IntPtr extended);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle file, out Info info);
    [StructLayout(LayoutKind.Sequential)] struct Stamp { public uint Low, High; }
    [StructLayout(LayoutKind.Sequential)] struct Info {
        public uint Attributes;
        public Stamp Created, Accessed, Modified;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }

    public static int Main(string[] args) { return Run(args, null); }

    // The hook is used only by a separately compiled synthetic harness, never exposed as a CLI flag.
    internal static int Run(string[] args, Action<string> testHook) {
        IntPtr transaction = IntPtr.Zero;
        byte[] refreshed = null, current = null;
        bool committed = false;
        int result = 4;
        try {
            long expectedModified;
            if (args.Length != 5 || !Path.IsPathRooted(args[0]) || !Path.IsPathRooted(args[1]) ||
                !ValidHash(args[2]) || !ValidHash(args[4]) ||
                !long.TryParse(args[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out expectedModified)) throw new Refused(4);
            string source = Path.GetFullPath(args[0]), staged = Path.GetFullPath(args[1]);
            if (!string.Equals(Path.GetDirectoryName(source), Path.GetDirectoryName(staged), StringComparison.OrdinalIgnoreCase) ||
                !Path.GetFileName(staged).StartsWith(".coach-auth-stage-", StringComparison.Ordinal)) throw new Refused(4);
            if ((File.GetAttributes(staged) & (FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0) throw new Refused(4);
            using (var input = new FileStream(staged, FileMode.Open, FileAccess.Read, FileShare.Read)) {
                refreshed = ReadBounded(input);
            }
            if (Hash(refreshed) != args[4]) throw new Refused(4);
            transaction = CreateTransaction(IntPtr.Zero, IntPtr.Zero, 0, 0, 0, 5000, "Coach auth compare-and-swap");
            if (transaction == IntPtr.Zero || transaction == new IntPtr(-1)) throw new Refused(4);

            // Opening for transactional modification locks the original against ordinary writers
            // and renames until commit/rollback, including after the handle itself is closed.
            using (var handle = CreateFileTransactedW(source, 0xC0000000, 7, IntPtr.Zero, 3, 0x00200080,
                IntPtr.Zero, transaction, IntPtr.Zero, IntPtr.Zero)) {
                if (handle.IsInvalid) {
                    int error = Marshal.GetLastWin32Error();
                    throw new Refused(error == 2 || error == 3 ? 3 : 4);
                }
                Info info;
                if (!GetFileInformationByHandle(handle, out info) || (info.Attributes & 0x410) != 0 || info.Links != 1) throw new Refused(4);
                long modified = unchecked((long)(((ulong)info.Modified.High << 32) | info.Modified.Low)) - UnixEpochFileTime;
                using (var output = new FileStream(handle, FileAccess.ReadWrite)) {
                    current = ReadBounded(output);
                    if (Hash(current) != args[2] || modified != expectedModified) throw new Refused(3);
                    if (testHook != null) testHook("compared");
                    // Replace contents inside the transaction, preserving the original file's
                    // identity, owner and ACL. Other readers see old bytes until atomic commit.
                    output.Position = 0;
                    output.Write(refreshed, 0, refreshed.Length);
                    output.SetLength(refreshed.Length);
                    output.Flush(true);
                    if (testHook != null) testHook("written");
                }
            }
            if (testHook != null) testHook("closed");
            if (!CommitTransaction(transaction)) throw new Refused(4);
            committed = true;
            result = 0;
        } catch (Refused refusal) { result = refusal.Code; }
        catch { result = 4; }
        finally {
            if (refreshed != null) Array.Clear(refreshed, 0, refreshed.Length);
            if (current != null) Array.Clear(current, 0, current.Length);
            if (transaction != IntPtr.Zero && transaction != new IntPtr(-1)) {
                if (!committed && !RollbackTransaction(transaction)) result = 5;
                if (!CloseHandle(transaction)) result = 5;
            }
        }
        return result;
    }

    static byte[] ReadBounded(Stream input) {
        if (input.Length < 1 || input.Length > MaxBytes) throw new IOException();
        byte[] bytes = new byte[(int)input.Length];
        int position = 0, read;
        while (position < bytes.Length && (read = input.Read(bytes, position, bytes.Length - position)) != 0) position += read;
        if (position != bytes.Length || input.ReadByte() != -1) { Array.Clear(bytes, 0, bytes.Length); throw new IOException(); }
        return bytes;
    }
    static string Hash(byte[] bytes) {
        using (var hash = SHA256.Create()) { return BitConverter.ToString(hash.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant(); }
    }
    static bool ValidHash(string value) {
        if (value.Length != 64) return false;
        foreach (char c in value) if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        return true;
    }
}

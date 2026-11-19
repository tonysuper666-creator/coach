using System;
using System.Drawing;
using System.Runtime.InteropServices;

// Fallback for the hosted ARM desktop, where Compose is sometimes absent from UI Automation.
public static class InstalledShelf {
    public static Point FindUpload(Bitmap frame, Bitmap button) {
        int top = button.GetPixel(button.Width / 2, 0).ToArgb() & 0xffffff;
        Point match = Point.Empty;
        for (int y = 1; y <= frame.Height - button.Height; y++) {
            for (int x = 0; x <= frame.Width - button.Width; x++) {
                int mid = x + button.Width / 2;
                if ((frame.GetPixel(mid, y).ToArgb() & 0xffffff) != top ||
                    (frame.GetPixel(mid, y-1).ToArgb() & 0xffffff) == top) continue;
                if (!Matches(frame, button, x, y)) continue;
                if (!match.IsEmpty) throw new InvalidOperationException("Ambiguous upload button");
                match = new Point(mid, y + button.Height / 2);
            }
        }
        return match;
    }

    private static bool Matches(Bitmap frame, Bitmap button, int x, int y) {
        // Compare the actual glyphs as well as the outline, ignoring capture alpha.
        for (int j = 0; j < button.Height; j++)
            for (int i = 0; i < button.Width; i++)
                if ((frame.GetPixel(x+i, y+j).ToArgb() & 0xffffff) !=
                    (button.GetPixel(i, j).ToArgb() & 0xffffff)) return false;
        return true;
    }

    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out int pid);
    [DllImport("user32.dll")] private static extern bool ScreenToClient(IntPtr window, ref Point p);
    [DllImport("user32.dll")] private static extern IntPtr ChildWindowFromPointEx(IntPtr parent, Point p, uint flags);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr window, uint msg, IntPtr key, IntPtr position);

    public static void ClickUpload(IntPtr frame, int pid, Point screen) {
        // Walk the owned AWT children even when an unrelated window covers the frame.
        // Each posted mouse event is addressed to the selected component. No global mouse input.
        IntPtr target = frame;
        Point local;
        while (true) {
            int owner;
            GetWindowThreadProcessId(target, out owner);
            if (owner != pid) throw new InvalidOperationException("Upload window owner changed");
            local = screen;
            if (!ScreenToClient(target, ref local)) throw new InvalidOperationException("Cannot map button position");
            IntPtr child = ChildWindowFromPointEx(target, local, 7); // skip invisible, disabled, transparent
            if (child == IntPtr.Zero || child == target) break;
            target = child;
        }
        IntPtr position = (IntPtr)((local.Y << 16) | (local.X & 0xffff));
        if (!PostMessage(target, 0x0200, IntPtr.Zero, position) ||
            !PostMessage(target, 0x0201, (IntPtr)1, position) ||
            !PostMessage(target, 0x0202, IntPtr.Zero, position))
            throw new InvalidOperationException("Could not click owned upload button");
    }
}

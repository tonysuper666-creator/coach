using System;
using System.Drawing;

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

}

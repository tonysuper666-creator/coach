package coach.smoke;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

/**
 * Smoke-only AWT drag. OS cursor injection moves the pointer on the ARM runner
 * and Compose still treats the gesture as a click, so the selection toolbar
 * never appears. This posts the same MouseEvents the scene mediator listens for.
 * It stays idle unless COACH_DRAG_PORT_FILE is set, and it listens on loopback.
 */
public final class ArmDragAgent {
    private ArmDragAgent() {}

    public static void premain(String args, Instrumentation instrumentation) {
        String portFile = System.getenv("COACH_DRAG_PORT_FILE");
        if (portFile == null || portFile.isBlank()) return;
        Thread thread = new Thread(() -> serve(Path.of(portFile)), "arm-drag-agent");
        thread.setDaemon(true);
        thread.start();
    }

    private static void serve(Path portFile) {
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            Files.writeString(portFile, Integer.toString(server.getLocalPort()), StandardCharsets.US_ASCII);
            while (true) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(8000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
                    String line = reader.readLine();
                    String reply;
                    try {
                        reply = handle(line);
                    } catch (Throwable thrown) {
                        reply = "ERR " + thrown.getClass().getSimpleName() + " " + thrown.getMessage();
                    }
                    writer.write(reply);
                    writer.write('\n');
                    writer.flush();
                } catch (Throwable ignored) {
                    // One bad client must not stop the next drag.
                }
            }
        } catch (Throwable thrown) {
            try {
                Files.writeString(portFile.resolveSibling("drag-agent-error.txt"),
                    thrown.toString(), StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String handle(String line) throws Exception {
        if (line == null || line.isBlank()) return "ERR empty";
        String[] parts = line.trim().split("\\s+");
        if (parts[0].equals("PING")) return "OK " + describeTargets();
        if (!parts[0].equals("DRAG") || parts.length != 5) return "ERR expected DRAG x1 y1 x2 y2";
        int x1 = Integer.parseInt(parts[1]);
        int y1 = Integer.parseInt(parts[2]);
        int x2 = Integer.parseInt(parts[3]);
        int y2 = Integer.parseInt(parts[4]);
        String[] reply = new String[1];
        SwingUtilities.invokeAndWait(() -> reply[0] = dragOnEdt(x1, y1, x2, y2));
        return reply[0];
    }

    private static String describeTargets() throws Exception {
        String[] reply = new String[1];
        SwingUtilities.invokeAndWait(() -> {
            int showing = 0;
            int listened = 0;
            for (Window window : Window.getWindows()) {
                if (!window.isShowing()) continue;
                showing++;
                List<Component> found = new ArrayList<>();
                collect(window, found);
                for (Component component : found) {
                    if (component.getMouseMotionListeners().length > 0) listened++;
                }
            }
            reply[0] = "OK showing=" + showing + " motionTargets=" + listened;
        });
        return reply[0];
    }

    private static String dragOnEdt(int screenX1, int screenY1, int screenX2, int screenY2) {
        Component target = findTarget(screenX1, screenY1);
        if (target == null) return "ERR no-mouse-target";
        try {
            Point start = new Point(screenX1, screenY1);
            Point end = new Point(screenX2, screenY2);
            SwingUtilities.convertPointFromScreen(start, target);
            SwingUtilities.convertPointFromScreen(end, target);
            target.requestFocusInWindow();
            long when = System.currentTimeMillis();
            // Press must carry BUTTON1 before any dragged event. The scene filter
            // drops a button-down move until a primary press has been seen.
            post(target, MouseEvent.MOUSE_MOVED, start.x, start.y, 0, MouseEvent.NOBUTTON, when);
            post(target, MouseEvent.MOUSE_PRESSED, start.x, start.y,
                InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1, when + 16);
            int steps = 8;
            for (int i = 1; i <= steps; i++) {
                int x = start.x + (end.x - start.x) * i / steps;
                int y = start.y + (end.y - start.y) * i / steps;
                post(target, MouseEvent.MOUSE_DRAGGED, x, y,
                    InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, when + 16L + i * 12L);
            }
            post(target, MouseEvent.MOUSE_RELEASED, end.x, end.y, 0, MouseEvent.BUTTON1, when + 140);
            Point origin = target.getLocationOnScreen();
            return "OK " + target.getClass().getName()
                + " local=" + start.x + "," + start.y + "->" + end.x + "," + end.y
                + " origin=" + origin.x + "," + origin.y
                + " size=" + target.getWidth() + "x" + target.getHeight();
        } catch (Throwable thrown) {
            return "ERR " + thrown.getClass().getSimpleName() + " " + thrown.getMessage();
        }
    }

    private static void post(Component target, int id, int x, int y, int modifiers, int button, long when) {
        MouseEvent event = new MouseEvent(target, id, when, modifiers, x, y, 1, false, button);
        target.dispatchEvent(event);
    }

    private static Component findTarget(int screenX, int screenY) {
        Component best = null;
        int bestArea = Integer.MAX_VALUE;
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) continue;
            List<Component> found = new ArrayList<>();
            collect(window, found);
            for (Component component : found) {
                if (!component.isShowing() || component.getMouseMotionListeners().length == 0) continue;
                Point origin;
                try {
                    origin = component.getLocationOnScreen();
                } catch (Throwable ignored) {
                    continue;
                }
                int width = component.getWidth();
                int height = component.getHeight();
                if (screenX < origin.x || screenY < origin.y
                    || screenX >= origin.x + width || screenY >= origin.y + height) continue;
                int area = width * height;
                if (area > 0 && area < bestArea) {
                    best = component;
                    bestArea = area;
                }
            }
        }
        return best;
    }

    private static void collect(Component component, List<Component> out) {
        out.add(component);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) collect(child, out);
        }
    }
}

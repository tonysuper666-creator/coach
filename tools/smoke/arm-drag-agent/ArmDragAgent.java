package coach.smoke;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
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
 * never appears. This invokes the mouse listeners on the canvas the scene
 * mediator registered. Dispatching through the component lets the input method
 * eat the drag before those listeners run. The whole gesture stays on one EDT
 * turn so a real cursor move cannot split the press from the drag. The agent
 * stays idle unless COACH_DRAG_PORT_FILE is set, and it listens on loopback.
 */
public final class ArmDragAgent {
    private static Component dragTarget;
    private static int dragX0;
    private static int dragY0;
    private static int dragX1;
    private static int dragY1;

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
        SwingUtilities.invokeAndWait(() -> reply[0] = runDrag(x1, y1, x2, y2));
        return reply[0];
    }

    private static String runDrag(int screenX1, int screenY1, int screenX2, int screenY2) {
        String prepared = prepareDrag(screenX1, screenY1, screenX2, screenY2);
        if (prepared.startsWith("ERR")) return prepared;
        StringBuilder errors = new StringBuilder();
        long when = System.currentTimeMillis();
        deliverOnEdt(MouseEvent.MOUSE_MOVED, dragX0, dragY0, 0, MouseEvent.NOBUTTON, when, errors);
        deliverOnEdt(MouseEvent.MOUSE_PRESSED, dragX0, dragY0,
            InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1, when + 16, errors);
        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            int x = dragX0 + (dragX1 - dragX0) * i / steps;
            int y = dragY0 + (dragY1 - dragY0) * i / steps;
            deliverOnEdt(MouseEvent.MOUSE_DRAGGED, x, y,
                InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON, when + 16L + i * 16L, errors);
        }
        deliverOnEdt(MouseEvent.MOUSE_RELEASED, dragX1, dragY1, 0, MouseEvent.BUTTON1, when + 200, errors);
        if (errors.length() > 0) return "ERR " + errors.toString().trim();
        return "OK direct " + prepared;
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

    private static String prepareDrag(int screenX1, int screenY1, int screenX2, int screenY2) {
        Component target = findTarget(screenX1, screenY1);
        if (target == null) return "ERR no-mouse-target";
        try {
            Point start = new Point(screenX1, screenY1);
            Point end = new Point(screenX2, screenY2);
            SwingUtilities.convertPointFromScreen(start, target);
            SwingUtilities.convertPointFromScreen(end, target);
            target.requestFocusInWindow();
            dragTarget = target;
            dragX0 = start.x;
            dragY0 = start.y;
            dragX1 = end.x;
            dragY1 = end.y;
            Point origin = target.getLocationOnScreen();
            StringBuilder names = new StringBuilder();
            for (MouseListener listener : target.getMouseListeners()) {
                if (names.length() > 0) names.append('+');
                names.append(listener.getClass().getName());
            }
            for (MouseMotionListener listener : target.getMouseMotionListeners()) {
                String name = listener.getClass().getName();
                if (names.indexOf(name) >= 0) continue;
                if (names.length() > 0) names.append('+');
                names.append(name);
            }
            return target.getClass().getName()
                + " listeners=" + names
                + " local=" + start.x + "," + start.y + "->" + end.x + "," + end.y
                + " origin=" + origin.x + "," + origin.y
                + " size=" + target.getWidth() + "x" + target.getHeight();
        } catch (Throwable thrown) {
            return "ERR " + thrown.getClass().getSimpleName() + " " + thrown.getMessage();
        }
    }

    // Press must carry BUTTON1 before any dragged event. The scene filter drops a
    // button-down move until a primary press has been seen. Listeners are called
    // directly so a heavyweight canvas cannot swallow the event in dispatchEvent.
    // Caller is already on the EDT. Do not hop again, or a queued cursor move lands
    // between the press and the drag.
    private static void deliverOnEdt(int id, int x, int y, int modifiers, int button, long when, StringBuilder errors) {
        Component target = dragTarget;
        if (target == null) {
            errors.append("no-target ");
            return;
        }
        try {
            Point origin = target.getLocationOnScreen();
            int clicks = (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED) ? 1 : 0;
            MouseEvent event = new MouseEvent(target, id, when, modifiers, x, y,
                origin.x + x, origin.y + y, clicks, false, button);
            if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED) {
                for (MouseMotionListener listener : target.getMouseMotionListeners()) {
                    if (id == MouseEvent.MOUSE_DRAGGED) listener.mouseDragged(event);
                    else listener.mouseMoved(event);
                }
            } else {
                for (MouseListener listener : target.getMouseListeners()) {
                    if (id == MouseEvent.MOUSE_PRESSED) listener.mousePressed(event);
                    else if (id == MouseEvent.MOUSE_RELEASED) listener.mouseReleased(event);
                }
            }
        } catch (Throwable thrown) {
            errors.append(thrown.getClass().getSimpleName()).append(':').append(thrown.getMessage()).append(' ');
        }
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

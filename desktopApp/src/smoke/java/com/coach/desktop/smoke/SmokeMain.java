package com.coach.desktop.smoke;

import androidx.compose.ui.awt.ComposeWindow;
import com.coach.desktop.WindowsTextbooksKt;
import com.coach.desktop.WindowsWindowGeometryKt;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skiko.SkiaLayer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Development-only entry point. This source set is absent from application jars. */
public final class SmokeMain implements AutoCloseable {
    private final String token = UUID.randomUUID().toString();
    private final HttpServer server;
    private final AtomicReference<Continuation<? super Path>> fileChoice = new AtomicReference<>();
    private ComposeWindow window;

    private SmokeMain() throws Exception {
        if (!"1".equals(System.getenv("COACH_SMOKE_ENABLED"))) {
            throw new IllegalStateException("COACH_SMOKE_ENABLED=1 is required");
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "coach-smoke-control");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
    }

    public static void main(String[] args) throws Exception {
        SmokeMain hook = new SmokeMain();
        Path directory = WindowsTextbooksKt.windowsLibraryDirectory(
            System::getenv, System::getProperty, Path.of(System.getProperty("user.home")));
        Path control = Path.of(Objects.requireNonNull(
            System.getenv("COACH_SMOKE_CONTROL_FILE"), "COACH_SMOKE_CONTROL_FILE"));
        Runtime.getRuntime().addShutdownHook(new Thread(hook::close));
        onEdt(() -> {
            hook.window = new ComposeWindow();
            hook.window.setTitle("LearningCoach");
            hook.window.setBounds(WindowsWindowGeometryKt.windowsInitialWindowBounds());
            // Logical Compose input is independent of the shared desktop focus.
            hook.window.setFocusableWindowState(false);
            hook.window.setContent((scope, composer, changed) -> {
                WindowsTextbooksKt.WindowsTextbooksContent(directory, continuation -> {
                    if (!hook.fileChoice.compareAndSet(null, continuation)) {
                        throw new IllegalStateException("A file choice is already pending");
                    }
                    return IntrinsicsKt.getCOROUTINE_SUSPENDED();
                }, composer, 0);
                return Unit.INSTANCE;
            });
            hook.window.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { hook.close(); }
            });
            hook.window.setVisible(true);
            return null;
        });
        hook.server.start();
        Files.createDirectories(control.toAbsolutePath().getParent());
        String config = "{\"url\":\"http://127.0.0.1:" + hook.server.getAddress().getPort()
            + "\",\"token\":\"" + hook.token + "\",\"pid\":" + ProcessHandle.current().pid() + "}";
        Path next = control.resolveSibling(control.getFileName() + ".next");
        Files.writeString(next, config, StandardCharsets.UTF_8);
        Files.move(next, control, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("COACH_SMOKE_READY pid=" + ProcessHandle.current().pid());
    }

    private void handle(HttpExchange exchange) {
        try {
            if (!("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "text/plain", new byte[0]);
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String route = exchange.getRequestURI().getPath();
            if (route.equals("/info")) {
                respond(exchange, 200, "application/json", ("{\"pid\":" + ProcessHandle.current().pid() + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (route.equals("/shot")) {
                respond(exchange, 200, "image/png", onEdt(this::screenshot));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, "text/plain", new byte[0]);
                return;
            }
            byte[] bytes = exchange.getRequestBody().readNBytes(65537);
            if (bytes.length > 65536) { respond(exchange, 413, "text/plain", new byte[0]); return; }
            String body = new String(bytes, StandardCharsets.UTF_8);
            onEdt(() -> {
                switch (route) {
                    case "/click" -> {
                        double x = number(query, "x"), y = number(query, "y");
                        pointer(x, y, MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                        pointer(x, y, MouseEvent.MOUSE_RELEASED, 0, MouseEvent.BUTTON1);
                        pointer(x, y, MouseEvent.MOUSE_CLICKED, 0, MouseEvent.BUTTON1);
                    }
                    case "/drag" -> {
                        double x1 = number(query, "x1"), y1 = number(query, "y1");
                        double x2 = number(query, "x2"), y2 = number(query, "y2");
                        pointer(x1, y1, MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                        pointer((x1+x2)/2, (y1+y2)/2, MouseEvent.MOUSE_DRAGGED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                        pointer(x2, y2, MouseEvent.MOUSE_DRAGGED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                        pointer(x2, y2, MouseEvent.MOUSE_RELEASED, 0, MouseEvent.BUTTON1);
                    }
                    case "/scroll" -> scroll(number(query, "x"), number(query, "y"), (int)number(query, "notches"));
                    case "/keys" -> keys(body);
                    case "/text" -> {
                        for (char value : body.toCharArray()) key(KeyEvent.KEY_TYPED, 0, value, 0);
                    }
                    case "/choose-file" -> {
                        Path file = Path.of(body).toAbsolutePath().normalize();
                        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("File does not exist");
                        Continuation<? super Path> continuation = fileChoice.getAndSet(null);
                        if (continuation == null) throw new NotReady("No pending file choice");
                        continuation.resumeWith(file);
                    }
                    default -> throw new IllegalArgumentException("Unknown operation");
                }
                return null;
            });
            respond(exchange, 200, "text/plain", new byte[0]);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            try { respond(exchange, failure instanceof NotReady ? 503 : 500, "text/plain",
                failure.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
        } finally { exchange.close(); }
    }

    private BufferedImage surface(SkiaLayer layer) throws Exception {
        Bitmap bitmap = layer.screenshot();
        if (bitmap == null) throw new NotReady("Surface is not ready");
        try {
            Image image = Image.Companion.makeFromBitmap(bitmap);
            try {
                Data data = image.encodeToData(EncodedImageFormat.PNG, 100, 0);
                if (data == null) throw new NotReady("Surface encoding failed");
                try { return ImageIO.read(new java.io.ByteArrayInputStream(data.getBytes())); }
                finally { data.close(); }
            } finally { image.close(); }
        } finally { bitmap.close(); }
    }

    private byte[] screenshot() throws Exception {
        window.renderImmediately();
        double scale = scale();
        BufferedImage output = new BufferedImage((int)Math.ceil(window.getWidth()*scale),
            (int)Math.ceil(window.getHeight()*scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, output.getWidth(), output.getHeight());
            Point origin = window.getLocationOnScreen();
            for (Surface surface : activeSurfaces()) {
                SkiaLayer layer = surface.layer;
                if (!layer.isShowing()) continue;
                Point point = layer.getCanvas().getLocationOnScreen();
                BufferedImage pixels = surface(layer);
                graphics.drawImage(pixels, (int)Math.round((point.x-origin.x)*scale),
                    (int)Math.round((point.y-origin.y)*scale), null);
            }
        } finally { graphics.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(output, "png", bytes);
        return bytes.toByteArray();
    }

    private record Surface(SkiaLayer layer, Object mediator, boolean focusable) { }

    private List<Surface> activeSurfaces() throws Exception {
        Object panel = field(window, "composePanel");
        Method getter = panel.getClass().getDeclaredMethod("getComposeContainer");
        getter.setAccessible(true);
        Object container = getter.invoke(panel);
        List<Surface> result = new ArrayList<>();
        addSurfaces(result, field(container, "mediator"), true);
        // Compose dialogs/popups have independent mediators even when they are
        // not separate top-level ComposeWindows. Route into the visible layer.
        for (Object layer : (List<?>)field(container, "layers")) {
            Method method = layer.getClass().getDeclaredMethod("getMediator");
            method.setAccessible(true);
            Object mediator = method.invoke(layer);
            boolean focusable = (boolean)layer.getClass().getMethod("getFocusable").invoke(layer);
            addSurfaces(result, mediator, focusable);
        }
        return result;
    }

    private static void addSurfaces(List<Surface> result, Object mediator, boolean focusable) throws Exception {
        Component component = (Component)mediator.getClass().getMethod("getContentComponent").invoke(mediator);
        for (SkiaLayer layer : layers(component)) {
            if (layer.isShowing()) result.add(new Surface(layer, mediator, focusable));
        }
    }

    private static List<SkiaLayer> layers(Component component) {
        List<SkiaLayer> result = new ArrayList<>();
        if (component instanceof SkiaLayer layer) result.add(layer);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) result.addAll(layers(child));
        }
        return result;
    }

    private double scale() { return window.getGraphicsConfiguration().getDefaultTransform().getScaleX(); }

    private record Target(SkiaLayer layer, Object mediator, int x, int y) { }

    private Target target(double x, double y) throws Exception {
        Point origin = window.getLocationOnScreen();
        List<Surface> surfaces = activeSurfaces();
        Collections.reverse(surfaces);
        for (Surface surface : surfaces) {
            SkiaLayer layer = surface.layer;
            Point point = layer.getCanvas().getLocationOnScreen();
            int localX = (int)Math.round(x/scale() + origin.x-point.x);
            int localY = (int)Math.round(y/scale() + origin.y-point.y);
            if (localX >= 0 && localY >= 0 && localX < layer.getCanvas().getWidth() && localY < layer.getCanvas().getHeight()) {
                return new Target(layer, surface.mediator, localX, localY);
            }
        }
        throw new IllegalArgumentException("Point is outside the owned Compose surfaces");
    }

    private Target lastTarget;

    private void pointer(double x, double y, int type, int modifiers, int button) throws Exception {
        Target target = target(x, y);
        lastTarget = target;
        takeLogicalFocus(target.mediator);
        MouseEvent event = new MouseEvent(target.layer.getCanvas(), type, System.currentTimeMillis(),
            modifiers, target.x, target.y, 1, false, button);
        invoke(target.mediator, "onMouseEvent", MouseEvent.class, event);
    }

    private void scroll(double x, double y, int notches) throws Exception {
        Target target = target(x, y);
        lastTarget = target;
        MouseWheelEvent event = new MouseWheelEvent(target.layer.getCanvas(), MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(), 0, target.x, target.y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -notches);
        invoke(target.mediator, "onMouseWheelEvent", MouseWheelEvent.class, event);
    }

    private void takeLogicalFocus(Object mediator) throws Exception {
        Object manager = mediator.getClass().getMethod("getFocusManager").invoke(mediator);
        if ((boolean)manager.getClass().getMethod("getHasFocus").invoke(manager)) return;
        Object companion = Class.forName("androidx.compose.ui.focus.FocusDirection").getField("Companion").get(null);
        Method enter = Arrays.stream(companion.getClass().getMethods()).filter(m -> m.getName().startsWith("getEnter-")).findFirst().orElseThrow();
        Method take = Arrays.stream(manager.getClass().getMethods()).filter(m -> m.getName().startsWith("takeFocus-")).findFirst().orElseThrow();
        take.invoke(manager, enter.invoke(companion));
    }

    private void key(int type, int code, char value, int modifiers) throws Exception {
        Target target = null;
        List<Surface> surfaces = activeSurfaces();
        Collections.reverse(surfaces);
        for (Surface surface : surfaces) {
            if (surface.focusable) {
                target = lastTarget != null && lastTarget.mediator == surface.mediator
                    ? lastTarget : new Target(surface.layer, surface.mediator, 0, 0);
                break;
            }
        }
        if (target == null) throw new NotReady("No keyboard input surface");
        takeLogicalFocus(target.mediator);
        KeyEvent event = new KeyEvent(target.layer.getCanvas(), type, System.currentTimeMillis(), modifiers, code, value);
        invoke(target.mediator, "onKeyEvent", KeyEvent.class, event);
    }

    private void keys(String keys) throws Exception {
        if (keys.equals("^a") || keys.equals("^A")) {
            key(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            key(KeyEvent.KEY_RELEASED, KeyEvent.VK_A, KeyEvent.CHAR_UNDEFINED, InputEvent.CTRL_DOWN_MASK);
            return;
        }
        Map<String, Integer> names = Map.ofEntries(
            Map.entry("{ESC}", KeyEvent.VK_ESCAPE), Map.entry("{ENTER}", KeyEvent.VK_ENTER),
            Map.entry("{TAB}", KeyEvent.VK_TAB), Map.entry("{HOME}", KeyEvent.VK_HOME),
            Map.entry("{END}", KeyEvent.VK_END), Map.entry("{LEFT}", KeyEvent.VK_LEFT),
            Map.entry("{RIGHT}", KeyEvent.VK_RIGHT), Map.entry("{UP}", KeyEvent.VK_UP),
            Map.entry("{DOWN}", KeyEvent.VK_DOWN), Map.entry("{BACKSPACE}", KeyEvent.VK_BACK_SPACE),
            Map.entry("{DELETE}", KeyEvent.VK_DELETE), Map.entry("{SPACE}", KeyEvent.VK_SPACE));
        Integer code = names.get(keys.toUpperCase(Locale.ROOT));
        if (code != null) {
            key(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, 0);
            key(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, 0);
        } else {
            if (keys.contains("{") || keys.contains("^") || keys.contains("%")) throw new IllegalArgumentException("Unsupported key sequence");
            for (char value : keys.toCharArray()) key(KeyEvent.KEY_TYPED, 0, value, 0);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    private static void invoke(Object owner, String name, Class<?> type, Object value) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(owner, value);
    }
    private static double number(Map<String,String> values, String name) { return Double.parseDouble(Objects.requireNonNull(values.get(name), name)); }
    private static Map<String,String> query(String query) {
        Map<String,String> result = new HashMap<>();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task);
        try { return task.get(15, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
    private static void respond(HttpExchange exchange, int code, String type, byte[] bytes) throws Exception {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); }
    private static final class NotReady extends Exception { private NotReady(String message) { super(message); } }
}
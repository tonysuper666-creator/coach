import androidx.compose.ui.ImageComposeScene;
import androidx.compose.ui.awt.ComposeWindow;
import androidx.compose.ui.geometry.OffsetKt;
import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.input.pointer.PointerEventType;
import androidx.compose.ui.platform.UriHandler;
import androidx.compose.ui.semantics.SemanticsActions;
import androidx.compose.ui.semantics.SemanticsNode;
import androidx.compose.ui.semantics.SemanticsOwner;
import androidx.compose.ui.semantics.SemanticsProperties;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.unit.DensityKt;
import androidx.compose.ui.unit.LayoutDirection;
import com.coach.desktop.WindowsTextbookHostKt;
import com.coach.desktop.books.BookImportControl;
import com.coach.desktop.books.Textbook;
import com.coach.desktop.books.TextbookController;
import com.coach.desktop.books.TextbookLibrary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.flow.StateFlow;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ColorSpace;
import org.jetbrains.skia.ColorType;
import org.jetbrains.skia.Data;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;
import org.jetbrains.skiko.SkiaLayer;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-version real-window and ImageComposeScene acceptance harness (TON-1785).
 * Compiles cleanly against 1.1.1 (98d6cd22), proto/v2-reader (67609d66), and main.
 * Runs non-focus-stealing Win32 ComposeWindows (TON-1690 compatible).
 *
 * Usage:
 *   ReaderAcceptanceHarness measure <flavor:111|v2|main> <libDir> <outDir> <surface:scene|win32> [page]
 *   ReaderAcceptanceHarness bugs    <flavor:111|v2|main> <libDir> <outDir>
 *   ReaderAcceptanceHarness login   <bookPdf> <outDir>
 * Login on a runtime without WindowsTextbookGate prints 不适用 and exits 0.
 * -Dcoach.acceptance.breakImport=true feeds login_0 a missing file; that check must fail.
 */
public final class ReaderAcceptanceHarness {
    private static ComposeWindow activeWindow;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Missing subcommand: measure | bugs | login");
        String cmd = args[0];
        try {
            switch (cmd) {
                case "measure" -> runMeasure(args);
                case "bugs" -> runBugs(args);
                case "login" -> runLogin(args);
                case "modes" -> runModes(args);
                case "newcomer" -> runNewcomer(args);
                default -> throw new IllegalArgumentException("Unknown subcommand: " + cmd);
            }
        } finally {
            if (activeWindow != null) {
                ComposeWindow w = activeWindow;
                activeWindow = null;
                try { SwingUtilities.invokeAndWait(w::dispose); } catch (Exception ignored) {}
            }
        }
        System.exit(0);
    }

    // ==================== 1. MEASURE ====================

    private static void runMeasure(String[] args) throws Exception {
        String flavor = args[1]; // "111" | "v2" | "main"
        Path libSeed = Path.of(args[2]);
        Path outDir = Path.of(args[3]);
        String surface = args[4]; // "scene" | "win32"
        int targetPage = args.length > 5 ? Integer.parseInt(args[5]) : 34;
        Files.createDirectories(outDir);

        if ("scene".equals(surface)) {
            int[][] profiles = { {1920, 1080, 100}, {2880, 1800, 150} };
            captureMeasureStatesSceneAll(flavor, libSeed, outDir, profiles, targetPage);
        } else if ("win32".equals(surface)) {
            boolean customWindow = args.length >= 8;
            int logicalW = customWindow ? Integer.parseInt(args[6]) : 752;
            int logicalH = customWindow ? Integer.parseInt(args[7]) : 552;
            String resTag = args.length >= 9 ? args[8] : "win32";
            captureMeasureStatesWin32(flavor, libSeed, outDir, targetPage, logicalW, logicalH, resTag, !customWindow);
        } else {
            throw new IllegalArgumentException("Unknown surface: " + surface);
        }
    }

    private static boolean isV2Flavor(String flavor) {
        return "v2".equals(flavor) || "main".equals(flavor);
    }

    private static void captureMeasureStatesSceneAll(
        String flavor, Path libSeed, Path outDir, int[][] profiles, int targetPage
    ) throws Exception {
        // State 1 + 3 + 4 (Original PDF + Companion + TOC) reusing one loaded library/controller
        Path libOrig = copyTempDir(libSeed, "m-orig-");
        try {
            TextbookController cOrig = openReaderAt(libOrig, targetPage, false);
            for (int[] p : profiles) {
                int w = p[0], h = p[1];
                float density = p[2] / 100.0f;
                String resTag = w + "x" + h;
                ImageComposeScene scene = newScene(w, h, density, cOrig);
                try {
                    long nano = pumpSceneUntilSettled(scene, 0L, 22, 45, 120,
                        () -> !hasTextContainingScene(scene, "正在加载页面"));
                    if (isV2Flavor(flavor)) {
                        nano = dismissV2HudIfVisibleScene(scene, nano);
                        nano = pumpSceneUntilSettled(scene, nano, 5, 45, 50,
                            () -> !hasTextContainingScene(scene, "正在加载页面"));
                    }
                    dumpScene(scene, nano, outDir, flavor, resTag, "pure_read_original");

                    if (isV2Flavor(flavor)) {
                        nano = revealV2HudScene(scene, nano, w);
                        clickByTagScene(scene, "immersive-companion-toggle");
                        nano = pumpScene(scene, nano, 25, 30);
                        dumpScene(scene, nano, outDir, flavor, resTag, "companion_initial");

                        if (onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), "companion-dock-toggle")) != null) {
                            String dockLabel = onEdt(() -> nodeText(findNodeByTag(scene.getSemanticsOwners(), "companion-dock-toggle")));
                            if (dockLabel.contains("并排")) {
                                clickByTagScene(scene, "companion-dock-toggle");
                                nano = pumpScene(scene, nano, 25, 30);
                            }
                        }
                        dumpScene(scene, nano, outDir, flavor, resTag, "wide_split_companion");

                        clickByTagScene(scene, "companion-close");
                        nano = pumpScene(scene, nano, 20, 25);
                        nano = revealV2HudScene(scene, nano, w);
                        clickByTagScene(scene, "v7-directory-toggle");
                        nano = pumpSceneUntilSettled(scene, nano, 20, 40, 30,
                            () -> onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), "v7-directory-drawer") != null));
                        dumpScene(scene, nano, outDir, flavor, resTag, "toc_expanded");
                        clickByTagScene(scene, "toc-drawer-close");
                        nano = pumpScene(scene, nano, 15, 20);
                    } else {
                        nano = pumpSceneUntilSettled(scene, nano, 0, 50, 100, () -> onEdt(() -> {
                            SemanticsNode btn = findNodeByTag(scene.getSemanticsOwners(), "v7-directory-toggle");
                            return btn != null && isNodeEnabled(btn);
                        }));
                        clickByTagScene(scene, "v7-directory-toggle");
                        nano = pumpSceneUntilSettled(scene, nano, 25, 45, 30,
                            () -> onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), "v7-directory-drawer") != null));
                        nano = pumpScene(scene, nano, 15, 25);
                        dumpScene(scene, nano, outDir, flavor, resTag, "toc_expanded");
                    }
                } finally {
                    onEdt(() -> { scene.close(); return null; });
                }
            }
        } finally {
            deleteQuietly(libOrig);
        }

        // State 2: pure_read_text
        Path libText = copyTempDir(libSeed, "m-text-");
        try {
            TextbookController cText = openReaderAt(libText, targetPage, true);
            for (int[] p : profiles) {
                int w = p[0], h = p[1];
                float density = p[2] / 100.0f;
                String resTag = w + "x" + h;
                ImageComposeScene scene = newScene(w, h, density, cText);
                try {
                    long nano = pumpSceneUntilSettled(scene, 0L, 30, 50, 120,
                        () -> !hasTextContainingScene(scene, "正在加载")
                            && !hasTextContainingScene(scene, "正在排版")
                            && onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), "book-page-loading") == null));
                    if (isV2Flavor(flavor)) {
                        nano = dismissV2HudIfVisibleScene(scene, nano);
                        nano = pumpSceneUntilSettled(scene, nano, 8, 60, 50,
                            () -> !hasTextContainingScene(scene, "正在加载")
                                && !hasTextContainingScene(scene, "正在排版")
                                && onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), "book-page-loading") == null));
                    }
                    dumpScene(scene, nano, outDir, flavor, resTag, "pure_read_text");
                } finally {
                    onEdt(() -> { scene.close(); return null; });
                }
            }
        } finally {
            deleteQuietly(libText);
        }

        // Legacy 1.1.1 guided screen (removed in 2.0)
        if ("111".equals(flavor)) {
            Path libGuided = copyTempDir(libSeed, "m-guided-");
            try {
                TextbookController cGuided = openGuided(libGuided);
                for (int[] p : profiles) {
                    int w = p[0], h = p[1];
                    float density = p[2] / 100.0f;
                    String resTag = w + "x" + h;
                    ImageComposeScene scene = newScene(w, h, density, cGuided);
                    try {
                        long nano = pumpScene(scene, 0L, 20, 80);
                        dumpScene(scene, nano, outDir, flavor, resTag, "guided");
                    } finally {
                        onEdt(() -> { scene.close(); return null; });
                    }
                }
            } finally {
                deleteQuietly(libGuided);
            }
        }
    }

    private static void captureMeasureStatesWin32(
        String flavor, Path libSeed, Path outDir, int targetPage, int logicalW, int logicalH, String resTag, boolean includeOverlays
    ) throws Exception {
        // Real OS window at requested logical dimensions (default 752x552 = 1128x828 @ 150%)
        Path libOrig = copyTempDir(libSeed, "w-orig-");
        try {
            TextbookController c = openReaderAt(libOrig, targetPage, false);
            ComposeWindow win = openHostWindow(c, 24, 24, logicalW, logicalH);
            try {
                Thread.sleep(2800);
                waitFor(6000, () -> !hasTextContaining(win, "正在加载页面"));
                if (isV2Flavor(flavor)) {
                    dismissV2HudIfVisibleWin32(win);
                    waitFor(6000, () -> !hasTextContaining(win, "正在加载页面"));
                }
                dumpWin32(win, outDir, flavor, resTag, "pure_read_original");

                if (includeOverlays) {
                    if (isV2Flavor(flavor)) {
                        revealV2HudWin32(win);
                        clickTagWin32(win, "immersive-companion-toggle");
                        Thread.sleep(700);
                        dumpWin32(win, outDir, flavor, resTag, "companion_initial");
                        clickTagWin32(win, "companion-close");
                        Thread.sleep(400);
                        revealV2HudWin32(win);
                        clickTagWin32(win, "v7-directory-toggle");
                        waitFor(4000, () -> findNodeByTag(win, "v7-directory-drawer") != null);
                        Thread.sleep(300);
                        dumpWin32(win, outDir, flavor, resTag, "toc_expanded");
                    } else {
                        waitFor(5000, () -> isNodeEnabled(findNodeByTag(win, "v7-directory-toggle")));
                        clickTagWin32(win, "v7-directory-toggle");
                        waitFor(4000, () -> findNodeByTag(win, "v7-directory-drawer") != null);
                        Thread.sleep(400);
                        dumpWin32(win, outDir, flavor, resTag, "toc_expanded");
                    }
                }
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(libOrig);
        }

        Path libText = copyTempDir(libSeed, "w-text-");
        try {
            TextbookController c = openReaderAt(libText, targetPage, true);
            ComposeWindow win = openHostWindow(c, 24, 24, logicalW, logicalH);
            try {
                Thread.sleep(3600);
                waitFor(6000, () -> !hasTextContaining(win, "正在加载")
                    && !hasTextContaining(win, "正在排版")
                    && findNodeByTag(win, "book-page-loading") == null);
                if (isV2Flavor(flavor)) {
                    dismissV2HudIfVisibleWin32(win);
                    waitFor(6000, () -> !hasTextContaining(win, "正在加载")
                        && !hasTextContaining(win, "正在排版")
                        && findNodeByTag(win, "book-page-loading") == null);
                }
                dumpWin32(win, outDir, flavor, resTag, "pure_read_text");
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(libText);
        }

        if (includeOverlays && "111".equals(flavor)) {
            Path libGuided = copyTempDir(libSeed, "w-guided-");
            try {
                TextbookController c = openGuided(libGuided);
                ComposeWindow win = openHostWindow(c, 24, 24, logicalW, logicalH);
                try {
                    Thread.sleep(2000);
                    dumpWin32(win, outDir, flavor, resTag, "guided");
                } finally {
                    disposeWindow(win);
                }
            } finally {
                deleteQuietly(libGuided);
            }
        }
    }

    // ==================== 2. 5 BLOCKING BUGS REGRESSION (REAL WIN32 WINDOW) ====================

    private static void runBugs(String[] args) throws Exception {
        String flavor = args[1]; // "111" | "v2" | "main"
        Path libSeed = Path.of(args[2]);
        Path outDir = Path.of(args[3]);
        Files.createDirectories(outDir);

        List<Map<String, Object>> results = new ArrayList<>();
        results.add(testBug1PageJumpSync(flavor, libSeed, outDir));
        results.add(testBug2ViewSwitchNoBounce(flavor, libSeed, outDir));
        results.add(testBug3TocDrawerUsable(flavor, libSeed, outDir));
        results.add(testBug4AskPassageNoScroll(flavor, libSeed, outDir));
        results.add(testBug5RelaunchNoFlashAndResume(flavor, libSeed, outDir));

        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        Map<String, Object> report = new HashMap<>();
        report.put("flavor", flavor);
        report.put("surface_type", "real_os_window");
        report.put("passed", passed);
        report.put("total", results.size());
        report.put("checks", results);

        Files.writeString(outDir.resolve("bugs-report.json"), toJson(report, 0), StandardCharsets.UTF_8);
        System.out.println("BUGS_REPORT flavor=" + flavor + " passed=" + passed + "/" + results.size());
        for (var r : results) {
            System.out.println((Boolean.TRUE.equals(r.get("passed")) ? "PASS " : "FAIL ") + r.get("id") + ": " + r.get("detail"));
        }
    }

    private static boolean hasSemanticsAction(SemanticsNode node, String actionKey) {
        return node != null && findSemanticsActionFn(node, actionKey) != null;
    }

    private static boolean isNodeEnabled(SemanticsNode node) {
        if (node == null) return false;
        SemanticsNode cur = node;
        while (cur != null) {
            for (var e : cur.getConfig()) {
                if ("Disabled".equals(e.getKey().getName())) return false;
            }
            cur = cur.getParent();
        }
        return true;
    }

    private static String nodeEditableOrText(SemanticsNode node) {
        if (node == null) return "";
        for (var e : node.getConfig()) {
            if ("EditableText".equals(e.getKey().getName()) && e.getValue() instanceof AnnotatedString as) {
                return as.getText();
            }
        }
        return ownText(node);
    }

    private static String readBug1PageNum(ComposeWindow win, String flavor) throws Exception {
        if (isV2Flavor(flavor)) revealV2HudWin32(win);
        SemanticsNode numNode = findNodeByTag(win, "reader-page-number");
        return ownTextOrChild(numNode);
    }

    private static final class JumpMetrics {
        long tPdfMs = -1;
        long tSyncMs = -1;
        long lagMs = -1;
        boolean synced = false;
        String pageNum = "";
        String title = "";
    }

    private static JumpMetrics measureJumpSync(
        ComposeWindow win, TextbookController c, String flavor, int target, String prevTitle, long timeoutMs
    ) throws Exception {
        JumpMetrics m = new JumpMetrics();
        String targetStr = String.valueOf(target);
        String pageTag = "original-pdf-page-" + target;
        long t0 = System.currentTimeMillis();
        long deadline = t0 + timeoutMs;
        int syncStable = 0;
        String lastKey = "";
        while (System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis() - t0;
            if (m.tPdfMs < 0 && findNodeByTag(win, pageTag) != null) {
                m.tPdfMs = now;
            }
            int cp = controllerPage(c);
            String num = readBug1PageNum(win, flavor);
            String top = topHeaderText(win);
            m.pageNum = num;
            m.title = top;
            boolean barOk = cp == target && num.contains(targetStr);
            boolean titleOk = !top.isEmpty() && (prevTitle == null || !top.equals(prevTitle));
            if (barOk && titleOk) {
                if (m.tSyncMs < 0) m.tSyncMs = now;
                String key = cp + "|" + num + "|" + top;
                if (key.equals(lastKey)) syncStable++;
                else {
                    lastKey = key;
                    syncStable = 1;
                }
                if (m.tPdfMs >= 0 && syncStable >= 2) {
                    m.synced = true;
                    break;
                }
            } else {
                syncStable = 0;
            }
            Thread.sleep(60);
        }
        if (m.tPdfMs >= 0 && m.tSyncMs >= 0) {
            m.lagMs = Math.max(0L, m.tSyncMs - m.tPdfMs);
        }
        return m;
    }

    private static int waitUntilControllerPageSettled(
        TextbookController c, int preferredTarget, long timeoutMs
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = -1;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int cur = controllerPage(c);
            if (cur == last) {
                stable++;
            } else {
                last = cur;
                stable = 1;
            }
            if (preferredTarget > 0 && cur == preferredTarget && stable >= 3) {
                return cur;
            }
            if (preferredTarget <= 0 && cur > 0 && stable >= 3) {
                return cur;
            }
            Thread.sleep(200);
        }
        return controllerPage(c);
    }

    private static void uiJumpToPage(ComposeWindow win, String flavor, int target) throws Exception {
        String targetStr = String.valueOf(target);
        if (isV2Flavor(flavor)) {
            waitFor(3000, () -> {
                revealV2HudWin32(win);
                SemanticsNode existing = findNodeByTag(win, "page-jump");
                if (existing != null && onEdt(() -> hasSemanticsAction(existing, "SetText"))) return true;
                SemanticsNode pill = findNodeByTag(win, "reader-page-number");
                if (pill != null) {
                    clickNodeWin32(win, pill);
                    return false;
                }
                sendKeysWin32(win, KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK);
                return false;
            });
        } else {
            waitFor(3000, () -> {
                SemanticsNode f = findNodeByTag(win, "page-jump");
                return f != null && onEdt(() -> hasSemanticsAction(f, "SetText"));
            });
        }
        SemanticsNode jumpField = findNodeByTag(win, "page-jump");
        if (jumpField != null) {
            waitFor(1500, () -> {
                SemanticsNode f = findNodeByTag(win, "page-jump");
                if (f == null) return false;
                boolean ok = onEdt(() -> invokeSetText(f, targetStr));
                if (!ok) {
                    clickNodeWin32(win, f);
                    sendCtrlAWin32(win);
                    for (int i = 0; i < 6; i++) sendKeysWin32(win, KeyEvent.VK_BACK_SPACE, 0);
                    sendTextWin32(win, targetStr);
                }
                SemanticsNode after = findNodeByTag(win, "page-jump");
                return targetStr.equals(onEdt(() -> nodeEditableOrText(after)));
            });
            SemanticsNode latestField = findNodeByTag(win, "page-jump");
            clickOrEnterJump(win, flavor, latestField != null ? latestField : jumpField);
            if (isV2Flavor(flavor)) {
                waitFor(2000, () -> findNodeByTag(win, "page-jump") == null);
            }
        }
    }

    private static Map<String, Object> testBug1PageJumpSync(String flavor, Path libSeed, Path outDir) throws Exception {
        Path lib = copyTempDir(libSeed, "bug1-");
        try {
            TextbookController c = openReaderAt(lib, 1, false);
            ComposeWindow win = openHostWindow(c, 24, 24, 752, 552);
            try {
                waitUntilControllerPageSettled(c, 1, 5000);
                String initialTitle = topHeaderText(win);
                uiJumpToPage(win, flavor, 32);
                JumpMetrics m32 = measureJumpSync(win, c, flavor, 32, initialTitle, 7000);

                uiJumpToPage(win, flavor, 173);
                JumpMetrics m173 = measureJumpSync(win, c, flavor, 173, m32.title, 7000);
                if (isV2Flavor(flavor)) revealV2HudWin32(win);
                dumpWin32(win, outDir, flavor, "win32", "bug1-jump-173");

                boolean page32Ok = m32.synced && m32.pageNum.contains("32");
                boolean page173Ok = m173.synced && m173.pageNum.contains("173");
                boolean titleUpdated = !m173.title.isEmpty() && !m173.title.equals(m32.title) && !m173.title.contains("Why health economics");
                boolean lagOk = m32.lagMs >= 0 && m32.lagMs <= 1000 && m173.lagMs >= 0 && m173.lagMs <= 1000;
                boolean passed = page32Ok && page173Ok && titleUpdated && lagOk;
                return checkResult("bug1_page_jump_sync", passed,
                    "after32={pageNum='" + m32.pageNum + "', lagMs=" + m32.lagMs + ", title='" + m32.title
                    + "'} after173={pageNum='" + m173.pageNum + "', lagMs=" + m173.lagMs + ", title='" + m173.title + "'}");
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(lib);
        }
    }

    private static boolean waitForModeSettled(ComposeWindow win, boolean expectText) throws Exception {
        return waitFor(10000, () -> {
            if (expectText) {
                return findNodeByTag(win, "book-page-loading") == null && findNodeByTag(win, "reader-text") != null;
            } else {
                return findNodeByTag(win, "reader-text") == null
                    && (findNodeByTag(win, "original-pdf-scroll") != null || findNodeByTag(win, "original-pdf-paged") != null);
            }
        });
    }

    private static Map<String, Object> testBug2ViewSwitchNoBounce(String flavor, Path libSeed, Path outDir) throws Exception {
        Path lib = copyTempDir(libSeed, "bug2-");
        try {
            TextbookController c = openReaderAt(lib, 32, false);
            ComposeWindow win = openHostWindow(c, 24, 24, 752, 552);
            try {
                waitUntilControllerPageSettled(c, 32, 8000);
                // Jump 32 -> 173 via UI, wait for original-pdf-page-173 to appear, then switch 5x waiting for stability per toggle
                uiJumpToPage(win, flavor, 173);
                waitFor(6000, () -> findNodeByTag(win, "original-pdf-page-173") != null);
                int prePage = controllerPage(c);
                List<Integer> observed = new ArrayList<>();
                boolean allNear173 = (prePage >= 171 && prePage <= 175);
                for (int i = 0; i < 5; i++) {
                    boolean switchingToText = (i % 2 == 0);
                    if (isV2Flavor(flavor)) revealV2HudWin32(win);
                    SemanticsNode toggle = null;
                    if (isV2Flavor(flavor)) {
                        String wantTag = switchingToText ? "view-mode-text" : "view-mode-original";
                        waitFor(2500, () -> {
                            revealV2HudWin32(win);
                            return findNodeByTag(win, wantTag) != null;
                        });
                        toggle = findNodeByTag(win, wantTag);
                    } else {
                        waitFor(6000, () -> {
                            SemanticsNode n = findNodeByTag(win, "book-original-toggle");
                            return n != null && onEdt(() -> isNodeEnabled(n));
                        });
                        toggle = findNodeByTag(win, "book-original-toggle");
                    }
                    if (toggle == null) {
                        allNear173 = false;
                        break;
                    }
                    clickNodeWin32(win, toggle);
                    waitForModeSettled(win, switchingToText);
                    int p = waitUntilControllerPageSettled(c, -1, 5000);
                    observed.add(p);
                    if (p < 171 || p > 175) {
                        allNear173 = false;
                    }
                }
                dumpWin32(win, outDir, flavor, "win32", "bug2-after-5-switches");
                return checkResult("bug2_view_switch_no_bounce", allNear173 && observed.size() == 5,
                    "pre=" + prePage + " pages_after_5_toggles=" + observed);
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(lib);
        }
    }

    private static Map<String, Object> testBug3TocDrawerUsable(String flavor, Path libSeed, Path outDir) throws Exception {
        Path lib = copyTempDir(libSeed, "bug3-");
        try {
            TextbookController c = "111".equals(flavor) ? openGuided(lib) : openReaderAt(lib, 34, false);
            ComposeWindow win = openHostWindow(c, 24, 24, 752, 552);
            try {
                Thread.sleep(1800);
                if (isV2Flavor(flavor)) revealV2HudWin32(win);
                clickTagWin32(win, "v7-directory-toggle");
                Thread.sleep(800);
                dumpWin32(win, outDir, flavor, "win32", "bug3-toc-drawer");

                SemanticsNode drawer = findNodeByTag(win, "v7-directory-drawer");
                if (drawer == null) drawer = findNodeByTag(win, "immersive-toc-drawer");
                Rect drawerRect = drawer != null ? drawer.getBoundsInRoot() : null;
                boolean drawerSized = drawerRect != null && (drawerRect.getRight() - drawerRect.getLeft()) >= 150
                    && (drawerRect.getBottom() - drawerRect.getTop()) >= 120;

                List<SemanticsNode> items = new ArrayList<>();
                if (drawer != null) {
                    collectDrawerItems(drawer, 552f, items);
                }
                boolean jumped = false;
                int beforePage = controllerPage(c);
                if (items.size() >= 3) {
                    // Pick an item further down (e.g. index 2 or 3) and click it
                    SemanticsNode target = items.get(Math.min(items.size() - 1, 3));
                    clickNodeWin32(win, target);
                    Thread.sleep(1000);
                    jumped = controllerPage(c) != beforePage;
                }
                boolean passed = drawerSized && items.size() >= 3 && jumped;
                return checkResult("bug3_toc_drawer_usable", passed,
                    "drawerSized=" + drawerSized + " visibleItems=" + items.size() + " jumped=" + jumped + " beforePage=" + beforePage + " afterPage=" + controllerPage(c));
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(lib);
        }
    }

    private static Map<String, Object> testBug4AskPassageNoScroll(String flavor, Path libSeed, Path outDir) throws Exception {
        Path lib = copyTempDir(libSeed, "bug4-");
        try {
            TextbookController c = openReaderAt(lib, 32, true);
            ComposeWindow win = openHostWindow(c, 24, 24, 752, 552);
            try {
                Thread.sleep(3200);
                boolean inputVisibleNoScroll = false;
                boolean sendVisibleNoScroll = false;
                String detail;
                float clientW = 736f, clientH = 513f;
                if (isV2Flavor(flavor)) {
                    revealV2HudWin32(win);
                    if (findNodeByTag(win, "immersive-companion-toggle") != null) {
                        clickTagWin32(win, "immersive-companion-toggle");
                    } else {
                        sendKeysWin32(win, KeyEvent.VK_L, 0);
                    }
                    waitFor(3000, () -> findNodeByTag(win, "companion-tab-ask") != null);
                    if (findNodeByTag(win, "companion-tab-ask") != null) {
                        clickTagWin32(win, "companion-tab-ask");
                    }
                    waitFor(2000, () -> findNodeByTag(win, "inline-followup-input") != null);
                    Thread.sleep(550);
                    SemanticsNode input = findNodeByTag(win, "inline-followup-input");
                    SemanticsNode send = findNodeByTag(win, "inline-followup-send");
                    inputVisibleNoScroll = isFullyInWindow(input, clientW, clientH);
                    sendVisibleNoScroll = isFullyInWindow(send, clientW, clientH);
                    detail = "inputInWin=" + inputVisibleNoScroll + " sendInWin=" + sendVisibleNoScroll
                        + " inputBounds=" + boundsStr(input) + " sendBounds=" + boundsStr(send);
                } else {
                    // 1.1.1: open 选段提问 picker and pick ask-paragraph-0
                    clickTagWin32(win, "ask-book-passage");
                    waitFor(2000, () -> findNodeByTag(win, "ask-paragraph-0") != null);
                    if (findNodeByTag(win, "ask-paragraph-0") != null) {
                        clickTagWin32(win, "ask-paragraph-0");
                        Thread.sleep(500);
                    }
                    SemanticsNode input = findNodeByTextOrTag(win, "inline-followup-input", "想问什么");
                    SemanticsNode send = findNodeByTextOrTag(win, "inline-followup-send", "发送");
                    inputVisibleNoScroll = isFullyInWindow(input, clientW, clientH);
                    sendVisibleNoScroll = isFullyInWindow(send, clientW, clientH);
                    detail = "inputInWin=" + inputVisibleNoScroll + " sendInWin=" + sendVisibleNoScroll
                        + " inputBounds=" + boundsStr(input) + " sendBounds=" + boundsStr(send);
                }
                dumpWin32(win, outDir, flavor, "win32", "bug4-ask-passage");
                return checkResult("bug4_ask_passage_no_scroll", inputVisibleNoScroll && sendVisibleNoScroll, detail);
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(lib);
        }
    }

    private static Map<String, Object> testBug5RelaunchNoFlashAndResume(String flavor, Path libSeed, Path outDir) throws Exception {
        Path lib = copyTempDir(libSeed, "bug5-");
        try {
            // Session 1: open reader at page 173, persist, close window
            TextbookController c1 = openReaderAt(lib, 173, false);
            ComposeWindow w1 = openHostWindow(c1, 24, 24, 752, 552);
            Thread.sleep(1800);
            disposeWindow(w1);

            // Session 2: relaunch with fresh library + controller and sample startup frames
            TextbookLibrary lib2 = createLibrary(lib);
            TextbookController c2 = createController(lib2);
            runSuspend(cont -> c2.load(cont));
            ComposeWindow w2 = openHostWindow(c2, 24, 24, 752, 552);
            boolean sawEmptyFlash = false;
            boolean resumedReader = false;
            try {
                long deadline = System.currentTimeMillis() + 4000L;
                while (System.currentTimeMillis() < deadline) {
                    if (hasTextContaining(w2, "书架还是空的")) sawEmptyFlash = true;
                    if (findNodeByTag(w2, "textbook-reader") != null) {
                        int p = controllerPage(c2);
                        if (p >= 171 && p <= 175) resumedReader = true;
                    }
                    if (resumedReader && System.currentTimeMillis() + 2500L < deadline) {
                        // keep polling a bit to ensure no flash
                    }
                    Thread.sleep(60);
                }
                dumpWin32(w2, outDir, flavor, "win32", "bug5-relaunch");
                boolean passed = !sawEmptyFlash && resumedReader;
                return checkResult("bug5_relaunch_no_flash_and_resume", passed,
                    "sawEmptyShelfFlash=" + sawEmptyFlash + " resumedToReaderAt173=" + resumedReader + " finalPage=" + controllerPage(c2));
            } finally {
                disposeWindow(w2);
            }
        } finally {
            deleteQuietly(lib);
        }
    }

    // ==================== 3. LOGIN 4 PATHS (MAIN / TON-1782) ====================

    private static void runLogin(String[] args) throws Exception {
        Path bookPdf = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);
        if (!windowsTextbookGatePresent()) {
            Map<String, Object> report = new HashMap<>();
            report.put("flavor", "legacy");
            report.put("applicable", false);
            report.put("verdict", "不适用");
            report.put("reason", "WindowsTextbookGate not found");
            report.put("surface_type", "real_os_window");
            report.put("passed", 0);
            report.put("total", 0);
            report.put("checks", List.of());
            Files.writeString(outDir.resolve("login-report.json"), toJson(report, 0), StandardCharsets.UTF_8);
            System.out.println("不适用");
            System.out.println("LOGIN_REPORT 不适用 WindowsTextbookGate not found");
            return;
        }

        Path root = Files.createTempDirectory("coach-login-accept-").toRealPath();
        Path home = Files.createDirectories(root.resolve("home"));
        Path libDir = Files.createDirectories(root.resolve("library"));
        Map<String, String> savedProps = new HashMap<>();
        for (String k : List.of("user.home", "jpackage.app-path", "coach.windows.theme")) {
            savedProps.put(k, System.getProperty(k));
        }

        ExecutorService bg = Executors.newSingleThreadExecutor();
        HttpClient http = HttpClient.newHttpClient();
        StubAuthServer server = new StubAuthServer();
        List<Map<String, Object>> checks = new ArrayList<>();
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString());
            System.setProperty("coach.windows.theme", "light");

            Object settings = newSettings(home, server.url);
            Object uriHandler = Proxy.newProxyInstance(
                UriHandler.class.getClassLoader(),
                new Class<?>[]{UriHandler.class},
                (proxy, method, mArgs) -> {
                    if ("openUri".equals(method.getName()) && mArgs != null && mArgs.length == 1) {
                        String uri = (String) mArgs[0];
                        bg.submit(() -> {
                            try {
                                http.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.discarding());
                            } catch (Exception ignored) {}
                        });
                    }
                    return null;
                }
            );

            // Path 0: product Windows library (stream + lazy PDF extras) so the upload click
            // reaches TextbookController.launchImport tier-0, not a whole-book stage.
            // Ready means textbook-reader is up and its page area is not 「正在加载页面」. 5s.
            // Product launchImport on this book opened page 32 in 1395ms.
            boolean breakImport = Boolean.parseBoolean(System.getProperty("coach.acceptance.breakImport", "false"));
            Path home0 = Files.createDirectories(root.resolve("home0"));
            Path libDir0 = Files.createDirectories(root.resolve("library0"));
            System.setProperty("user.home", home0.toString());
            System.setProperty("jpackage.app-path", home0.resolve("LearningCoach.exe").toString());
            Object settings0 = newSettings(home0, server.url);
            TextbookLibrary lib0 = createLibrary(libDir0, true);
            boolean productLazy = libraryLazyPdfExtras(lib0);
            TextbookController c0 = createController(lib0);
            Path picked0 = breakImport ? libDir0.resolve("deliberately-missing.pdf") : bookPdf;
            Function1<Continuation<? super Path>, Object> pickBook0 = cont -> picked0;
            ComposeWindow w0 = openGateWindow(settings0, server.url, uriHandler, c0, pickBook0, 24, 24, 752, 552);
            try {
                boolean loginFirst0 = waitFor(6000, () -> findNodeByTag(w0, "textbook-login-screen") != null);
                int onboardingClicks = 0;
                clickTextExactWin32(w0, "用 Sable 账号登录");
                boolean emptyHub = waitFor(10000, () -> findNodeByTag(w0, "home-empty-hub") != null && findNodeByTag(w0, "upload-textbook") != null);
                long importStarted = System.currentTimeMillis();
                if (emptyHub) {
                    clickTagWin32(w0, "upload-textbook");
                    onboardingClicks++;
                }
                boolean readerReady = waitFor(5000, () -> readerPageReady(w0));
                long readyMs = System.currentTimeMillis() - importStarted;
                Thread.sleep(400);
                dumpWin32(w0, outDir, "main", "win32", "login0-onboarding-page1");
                checks.add(checkResult("login_0_onboarding_to_page1",
                    loginFirst0 && emptyHub && readerReady && productLazy && onboardingClicks <= 2,
                    "loginFirst=" + loginFirst0 + " emptyHub=" + emptyHub + " readerReady=" + readerReady
                        + " clicks_excluding_sable_auth=" + onboardingClicks + " page=" + controllerPage(c0)
                        + " ready_ms=" + readyMs + " lazyPdfExtras=" + productLazy
                        + " import=product-launchImport breakImport=" + breakImport));
            } finally {
                disposeWindow(w0);
                deleteQuietly(home0);
                deleteQuietly(libDir0);
            }
            System.setProperty("user.home", home.toString());
            System.setProperty("jpackage.app-path", home.resolve("LearningCoach.exe").toString());

            // Paths 1..4 reopen the same product library. Seed with tier-0, the import launchImport uses,
            // so the shelf is not waiting on a whole-book stage.
            TextbookLibrary seedLib = createLibrary(libDir, true);
            stageProductSeed(seedLib, bookPdf);

            // Path 1 & 2: Fresh install -> first screen is login, <=2 clicks to 2.0 single-column home
            TextbookController c1 = createController(createLibrary(libDir, true));
            ComposeWindow w1 = openGateWindow(settings, server.url, uriHandler, c1, 24, 24, 752, 552);
            try {
                boolean loginFirst = waitFor(6000, () -> findNodeByTag(w1, "textbook-login-screen") != null);
                boolean pairingHidden = findNodeByTag(w1, "login-pairing-form") == null;
                boolean noInternalTerms = !hasTextContaining(w1, "朋友服务") && !hasTextContaining(w1, "Mac");
                Thread.sleep(400);
                dumpWin32(w1, outDir, "main", "win32", "login1-first-screen");
                checks.add(checkResult("login_1_first_screen_and_clean_copy", loginFirst && pairingHidden && noInternalTerms,
                    "loginFirst=" + loginFirst + " pairingHidden=" + pairingHidden + " noInternalTerms=" + noInternalTerms));

                int clicks = 0;
                clickTextExactWin32(w1, "用 Sable 账号登录");
                clicks++;
                boolean enteredShelf = waitFor(10000, () -> findNodeByTag(w1, "windows-host") != null
                    && (findNodeByTag(w1, "home-daily-hub") != null
                        || findNodeByTag(w1, "home-focus-card") != null
                        || findNodeByTag(w1, "home-empty-hub") != null
                        || findNodeByTag(w1, "upload-textbook") != null));
                Thread.sleep(400);
                dumpWin32(w1, outDir, "main", "win32", "login2-shelf");
                checks.add(checkResult("login_2_clicks_to_shelf", enteredShelf && clicks <= 2,
                    "enteredShelf=" + enteredShelf + " clicks=" + clicks));

                // Open book & go to page 32 so Path 3 resumes there
                runSuspend(cont -> c1.load(cont));
                runSuspend(cont -> c1.read(cont));
                runSuspend(cont -> c1.dismissPrompt(cont));
                controllerPageTo(c1, 32);
                Thread.sleep(800);
            } finally {
                disposeWindow(w1);
            }

            // Path 3: Stop server (offline) & relaunch -> 0 clicks back to page 32
            server.close();
            TextbookController c2 = createController(createLibrary(libDir, true));
            ComposeWindow w2 = openGateWindow(settings, server.url, uriHandler, c2, 24, 24, 752, 552);
            try {
                boolean resumed = waitFor(8000, () -> findNodeByTag(w2, "textbook-reader") != null && controllerPage(c2) == 32);
                boolean noLoginBlock = findNodeByTag(w2, "textbook-login-screen") == null;
                Thread.sleep(400);
                dumpWin32(w2, outDir, "main", "win32", "login3-offline-resume");
                checks.add(checkResult("login_3_offline_zero_click_resume", resumed && noLoginBlock,
                    "resumedAt32=" + resumed + " page=" + controllerPage(c2) + " noLoginBlock=" + noLoginBlock));
            } finally {
                disposeWindow(w2);
            }

            // Path 4: Revoke token on server -> <= 3s back to login screen with human-readable reason
            Path root4 = Files.createTempDirectory("coach-login-rev4-").toRealPath();
            Path home4 = Files.createDirectories(root4.resolve("home"));
            System.setProperty("user.home", home4.toString());
            System.setProperty("jpackage.app-path", home4.resolve("LearningCoach.exe").toString());
            StubAuthServer server2 = new StubAuthServer();
            try {
                Object settings2 = newSettings(home4, server2.url);
                TextbookController c4a = createController(createLibrary(libDir, true));
                ComposeWindow w4a = openGateWindow(settings2, server2.url, uriHandler, c4a, 24, 24, 752, 552);
                try {
                    waitFor(8000, () -> findNodeByTag(w4a, "textbook-login-screen") != null);
                    clickTextExactWin32(w4a, "用 Sable 账号登录");
                    waitFor(10000, () -> findNodeByTag(w4a, "windows-host") != null);
                } finally {
                    disposeWindow(w4a);
                }
                // Now revoke on server2 and reopen: must return to login screen <= 3s with clear reason
                server2.revokeAll = true;
                long t0 = System.currentTimeMillis();
                TextbookController c4b = createController(createLibrary(libDir, true));
                ComposeWindow w4b = openGateWindow(settings2, server2.url, uriHandler, c4b, 24, 24, 752, 552);
                try {
                    boolean backToLogin = waitFor(5000, () -> findNodeByTag(w4b, "login-revoked-reason") != null);
                    long elapsedMs = System.currentTimeMillis() - t0;
                    String reason = nodeText(findNodeByTag(w4b, "login-revoked-reason"));
                    boolean humanText = !reason.isBlank() && !reason.contains("401") && !reason.contains("朋友服务");
                    Thread.sleep(300);
                    dumpWin32(w4b, outDir, "main", "win32", "login4-revoked");
                    checks.add(checkResult("login_4_revoked_within_3s", backToLogin && elapsedMs <= 3000 && humanText,
                        "backToLogin=" + backToLogin + " elapsed_ms=" + elapsedMs + " reason='" + reason + "'"));
                } finally {
                    disposeWindow(w4b);
                }
            } finally {
                server2.close();
                deleteQuietly(root4);
            }

            long passed = checks.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
            Map<String, Object> report = new HashMap<>();
            report.put("flavor", "main");
            report.put("surface_type", "real_os_window");
            report.put("passed", passed);
            report.put("total", checks.size());
            report.put("checks", checks);
            Files.writeString(outDir.resolve("login-report.json"), toJson(report, 0), StandardCharsets.UTF_8);
            System.out.println("LOGIN_REPORT passed=" + passed + "/" + checks.size());
            for (var r : checks) {
                System.out.println((Boolean.TRUE.equals(r.get("passed")) ? "PASS " : "FAIL ") + r.get("id") + ": " + r.get("detail"));
            }
        } finally {
            server.close();
            bg.shutdownNow();
            for (var e : savedProps.entrySet()) {
                if (e.getValue() == null) System.clearProperty(e.getKey());
                else System.setProperty(e.getKey(), e.getValue());
            }
            deleteQuietly(root);
        }
    }

    // ==================== HELPERS ====================

    private static TextbookController openReaderAt(Path libDir, int page, boolean textView) throws Exception {
        TextbookLibrary lib = createLibrary(libDir);
        var catalog = lib.catalog();
        if (catalog.isEmpty()) throw new IllegalStateException("Empty library: " + libDir);
        String bookId = catalog.get(0).getId();
        lib.saveTextView(bookId, textView);
        TextbookController controller = createController(lib);
        runSuspend(c -> controller.load(c));
        runSuspend(c -> controller.read(c));
        runSuspend(c -> controller.dismissPrompt(c));
        controllerPageTo(controller, page);
        return controller;
    }

    private static void controllerPageTo(TextbookController controller, int page) throws Exception {
        for (Method m : TextbookController.class.getDeclaredMethods()) {
            if (!"page".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == int.class) {
                m.setAccessible(true);
                runSuspend(c -> m.invoke(controller, page, c));
                return;
            }
            if (p.length == 3 && p[0] == int.class && p[1] == boolean.class) {
                m.setAccessible(true);
                runSuspend(c -> m.invoke(controller, page, true, c));
                return;
            }
        }
        throw new IllegalStateException("TextbookController.page(...) not found");
    }

    private static TextbookController openGuided(Path libDir) throws Exception {
        TextbookLibrary lib = createLibrary(libDir);
        TextbookController controller = createController(lib);
        runSuspend(c -> controller.load(c));
        Class<?> lpClass = Class.forName("com.coach.desktop.books.LearningPath");
        @SuppressWarnings("unchecked")
        Object readAndLearn = Enum.valueOf((Class<Enum>) lpClass, "READ_AND_LEARN");
        Method chooseM = TextbookController.class.getDeclaredMethod("chooseLearningPath", lpClass, Continuation.class);
        Method enableM = TextbookController.class.getDeclaredMethod("enableLearning", Continuation.class);
        chooseM.setAccessible(true);
        enableM.setAccessible(true);
        runSuspend(c -> chooseM.invoke(controller, readAndLearn, c));
        runSuspend(c -> enableM.invoke(controller, c));
        return controller;
    }

    private static ImageComposeScene newScene(int w, int h, float density, TextbookController controller) throws Exception {
        return onEdt(() -> new ImageComposeScene(
            w, h, DensityKt.Density(density, 1f), LayoutDirection.Ltr, Dispatchers.getMain(),
            (composer, changed) -> { callHost(controller, composer); return Unit.INSTANCE; }
        ));
    }

    private static long pumpScene(ImageComposeScene scene, long nano, int frames, int sleepMs) throws Exception {
        for (int i = 0; i < frames; i++) {
            final long n = nano + 20_000_000L;
            nano = n;
            onEdt(() -> { try (Image img = scene.render(n)) {} return null; });
            if (sleepMs > 0) Thread.sleep(sleepMs);
        }
        return nano;
    }

    private static long pumpSceneUntilSettled(
        ImageComposeScene scene, long nano, int minFrames, int maxExtraFrames, int sleepMs, Callable<Boolean> pred
    ) throws Exception {
        nano = pumpScene(scene, nano, minFrames, sleepMs);
        for (int i = 0; i < maxExtraFrames; i++) {
            if (Boolean.TRUE.equals(pred.call())) break;
            nano = pumpScene(scene, nano, 1, sleepMs);
        }
        return nano;
    }

    private static boolean hasTextContainingScene(ImageComposeScene scene, String needle) throws Exception {
        return onEdt(() -> {
            for (SemanticsOwner o : scene.getSemanticsOwners()) {
                if (findByText(o.getUnmergedRootSemanticsNode(), needle, true) != null) return true;
            }
            return false;
        });
    }

    private static long dismissV2HudIfVisibleScene(ImageComposeScene scene, long nano) throws Exception {
        boolean hasHud = onEdt(() ->
            findNodeByTag(scene.getSemanticsOwners(), "immersive-top-hud") != null
                || findNodeByTag(scene.getSemanticsOwners(), "immersive-bottom-hud") != null
        );
        if (!hasHud) return nano;
        sendTabScene(scene);
        nano = pumpSceneUntilSettled(scene, nano, 10, 45, 20, () -> onEdt(() ->
            findNodeByTag(scene.getSemanticsOwners(), "immersive-top-hud") == null
                && findNodeByTag(scene.getSemanticsOwners(), "immersive-bottom-hud") == null
                && findNodeByTag(scene.getSemanticsOwners(), "immersive-progress-hairline") != null
        ));
        return pumpScene(scene, nano, 20, 20);
    }

    private static void sendTabScene(ImageComposeScene scene) throws Exception {
        onEdt(() -> {
            SemanticsNode readerPage = findNodeByTag(scene.getSemanticsOwners(), "reader-page");
            if (readerPage != null) {
                Object reqFocus = findSemanticsActionFn(readerPage, "RequestFocus");
                if (reqFocus instanceof Function0<?> f0) {
                    f0.invoke();
                }
            }
            return null;
        });
        Class<?> ktClass = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt");
        Method toCompose = ktClass.getMethod("toComposeEvent", KeyEvent.class);
        Method sendKey = null;
        for (Method m : ImageComposeScene.class.getDeclaredMethods()) {
            if (m.getName().startsWith("sendKeyEvent-")) {
                sendKey = m;
                break;
            }
        }
        if (sendKey == null) throw new IllegalStateException("ImageComposeScene.sendKeyEvent not found");
        sendKey.setAccessible(true);
        final Method sendKeyFinal = sendKey;
        java.awt.Component dummy = new java.awt.Canvas();
        long now = System.currentTimeMillis();
        Object press = toCompose.invoke(null, new KeyEvent(dummy, KeyEvent.KEY_PRESSED, now, 0, KeyEvent.VK_TAB, '\t'));
        Object release = toCompose.invoke(null, new KeyEvent(dummy, KeyEvent.KEY_RELEASED, now + 5, 0, KeyEvent.VK_TAB, '\t'));
        onEdt(() -> {
            try {
                sendKeyFinal.invoke(scene, press);
                sendKeyFinal.invoke(scene, release);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    private static void dismissV2HudIfVisibleWin32(ComposeWindow win) throws Exception {
        // A pointer parked at the top/bottom edge reveals the HUD again on relayout.
        // Move inside this window's canvas without moving the system pointer or taking OS focus.
        onEdt(() -> {
            Surface main = activeSurfaces(win).get(0);
            Component canvas = main.layer.getCanvas();
            takeLogicalFocus(main.mediator);
            invoke(main.mediator, "onMouseEvent", MouseEvent.class, new MouseEvent(canvas,
                MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, canvas.getWidth() / 2,
                canvas.getHeight() / 2, 0, false, MouseEvent.NOBUTTON));
            return null;
        });
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!pureReadHudHidden(win)) {
                onEdt(() -> {
                    SemanticsNode reader = findNodeByTag(win.getSemanticsOwners(), "reader-page");
                    if (reader != null && findSemanticsActionFn(reader, "RequestFocus") instanceof Function0<?> focus) {
                        focus.invoke();
                    }
                    return null;
                });
                sendKeysWin32(win, KeyEvent.VK_TAB, 0);
            }
            if (waitFor(2000, () -> pureReadHudHidden(win))) {
                Thread.sleep(350);
                if (pureReadHudHidden(win)) return;
            }
        }
        throw new IllegalStateException("Pure-read capture requires both HUDs hidden and the progress line visible");
    }

    private static boolean pureReadHudHidden(ComposeWindow win) throws Exception {
        return findNodeByTag(win, "immersive-top-hud") == null
            && findNodeByTag(win, "immersive-bottom-hud") == null
            && findNodeByTag(win, "immersive-progress-hairline") != null;
    }

    private static long revealV2HudScene(ImageComposeScene scene, long nano, int w) throws Exception {
        sendScenePointer(scene, pointerType("Enter"), w / 2f, 10f);
        sendScenePointer(scene, pointerType("Move"), w / 2f, 12f);
        return pumpScene(scene, nano, 20, 25);
    }

    private static void dumpScene(
        ImageComposeScene scene, long nano, Path outDir, String flavor, String resTag, String stateName
    ) throws Exception {
        String base = flavor + "-" + resTag + "-scene-" + stateName;
        Path pngPath = outDir.resolve(base + ".png");
        Path metaPath = outDir.resolve(base + ".meta.json");
        int[] dims = new int[2];
        byte[] pngBytes = onEdt(() -> {
            try (Image img = scene.render(nano); Data png = img.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                if (png == null) throw new IllegalStateException("Failed to encode scene PNG");
                dims[0] = img.getWidth();
                dims[1] = img.getHeight();
                return png.getBytes();
            }
        });
        Files.write(pngPath, pngBytes);
        Collection<SemanticsOwner> owners = onEdt(scene::getSemanticsOwners);
        writeMeta(metaPath, flavor, stateName, "image_compose_scene_offscreen", dims[0], dims[1], resTag.startsWith("2880") ? 1.5f : 1.0f, owners, 0, 0);
        System.out.println("DUMP " + pngPath.getFileName() + " (image_compose_scene_offscreen " + dims[0] + "x" + dims[1] + ")");
    }

    private static void dumpWin32(
        ComposeWindow win, Path outDir, String flavor, String resTag, String stateName
    ) throws Exception {
        String base = flavor + "-" + resTag + "-win32-" + stateName;
        Path pngPath = outDir.resolve(base + ".png");
        Path metaPath = outDir.resolve(base + ".meta.json");
        BufferedImage img = onEdt(() -> screenshotWin32(win));
        ImageIO.write(img, "png", pngPath.toFile());
        float scale = onEdt(() -> (float) win.getGraphicsConfiguration().getDefaultTransform().getScaleX());
        int[] canvasOffset = onEdt(() -> mainCanvasPixelOffset(win, scale));
        Collection<SemanticsOwner> owners = onEdt(win::getSemanticsOwners);
        writeMeta(metaPath, flavor, stateName, "real_os_window", img.getWidth(), img.getHeight(), scale, owners, canvasOffset[0], canvasOffset[1]);
        System.out.println("DUMP " + pngPath.getFileName() + " (real_os_window " + img.getWidth() + "x" + img.getHeight() + ")");
    }

    private static int[] mainCanvasPixelOffset(ComposeWindow window, double scale) {
        try {
            Point origin = window.getLocationOnScreen();
            for (Surface s : activeSurfaces(window)) {
                if (!s.layer.isShowing()) continue;
                Point pt = s.layer.getCanvas().getLocationOnScreen();
                return new int[]{
                    (int) Math.round((pt.x - origin.x) * scale),
                    (int) Math.round((pt.y - origin.y) * scale)
                };
            }
        } catch (Exception ignored) {}
        return new int[]{0, 0};
    }

    private static void writeMeta(
        Path metaPath, String flavor, String stateName, String surfaceType, int w, int h, float scale,
        Collection<SemanticsOwner> owners, int offsetX, int offsetY
    ) throws IOException {
        List<Map<String, Object>> roots = new ArrayList<>();
        for (SemanticsOwner o : owners) {
            roots.add(serializeNode(o.getUnmergedRootSemanticsNode(), offsetX, offsetY));
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put("flavor", flavor);
        meta.put("state_name", stateName);
        meta.put("surface_type", surfaceType);
        meta.put("window_px", List.of(w, h));
        meta.put("scale", scale);
        meta.put("semantics", roots);
        Files.writeString(metaPath, toJson(meta, 0), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> serializeNode(SemanticsNode node, int offsetX, int offsetY) {
        Rect r = node.getBoundsInRoot();
        String tag = "";
        List<String> texts = new ArrayList<>();
        for (var e : node.getConfig()) {
            String key = e.getKey().getName();
            if ("TestTag".equals(key) && e.getValue() != null) {
                tag = e.getValue().toString();
            } else if ("Text".equals(key) && e.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof AnnotatedString as) texts.add(as.getText());
                    else if (item != null) texts.add(item.toString());
                }
            }
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (SemanticsNode ch : node.getChildren()) {
            children.add(serializeNode(ch, offsetX, offsetY));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("tag", tag);
        m.put("text", String.join(" ", texts));
        m.put("bounds", List.of(
            Math.round(r.getLeft()) + offsetX,
            Math.round(r.getTop()) + offsetY,
            Math.round(r.getRight()) + offsetX,
            Math.round(r.getBottom()) + offsetY
        ));
        m.put("children", children);
        return m;
    }

    private static ComposeWindow openHostWindow(TextbookController controller, int x, int y, int w, int h) throws Exception {
        ComposeWindow[] box = new ComposeWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            ComposeWindow win = new ComposeWindow();
            win.setTitle("LearningCoach-Acceptance");
            win.setBounds(x, y, w, h);
            win.setAutoRequestFocus(false);
            win.setFocusableWindowState(false);
            win.setContent((scope, composer, changed) -> {
                callHost(controller, composer);
                return Unit.INSTANCE;
            });
            win.setVisible(true);
            box[0] = win;
            activeWindow = win;
        });
        return box[0];
    }

    private static ComposeWindow openGateWindow(
        Object settings, String sableUrl, Object uriHandler, TextbookController controller, int x, int y, int w, int h
    ) throws Exception {
        return openGateWindow(settings, sableUrl, uriHandler, controller,
            cont -> IntrinsicsKt.getCOROUTINE_SUSPENDED(), x, y, w, h);
    }

    private static ComposeWindow openGateWindow(
        Object settings, String sableUrl, Object uriHandler, TextbookController controller,
        Function1<Continuation<? super Path>, Object> chooseBookFn,
        int x, int y, int w, int h
    ) throws Exception {
        ComposeWindow[] box = new ComposeWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            ComposeWindow win = new ComposeWindow();
            win.setTitle("LearningCoach-Login-Acceptance");
            win.setBounds(x, y, w, h);
            win.setAutoRequestFocus(false);
            win.setFocusableWindowState(false);
            win.setContent((scope, composer, changed) -> {
                callGate(settings, sableUrl, uriHandler, controller, chooseBookFn, composer);
                return Unit.INSTANCE;
            });
            win.setVisible(true);
            box[0] = win;
            activeWindow = win;
        });
        return box[0];
    }

    private static void disposeWindow(ComposeWindow win) throws Exception {
        if (win == null) return;
        if (activeWindow == win) activeWindow = null;
        SwingUtilities.invokeAndWait(win::dispose);
        Thread.sleep(200);
    }

    private static void revealV2HudWin32(ComposeWindow win) throws Exception {
        if (findNodeByTag(win, "immersive-top-hud") != null && findNodeByTag(win, "immersive-bottom-hud") != null) return;
        onEdt(() -> {
            List<Surface> surfaces = activeSurfaces(win);
            if (surfaces.isEmpty()) return null;
            Surface main = surfaces.get(0);
            int lx = Math.max(10, main.layer.getCanvas().getWidth() / 2);
            int ly = 8;
            takeLogicalFocus(main.mediator);
            invoke(main.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(main.layer.getCanvas(), MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, lx, ly, 0, false, MouseEvent.NOBUTTON));
            invoke(main.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(main.layer.getCanvas(), MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, lx, ly, 0, false, MouseEvent.NOBUTTON));
            return null;
        });
        Thread.sleep(350);
        if (findNodeByTag(win, "immersive-top-hud") == null) {
            sendKeysWin32(win, KeyEvent.VK_TAB, 0);
            Thread.sleep(400);
        }
    }

    private static void clickTagWin32(ComposeWindow win, String tag) throws Exception {
        SemanticsNode n = findNodeByTag(win, tag);
        if (n == null) throw new IllegalStateException("Tag not found: " + tag);
        clickNodeWin32(win, n);
    }

    private static Object findSemanticsActionFn(SemanticsNode node, String actionKey) {
        SemanticsNode cur = node;
        while (cur != null) {
            for (var e : cur.getConfig()) {
                if (actionKey.equals(e.getKey().getName()) && e.getValue() != null) {
                    try {
                        Object action = e.getValue();
                        Method getAction = action.getClass().getMethod("getAction");
                        getAction.setAccessible(true);
                        Object fn = getAction.invoke(action);
                        if (fn != null) return fn;
                    } catch (Exception ignored) {}
                }
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean invokeOnClick(SemanticsNode node) {
        Object fn = findSemanticsActionFn(node, "OnClick");
        return fn instanceof Function0<?> f0 && Boolean.TRUE.equals(f0.invoke());
    }

    private static void clickNodeWin32(ComposeWindow win, SemanticsNode n) throws Exception {
        boolean invoked = onEdt(() -> invokeOnClick(n));
        if (invoked) return;
        Rect r = n.getBoundsInRoot();
        double cx = (r.getLeft() + r.getRight()) / 2.0;
        double cy = (r.getTop() + r.getBottom()) / 2.0;
        onEdt(() -> {
            double scale = win.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            List<Surface> surfaces = activeSurfaces(win);
            if (surfaces.isEmpty()) return null;
            Surface target = surfaces.get(surfaces.size() - 1);
            int lx = (int) Math.round(cx / scale);
            int ly = (int) Math.round(cy / scale);
            if (lx < 0 || ly < 0 || lx >= target.layer.getCanvas().getWidth() || ly >= target.layer.getCanvas().getHeight()) {
                target = surfaces.get(0);
            }
            takeLogicalFocus(target.mediator);
            invoke(target.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(target.layer.getCanvas(), MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, lx, ly, 1, false, MouseEvent.NOBUTTON));
            invoke(target.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(target.layer.getCanvas(), MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, lx, ly, 1, false, MouseEvent.BUTTON1));
            invoke(target.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(target.layer.getCanvas(), MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, lx, ly, 1, false, MouseEvent.BUTTON1));
            invoke(target.mediator, "onMouseEvent", MouseEvent.class,
                new MouseEvent(target.layer.getCanvas(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, lx, ly, 1, false, MouseEvent.BUTTON1));
            return null;
        });
    }

    private static void clickTextExactWin32(ComposeWindow win, String label) throws Exception {
        SemanticsNode n = onEdt(() -> {
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                SemanticsNode found = findByText(o.getUnmergedRootSemanticsNode(), label, false);
                if (found != null) return found;
            }
            return null;
        });
        if (n == null) throw new IllegalStateException("Text not found: " + label);
        clickNodeWin32(win, n);
    }

    private static void clickTextContainingWin32(ComposeWindow win, String needle) throws Exception {
        SemanticsNode n = onEdt(() -> {
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                SemanticsNode found = findByText(o.getUnmergedRootSemanticsNode(), needle, true);
                if (found != null) return found;
            }
            return null;
        });
        if (n != null) clickNodeWin32(win, n);
    }

    @SuppressWarnings("unchecked")
    private static boolean invokeSetText(SemanticsNode node, String text) {
        Object fn = findSemanticsActionFn(node, "SetText");
        return fn instanceof Function1<?, ?> f1 && Boolean.TRUE.equals(((Function1<AnnotatedString, Boolean>) f1).invoke(new AnnotatedString(text, List.of(), List.of())));
    }

    private static boolean invokeImeAction(SemanticsNode node) {
        Object fn = findSemanticsActionFn(node, "OnImeAction");
        return fn instanceof Function0<?> f0 && Boolean.TRUE.equals(f0.invoke());
    }

    private static SemanticsNode findEnabledGoButton(ComposeWindow win) throws Exception {
        return onEdt(() -> {
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                SemanticsNode found = findByText(o.getUnmergedRootSemanticsNode(), "前往", false);
                if (found != null && isFullyInWindow(found, 752f, 552f) && isNodeEnabled(found)) return found;
            }
            return null;
        });
    }

    private static void clickOrEnterJump(ComposeWindow win, String flavor, SemanticsNode jumpField) throws Exception {
        if (!isV2Flavor(flavor)) {
            waitFor(6000, () -> findEnabledGoButton(win) != null);
            SemanticsNode goBtn = findEnabledGoButton(win);
            if (goBtn != null) {
                clickNodeWin32(win, goBtn);
                return;
            }
        }
        if (!onEdt(() -> invokeImeAction(jumpField))) {
            sendKeysWin32(win, KeyEvent.VK_ENTER, 0);
        }
    }

    private static void sendCtrlAWin32(ComposeWindow win) throws Exception {
        sendKeysWin32(win, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK);
    }

    private static void sendKeysWin32(ComposeWindow win, int keyCode, int modifiers) throws Exception {
        onEdt(() -> {
            Target t = focusTarget(win);
            if (t == null) return null;
            takeLogicalFocus(t.mediator);
            invoke(t.mediator, "onKeyEvent", KeyEvent.class, new KeyEvent(t.layer.getCanvas(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED));
            invoke(t.mediator, "onKeyEvent", KeyEvent.class, new KeyEvent(t.layer.getCanvas(), KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static void sendTextWin32(ComposeWindow win, String text) throws Exception {
        onEdt(() -> {
            Target t = focusTarget(win);
            if (t == null) return null;
            takeLogicalFocus(t.mediator);
            for (char c : text.toCharArray()) {
                invoke(t.mediator, "onKeyEvent", KeyEvent.class, new KeyEvent(t.layer.getCanvas(), KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, 0, c));
            }
            return null;
        });
    }

    private static String topHeaderText(ComposeWindow win) throws Exception {
        return onEdt(() -> {
            SemanticsNode v2Title = findNodeByTag(win.getSemanticsOwners(), "immersive-title");
            if (v2Title != null) {
                String t = nodeText(v2Title);
                if (!t.isEmpty()) return t;
            }
            List<String> parts = new ArrayList<>();
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                collectTopTexts(o.getUnmergedRootSemanticsNode(), parts);
            }
            return String.join(" | ", parts);
        });
    }

    private static void collectTopTexts(SemanticsNode node, List<String> out) {
        Rect r = node.getBoundsInRoot();
        String t = nodeText(node);
        if (!t.isEmpty() && r.getTop() >= 0 && r.getBottom() <= 240 && r.getBottom() > r.getTop()) {
            if (!t.equals("Coach") && !t.equals("目录") && !t.equals("返回书架") && !t.equals("Aa") && !t.equals("专心读")
                && !t.equals("看原页") && !t.equals("看文字") && !t.equals("选段提问") && !t.equals("讲解") && !t.equals("批注") && !t.equals("开始学")) {
                out.add(t);
            }
        }
        for (SemanticsNode ch : node.getChildren()) collectTopTexts(ch, out);
    }

    private static void collectDrawerItems(SemanticsNode node, float maxH, List<SemanticsNode> out) {
        String tag = nodeTag(node);
        if ("left-tab-toc".equals(tag) || "left-tab-bookmarks".equals(tag) || tag.startsWith("toc-read-toggle-")) return;
        Rect r = node.getBoundsInRoot();
        String t = nodeText(node);
        if (!t.isEmpty() && r.getBottom() > r.getTop() && r.getTop() >= 110 && r.getBottom() <= maxH - 45
            && !t.contains("收起") && !"×".equals(t) && !"目录".equals(t) && !"书签".equals(t) && !"识别目录".equals(t)
            && !"标为已读".equals(t) && !"已读".equals(t) && !t.matches("\\d+")) {
            out.add(node);
        }
        for (SemanticsNode ch : node.getChildren()) collectDrawerItems(ch, maxH, out);
    }

    private static boolean isFullyInWindow(SemanticsNode n, float w, float h) {
        if (n == null) return false;
        Rect r = n.getBoundsInRoot();
        return (r.getRight() - r.getLeft()) > 2 && (r.getBottom() - r.getTop()) > 2
            && r.getLeft() >= 0 && r.getTop() >= 0 && r.getRight() <= w && r.getBottom() <= h;
    }

    private static String boundsStr(SemanticsNode n) {
        if (n == null) return "null";
        Rect r = n.getBoundsInRoot();
        return "[" + Math.round(r.getLeft()) + "," + Math.round(r.getTop()) + "->" + Math.round(r.getRight()) + "," + Math.round(r.getBottom()) + "]";
    }

    private static int controllerPage(TextbookController c) {
        try {
            Method getState = TextbookController.class.getMethod("getState");
            getState.setAccessible(true);
            Object flow = getState.invoke(c);
            Object ui = (flow instanceof StateFlow<?> sf) ? sf.getValue() : null;
            if (ui == null) {
                Method gv = StateFlow.class.getMethod("getValue");
                ui = gv.invoke(flow);
            }
            Method getPage = ui.getClass().getMethod("getPage");
            getPage.setAccessible(true);
            return ((Number) getPage.invoke(ui)).intValue();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return -1;
        }
    }

    private static Map<String, Object> checkResult(String id, boolean passed, String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("passed", passed);
        m.put("detail", detail);
        return m;
    }

    private static SemanticsNode findNodeByTag(ComposeWindow win, String tag) throws Exception {
        return onEdt(() -> findNodeByTag(win.getSemanticsOwners(), tag));
    }

    private static SemanticsNode findNodeByTag(Collection<SemanticsOwner> owners, String tag) {
        for (SemanticsOwner o : owners) {
            SemanticsNode found = findByTag(o.getUnmergedRootSemanticsNode(), tag);
            if (found != null) return found;
        }
        return null;
    }

    private static SemanticsNode findByTag(SemanticsNode node, String tag) {
        for (var e : node.getConfig()) {
            if ("TestTag".equals(e.getKey().getName()) && tag.equals(e.getValue())) return node;
        }
        for (SemanticsNode ch : node.getChildren()) {
            SemanticsNode found = findByTag(ch, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static SemanticsNode findNodeByTextOrTag(ComposeWindow win, String tag, String textSub) throws Exception {
        SemanticsNode byTag = findNodeByTag(win, tag);
        if (byTag != null) return byTag;
        return onEdt(() -> {
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                SemanticsNode found = findByText(o.getUnmergedRootSemanticsNode(), textSub, true);
                if (found != null) return found;
            }
            return null;
        });
    }

    private static String nodeTag(SemanticsNode node) {
        for (var e : node.getConfig()) {
            if ("TestTag".equals(e.getKey().getName()) && e.getValue() != null) return e.getValue().toString();
        }
        return "";
    }

    private static String ownText(SemanticsNode node) {
        if (node == null) return "";
        List<String> list = new ArrayList<>();
        for (var e : node.getConfig()) {
            if ("Text".equals(e.getKey().getName()) && e.getValue() instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof AnnotatedString as && !as.getText().isEmpty()) list.add(as.getText());
                }
            }
        }
        return String.join(" ", list).trim();
    }

    private static String ownTextOrChild(SemanticsNode node) {
        return nodeText(node);
    }

    private static SemanticsNode findByText(SemanticsNode node, String text, boolean substring) {
        String nt = ownText(node);
        if (!nt.isEmpty() && (substring ? nt.contains(text) : nt.equals(text))) return node;
        for (SemanticsNode ch : node.getChildren()) {
            SemanticsNode found = findByText(ch, text, substring);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasTextContaining(ComposeWindow win, String needle) throws Exception {
        return onEdt(() -> {
            for (SemanticsOwner o : win.getSemanticsOwners()) {
                if (findByText(o.getUnmergedRootSemanticsNode(), needle, true) != null) return true;
            }
            return false;
        });
    }

    private static String nodeText(SemanticsNode node) {
        if (node == null) return "";
        List<String> list = new ArrayList<>();
        collectSelfAndChildTexts(node, list);
        return String.join(" ", list).trim();
    }

    private static void collectSelfAndChildTexts(SemanticsNode node, List<String> out) {
        for (var e : node.getConfig()) {
            if ("Text".equals(e.getKey().getName()) && e.getValue() instanceof List<?> l) {
                for (Object item : l) {
                    if (item instanceof AnnotatedString as && !as.getText().isEmpty()) out.add(as.getText());
                }
            }
        }
        for (SemanticsNode ch : node.getChildren()) collectSelfAndChildTexts(ch, out);
    }

    private static void clickByTagScene(ImageComposeScene scene, String tag) throws Exception {
        SemanticsNode node = onEdt(() -> findNodeByTag(scene.getSemanticsOwners(), tag));
        if (node == null) {
            System.out.println("WARN scene tag not found: " + tag);
            return;
        }
        if (Boolean.TRUE.equals(onEdt(() -> invokeOnClick(node)))) {
            return;
        }
        Rect r = node.getBoundsInRoot();
        float cx = (r.getLeft() + r.getRight()) / 2f;
        float cy = (r.getTop() + r.getBottom()) / 2f;
        sendScenePointer(scene, pointerType("Move"), cx, cy);
        onEdt(() -> { try (Image i1 = scene.render(System.nanoTime())) {} return null; });
        sendScenePointer(scene, pointerType("Press"), cx, cy);
        onEdt(() -> { try (Image i2 = scene.render(System.nanoTime())) {} return null; });
        sendScenePointer(scene, pointerType("Release"), cx, cy);
    }

    private static void sendScenePointer(ImageComposeScene scene, int eventType, float x, float y) throws Exception {
        long offset = OffsetKt.Offset(x, y);
        for (Method m : ImageComposeScene.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("sendPointerEvent-BGSDPeU$default")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];
            a[0] = scene; a[1] = eventType; a[2] = offset; a[3] = 0L;
            a[4] = System.currentTimeMillis(); a[5] = 1;
            a[10] = 0b111111100;
            m.setAccessible(true);
            onEdt(() -> { m.invoke(null, a); return null; });
            return;
        }
        throw new IllegalStateException("sendPointerEvent$default not found");
    }

    private static int pointerType(String name) throws Exception {
        for (Method m : PointerEventType.Companion.getClass().getDeclaredMethods()) {
            if (m.getName().startsWith("get" + name + "-")) {
                m.setAccessible(true);
                return ((Number) m.invoke(PointerEventType.Companion)).intValue();
            }
        }
        throw new IllegalStateException("PointerEventType." + name + " not found");
    }

    private static boolean waitFor(long timeoutMs, Callable<Boolean> pred) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(pred.call())) return true;
            Thread.sleep(100);
        }
        return false;
    }

    // ==================== REFLECTION / HOST / GATE ====================

    private static TextbookLibrary createLibrary(Path libDir) throws Exception {
        return createLibrary(libDir, false);
    }

    /**
     * productImport matches WindowsTextbooks: TextbookLibrary(directory, streamPdfExtras = true, lazyPdfExtras = true).
     * Those are the only two boolean parameters, in that order. A set default-mask bit would force them false
     * and send launchImport down the whole-book path.
     */
    private static TextbookLibrary createLibrary(Path libDir, boolean productImport) throws Exception {
        for (var ctor : TextbookLibrary.class.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length >= 3 && p[0] == Path.class && p[p.length - 1].getSimpleName().equals("DefaultConstructorMarker")) {
                Object[] a = new Object[p.length];
                a[0] = libDir;
                int mask = 0;
                int boolCount = 0;
                int seenBool = 0;
                for (int i = 1; i < p.length - 2; i++) {
                    if (p[i] == boolean.class) boolCount++;
                }
                if (productImport && boolCount != 2) {
                    throw new IllegalStateException("TextbookLibrary product flags missing, booleanCount=" + boolCount);
                }
                for (int i = 1; i < p.length - 2; i++) {
                    if (productImport && p[i] == boolean.class && seenBool < 2) {
                        a[i] = true;
                        seenBool++;
                    } else {
                        mask |= (1 << i);
                        if (p[i] == boolean.class) a[i] = false;
                    }
                }
                a[p.length - 2] = mask;
                a[p.length - 1] = null;
                ctor.setAccessible(true);
                return (TextbookLibrary) ctor.newInstance(a);
            }
        }
        throw new IllegalStateException("TextbookLibrary ctor not found");
    }

    private static void stageProductSeed(TextbookLibrary lib, Path book) throws Exception {
        Method tier0 = null;
        for (Method m : TextbookLibrary.class.getMethods()) {
            if ("stageBookImportTier0".equals(m.getName()) && m.getParameterCount() == 2) {
                tier0 = m;
                break;
            }
        }
        if (tier0 == null) throw new IllegalStateException("stageBookImportTier0 not found");
        tier0.setAccessible(true);
        tier0.invoke(lib, book, new BookImportControl());
    }

    private static boolean libraryLazyPdfExtras(TextbookLibrary lib) {
        try {
            for (Method m : lib.getClass().getMethods()) {
                if (m.getName().startsWith("getLazyPdfExtras") && m.getParameterCount() == 0) {
                    return Boolean.TRUE.equals(m.invoke(lib));
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean windowsTextbookGatePresent() {
        try {
            Class<?> gateCls = Class.forName("com.coach.desktop.WindowsTextbooksKt");
            for (Method m : gateCls.getDeclaredMethods()) {
                if ("WindowsTextbookGate".equals(m.getName())) return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** textbook-reader is showing and the page area is not the loading placeholder. */
    private static boolean readerPageReady(ComposeWindow win) throws Exception {
        return Boolean.TRUE.equals(onEdt(() -> {
            SemanticsNode reader = findNodeByTag(win.getSemanticsOwners(), "textbook-reader");
            if (reader == null) return false;
            return !nodeText(reader).contains("正在加载页面");
        }));
    }

    private static TextbookController createController(TextbookLibrary lib) throws Exception {
        for (var ctor : TextbookController.class.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 1 && p[0] == TextbookLibrary.class) {
                ctor.setAccessible(true);
                return (TextbookController) ctor.newInstance(lib);
            }
            if (p.length >= 3 && p[0] == TextbookLibrary.class && p[p.length - 1].getSimpleName().equals("DefaultConstructorMarker")) {
                Object[] a = new Object[p.length];
                a[0] = lib;
                int mask = 0;
                for (int i = 1; i < p.length - 2; i++) mask |= (1 << i);
                a[p.length - 2] = mask;
                a[p.length - 1] = null;
                ctor.setAccessible(true);
                return (TextbookController) ctor.newInstance(a);
            }
        }
        throw new IllegalStateException("TextbookController ctor not found");
    }

    private static void callHost(TextbookController controller, androidx.compose.runtime.Composer composer) {
        callHost(controller, cont -> IntrinsicsKt.getCOROUTINE_SUSPENDED(), composer);
    }

    private static void callHost(
        TextbookController controller,
        Function1<Continuation<? super Path>, Object> chooseBookFn,
        androidx.compose.runtime.Composer composer
    ) {
        try {
            for (Method m : WindowsTextbookHostKt.class.getDeclaredMethods()) {
                if (!m.getName().equals("WindowsTextbookHost")) continue;
                Class<?>[] p = m.getParameterTypes();
                int composerIdx = -1;
                for (int i = 0; i < p.length; i++) {
                    if (p[i].getName().equals("androidx.compose.runtime.Composer")) {
                        composerIdx = i;
                        break;
                    }
                }
                if (composerIdx < 2) continue;
                int chooseIdx = composerIdx - 1;
                Object[] invokeArgs = new Object[p.length];
                invokeArgs[0] = controller;
                invokeArgs[chooseIdx] = chooseBookFn;
                invokeArgs[composerIdx] = composer;
                for (int k = composerIdx + 1; k < p.length - 1; k++) {
                    invokeArgs[k] = 0;
                }
                int mask = 0;
                for (int i = 1; i < chooseIdx; i++) {
                    mask |= (1 << i);
                    if (p[i] == boolean.class) invokeArgs[i] = false;
                    else if (p[i] == int.class) invokeArgs[i] = 0;
                }
                invokeArgs[p.length - 1] = mask;
                m.setAccessible(true);
                m.invoke(null, invokeArgs);
                return;
            }
            throw new IllegalStateException("WindowsTextbookHost not found");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object newSettings(Path home, String serverUrl) throws Exception {
        Class<?> cls = Class.forName("com.coach.desktop.DesktopConnectionSettings");
        var ctor = cls.getDeclaredConstructor(Path.class, Function1.class, boolean.class, Function1.class);
        ctor.setAccessible(true);
        Function1<String, String> env = k -> "COACH_FRIEND_URL".equals(k) ? serverUrl : System.getenv(k);
        Function1<String, String> prop = System::getProperty;
        return ctor.newInstance(home, env, false, prop);
    }

    private static void rewriteAddress(Path home, String newUrl) throws Exception {
        Path clientDir = home.resolve("Coach/client");
        if (!Files.isDirectory(clientDir)) return;
        Path addr = clientDir.resolve("address");
        if (Files.exists(addr)) Files.writeString(addr, newUrl, StandardCharsets.UTF_8);
    }

    private static void callGate(
        Object settings, String sableUrl, Object uriHandler, TextbookController controller,
        Function1<Continuation<? super Path>, Object> chooseBookFn,
        androidx.compose.runtime.Composer composer
    ) {
        try {
            Class<?> cl = Class.forName("androidx.compose.ui.platform.CompositionLocalsKt");
            Object localUri = cl.getMethod("getLocalUriHandler").invoke(null);
            Object provided = localUri.getClass().getMethod("provides", Object.class).invoke(localUri, uriHandler);

            Class<?> gateCls = Class.forName("com.coach.desktop.WindowsTextbooksKt");
            Method gateM = null;
            for (Method m : gateCls.getDeclaredMethods()) {
                if ("WindowsTextbookGate".equals(m.getName())) { gateM = m; break; }
            }
            if (gateM == null) throw new IllegalStateException("WindowsTextbookGate not found");
            final Method fGate = gateM;

            Class<?> clpCls = Class.forName("androidx.compose.runtime.CompositionLocalKt");
            Method clpM = null;
            for (Method m : clpCls.getMethods()) {
                if ("CompositionLocalProvider".equals(m.getName()) && m.getParameterCount() == 4
                    && m.getParameterTypes()[0].getSimpleName().equals("ProvidedValue")) {
                    clpM = m;
                    break;
                }
            }
            if (clpM == null) throw new IllegalStateException("CompositionLocalProvider not found");

            Function3<Object, androidx.compose.runtime.Composer, Integer, Unit> inner = (identity, c2, ch2) -> {
                callHost(controller, chooseBookFn, c2);
                return Unit.INSTANCE;
            };
            Function2<androidx.compose.runtime.Composer, Integer, Unit> outer = (c1, ch1) -> {
                try {
                    fGate.setAccessible(true);
                    fGate.invoke(null, settings, sableUrl, false, inner, c1, 0, 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return Unit.INSTANCE;
            };
            clpM.invoke(null, provided, outer, composer, 8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface SuspendCall { Object call(Continuation<Object> c) throws Exception; }

    private static void runSuspend(SuspendCall call) throws Exception {
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, cont) -> {
            try {
                @SuppressWarnings("unchecked") Continuation<Object> c = (Continuation<Object>) cont;
                return call.call(c);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== WIN32 SURFACE INPUT & SCREENSHOT ====================

    private record Surface(SkiaLayer layer, Object mediator, boolean focusable) {}
    private record Target(SkiaLayer layer, Object mediator, int x, int y) {}

    private static BufferedImage screenshotWin32(ComposeWindow window) throws Exception {
        window.renderImmediately();
        double scale = window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        BufferedImage output = new BufferedImage(
            (int) Math.round(window.getWidth() * scale),
            (int) Math.round(window.getHeight() * scale),
            BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = output.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, output.getWidth(), output.getHeight());
            Point origin = window.getLocationOnScreen();
            for (Surface s : activeSurfaces(window)) {
                if (!s.layer.isShowing()) continue;
                Point pt = s.layer.getCanvas().getLocationOnScreen();
                try (Bitmap bmp = s.layer.screenshot()) {
                    if (bmp == null) continue;
                    g.drawImage(surfaceToImage(bmp), (int) Math.round((pt.x - origin.x) * scale),
                        (int) Math.round((pt.y - origin.y) * scale), null);
                }
            }
        } finally {
            g.dispose();
        }
        return output;
    }

    private static BufferedImage surfaceToImage(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        ImageInfo format = new ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.Companion.getSRGB());
        byte[] bgra = bitmap.readPixels(format, Math.multiplyExact(width, 4), 0, 0);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] argb = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb);
        return image;
    }

    private static List<Surface> activeSurfaces(ComposeWindow window) throws Exception {
        Object panel = field(window, "composePanel");
        Method getter = panel.getClass().getDeclaredMethod("getComposeContainer");
        getter.setAccessible(true);
        Object container = getter.invoke(panel);
        List<Surface> result = new ArrayList<>();
        addSurfaces(result, field(container, "mediator"), true);
        for (Object layer : (List<?>) field(container, "layers")) {
            Method m = layer.getClass().getDeclaredMethod("getMediator");
            m.setAccessible(true);
            Object mediator = m.invoke(layer);
            boolean focusable = (boolean) layer.getClass().getMethod("getFocusable").invoke(layer);
            addSurfaces(result, mediator, focusable);
        }
        return result;
    }

    private static void addSurfaces(List<Surface> result, Object mediator, boolean focusable) throws Exception {
        Component comp = (Component) mediator.getClass().getMethod("getContentComponent").invoke(mediator);
        for (SkiaLayer l : layers(comp)) {
            if (l.isShowing()) result.add(new Surface(l, mediator, focusable));
        }
    }

    private static List<SkiaLayer> layers(Component c) {
        List<SkiaLayer> res = new ArrayList<>();
        if (c instanceof SkiaLayer l) res.add(l);
        if (c instanceof Container ct) for (Component ch : ct.getComponents()) res.addAll(layers(ch));
        return res;
    }

    private static Target targetAt(ComposeWindow window, double xPx, double yPx) throws Exception {
        double scale = window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        Point origin = window.getLocationOnScreen();
        List<Surface> surfaces = activeSurfaces(window);
        Collections.reverse(surfaces);
        for (Surface s : surfaces) {
            Point pt = s.layer.getCanvas().getLocationOnScreen();
            int lx = (int) Math.round(xPx / scale + origin.x - pt.x);
            int ly = (int) Math.round(yPx / scale + origin.y - pt.y);
            if (lx >= 0 && ly >= 0 && lx < s.layer.getCanvas().getWidth() && ly < s.layer.getCanvas().getHeight()) {
                return new Target(s.layer, s.mediator, lx, ly);
            }
        }
        throw new IllegalArgumentException("Point outside owned Compose surfaces: (" + xPx + "," + yPx + ")");
    }

    private static Target focusTarget(ComposeWindow window) throws Exception {
        List<Surface> surfaces = activeSurfaces(window);
        Collections.reverse(surfaces);
        for (Surface s : surfaces) {
            if (s.focusable) return new Target(s.layer, s.mediator, 0, 0);
        }
        return null;
    }

    private static void sendPointerWin32(ComposeWindow window, double xPx, double yPx, int type, int modifiers, int button) throws Exception {
        Target t = targetAt(window, xPx, yPx);
        takeLogicalFocus(t.mediator);
        MouseEvent ev = new MouseEvent(t.layer.getCanvas(), type, System.currentTimeMillis(), modifiers, t.x, t.y, 1, false, button);
        invoke(t.mediator, "onMouseEvent", MouseEvent.class, ev);
    }

    private static void takeLogicalFocus(Object mediator) throws Exception {
        Object manager = mediator.getClass().getMethod("getFocusManager").invoke(mediator);
        if ((boolean) manager.getClass().getMethod("getHasFocus").invoke(manager)) return;
        Object companion = Class.forName("androidx.compose.ui.focus.FocusDirection").getField("Companion").get(null);
        Method enter = Arrays.stream(companion.getClass().getMethods()).filter(m -> m.getName().startsWith("getEnter-")).findFirst().orElseThrow();
        Method take = Arrays.stream(manager.getClass().getMethods()).filter(m -> m.getName().startsWith("takeFocus-")).findFirst().orElseThrow();
        take.invoke(manager, enter.invoke(companion));
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void invoke(Object o, String name, Class<?> type, Object val) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, type);
        m.setAccessible(true);
        m.invoke(o, val);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task);
        return task.get(45, TimeUnit.SECONDS);
    }

    private static Path copyTempDir(Path src, String prefix) throws IOException {
        Path dst = Files.createTempDirectory(prefix);
        try (var stream = Files.walk(src)) {
            for (Path p : stream.toList()) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
        return dst;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ==================== 4. STUDY MODES REAL-WINDOW & NEWCOMER HARNESS (TON-1805) ====================

    private static String queryModeDbStateDigest(Path libDir) throws Exception {
        Path dbFile = libDir.resolve("library.sqlite");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            StringBuilder sb = new StringBuilder();
            try (var st = conn.createStatement()) {
                try (var rs = st.executeQuery("SELECT id, reading_page FROM books ORDER BY id")) {
                    while (rs.next()) sb.append("B:").append(rs.getString(1)).append("@").append(rs.getInt(2)).append(";");
                }
                try (var rs = st.executeQuery("SELECT book, page, start, end, quote FROM book_highlights ORDER BY page, start")) {
                    while (rs.next()) sb.append("H:").append(rs.getInt(2)).append(":").append(rs.getInt(3)).append("-").append(rs.getInt(4)).append(";");
                }
                try (var rs = st.executeQuery("SELECT id, page, start, end, text_sha256, note FROM book_notes WHERE deleted=0 ORDER BY id")) {
                    while (rs.next()) {
                        sb.append("N:").append(rs.getString(1)).append("@").append(rs.getInt(2))
                          .append(":").append(rs.getInt(3)).append("-").append(rs.getInt(4))
                          .append("#").append(rs.getString(5)).append("=").append(rs.getString(6)).append(";");
                    }
                }
                try {
                    try (var rs = st.executeQuery("SELECT book_id, toc_node_id, practice_draft_json FROM book_chapter_progress WHERE practice_draft_json <> '{}' ORDER BY book_id, toc_node_id")) {
                        while (rs.next()) {
                            sb.append("D:").append(rs.getString(1)).append("/").append(rs.getString(2))
                              .append("=").append(rs.getString(3)).append(";");
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    try (var rs = st.executeQuery("SELECT id, COALESCE(last_active_anchor_json, '') FROM collections ORDER BY id")) {
                        while (rs.next()) {
                            sb.append("A:").append(rs.getString(1)).append("=").append(rs.getString(2)).append(";");
                        }
                    }
                } catch (Exception ignored) {}
            }
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : dig) hex.append(String.format("%02x", b));
            return hex.toString();
        }
    }

    private static String currentSavedStudyMode(Path libDir) {
        Path dbFile = libDir.resolve("library.sqlite");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT study_mode, user_locked FROM book_study_preferences LIMIT 1")) {
            if (rs.next()) return rs.getString(1) + (rs.getInt(2) != 0 ? ":LOCKED" : ":DEFAULT");
        } catch (Exception ignored) {}
        return "NONE";
    }

    private static void seedHighlightsAndNotesForModeTest(Path libDir, String bookId, int page, Path sampleCh8Json) throws Exception {
        Path dbFile = libDir.resolve("library.sqlite");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var st = conn.createStatement()) {
            st.execute("INSERT OR REPLACE INTO book_highlights(book, page, start, end, quote) VALUES('" + bookId + "', " + page + ", 10, 42, 'sample-highlight-quote-1')");
            st.execute("INSERT OR REPLACE INTO book_notes(book, id, version, page, start, end, quote, note, text_sha256, revision, last_mutation_id, deleted) VALUES('" + bookId + "', 'mode-test-n1', '1', " + page + ", 10, 42, 'sample-note-quote-1', 'keep-note-across-modes', 'sha', 1, 'm1', 0)");
            try {
                st.execute("INSERT OR REPLACE INTO book_chapter_progress(book_id, toc_node_id, pages_total, pages_dwelled, dwelled_pages_json, read_state, manual_override, practice_draft_json, updated_at) VALUES('" + bookId + "', 'mode-test-ch8', 21, 1, '[179]', 'PARTIAL', 0, '{\"bookId\":\"" + bookId + "\",\"tocNodeId\":\"mode-test-ch8\",\"page\":179,\"exerciseHintLevels\":{\"ex-1\":1}}', 1000)");
            } catch (Exception ignored) {}
            st.execute("CREATE TABLE IF NOT EXISTS book_study_mode_packs(book_id TEXT NOT NULL, chapter_num INTEGER NOT NULL, study_mode TEXT NOT NULL, pack_json TEXT NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(book_id, chapter_num, study_mode))");
            if (sampleCh8Json != null && Files.isRegularFile(sampleCh8Json)) {
                String raw = Files.readString(sampleCh8Json, StandardCharsets.UTF_8);
                var json = kotlinx.serialization.json.Json.Default;
                var root = kotlinx.serialization.json.JsonElementKt.getJsonObject(json.parseToJsonElement(raw));
                var modesObj = kotlinx.serialization.json.JsonElementKt.getJsonObject(root.get("modes"));
                try (var ps = conn.prepareStatement("INSERT OR REPLACE INTO book_study_mode_packs(book_id, chapter_num, study_mode, pack_json, updated_at) VALUES(?,?,?,?,?)")) {
                    for (String mKey : List.of("BOOK_MAP", "EXAM_FOCUS", "DIAGNOSTIC_SKIP", "PROBLEM_DRIVEN", "QUESTION_DRIVEN", "READ_THROUGH")) {
                        var mEl = modesObj.get(mKey);
                        if (mEl != null) {
                            ps.setString(1, bookId);
                            ps.setInt(2, 8);
                            ps.setString(3, mKey);
                            ps.setString(4, mEl.toString());
                            ps.setLong(5, System.currentTimeMillis());
                            ps.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    private static void runModes(String[] args) throws Exception {
        Path libSeed = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Path sampleCh8Json = args.length > 3 ? Path.of(args[3]) : null;
        Files.createDirectories(outDir);
        Path shotsDir = outDir.resolve("shots");
        Files.createDirectories(shotsDir);

        Path libWork = copyTempDir(libSeed, "modes-win32-");
        try {
            TextbookLibrary lib = createLibrary(libWork);
            var catalog = lib.catalog();
            if (catalog.isEmpty()) throw new IllegalStateException("Empty library seed: " + libSeed);
            String bookId = catalog.get(0).getId();
            lib.saveTextView(bookId, false);

            TextbookController controller = createController(lib);
            runSuspend(c -> controller.load(c));
            controllerPageTo(controller, 179);
            int pageBefore = controllerPage(controller);
            runSuspend(c -> controller.back(c));
            seedHighlightsAndNotesForModeTest(libWork, bookId, 179, sampleCh8Json);
            String beforeDigest = queryModeDbStateDigest(libWork);

            ComposeWindow win = openHostWindow(controller, 32, 32, 1280, 820);
            try {
                waitFor(8000, () -> findNodeByTag(win, "home-mode-trigger") != null);
                Thread.sleep(600);
                dumpWin32(win, shotsDir, "modes", "1280x820", "01_home_smart_default");

                // Journey 1: Home -> non-default mode in <= 2 clicks (NO extra controller.page call!)
                int homeToNonDefaultClicks = 0;
                clickTagWin32(win, "home-mode-trigger");
                homeToNonDefaultClicks++;
                waitFor(4000, () -> findNodeByTag(win, "mode-option-BOOK_MAP") != null);
                Thread.sleep(400);
                dumpWin32(win, shotsDir, "modes", "1280x820", "02_mode_popover_open");

                clickTagWin32(win, "mode-option-BOOK_MAP");
                homeToNonDefaultClicks++;
                waitFor(6000, () -> findNodeByTag(win, "textbook-reader") != null);
                if (!waitFor(4000, () -> findNodeByTag(win, "mode-surface-BOOK_MAP") != null)) {
                    throw new IllegalStateException("Expected immediate mode-surface-BOOK_MAP First Screen after 2-click Home selection");
                }
                if (!waitFor(4000, () -> hasTextContaining(win, "第 8 / 24 章"))) {
                    throw new IllegalStateException("Expected BOOK_MAP chapter badge '第 8 / 24 章'");
                }
                if (sampleCh8Json != null && !hasTextContaining(win, "各节角色分工")) {
                    throw new IllegalStateException("Expected real BOOK_MAP one-pager sections ('各节角色分工') on First Screen");
                }
                Thread.sleep(700);

                // Landed directly in BOOK_MAP First Screen with 2 clicks total
                dumpWin32(win, shotsDir, "modes", "1280x820", "04_mode_BOOK_MAP");

                // Close companion to inspect clean reader state, then reopen to cycle remaining 5 modes
                revealV2HudWin32(win);
                if (findNodeByTag(win, "immersive-companion-drawer") != null) {
                    clickTagWin32(win, "immersive-companion-toggle");
                    waitFor(3000, () -> findNodeByTag(win, "immersive-companion-drawer") == null);
                }
                Thread.sleep(500);
                dumpWin32(win, shotsDir, "modes", "1280x820", "03_pure_read_ch8");

                revealV2HudWin32(win);
                clickTagWin32(win, "immersive-companion-toggle");
                waitFor(4000, () -> findNodeByTag(win, "companion-mode-trigger") != null);
                Thread.sleep(400);

                String[] switchSeq = {
                    "EXAM_FOCUS", "DIAGNOSTIC_SKIP", "PROBLEM_DRIVEN", "QUESTION_DRIVEN", "READ_THROUGH"
                };
                int maxSwitchClicks = 0;
                int idx = 5;
                for (String mName : switchSeq) {
                    int clicks = 0;
                    clickTagWin32(win, "companion-mode-trigger");
                    clicks++;
                    waitFor(4000, () -> findNodeByTag(win, "mode-option-" + mName) != null);
                    clickTagWin32(win, "mode-option-" + mName);
                    clicks++;
                    maxSwitchClicks = Math.max(maxSwitchClicks, clicks);
                    Thread.sleep(600);
                    dumpWin32(win, shotsDir, "modes", "1280x820", String.format("0%d_mode_%s", idx++, mName));
                }

                // Cycle all 3 adjustment knobs (detailLevel, explainLang, pacePreset) and verify zero data loss
                clickTagWin32(win, "companion-mode-trigger");
                waitFor(4000, () -> findNodeByTag(win, "mode-detail-brief") != null);
                clickTagWin32(win, "mode-detail-brief");
                Thread.sleep(200);
                clickTagWin32(win, "mode-lang-zh");
                Thread.sleep(200);
                clickTagWin32(win, "mode-pace-30");
                Thread.sleep(200);
                clickTagWin32(win, "mode-detail-detailed");
                Thread.sleep(200);
                clickTagWin32(win, "mode-lang-bilingual");
                Thread.sleep(200);
                clickTagWin32(win, "mode-pace-unpaced");
                Thread.sleep(200);
                clickTagWin32(win, "mode-option-READ_THROUGH");
                Thread.sleep(400);

                String afterDigest = queryModeDbStateDigest(libWork);
                int pageAfter = controllerPage(controller);
                boolean zeroLoss = beforeDigest.equals(afterDigest) && pageBefore == pageAfter;

                Map<String, Object> report = new HashMap<>();
                report.put("surface_type", "real_os_window");
                report.put("window_px", List.of(1280, 820));
                report.put("home_to_non_default_clicks", homeToNonDefaultClicks);
                report.put("max_mode_switch_clicks", maxSwitchClicks);
                report.put("page_before", pageBefore);
                report.put("page_after", pageAfter);
                report.put("state_sha256_before", beforeDigest);
                report.put("state_sha256_after", afterDigest);
                report.put("zero_data_loss", zeroLoss);
                report.put("modes_verified", List.of("BOOK_MAP", "EXAM_FOCUS", "DIAGNOSTIC_SKIP", "PROBLEM_DRIVEN", "QUESTION_DRIVEN", "READ_THROUGH"));
                Files.writeString(outDir.resolve("modes-winbox-report.json"), toJson(report, 0), StandardCharsets.UTF_8);
                System.out.println("MODES_REPORT homeClicks=" + homeToNonDefaultClicks + " switchClicks=" + maxSwitchClicks + " zeroLoss=" + zeroLoss);
                if (!zeroLoss || homeToNonDefaultClicks > 2 || maxSwitchClicks > 2) {
                    throw new IllegalStateException("Modes verification failed: " + report);
                }
            } finally {
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(libWork);
        }
    }

    private static void runNewcomer(String[] args) throws Exception {
        Path libSeed = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        int holdSec = args.length > 3 ? Integer.parseInt(args[3]) : 420;
        Files.createDirectories(outDir);
        Path libWork = copyTempDir(libSeed, "modes-newcomer-");
        try {
            TextbookLibrary lib = createLibrary(libWork);
            TextbookController controller = createController(lib);
            runSuspend(c -> controller.load(c));
            controllerPageTo(controller, 172);
            runSuspend(c -> controller.back(c));

            ComposeWindow[] box = new ComposeWindow[1];
            SwingUtilities.invokeAndWait(() -> {
                ComposeWindow win = new ComposeWindow();
                win.setTitle("LearningCoach-Newcomer-Session");
                win.setBounds(80, 60, 1280, 820);
                win.setAutoRequestFocus(true);
                win.setFocusableWindowState(true);
                win.setContent((scope, composer, changed) -> {
                    callHost(controller, composer);
                    return Unit.INSTANCE;
                });
                win.setVisible(true);
                box[0] = win;
                activeWindow = win;
            });
            ComposeWindow win = box[0];
            Thread.sleep(900);
            long tOpen = System.currentTimeMillis();
            AtomicInteger rawMouseClicks = new AtomicInteger(0);
            AtomicInteger cmdClicks = new AtomicInteger(0);
            java.awt.event.AWTEventListener clickListener = event -> {
                if (event instanceof MouseEvent me && me.getID() == MouseEvent.MOUSE_PRESSED) {
                    rawMouseClicks.incrementAndGet();
                }
            };
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clickListener, java.awt.AWTEvent.MOUSE_EVENT_MASK);

            try {
                Path liveShot = outDir.resolve("live-window.png");
                Path liveTmp = outDir.resolve("live-window.tmp.png");
                BufferedImage firstImg = onEdt(() -> screenshotWin32(win));
                ImageIO.write(firstImg, "png", liveShot.toFile());

                Path readyFile = outDir.resolve("window-ready.json");
                Point loc = onEdt(win::getLocationOnScreen);
                Map<String, Object> readyInfo = new HashMap<>();
                readyInfo.put("windowTitle", "LearningCoach-Newcomer-Session");
                readyInfo.put("x", loc.x);
                readyInfo.put("y", loc.y);
                readyInfo.put("width", firstImg.getWidth());
                readyInfo.put("height", firstImg.getHeight());
                readyInfo.put("liveScreenshot", liveShot.toString());
                readyInfo.put("openedAtEpochMs", tOpen);
                readyInfo.put("libWorkDir", libWork.toString());
                Files.writeString(readyFile, toJson(readyInfo, 0), StandardCharsets.UTF_8);
                System.out.println("NEWCOMER_WINDOW_READY " + readyFile);

                String initialState = currentSavedStudyMode(libWork);
                long firstSwitchElapsedMs = -1L;
                int clicksAtFirstSwitch = -1;
                long deadline = tOpen + holdSec * 1000L;
                Path stopFile = outDir.resolve("stop.signal");
                Path clickCmdFile = outDir.resolve("click.cmd");
                int tick = 0;
                while (System.currentTimeMillis() < deadline && !Files.exists(stopFile) && win.isDisplayable()) {
                    boolean didCmdClick = false;
                    if (Files.exists(clickCmdFile)) {
                        String cmdRaw = Files.readString(clickCmdFile, StandardCharsets.UTF_8).trim();
                        Files.deleteIfExists(clickCmdFile);
                        String[] parts = cmdRaw.split("\\s+");
                        if (parts.length >= 2) {
                            double cx = Double.parseDouble(parts[0]);
                            double cy = Double.parseDouble(parts[1]);
                            cmdClicks.incrementAndGet();
                            onEdt(() -> {
                                sendPointerWin32(win, cx, cy, MouseEvent.MOUSE_MOVED, 0, MouseEvent.NOBUTTON);
                                sendPointerWin32(win, cx, cy, MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                                sendPointerWin32(win, cx, cy, MouseEvent.MOUSE_RELEASED, 0, MouseEvent.BUTTON1);
                                sendPointerWin32(win, cx, cy, MouseEvent.MOUSE_CLICKED, 0, MouseEvent.BUTTON1);
                                return null;
                            });
                            didCmdClick = true;
                            Thread.sleep(350);
                        }
                    }
                    if (didCmdClick || (tick++ % 5 == 0)) {
                        BufferedImage snap = onEdt(() -> screenshotWin32(win));
                        ImageIO.write(snap, "png", liveTmp.toFile());
                        Files.move(liveTmp, liveShot, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    int totalClicks = rawMouseClicks.get() + cmdClicks.get();
                    String cur = currentSavedStudyMode(libWork);
                    boolean switched = !cur.equals(initialState) && cur.endsWith(":LOCKED");
                    if (switched && firstSwitchElapsedMs < 0) {
                        firstSwitchElapsedMs = System.currentTimeMillis() - tOpen;
                        clicksAtFirstSwitch = totalClicks;
                        dumpWin32(win, outDir, "newcomer", "1280x820", "switched_state");
                    }
                    Map<String, Object> status = new HashMap<>();
                    status.put("initialMode", initialState);
                    status.put("currentMode", cur);
                    status.put("modeSwitched", switched);
                    status.put("totalClicks", totalClicks);
                    status.put("clicksAtFirstSwitch", clicksAtFirstSwitch);
                    status.put("misclicksAtFirstSwitch", clicksAtFirstSwitch > 0 ? Math.max(0, clicksAtFirstSwitch - 2) : -1);
                    status.put("firstSwitchElapsedMs", firstSwitchElapsedMs);
                    status.put("elapsedMs", System.currentTimeMillis() - tOpen);
                    Files.writeString(outDir.resolve("newcomer-telemetry.json"), toJson(status, 0), StandardCharsets.UTF_8);
                    if (didCmdClick) {
                        Files.writeString(outDir.resolve("click.done"), String.valueOf(totalClicks), StandardCharsets.UTF_8);
                    }
                    Thread.sleep(200);
                }
                dumpWin32(win, outDir, "newcomer", "1280x820", "final_state");
            } finally {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(clickListener);
                disposeWindow(win);
            }
        } finally {
            deleteQuietly(libWork);
        }
    }

    // ==================== LOOPBACK STUB SERVER FOR LOGIN 4 PATHS ====================

    private static final class StubAuthServer implements AutoCloseable {
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final HttpServer server;
        final String url;
        final AtomicBoolean approved = new AtomicBoolean(false);
        final AtomicInteger counter = new AtomicInteger(0);
        volatile boolean revokeAll = false;

        StubAuthServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(pool);
            url = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : "";
            String path = ex.getRequestURI().getPath();
            int status;
            String body;
            switch (path) {
                case "/api/device/code" -> {
                    approved.set(false);
                    status = 200;
                    body = "{\"device_code\":\"acc-dev\",\"user_code\":\"BCDF-GHJK\",\"expires_in\":120,\"interval\":1,\"verification_uri\":\"" + url + "/link\",\"verification_uri_complete\":\"" + url + "/link?code=BCDF-GHJK\"}";
                }
                case "/link" -> {
                    approved.set("code=BCDF-GHJK".equals(ex.getRequestURI().getQuery()));
                    status = 200;
                    body = "{}";
                }
                case "/api/device/token" -> {
                    if (approved.get()) { status = 200; body = "{\"access_token\":\"acc-sable-grant\"}"; }
                    else { status = 400; body = "{\"error\":\"authorization_pending\"}"; }
                }
                case "/v1/public/sable" -> {
                    if ("acc-sable-grant".equals(token)) {
                        int idx = counter.getAndIncrement();
                        String tok = String.valueOf((char) ('a' + (idx % 20))).repeat(43);
                        status = 200;
                        body = "{\"deviceId\":\"acc-pc-" + idx + "\",\"token\":\"" + tok + "\",\"role\":\"friend\"}";
                    } else { status = 401; body = "{\"error\":\"unauthorized\"}"; }
                }
                case "/v1/public/status" -> {
                    if (revokeAll) { status = 401; body = "{\"error\":\"unauthorized\"}"; }
                    else { status = 200; body = "{\"online\":true}"; }
                }
                default -> { status = 404; body = "{}"; }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        @Override public void close() {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    // ==================== MINIMAL JSON SERIALIZER ====================

    private static String toJson(Object obj, int indent) {
        String pad = "  ".repeat(indent);
        String pad2 = "  ".repeat(indent + 1);
        if (obj == null) return "null";
        if (obj instanceof Boolean || obj instanceof Number) return obj.toString();
        if (obj instanceof String s) return quote(s);
        if (obj instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(pad2).append(toJson(list.get(i), indent + 1));
                if (i + 1 < list.size()) sb.append(',');
                sb.append('\n');
            }
            return sb.append(pad).append(']').toString();
        }
        if (obj instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{\n");
            int i = 0;
            for (var e : map.entrySet()) {
                sb.append(pad2).append(quote(String.valueOf(e.getKey()))).append(": ").append(toJson(e.getValue(), indent + 1));
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            return sb.append(pad).append('}').toString();
        }
        return quote(obj.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}

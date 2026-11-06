import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/** Closed-schema inspection of the recording itself, never a caller's count flag. */
class Ton1175Jfr {
    // Observed in the old 8d3608f managed JVM's real, untruncated recording.
    // The verifier binds bridgePort to the staged old plist, not idle evidence.
    static final String HEALTH_FRAME = "com.coach.server.ChildServices$supervise$1#invokeSuspend";
    static final long MAX_HEALTH_BYTES = 1024;
    static final int MAX_ROUND_READS = 4;
    static final long ROUND_SPAN_NANOS = Duration.ofSeconds(5).toNanos();
    static final long MIN_ROUND_GAP_NANOS = Duration.ofSeconds(9).toNanos();

    record HealthEvent(Instant start, long thread, long bytes) {}
    static class Counts {
        long pid, jvmStartEpochMillis, socketReads, attributedHealthReads, clientReads;
        long healthRounds, maxReadsPerRound, maxBytesPerRound, minRoundGapMillis;
        String rejectReason = "NONE";
        void emit() {
            System.out.printf(Locale.ROOT, "{\"pid\":%d,\"jvmStartEpochMillis\":%d,"
                + "\"socketReads\":%d,\"attributedHealthReads\":%d,\"clientReads\":%d,"
                + "\"healthRounds\":%d,\"maxReadsPerRound\":%d,\"maxBytesPerRound\":%d,"
                + "\"minRoundGapMillis\":%d,\"rejectReason\":\"%s\"}%n",
                pid, jvmStartEpochMillis, socketReads, attributedHealthReads, clientReads,
                healthRounds, maxReadsPerRound, maxBytesPerRound, minRoundGapMillis, rejectReason);
        }
    }
    static String rounds(List<HealthEvent> events, long windowNanos, Counts counts) {
        events.sort(Comparator.comparing(HealthEvent::start));
        Instant first = null;
        long thread = -1, reads = 0, bytes = 0, minGap = Long.MAX_VALUE;
        boolean crossedThreads = false, overReads = false, overBytes = false, shortGap = false;
        for (HealthEvent event : events) {
            if (first == null || Duration.between(first, event.start()).toNanos() > ROUND_SPAN_NANOS) {
                if (first != null) {
                    long gap = Duration.between(first, event.start()).toNanos();
                    minGap = Math.min(minGap, gap);
                    if (gap < MIN_ROUND_GAP_NANOS) shortGap = true;
                }
                first = event.start(); thread = event.thread(); reads = 0; bytes = 0;
                counts.healthRounds++;
            }
            if (event.thread() < 0 || event.thread() != thread) crossedThreads = true;
            reads++; bytes += event.bytes();
            counts.maxReadsPerRound = Math.max(counts.maxReadsPerRound, reads);
            counts.maxBytesPerRound = Math.max(counts.maxBytesPerRound, bytes);
            if (reads > MAX_ROUND_READS) overReads = true;
            if (bytes > MAX_HEALTH_BYTES) overBytes = true;
        }
        counts.minRoundGapMillis = minGap == Long.MAX_VALUE ? 0 : minGap / 1_000_000;
        if (crossedThreads) return "ROUND_THREADS";
        if (overReads) return "ROUND_READ_LIMIT";
        if (overBytes) return "ROUND_BYTES_LIMIT";
        if (shortGap) return "ROUND_GAP";
        if (counts.healthRounds > healthReadLimit(windowNanos)) return "ROUND_COUNT";
        return "NONE";
    }

    static boolean healthRead(String address, int port, long bytes, boolean truncated,
                              List<String> frames, int bridgePort) {
        if (!"127.0.0.1".equals(address) || bridgePort <= 0 || bridgePort > 65535
                || port != bridgePort || bytes <= 0 || bytes > MAX_HEALTH_BYTES
                || truncated || frames == null || frames.isEmpty()) return false;
        boolean health = false;
        for (String raw : frames) {
            if (raw == null) return false;
            String frame = raw.replace('/', '.');
            String lower = frame.toLowerCase(Locale.ROOT);
            if (lower.contains("bridgeclient") || lower.contains("io.ktor.server")
                    || lower.contains("routing") || lower.contains("session")) return false;
            if (HEALTH_FRAME.equals(frame)) health = true;
        }
        return health;
    }

    static long healthReadLimit(long windowNanos) {
        need(windowNanos > 0);
        long period = Duration.ofSeconds(10).toNanos();
        return Math.addExact(Math.floorDiv(Math.addExact(windowNanos, period - 1), period), 2);
    }

    static void need(boolean condition) {
        if (!condition) throw new IllegalArgumentException("JFR evidence rejected");
    }

    public static void main(String[] args) throws Exception {
        Counts counts = new Counts();
        try {
            need(args.length == 4 || args.length == 5);
            Path path = Path.of(args[0]);
            Instant start = Instant.parse(args[1]), end = Instant.parse(args[2]);
            long pid = Long.parseLong(args[3]);
            counts.pid = pid;
            int bridgePort = args.length == 5 ? Integer.parseInt(args[4]) : 0;
            need(bridgePort >= 0 && bridgePort <= 65535 && start.isBefore(end));
            long socketId = -1;
            Set<Long> guardedIds = new HashSet<>();
            try (RecordingFile file = new RecordingFile(path)) {
                for (EventType type : file.readEventTypes()) {
                    if (type.getName().equals("jdk.SocketRead")) socketId = type.getId();
                    if (Set.of("jdk.SocketRead", "jdk.ActiveSetting", "jdk.DataLoss",
                            "jdk.CPULoad", "jdk.JVMInformation").contains(type.getName())) {
                        guardedIds.add(type.getId());
                    }
                }
            }
            need(socketId >= 0 && guardedIds.size() == 5);
            List<Instant> heartbeats = new ArrayList<>();
            List<RecordedEvent> settings = new ArrayList<>();
            Instant jvmStart = null;
            List<HealthEvent> health = new ArrayList<>();
            boolean lost = false, badIdentity = false;
            boolean identity = false;
            try (RecordingFile file = new RecordingFile(path)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    switch (event.getEventType().getName()) {
                        case "jdk.DataLoss":
                            lost = true;
                            break;
                        case "jdk.CPULoad":
                            heartbeats.add(event.getStartTime());
                            break;
                        case "jdk.ActiveSetting":
                            if (guardedIds.contains(event.getLong("id"))) settings.add(event);
                            break;
                        case "jdk.JVMInformation":
                            if (event.getLong("pid") != pid) badIdentity = true;
                            Instant stamp = event.getInstant("jvmStartTime");
                            if (jvmStart != null && !jvmStart.equals(stamp)) badIdentity = true;
                            jvmStart = stamp;
                            if (!"com.coach.server.MainKt".equals(event.getString("javaArguments"))) badIdentity = true;
                            identity = true;
                            break;
                        case "jdk.SocketRead":
                            if (event.getLong("bytesRead") > 0 && !event.getEndTime().isBefore(start)
                                    && !event.getStartTime().isAfter(end)) {
                                counts.socketReads++;
                                RecordedStackTrace stack = event.getStackTrace();
                                List<String> frames = new ArrayList<>();
                                if (stack != null) {
                                    for (RecordedFrame frame : stack.getFrames()) {
                                        frames.add(frame.getMethod().getType().getName() + "#"
                                                + frame.getMethod().getName());
                                    }
                                }
                                if (healthRead(event.getString("address"), event.getInt("port"),
                                        event.getLong("bytesRead"), stack == null || stack.isTruncated(),
                                        frames, bridgePort)) {
                                    counts.attributedHealthReads++;
                                    var thread = event.getThread();
                                    health.add(new HealthEvent(event.getStartTime(), thread == null ? -1 : thread.getId(),
                                            event.getLong("bytesRead")));
                                }
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
            counts.clientReads = counts.socketReads - counts.attributedHealthReads;
            if (jvmStart != null) counts.jvmStartEpochMillis = jvmStart.toEpochMilli();
            counts.rejectReason = rounds(health, Duration.between(start, end).toNanos(), counts);
            need(!lost && !badIdentity && identity && jvmStart != null && jvmStart.isBefore(start));
            // Select the setting in effect at window start, reject loss of
            // coverage anywhere inside it. No fixed JDK event IDs are assumed.
            settings.sort(Comparator.comparing(RecordedEvent::getStartTime));
            Map<Long, Map<String, String>> active = new HashMap<>();
            for (RecordedEvent setting : settings) {
                if (!setting.getStartTime().isAfter(start)) {
                    active.computeIfAbsent(setting.getLong("id"), ignored -> new HashMap<>())
                            .put(setting.getString("name"), setting.getString("value"));
                }
            }
            for (long id : guardedIds) {
                need(active.containsKey(id) && "true".equals(active.get(id).get("enabled")));
            }
            need("0 ns".equals(active.get(socketId).get("threshold")));
            if (bridgePort != 0) need("true".equals(active.get(socketId).get("stackTrace")));
            for (RecordedEvent setting : settings) {
                if (setting.getStartTime().isAfter(start) && !setting.getStartTime().isAfter(end)) {
                    String name = setting.getString("name"), value = setting.getString("value");
                    need(!name.equals("enabled") || value.equals("true"));
                    need(setting.getLong("id") != socketId || !name.equals("threshold") || value.equals("0 ns"));
                    need(bridgePort == 0 || setting.getLong("id") != socketId
                            || !name.equals("stackTrace") || value.equals("true"));
                }
            }
            heartbeats.sort(Comparator.naturalOrder());
            need(!heartbeats.isEmpty() && !heartbeats.get(0).isAfter(start)
                    && !heartbeats.get(heartbeats.size()-1).isBefore(end));
            Instant previous = heartbeats.get(0);
            for (Instant beat : heartbeats) {
                if (!beat.isBefore(start) && !previous.isAfter(end)) {
                    need(Duration.between(previous, beat).compareTo(Duration.ofSeconds(2)) <= 0);
                }
                previous = beat;
            }
            need(counts.rejectReason.equals("NONE"));
            // The caller rejects clientReads even where this parser succeeded.
            if (counts.clientReads != 0) counts.rejectReason = "CLIENT_READ";
            counts.emit();
        } catch (Exception error) {
            counts.clientReads = counts.socketReads - counts.attributedHealthReads;
            if (counts.rejectReason.equals("NONE")) counts.rejectReason = "EVIDENCE_INVALID";
            counts.emit();
            // Static source location is useful to reviewers without disclosing
            // recording values or arbitrary exception messages.
            StackTraceElement[] trace = error.getStackTrace();
            int line = trace.length > 1 ? trace[1].getLineNumber() : 0;
            System.err.println("JFR evidence rejected; inspector line " + line);
            System.exit(1);
        }
    }
}

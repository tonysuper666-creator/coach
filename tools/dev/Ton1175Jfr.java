import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Closed-schema inspection of the recording itself, never a caller's count flag. */
class Ton1175Jfr {
    static void need(boolean condition) {
        if (!condition) throw new IllegalArgumentException("JFR evidence rejected");
    }

    public static void main(String[] args) throws Exception {
        try {
            Path path = Path.of(args[0]);
            Instant start = Instant.parse(args[1]), end = Instant.parse(args[2]);
            long pid = Long.parseLong(args[3]);
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
            long reads = 0;
            boolean identity = false;
            try (RecordingFile file = new RecordingFile(path)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    switch (event.getEventType().getName()) {
                        case "jdk.DataLoss":
                            need(false);
                            break;
                        case "jdk.CPULoad":
                            heartbeats.add(event.getStartTime());
                            break;
                        case "jdk.ActiveSetting":
                            if (guardedIds.contains(event.getLong("id"))) settings.add(event);
                            break;
                        case "jdk.JVMInformation":
                            need(event.getLong("pid") == pid);
                            Instant stamp = event.getInstant("jvmStartTime");
                            need(jvmStart == null || jvmStart.equals(stamp));
                            jvmStart = stamp;
                            need("com.coach.server.MainKt".equals(event.getString("javaArguments")));
                            identity = true;
                            break;
                        case "jdk.SocketRead":
                            if (event.getLong("bytesRead") > 0 && !event.getEndTime().isBefore(start)
                                    && !event.getStartTime().isAfter(end)) reads++;
                            break;
                        default:
                            break;
                    }
                }
            }
            need(identity && jvmStart != null && jvmStart.isBefore(start));
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
            for (RecordedEvent setting : settings) {
                if (setting.getStartTime().isAfter(start) && !setting.getStartTime().isAfter(end)) {
                    String name = setting.getString("name"), value = setting.getString("value");
                    need(!name.equals("enabled") || value.equals("true"));
                    need(setting.getLong("id") != socketId || !name.equals("threshold") || value.equals("0 ns"));
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
            // Count every socket read, including health checks: conservative
            // rejection is preferable to guessing that a peer was internal.
            System.out.printf(Locale.ROOT, "{\"pid\":%d,\"jvmStart\":\"%s\",\"socketReads\":%d}%n",
                    pid, jvmStart, reads);
        } catch (Exception error) {
            // Static source location is useful to reviewers without disclosing
            // recording values or arbitrary exception messages.
            StackTraceElement[] trace = error.getStackTrace();
            int line = trace.length > 1 ? trace[1].getLineNumber() : 0;
            System.err.println("JFR evidence rejected; inspector line " + line);
            System.exit(1);
        }
    }
}

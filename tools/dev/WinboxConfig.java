import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

/** Read-only fingerprints using precisely androidApp's Properties.load semantics. */
class WinboxConfig {
    public static void main(String[] args) throws Exception {
        Path file = args.length == 0
            ? Path.of(System.getProperty("user.home"), "Coach/config/local.properties")
            : Path.of(args[0]);
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
        }
        String[] keys = {"coach.tailHost", "coach.course", "coach.port"};
        System.out.print("{");
        for (int i = 0; i < keys.length; i++) {
            String value = properties.getProperty(keys[i], keys[i].equals("coach.port") ? "18791" : "");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            if (i > 0) System.out.print(",");
            System.out.print("\"" + keys[i] + "\":\"" + HexFormat.of().formatHex(hash) + "\"");
        }
        System.out.println("}");
    }
}

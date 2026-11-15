import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// Source-launcher fixture. Holds only a lock in the test's generated synthetic directory.
class HoldAuthLock {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            System.out.println("SYNTHETIC_LOCK_HELD");
            System.out.flush();
            System.in.read();
        }
    }
}

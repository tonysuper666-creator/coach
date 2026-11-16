import com.coach.desktop.books.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.*;

/** Local native provenance persistence exercise. Emits aggregate counts only.
 * Input EPUB/DOCX and the new private library directory must remain on their owning machine.
 * No rendering, semantic correctness or permission to hide source text is established.
 */
public class NativeSourcePersistenceProbe {
    static void require(boolean value) { if (!value) throw new AssertionError("native persistence invariant"); }
    static TextbookLibrary library(Path directory) {
        BookOcr noOcr = (image, control) -> { throw new AssertionError("unexpected OCR"); };
        return new TextbookLibrary(directory, Clock.systemUTC(), noOcr,
            (path, format, title, ocr, control) -> TextbookExtraction.INSTANCE.extract(path, format, title, ocr, control));
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3);
        Path input = Path.of(args[0]); String format = args[1];
        require(format.equals("epub") || format.equals("docx"));
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(!Files.exists(output)); Files.createDirectories(output);
        output = output.toRealPath();
        for (Path parent = output; parent != null; parent = parent.getParent())
            if (Files.exists(parent.resolve(".git"))) throw new IllegalArgumentException("output must be outside Git");
        var baseline = TextbookExtraction.INSTANCE.extract(input, format, "Private native check",
            (image, control) -> { throw new AssertionError("unexpected OCR"); }, new BookImportControl());
        var library = library(output); var book = library.importBook(input, new BookImportControl());
        require(book.getFormat().equals(format) && baseline.getPages().equals(book.getPages()));
        var sources = library.nativeSources(book.getId()); require(sources != null);
        var blocks = StudyCoachCourse.INSTANCE.blocks(book.getId(), book.getPages());
        var citations = new ArrayList<kotlin.Pair<OwnedBookCitation, String>>();
        for (var block : blocks) if (!block.getHeading()) citations.add(new kotlin.Pair<>(
            new OwnedBookCitation(book.getId(), block.getPage(), block.getStart(), block.getEnd()), block.getText()));
        var resolved = new BookSourceResolver(library).resolveAll(citations);
        require(resolved.stream().allMatch(r -> r instanceof BookSourceResolution.Resolved));
        var reopened = library(output);
        require(sources.equals(reopened.nativeSources(book.getId())));
        require(book.getPages().equals(reopened.book(book.getId()).getPages()));
        require(resolved.equals(new BookSourceResolver(reopened).resolveAll(citations)));
        // Simulate an old library without an index, then explicitly backfill its retained archive.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + output.resolve("library.sqlite")); var sql = db.createStatement()) {
            sql.executeUpdate("DELETE FROM book_native_sources");
        }
        require(reopened.nativeSources(book.getId()) == null);
        require(sources.equals(reopened.retainNativeSources(book.getId(), new BookImportControl())));
        require(sources.equals(library(output).nativeSources(book.getId())));
        require(blocks.equals(StudyCoachCourse.INSTANCE.blocks(book.getId(), reopened.book(book.getId()).getPages())));
        int ranges = sources.getElements().stream().mapToInt(e -> e.getRanges().size()).sum();
        // A same-length change still invalidates the whole saved index and cannot be relocated.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + output.resolve("library.sqlite"));
             var sql = db.prepareStatement("UPDATE pages SET text=? WHERE book=? AND page=?")) {
            var first = book.getPages().stream().filter(p -> !p.getText().isEmpty()).findFirst().orElseThrow();
            String text = first.getText(); char replacement = text.charAt(0) == 'X' ? 'Y' : 'X';
            sql.setString(1, replacement + text.substring(1)); sql.setString(2, book.getId()); sql.setInt(3, first.getNumber()); sql.executeUpdate();
        }
        require(library(output).nativeSources(book.getId()) == null);
        require(library(output).retainNativeSources(book.getId(), new BookImportControl()) == null);
        // Restore only the private disposable test library, leaving no changed canonical page.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + output.resolve("library.sqlite"));
             var sql = db.prepareStatement("UPDATE pages SET text=? WHERE book=? AND page=?")) {
            for (var page : book.getPages()) { sql.setString(1, page.getText()); sql.setString(2, book.getId()); sql.setInt(3, page.getNumber()); sql.addBatch(); }
            sql.executeBatch();
        }
        require(sources.equals(library(output).nativeSources(book.getId())));
        require(resolved.equals(new BookSourceResolver(library(output)).resolveAll(citations)));
        System.out.printf("{\"format\":\"%s\",\"pages\":%d,\"elements\":%d,\"ranges\":%d,\"citations_unchanged\":%d,\"reopened_equal\":true,\"backfill_equal\":true,\"same_length_stale_rejected\":true}%n",
            format, book.getPages().size(), sources.getElements().size(), ranges, citations.size());
    }
}

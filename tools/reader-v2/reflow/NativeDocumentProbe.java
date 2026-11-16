import com.coach.desktop.books.*;
import java.nio.file.*;
import java.util.*;

/** Aggregate-only, local read of a private library already made by NativeSourcePersistenceProbe.
 * Does not export XML or certify styles, formulas, semantic correctness or reader display.
 */
public class NativeDocumentProbe {
    static void require(boolean value) { if (!value) throw new AssertionError("native document invariant"); }
    static int[] count(BookXmlElement root) {
        int[] result = new int[5];
        var pending = new ArrayDeque<BookXmlElement>(); pending.push(root);
        while (!pending.isEmpty()) {
            var node = pending.pop(); result[0]++; result[1] += node.getAttributes().size();
            if (node.getName().getLocalName().equals("table") || node.getName().getLocalName().equals("tbl")) result[2]++;
            if (node.getName().getLocalName().equals("math") || node.getName().getLocalName().equals("oMath")) result[3]++;
            if (Set.of("style", "pStyle", "rStyle", "pPr", "rPr").contains(node.getName().getLocalName())) result[4]++;
            for (var child : node.getChildren()) pending.push(child);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 1);
        Path directory = Path.of(args[0]).toRealPath();
        for (Path p = directory; p != null; p = p.getParent()) require(!Files.exists(p.resolve(".git")));
        var library = NativeSourcePersistenceProbe.library(directory);
        require(library.catalog().size() == 1);
        var book = library.book(library.catalog().get(0).getId());
        var index = library.nativeSources(book.getId()); require(index != null);
        var citations = new ArrayList<kotlin.Pair<OwnedBookCitation, String>>();
        for (var block : StudyCoachCourse.INSTANCE.blocks(book.getId(), book.getPages())) if (!block.getHeading())
            citations.add(new kotlin.Pair<>(new OwnedBookCitation(book.getId(), block.getPage(), block.getStart(), block.getEnd()), block.getText()));
        var before = new BookSourceResolver(library).resolveAll(citations);
        var members = new LinkedHashSet<String>();
        for (var source : index.getElements()) members.add(source.getMember());
        int resolved = 0; int[] counts = new int[5]; long start = System.nanoTime();
        var reopened = NativeSourcePersistenceProbe.library(directory);
        for (var member : members) {
            var doc = library.nativeDocument(book.getId(), member, new BookImportControl()); require(doc != null);
            var again = reopened.nativeDocument(book.getId(), member, new BookImportControl()); require(again != null);
            require(doc.getRoot().equals(again.getRoot()));
            for (var source : doc.getSources()) { require(doc.resolve(source) != null); resolved++; }
            var total = count(doc.getRoot()); for (int i = 0; i < total.length; i++) counts[i] += total[i];
        }
        require(resolved == index.getElements().size());
        require(index.equals(reopened.nativeSources(book.getId())));
        require(book.getPages().equals(reopened.book(book.getId()).getPages()));
        require(before.equals(new BookSourceResolver(reopened).resolveAll(citations)));
        System.out.printf(Locale.ROOT, "{\"format\":\"%s\",\"pages\":%d,\"members\":%d,\"resolved_elements\":%d,\"xml_elements\":%d,\"attributes\":%d,\"tables\":%d,\"formula_roots\":%d,\"style_nodes\":%d,\"citations_unchanged\":%d,\"read_and_reopen_ms\":%.1f,\"reopened_equal\":true}%n",
            book.getFormat(), book.getPages().size(), members.size(), resolved, counts[0], counts[1], counts[2], counts[3], counts[4], citations.size(), (System.nanoTime() - start) / 1e6);
    }
}

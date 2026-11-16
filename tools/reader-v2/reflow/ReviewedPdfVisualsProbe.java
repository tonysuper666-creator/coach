import com.coach.desktop.books.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import javax.imageio.ImageIO;

/** Local full-size review and production persistence exercise. Emits aggregates only.
 * preview PDF selections-dir new-output-dir
 * apply PDF approved-reviews-dir library-dir
 * Selections/reviews/images contain private material: all directories must be outside Git.
 * No command automatically changes UNREVIEWED into COMPLETE_OBJECT_WITH_LABELS.
 */
public class ReviewedPdfVisualsProbe {
    static void require(boolean value) { if (!value) throw new AssertionError("reviewed visual invariant"); }
    static Path privateDirectory(String value) throws Exception {
        Path path = Path.of(value).toAbsolutePath().normalize();
        Files.createDirectories(path);
        path = path.toRealPath();
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.exists(parent.resolve(".git"))) throw new IllegalArgumentException("output must be outside Git");
        return path;
    }
    static List<Path> files(Path directory, String suffix) throws Exception {
        try (var paths = Files.list(directory)) { return paths.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList(); }
    }
    static TextbookLibrary library(Path directory) {
        BookOcr noOcr = (image, control) -> { throw new AssertionError("unexpected OCR"); };
        return new TextbookLibrary(directory, Clock.systemUTC(), noOcr,
            (path, format, title, ocr, control) -> TextbookExtraction.INSTANCE.extract(path, format, title, ocr, control));
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 4);
        Path pdf = Path.of(args[1]);
        Path input = privateDirectory(args[2]);
        Path output = privateDirectory(args[3]);
        if (args[0].equals("preview")) {
            require(files(output, ".review.json").isEmpty());
            var selections = new ArrayList<PdfVisualSelection>();
            var names = files(input, ".selection.json");
            for (Path path : names) selections.add(PdfVisualReviewCodec.INSTANCE.decodeSelection(Files.readString(path)));
            byte[] bytes = Files.readAllBytes(pdf);
            var pages = new ArrayList<BookPage>();
            try (var document = Loader.loadPDF(bytes)) {
                for (int number : selections.stream().map(PdfVisualSelection::getPage).distinct().toList()) {
                    pages.add(new BookPage(number, PdfPageTextExtraction.INSTANCE.extract(document, number).getUnit().getText(), false, List.of(), null));
                    var frame = document.getPage(number - 1).getCropBox();
                    var raster = new PDFRenderer(document).renderImage(number - 1, Math.min(2f, 4096f / Math.max(frame.getWidth(), frame.getHeight())));
                    try { ImageIO.write(raster, "png", output.resolve("page-" + number + ".png").toFile()); }
                    finally { raster.flush(); }
                }
            }
            var previews = PdfReviewedVisuals.INSTANCE.preview(bytes, pages, selections, new BookImportControl());
            for (int i = 0; i < previews.size(); i++) {
                String name = names.get(i).getFileName().toString().replace(".selection.json", "");
                Files.write(output.resolve(name + ".png"), previews.get(i).getImage().getBytes());
                Files.writeString(output.resolve(name + ".review.json"), PdfVisualReviewCodec.INSTANCE.encode(previews.get(i).getReview()));
            }
            System.out.println("{\"preview_objects\":" + previews.size() + ",\"pages\":" + pages.size() + ",\"approved\":false}");
        } else {
            require(args[0].equals("apply"));
            var reviews = new ArrayList<PdfVisualReview>();
            for (Path path : files(input, ".review.json")) reviews.add(PdfVisualReviewCodec.INSTANCE.decode(Files.readString(path)));
            require(!reviews.isEmpty() && reviews.stream().allMatch(r -> r.getDecision() == PdfVisualDecision.COMPLETE_OBJECT_WITH_LABELS));
            var library = library(output);
            var book = library.importBook(pdf, new BookImportControl());
            var blocks = StudyCoachCourse.INSTANCE.blocks(book.getId(), book.getPages());
            var citations = new ArrayList<kotlin.Pair<OwnedBookCitation, String>>();
            for (var block : blocks) if (!block.getHeading()) citations.add(new kotlin.Pair<>(
                new OwnedBookCitation(book.getId(), block.getPage(), block.getStart(), block.getEnd()), block.getText()));
            var resolved = new BookSourceResolver(library).resolveAll(citations);
            require(resolved.stream().allMatch(r -> r instanceof BookSourceResolution.Resolved));
            var saved = library.addReviewedPdfVisuals(book.getId(), reviews, new BookImportControl());
            require(book.getPages().stream().map(BookPage::getText).toList().equals(saved.getPages().stream().map(BookPage::getText).toList()));
            var reopened = library(output);
            require(saved.getPages().equals(reopened.book(book.getId()).getPages()));
            require(blocks.equals(StudyCoachCourse.INSTANCE.blocks(book.getId(), saved.getPages())));
            require(resolved.equals(new BookSourceResolver(reopened).resolveAll(citations)));
            require(new HashSet<>(reviews).equals(new HashSet<>(reopened.pdfVisualReviews(book.getId()))));
            int hideChecks = 0;
            for (var page : saved.getPages()) for (boolean hide : new boolean[]{false, true}) {
                var text = new StringBuilder();
                for (var segment : BookIllustrationLayout.INSTANCE.segment(page.getText(), page.getIllustrations(), hide))
                    if (segment instanceof BookPageSegment.Text t) text.append(t.getText());
                require(text.toString().equals(page.getText())); hideChecks++;
            }
            int bytesVerified = 0;
            for (var review : reviews) {
                var asset = saved.page(review.getSelection().getPage()).getIllustrations().stream()
                    .filter(a -> a.getImageSha256().equals(review.getPngSha256())).findFirst().orElseThrow();
                byte[] data = Files.readAllBytes(BookIllustrationAssets.INSTANCE.imageFile(output, book.getId(), asset));
                require(BookIllustrationAssets.INSTANCE.sha256(data).equals(review.getPngSha256()));
                require(asset.getSourceStart() == null && asset.getSourceEnd() == null); bytesVerified++;
            }
            System.out.println("{\"reviewed_objects\":" + reviews.size() + ",\"pages\":" + saved.getPages().size() +
                ",\"asset_digests_verified\":" + bytesVerified + ",\"citations_unchanged\":" + citations.size() +
                ",\"hide_checks\":" + hideChecks + ",\"reopened_equal\":true}");
        }
    }
}

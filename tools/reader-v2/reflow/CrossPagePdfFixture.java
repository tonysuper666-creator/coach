/* Self-authored long table: header on pages 1/3, absent on page 2. */
import java.io.*;
import java.util.Base64;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;

public final class CrossPagePdfFixture {
    static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static void text(PDPageContentStream s, float x, float y, String value) throws IOException {
        s.beginText(); s.setFont(FONT, 12); s.newLineAtOffset(x, y); s.showText(value); s.endText();
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        String[][] values = {{"Header A", "Header B", "A1", "B1"},
                             {"A2", "B2", "A3", "B3"},
                             {"Header A", "Header B", "A4", "B4"}};
        try (PDDocument doc = new PDDocument()) {
            for (int p = 0; p < values.length; p++) {
                PDPage page = new PDPage(new PDRectangle(400, 400)); doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    text(s, 30, 370, "Body before " + (p + 1));
                    for (int y : new int[]{320, 290, 260}) {
                        s.moveTo(30, y); s.lineTo(230, y); s.stroke();
                    }
                    for (int x : new int[]{30, 130, 230}) {
                        s.moveTo(x, 260); s.lineTo(x, 320); s.stroke();
                    }
                    for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++)
                        text(s, 40 + c*100, 300 - r*30, values[p][r*2+c]);
                    text(s, 30, 230, "Body after " + (p + 1));
                }
            }
            doc.save(file);
        }
        try (PDDocument doc = Loader.loadPDF(file)) {
            for (int n = 1; n <= doc.getNumberOfPages(); n++) {
                PDFTextStripper s = new PDFTextStripper(); s.setSortByPosition(true);
                s.setParagraphEnd("\n\n"); s.setStartPage(n); s.setEndPage(n);
                String value = s.getText(doc).replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
                System.out.println(Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}

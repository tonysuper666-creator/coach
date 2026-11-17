/* Self-authored geometry-gap regression. No private book data. */
import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

public final class ProbeGeometryFixture {
    public static void main(String[] args) throws Exception {
        File file=new File(args[0]);
        try(PDDocument doc=new PDDocument()) {
            for(int mode=0;mode<5;mode++) {
                PDPage page=new PDPage(new PDRectangle(400,400));doc.addPage(page);
                try(PDPageContentStream s=new PDPageContentStream(doc,page)) {
                    SyntheticPdfFixture.text(s,30,300,"Object");
                    s.beginText();s.setFont(SyntheticPdfFixture.FONT,12);
                    if(mode==1) s.setHorizontalScaling(0);
                    s.setTextMatrix(new Matrix(1,0,0,mode==2?0:1,mode==3?500:250,mode==4?500:350));
                    s.showText("x");s.endText();
                }
            }
            doc.save(file);
        }
        try(PDDocument doc=Loader.loadPDF(file)) {
            for(int n=1;n<=doc.getNumberOfPages();n++) {
                PDFTextStripper text=new PDFTextStripper();text.setSortByPosition(true);
                text.setParagraphEnd("\n\n");text.setStartPage(n);text.setEndPage(n);
                String clean=PdfReflowProbe.Text.clean(text.getText(doc));
                System.out.println(Base64.getEncoder().encodeToString(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}

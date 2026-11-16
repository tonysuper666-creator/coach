/* Self-authored source-offset regression fixture; no textbook inputs. */
import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;

public final class SyntheticPdfFixture {
    static final PDFont FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static void text(PDPageContentStream s,float x,float y,String value) throws IOException {
        s.beginText();s.setFont(FONT,12);s.newLineAtOffset(x,y);s.showText(value);s.endText();
    }
    public static void main(String[] args) throws Exception {
        File file=new File(args[0]);
        try(PDDocument doc=new PDDocument()) {
            PDPage table=new PDPage(new PDRectangle(400,400));doc.addPage(table);
            try(PDPageContentStream s=new PDPageContentStream(doc,table)) {
                text(s,30,370,"Before table");
                for(int y:new int[]{320,290,260,230}) {
                    if(y==290) {
                        for(int x=30;x<230;x+=10) { s.moveTo(x,y);s.lineTo(x+10,y);s.stroke(); }
                    } else { s.moveTo(30,y);s.lineTo(230,y);s.stroke(); }
                }
                for(int x:new int[]{30,130,230}) { s.moveTo(x,230);s.lineTo(x,320);s.stroke(); }
                text(s,40,300,"Header A");text(s,140,300,"Header B");
                text(s,40,270,"A1");text(s,140,270,"B1");
                text(s,40,240,"A2");text(s,140,240,"B2");
                text(s,30,210,"Table 1. Caption");
                s.setNonStrokingColor(0.9f);s.addRect(30,80,200,80);s.fill();
                s.setNonStrokingColor(0f);text(s,40,130,"Knowledge box");text(s,40,100,"x = 2 + 3");
            }
            PDPage columns=new PDPage(new PDRectangle(400,400));doc.addPage(columns);
            try(PDPageContentStream s=new PDPageContentStream(doc,columns)) {
                text(s,30,350,"LEFT1");text(s,230,350,"RIGHT1");
                text(s,30,320,"LEFT2");text(s,230,320,"RIGHT2");
            }
            PDPage cropped=new PDPage(new PDRectangle(-50,-30,400,400));
            cropped.setCropBox(new PDRectangle(-30,-10,300,300));doc.addPage(cropped);
            try(PDPageContentStream s=new PDPageContentStream(doc,cropped)) { text(s,10,200,"Repeated repeated"); }
            PDPage rotated=new PDPage(new PDRectangle(400,400));rotated.setRotation(90);doc.addPage(rotated);
            try(PDPageContentStream s=new PDPageContentStream(doc,rotated)) { text(s,30,350,"Rotated"); }
            doc.save(file);
        }
        // Independent reference: the existing importer settings, without the
        // offset-collecting subclass. Assert the probe leaves every page intact.
        try(PDDocument doc=Loader.loadPDF(file)) {
            for(int n=1;n<=doc.getNumberOfPages();n++) {
                PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                stripper.setParagraphEnd("\n\n");stripper.setStartPage(n);stripper.setEndPage(n);
                String text=stripper.getText(doc).replace("\r\n","\n").replace('\r','\n').replace("\u0000","");
                System.out.println(Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}

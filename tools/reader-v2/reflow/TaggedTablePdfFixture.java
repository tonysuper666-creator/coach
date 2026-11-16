/* Self-authored PDF with an actual shared structure-tree table and ParentTree.
 * No known layout JSON or model is used by its integration verifier.
 */
import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;

public final class TaggedTablePdfFixture {
    static final PDFont FONT=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    static void text(PDPageContentStream s,int x,int y,String value) throws IOException {
        s.beginText();s.setFont(FONT,12);s.newLineAtOffset(x,y);s.showText(value);s.endText();
    }
    public static void main(String[] args) throws Exception {
        File file=new File(args[0]);String mode=args.length>1?args[1]:"normal";
        try(PDDocument doc=new PDDocument()) {
            PDStructureTreeRoot root=new PDStructureTreeRoot();doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDMarkInfo mark=new PDMarkInfo();mark.setMarked(true);doc.getDocumentCatalog().setMarkInfo(mark);
            PDStructureElement table=new PDStructureElement("Table",root);root.appendKid(table);
            COSArray nums=new COSArray();
            for(int p=0;p<3;p++) {
                PDPage page=new PDPage(new PDRectangle(400,400));doc.addPage(page);
                page.getCOSObject().setInt(COSName.STRUCT_PARENTS,p);
                COSArray parents=new COSArray();nums.add(COSInteger.get(p));nums.add(parents);
                if(mode.equals("split_tables") && p>0) {
                    table=new PDStructureElement("Table",root);root.appendKid(table);
                }
                try(PDPageContentStream s=new PDPageContentStream(doc,page)) {
                    text(s,30,370,"Body before "+(p+1));
                    for(int y:new int[]{320,290,260}) { s.moveTo(30,y);s.lineTo(230,y);s.stroke(); }
                    for(int x:new int[]{30,130,230}) { s.moveTo(x,260);s.lineTo(x,320);s.stroke(); }
                    for(int r=0;r<2;r++) {
                        PDStructureElement row=new PDStructureElement("TR",table);table.appendKid(row);
                        for(int c=0;c<2;c++) {
                            int id=r*2+c;
                            PDStructureElement cell=new PDStructureElement(p==0 && r==0?"TH":"TD",row);
                            row.appendKid(cell);cell.setPage(page);cell.appendKid(id);parents.add(cell);
                            if(mode.equals("rowspan") && p==0 && id==0) {
                                COSDictionary a=new COSDictionary();a.setName(COSName.O,"Table");
                                a.setInt(COSName.getPDFName("RowSpan"),2);cell.getCOSObject().setItem(COSName.A,a);
                            }
                            if(mode.equals("bad_parent") && p==0 && id==0) parents.set(0,row);
                            if(mode.equals("duplicate_owner") && p==0 && id==1) cell.appendKid(0);
                            COSDictionary props=new COSDictionary();props.setInt(COSName.MCID,id);
                            if(mode.equals("actual_text") && p==1 && id==0) props.setString(COSName.ACTUAL_TEXT,"Other");
                            s.beginMarkedContent(COSName.getPDFName("Span"),PDPropertyList.create(props));
                            String value=p==0 && r==0?"Header "+(c==0?"A":"B"):
                                (c==0?"A":"B")+(p*2+r);
                            text(s,40+c*100,300-r*30,value);s.endMarkedContent();
                            if(mode.equals("duplicate_mcid") && p==1 && id==0) {
                                s.beginMarkedContent(COSName.getPDFName("Span"),PDPropertyList.create(props));
                                text(s,40,180,"Duplicate");s.endMarkedContent();
                            }
                        }
                    }
                    text(s,30,230,"Body after "+(p+1));
                }
            }
            COSDictionary parentTree=new COSDictionary();parentTree.setItem(COSName.NUMS,nums);
            root.getCOSObject().setItem(COSName.PARENT_TREE,parentTree);
            root.setParentTreeNextKey(3);
            if(mode.equals("untagged")) doc.getDocumentCatalog().setStructureTreeRoot(null);
            doc.save(file);
        }
        // Independent ordinary stripper baseline, not the tagged extractor.
        try(PDDocument doc=Loader.loadPDF(file)) {
            for(int n=1;n<=3;n++) {
                PDFTextStripper s=new PDFTextStripper();s.setSortByPosition(true);s.setParagraphEnd("\n\n");
                s.setStartPage(n);s.setEndPage(n);
                String text=s.getText(doc).replace("\r\n","\n").replace('\r','\n').replace("\u0000","");
                System.out.println(Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
    }
}

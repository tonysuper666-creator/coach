/* Local benchmark, not a production reader. Requires the PDFBox 3 jars already used by desktopApp.
 * Output contains source text: write it outside the repository and never attach it to an issue.
 * Usage: java -cp <classes:pdfbox:pdfbox-io:fontbox:commons-logging> PdfReflowProbe book.pdf 46,49
 */
import java.awt.geom.*;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.*;

public final class PdfReflowProbe {
    record Box(double l, double t, double r, double b) {
        double w() { return r-l; } double h() { return b-t; }
        Box grow(double d) { return new Box(l-d,t-d,r+d,b+d); }
        Box union(Box a) { return new Box(Math.min(l,a.l),Math.min(t,a.t),Math.max(r,a.r),Math.max(b,a.b)); }
        boolean touches(Box a) { return l<=a.r && r>=a.l && t<=a.b && b>=a.t; }
        String json() { return String.format(Locale.ROOT,"[%.3f,%.3f,%.3f,%.3f]",l,t,r,b); }
    }
    record Glyph(String text, String font, Box box) {}
    record Region(String kind, Box box) {}
    static final class Text extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();
        boolean rotatedGlyphs;
        Text() throws IOException { setSortByPosition(true); setParagraphEnd("\n\n"); }
        @Override protected void writeString(String s,List<TextPosition> ps) throws IOException {
            for (TextPosition p: ps) {
                rotatedGlyphs |= p.getDir() != 0;
                glyphs.add(new Glyph(p.getUnicode(),p.getFont().getName(),
                    new Box(p.getXDirAdj(),p.getYDirAdj()-p.getHeightDir(),p.getXDirAdj()+p.getWidthDirAdj(),p.getYDirAdj())));
            }
            super.writeString(s,ps);
        }
    }
    static final class Graphics extends PDFGraphicsStreamEngine {
        final GeneralPath path = new GeneralPath();
        final List<Region> regions = new ArrayList<>();
        final double height, left, bottom;
        Graphics(PDPage p) { super(p); height=p.getCropBox().getHeight(); left=p.getCropBox().getLowerLeftX(); bottom=p.getCropBox().getLowerLeftY(); }
        Box box(Rectangle2D b) { return new Box(b.getMinX()-left,height-(b.getMaxY()-bottom),b.getMaxX()-left,height-(b.getMinY()-bottom)); }
        void painted(String kind) {
            Rectangle2D b=path.getBounds2D();
            if (b.getWidth()>20 && b.getHeight()>8) regions.add(new Region(kind,box(b)));
            path.reset();
        }
        @Override public void appendRectangle(Point2D a,Point2D b,Point2D c,Point2D d) { path.moveTo(a.getX(),a.getY());path.lineTo(b.getX(),b.getY());path.lineTo(c.getX(),c.getY());path.lineTo(d.getX(),d.getY());path.closePath(); }
        @Override public void drawImage(PDImage image) {
            AffineTransform tx=getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
            regions.add(new Region("image",box(tx.createTransformedShape(new Rectangle2D.Double(0,0,1,1)).getBounds2D())));
        }
        @Override public void clip(int rule) { clipped=true; /* Bounds cannot be certified without tracking the clip stack. */ }
        @Override public void moveTo(float x,float y) { path.moveTo(x,y); }
        @Override public void lineTo(float x,float y) { path.lineTo(x,y); }
        @Override public void curveTo(float a,float b,float c,float d,float e,float f) { path.curveTo(a,b,c,d,e,f); }
        @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
        @Override public void closePath() { path.closePath(); }
        @Override public void endPath() { path.reset(); }
        @Override public void strokePath() { painted("vector"); }
        @Override public void fillPath(int rule) { painted("filled"); }
        @Override public void fillAndStrokePath(int rule) { painted("filled"); }
        @Override public void shadingFill(COSName name) { /* Cannot localise shading; recorded as unsupported below. */ unsupported=true; }
        boolean unsupported, clipped;
    }
    static List<Region> detect(Graphics g,List<Glyph> glyphs,double w,double h) {
        List<Region> rs=new ArrayList<>();
        for (Region r:g.regions) if(r.box.w()>60 && r.box.h()>18 && r.box.w()*r.box.h()<w*h*.85) rs.add(r);
        // Math fonts are evidence of a risky line, not evidence of a correct formula transcription.
        for (Glyph a:glyphs) if(a.font.toLowerCase(Locale.ROOT).matches(".*(math|symbol|cmmi|cmsy|cmex|mtextra|mtmi|mtsy).*")) {
            Box line=a.box;
            for(Glyph b:glyphs) if(Math.abs(b.box.b-a.box.b)<5) line=line.union(b.box);
            rs.add(new Region("math_line",line.grow(3)));
        }
        // Join nearby paint operations; deliberately conservative. Text is never substituted.
        boolean changed=true;
        while(changed) {
            changed=false;
            outer: for(int i=0;i<rs.size();i++) for(int j=i+1;j<rs.size();j++) {
                Region a=rs.get(i),b=rs.get(j);
                if(a.box.grow(3).touches(b.box)) {
                    rs.set(i,new Region(a.kind.equals(b.kind)?a.kind:"mixed",a.box.union(b.box)));rs.remove(j);changed=true;break outer;
                }
            }
        }
        List<Region> result=new ArrayList<>();
        for(Region r:rs) {
            Box b=r.box.grow(6);
            // Capture small labels attached to a box (e.g. the tab above a definition).
            for(Region q:g.regions) if(b.touches(q.box)) b=b.union(q.box).grow(1);
            result.add(new Region(r.kind,new Box(Math.max(0,b.l),Math.max(0,b.t),Math.min(w,b.r),Math.min(h,b.b))));
        }
        result.sort(Comparator.comparingDouble(a->a.box.t));
        return result;
    }
    static String quote(String s) {
        StringBuilder out=new StringBuilder("\"");
        for(char c:s.toCharArray()) switch(c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
            case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
            default -> { if(c<32) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
        }
        return out.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        long started=System.nanoTime();
        try(PDDocument doc=Loader.loadPDF(new File(args[0]))) {
            double load=(System.nanoTime()-started)/1e9;
            for(String value:args[1].split(",")) {
                int n=Integer.parseInt(value); PDPage page=doc.getPage(n-1);
                long t=System.nanoTime(); Text stripper=new Text();stripper.setStartPage(n);stripper.setEndPage(n);
                String text=stripper.getText(doc).replace("\r\n","\n").replace('\r','\n').replace("\u0000","");
                double a=(System.nanoTime()-t)/1e9;
                t=System.nanoTime(); Graphics graphics=new Graphics(page);graphics.processPage(page);
                var crop=page.getCropBox();var media=page.getMediaBox();
                boolean bad=page.getRotation()!=0 || crop.getLowerLeftX()!=media.getLowerLeftX()
                    || crop.getLowerLeftY()!=media.getLowerLeftY() || crop.getUpperRightX()!=media.getUpperRightX()
                    || crop.getUpperRightY()!=media.getUpperRightY() || graphics.unsupported || graphics.clipped || stripper.rotatedGlyphs;
                List<Region> regions=detect(graphics,stripper.glyphs,page.getCropBox().getWidth(),page.getCropBox().getHeight());
                double c=(System.nanoTime()-t)/1e9;
                StringJoiner r=new StringJoiner(",");for(Region region:regions) r.add("{\"kind\":"+quote(region.kind)+",\"bbox\":"+region.box.json()+"}");
                StringJoiner gs=new StringJoiner(",");for(Glyph g:stripper.glyphs) gs.add("{\"text\":"+quote(g.text)+",\"font\":"+quote(g.font)+",\"bbox\":"+g.box.json()+"}");
                System.out.println("{\"page\":"+n+",\"text\":"+quote(text)+",\"width\":"+page.getCropBox().getWidth()+",\"height\":"+page.getCropBox().getHeight()+",\"load_seconds\":"+load+",\"a_seconds\":"+a+",\"c_seconds\":"+c+",\"fallback\":"+bad+",\"clip_unverified\":"+graphics.clipped+",\"rotated_glyphs\":"+stripper.rotatedGlyphs+",\"regions\":["+r+"],\"glyphs\":["+gs+"]}");
            }
        }
    }
}

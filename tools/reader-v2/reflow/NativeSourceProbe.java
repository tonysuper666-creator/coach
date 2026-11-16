import com.coach.desktop.books.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Production provenance smoke check for privately supplied, independently authored native books.
 * Checks XML identity and range integrity, not rendering or semantic/character correctness.
 * Emits aggregate counts only; never emits book text, paths or coordinates.
 */
public class NativeSourceProbe {
    static void require(boolean value) { if (!value) throw new AssertionError("native provenance invariant"); }
    static Element child(Element node, int index) {
        for (int i=0, n=0; i<node.getChildNodes().getLength(); i++)
            if (node.getChildNodes().item(i) instanceof Element e && n++ == index) return e;
        throw new AssertionError("missing XML element");
    }
    static boolean endpoint(String text, int offset) {
        return offset >= 0 && offset <= text.length() &&
            !(offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset-1)) && Character.isLowSurrogate(text.charAt(offset)));
    }
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args[0]); String format=args[1];
        require(format.equals("docx") || format.equals("epub"));
        BookOcr noOcr=(image,control)->{throw new AssertionError("unexpected OCR");};
        var book=TextbookExtraction.INSTANCE.extract(path,format,"Private native check",noOcr,new BookImportControl());
        var pages=book.getPages(); require(!pages.isEmpty());
        var stale=new ArrayList<>(pages);
        var first=pages.get(0);
        stale.set(0,new BookPage(first.getNumber(),first.getText()+"x",first.getOcr(),first.getIllustrations(),first.getTextLayout()));
        List<?> elements;
        if(format.equals("docx")) {
            var mapping=StructuredBookExtraction.INSTANCE.wordSourceMapping(path,pages);
            require(mapping.getStatus()==WordSourceMapping.Status.MAPPED); elements=mapping.getElements();
            var rejected=StructuredBookExtraction.INSTANCE.wordSourceMapping(path,stale);
            require(rejected.getStatus()==WordSourceMapping.Status.STALE_TEXT && rejected.getElements().isEmpty());
        } else {
            var mapping=TextbookExtraction.INSTANCE.epubSourceMapping(path,pages);
            require(mapping.getStatus()==EpubSourceMapping.Status.MAPPED); elements=mapping.getElements();
            var rejected=TextbookExtraction.INSTANCE.epubSourceMapping(path,stale);
            require(rejected.getStatus()==EpubSourceMapping.Status.STALE_TEXT && rejected.getElements().isEmpty());
        }
        require(!elements.isEmpty());
        var factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        var roots=new HashMap<String,Element>(); var hashes=new HashMap<String,String>();
        var identities=new HashSet<String>(); var members=new HashSet<String>();
        int ranges=0, nonempty=0;
        try(var zip=new ZipFile(path.toFile())) {
            for(Object element:elements) {
                var type=element.getClass();
                String member=(String)type.getMethod("getMember").invoke(element);
                members.add(member);
                if(!roots.containsKey(member)) {
                    byte[] bytes;
                    try(var stream=zip.getInputStream(zip.getEntry(member))) { bytes=stream.readAllBytes(); }
                    // EPUB uses the production declaration guard too; path/hash/range checks below
                    // remain independent assertions. This is not a second XML parser audit.
                    roots.put(member,format.equals("epub") ? EpubXml.INSTANCE.parse(bytes) :
                        factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement());
                    hashes.put(member,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                }
                require(hashes.get(member).equals(type.getMethod("getMemberSha256").invoke(element)));
                @SuppressWarnings("unchecked") var nodePath=(List<Integer>)type.getMethod("getNodePath").invoke(element);
                Element node=roots.get(member); for(int i:nodePath) node=child(node,i);
                require(Objects.toString(node.getNamespaceURI(),"").equals(type.getMethod("getNamespace").invoke(element)));
                require(node.getLocalName().equals(type.getMethod("getLocalName").invoke(element)));
                // A repeated spine item may legitimately repeat the same XML identity.
                String spine=format.equals("epub")?type.getMethod("getSpineIndex").invoke(element).toString():"";
                require(identities.add(spine+":"+member+":"+nodePath));
                var spans=(List<?>)type.getMethod("getRanges").invoke(element);
                if(!spans.isEmpty()) nonempty++;
                for(Object span:spans) {
                    var rt=span.getClass();
                    int page=(int)rt.getMethod("getPage").invoke(span);
                    int start=(int)rt.getMethod("getStart").invoke(span),end=(int)rt.getMethod("getEnd").invoke(span);
                    String text=pages.get(page-1).getText();
                    require(pages.get(page-1).getNumber()==page && start<end && endpoint(text,start) && endpoint(text,end));
                    ranges++;
                }
            }
        }
        var again=TextbookExtraction.INSTANCE.extract(path,format,"Private native check",noOcr,new BookImportControl());
        require(pages.equals(again.getPages()));
        System.out.printf("{\"format\":\"%s\",\"pages\":%d,\"members\":%d,\"elements\":%d,\"nonempty_elements\":%d,\"ranges\":%d,\"stale_rejected\":true,\"canonical_unchanged\":true}%n",
            format,pages.size(),members.size(),elements.size(),nonempty,ranges);
    }
}

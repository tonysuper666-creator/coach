/* Private, offline tagged-PDF evidence. No model, source rewriting or hiding.
 * Only direct page MCIDs and standard Table/TR/TH/TD tags are supported.
 * Compile with PdfReflowProbe.java and the existing PDFBox 3 dependencies.
 */
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.TextPosition;

public final class TaggedTableProbe {
    static String q(String s) { return PdfReflowProbe.quote(s); }
    record Ref(int page, int mcid) {
        String json() { return "{\"page\":"+page+",\"mcid\":"+mcid+"}"; }
    }
    record Node(String type, String path, List<Node> children, List<Ref> refs, boolean unsupported) {}
    static final class Tree {
        final Map<COSDictionary,Integer> pages = new IdentityHashMap<>();
        final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Ref,Integer> owners = new HashMap<>();
        final Map<Ref,COSDictionary> declaredParents = new HashMap<>();
        int budget = 20_000;
        Tree(PDDocument doc) { int n=1; for(PDPage p:doc.getPages()) pages.put(p.getCOSObject(),n++); }
        static COSBase deref(COSBase b) { return b instanceof COSObject o ? o.getObject() : b; }
        static List<COSBase> kids(COSBase b) {
            if(b==null) return List.of();
            b=deref(b);
            if(b instanceof COSArray a) {
                if(a.size()>20_000) throw new IllegalArgumentException("structure_budget");
                List<COSBase> out=new ArrayList<>();for(COSBase k:a) out.add(deref(k));return out;
            }
            return List.of(b);
        }
        Node read(COSBase value, COSDictionary parent, Integer inheritedPage, String path, int depth) {
            if(--budget<0 || depth>64) throw new IllegalArgumentException("structure_budget");
            value=deref(value);
            if(value instanceof COSInteger n) {
                if(inheritedPage==null || n.intValue()<0 || n.longValue()>Integer.MAX_VALUE)
                    throw new IllegalArgumentException("invalid_mcid_reference");
                Ref ref=new Ref(inheritedPage,n.intValue());owners.merge(ref,1,Integer::sum);
                declaredParents.put(ref,parent);
                return new Node("MCR",path,List.of(),List.of(ref),false);
            }
            if(!(value instanceof COSDictionary d) || !seen.add(d))
                throw new IllegalArgumentException("invalid_or_reused_structure_node");
            COSBase pg=d.getDictionaryObject(COSName.PG);
            Integer page=pg==null?inheritedPage:pages.get(pg);
            if(pg!=null && page==null) throw new IllegalArgumentException("foreign_page");
            if("MCR".equals(d.getNameAsString(COSName.TYPE))) {
                COSBase id=d.getDictionaryObject(COSName.MCID);
                if(!(id instanceof COSInteger) || d.containsKey(COSName.getPDFName("Stm")))
                    throw new IllegalArgumentException("non_page_mcid_reference");
                return read(id,parent,page,path+"/MCID",depth+1);
            }
            String type=d.getNameAsString(COSName.S);
            if(type==null || d.getDictionaryObject(COSName.P)!=parent)
                throw new IllegalArgumentException("invalid_structure_parent");
            boolean unsupported=d.containsKey(COSName.ACTUAL_TEXT) || d.containsKey(COSName.C);
            for(COSBase a:kids(d.getDictionaryObject(COSName.A))) {
                if(a instanceof COSDictionary attr) {
                    for(String name:List.of("RowSpan","ColSpan")) {
                        COSBase span=attr.getDictionaryObject(COSName.getPDFName(name));
                        if(span!=null && (!(span instanceof COSInteger n) || n.longValue()!=1)) unsupported=true;
                    }
                } else unsupported=true;
            }
            List<Node> children=new ArrayList<>();int i=0;
            for(COSBase k:kids(d.getDictionaryObject(COSName.K)))
                children.add(read(k,d,page,path+"/K/"+i++,depth+1));
            return new Node(type,path,children,List.of(),unsupported);
        }
        List<Ref> refs(Node n) {
            List<Ref> out=new ArrayList<>(n.refs);for(Node c:n.children) out.addAll(refs(c));return out;
        }
        boolean unsupported(Node n) { return n.unsupported || n.children.stream().anyMatch(this::unsupported); }
        String table(Node n) {
            String reason=null;
            if(unsupported(n)) reason="unsupported_attributes";
            if(n.children.isEmpty() || n.children.stream().anyMatch(c->!c.type.equals("TR")))
                reason="unsupported_table_hierarchy";
            List<String> rows=new ArrayList<>();
            for(Node row:n.children) {
                List<String> cells=new ArrayList<>();
                if(row.children.isEmpty()) reason="empty_row";
                for(Node cell:row.children) {
                    List<Ref> refs=refs(cell);
                    if(!Set.of("TH","TD").contains(cell.type) || refs.isEmpty() ||
                       refs.stream().anyMatch(r->owners.getOrDefault(r,0)!=1)) reason="ambiguous_cell_reference";
                    if(cell.children.stream().anyMatch(c->hasTable(c))) reason="nested_table";
                    StringJoiner joined=new StringJoiner(",");for(Ref ref:refs) joined.add(ref.json());
                    cells.add("{\"source_path\":"+q(cell.path)+",\"header\":"+cell.type.equals("TH")+",\"refs\":["+joined+"]}");
                }
                rows.add("["+String.join(",",cells)+"]");
            }
            return "{\"source_path\":"+q(n.path)+",\"reason\":"+(reason==null?"null":q(reason))+",\"rows\":["+String.join(",",rows)+"]}";
        }
        boolean hasTable(Node n) { return n.type.equals("Table") || n.children.stream().anyMatch(this::hasTable); }
        void collect(Node n,List<Node> tables) {
            if(n.type.equals("Table")) tables.add(n);
            for(Node c:n.children) collect(c,tables);
        }
        void numberTree(COSBase value,Map<Integer,COSBase> values,int depth) {
            value=deref(value);
            if(--budget<0 || depth>64 || !(value instanceof COSDictionary d) || !seen.add(d))
                throw new IllegalArgumentException("invalid_parent_tree");
            List<COSBase> nums=kids(d.getDictionaryObject(COSName.NUMS));
            if(nums.size()%2!=0 || (!nums.isEmpty() && d.containsKey(COSName.KIDS)))
                throw new IllegalArgumentException("invalid_parent_tree");
            for(int i=0;i<nums.size();i+=2) {
                if(!(nums.get(i) instanceof COSInteger n) || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE ||
                        values.putIfAbsent(n.intValue(),nums.get(i+1))!=null)
                    throw new IllegalArgumentException("invalid_parent_tree_key");
            }
            for(COSBase child:kids(d.getDictionaryObject(COSName.KIDS))) numberTree(child,values,depth+1);
        }
        void verifyParents(COSDictionary root) {
            if(owners.isEmpty()) return;
            Map<Integer,COSBase> values=new HashMap<>();
            numberTree(root.getDictionaryObject(COSName.PARENT_TREE),values,0);
            Map<Integer,COSDictionary> physical=new HashMap<>();
            Map<Integer,COSDictionary> keys=new HashMap<>();
            for(var entry:pages.entrySet()) {
                physical.put(entry.getValue(),entry.getKey());
                int key=entry.getKey().getInt(COSName.STRUCT_PARENTS,-1);
                if(key>=0 && keys.putIfAbsent(key,entry.getKey())!=null)
                    throw new IllegalArgumentException("duplicate_structparents_key");
            }
            for(var entry:declaredParents.entrySet()) {
                Ref ref=entry.getKey();
                int key=physical.get(ref.page).getInt(COSName.STRUCT_PARENTS,-1);
                COSBase value=values.get(key);
                if(!(value instanceof COSArray a) || ref.mcid>=a.size() ||
                        a.getObject(ref.mcid)!=entry.getValue())
                    throw new IllegalArgumentException("parent_tree_owner_mismatch");
            }
        }
    }
    // Track the actual TextPosition objects through the SAME canonical stripper.
    // Duplicate/merged/ActualText/form cases cannot silently acquire a cell owner.
    static final class TaggedText extends PdfReflowProbe.Text {
        final Deque<Integer> stack=new ArrayDeque<>();
        final Set<Integer> sequences=new HashSet<>();
        final Map<TextPosition,Integer> ids=new IdentityHashMap<>();
        final Map<TextPosition,String> originals=new IdentityHashMap<>();
        final Set<TextPosition> emitted=Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Integer> glyphIds=new ArrayList<>();
        boolean unsafe;
        TaggedText() throws IOException { super(); }
        @Override public void beginMarkedContentSequence(COSName tag,COSDictionary props) {
            int id=-1;
            if(props!=null) {
                if(props.containsKey(COSName.ACTUAL_TEXT)) unsafe=true;
                COSBase value=props.getDictionaryObject(COSName.MCID);
                if(value!=null) {
                    if(!(value instanceof COSInteger n) || n.longValue()<0 || n.longValue()>Integer.MAX_VALUE) unsafe=true;
                    else {
                        id=((COSInteger)value).intValue();
                        if(!sequences.add(id) || stack.stream().anyMatch(x->x>=0)) unsafe=true;
                    }
                }
            }
            stack.push(id);super.beginMarkedContentSequence(tag,props);
        }
        @Override public void endMarkedContentSequence() {
            if(stack.isEmpty()) unsafe=true;else stack.pop();super.endMarkedContentSequence();
        }
        @Override public void showForm(PDFormXObject form) throws IOException {
            unsafe=true;super.showForm(form);
        }
        @Override protected void processTextPosition(TextPosition p) {
            ids.put(p,stack.stream().filter(x->x>=0).findFirst().orElse(-1));
            originals.put(p,p.getUnicode());super.processTextPosition(p);
        }
        @Override protected void writeString(String s,List<TextPosition> ps) throws IOException {
            for(TextPosition p:ps) {
                if(!emitted.add(p) || !Objects.equals(originals.get(p),p.getUnicode())) unsafe=true;
                glyphIds.add(ids.getOrDefault(p,-1));
            }
            super.writeString(s,ps);
        }
        boolean safe() { return !unsafe && stack.isEmpty() && emitted.size()==ids.size(); }
    }
    public static void main(String[] args) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(Path.of(args[0]))) {
            byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1) digest.update(bytes,0,n);
        }
        String sha=HexFormat.of().formatHex(digest.digest());
        try(PDDocument doc=Loader.loadPDF(new File(args[0]))) {
            Tree tree=new Tree(doc);List<Node> tables=new ArrayList<>();String status="untagged";
            var root=doc.getDocumentCatalog().getStructureTreeRoot();
            if(root!=null) {
                try {
                    if(!root.getRoleMap().isEmpty()) throw new IllegalArgumentException("role_map_unverified");
                    int i=0;for(COSBase k:Tree.kids(root.getK()))
                        tree.collect(tree.read(k,root.getCOSObject(),null,"/StructTreeRoot/K/"+i++,0),tables);
                    tree.verifyParents(root.getCOSObject());
                    status="parsed";
                } catch(IllegalArgumentException e) { status=e.getMessage();tables.clear(); }
            }
            StringJoiner ts=new StringJoiner(",");for(Node table:tables) ts.add(tree.table(table));
            StringJoiner pages=new StringJoiner(",");
            // Only explicitly requested physical pages; missing table pages fail in the consumer.
            for(String s:args[1].split(",")) {
                int number=Integer.parseInt(s);PDPage page=doc.getPage(number-1);
                TaggedText text=new TaggedText();text.setStartPage(number);text.setEndPage(number);
                String canonical=text.extract(doc);
                var graphics=new PdfReflowProbe.Graphics(page);graphics.processPage(page);
                var crop=page.getCropBox();var media=page.getMediaBox();
                boolean safe=text.safe() && text.unmapped.isEmpty() && !text.rotatedGlyphs && page.getRotation()==0 &&
                    crop.getCOSArray().equals(media.getCOSArray()) && !graphics.clipped && !graphics.unsupported;
                StringJoiner gs=new StringJoiner(",");
                for(int i=0;i<text.glyphs.size();i++) {
                    var g=text.glyphs.get(i);
                    gs.add("{\"mcid\":"+text.glyphIds.get(i)+",\"bbox\":"+g.box().json()+",\"text\":"+q(PdfReflowProbe.Text.presentationText(g.text()))+
                        ",\"start\":"+(g.start()<0?"null":text.cleanedOffsets[g.start()])+
                        ",\"end\":"+(g.end()<0?"null":text.cleanedOffsets[g.end()])+"}");
                }
                pages.add("{\"page\":"+number+",\"text\":"+q(canonical)+",\"safe\":"+safe+
                    ",\"width\":"+crop.getWidth()+",\"height\":"+crop.getHeight()+",\"glyphs\":["+gs+"]}");
            }
            System.out.println("{\"schema\":1,\"doc_sha256\":"+q(sha)+",\"structure_status\":"+q(status)+
                ",\"tables\":["+ts+"],\"pages\":["+pages+"]}");
        }
    }
}

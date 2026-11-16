"""Reproducible, original Coach fixtures. Python standard library only; no learner material."""
from pathlib import Path
import struct
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).parent
BODY = [
    "光合作用是植物利用光能制造糖的过程。叶绿素吸收光，叶绿体完成能量转换。",
    "细胞是生命活动的基本单位。细胞膜控制物质进出，细胞核保存遗传信息。",
    "呼吸作用释放糖中储存的能量。比较两个过程时，应同时说明原料和产物。",
]
ENGLISH = [
    "Photosynthesis is the process by which plants use light to make sugars.",
    "A cell is the basic unit of living organisms and contains genetic information.",
    "Respiration is the process that releases energy from stored sugars.",
]
TEXT = "\n\n".join(f"第{i+1}章 学习材料\n\n{body}\n\n{ENGLISH[i]}\n\n请用自己的话解释这个概念，并举一个生活中的例子。" for i, body in enumerate(BODY))
for name, encoding, prefix in [
    ("utf8.txt", "utf-8", b""), ("utf8-bom.text", "utf-8", b"\xef\xbb\xbf"),
    ("gb18030.txt", "gb18030", b""), ("gbk.txt", "gbk", b""),
    ("utf16le.txt", "utf-16le", b"\xff\xfe"), ("utf16be.txt", "utf-16be", b"\xfe\xff"),
    ("utf16le-no-bom.txt", "utf-16le", b""), ("utf16be-no-bom.txt", "utf-16be", b""),
]:
    (ROOT / name).write_bytes(prefix + TEXT.encode(encoding))
TRADITIONAL = "\n\n".join(f"第{i+1}章 學習材料\n\n" + p for i, p in enumerate([
    "光合作用是植物利用光能製造糖的過程。葉綠素吸收光，葉綠體完成能量轉換。",
    "細胞是生命活動的基本單位。細胞膜控制物質進出，細胞核保存遺傳資訊。",
    "呼吸作用釋放糖中儲存的能量。比較兩個過程時，應同時說明原料與產物。",
]))
(ROOT / "big5.txt").write_bytes(TRADITIONAL.encode("big5"))
(ROOT / "expected.txt").write_text(TEXT, encoding="utf-8")
(ROOT / "expected-big5.txt").write_text(TRADITIONAL, encoding="utf-8")
(ROOT / "unicode.markdown").write_text("# 第一章 光與生命\n\n" + BODY[0] + " 😀 ﬁ Ａ\n\n## 第二章 細胞\n\n" + BODY[1] + "\n\n第三段是保留原文偏移的练习。", encoding="utf-8")
HTML = "<!doctype html><html><head><title>自制生物教材</title></head><body><nav>不要导入导航</nav><script>不要导入脚本</script><article>" + "".join(f"<h1>第{i+1}章 生物</h1><p>{b} 😀</p><p>{ENGLISH[i]}</p>" for i, b in enumerate(BODY)) + "</article><footer>不要导入页脚</footer></body></html>"
for name in ["article.html", "wrong-extension.txt", "wrong-extension.pdf"]:
    (ROOT / name).write_text(HTML, encoding="utf-8")

def archive(name, entries):
    with ZipFile(ROOT / name, "w", compression=ZIP_DEFLATED) as z:
        for path, content in entries.items():
            info = ZipInfo(path, date_time=(2026, 11, 16, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            z.writestr(info, content)

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/package/2006/relationships"
CT = "http://schemas.openxmlformats.org/package/2006/content-types"
def p(text, heading=False):
    return f"<w:p>{'<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>' if heading else ''}<w:r><w:t>{text}</w:t></w:r></w:p>"
word = {
    "[Content_Types].xml": f'<Types xmlns="{CT}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>',
    "_rels/.rels": f'<Relationships xmlns="{R}"><Relationship Id="doc" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>',
    "word/document.xml": f'<w:document xmlns:w="{W}"><w:body>' + p("第一章 光合作用", True) + p(BODY[0] + " 😀") + '<w:p><w:r><w:t>光的研究有历史记录</w:t><w:footnoteReference w:id="1"/></w:r></w:p><w:tbl><w:tr><w:tc>' + p("原料") + '</w:tc><w:tc>' + p("二氧化碳和水") + '</w:tc></w:tr></w:tbl><w:p><w:r><w:br w:type="page"/></w:r></w:p>' + p("第二章 细胞", True) + p(BODY[1]) + p(BODY[2]) + '</w:body></w:document>',
    "word/styles.xml": f'<w:styles xmlns:w="{W}"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>',
    "word/footnotes.xml": f'<w:footnotes xmlns:w="{W}"><w:footnote w:id="1">' + p("这是自制脚注，用于核对出处。") + '</w:footnote></w:footnotes>',
}
archive("lesson.docx", word)
archive("word-xxe.docx", {**word, "word/document.xml": '<!DOCTYPE x [<!ENTITY leak SYSTEM "file:///coach-test-do-not-open">]>' + word["word/document.xml"].replace(BODY[0], "&leak;")})
archive("word-traversal.docx", {**word, "../outside.xml": "never extract this"})
archive("word-oversize.docx", {**word, "word/huge.xml": "x" * (16 * 1024 * 1024 + 1)})
P = "http://schemas.openxmlformats.org/presentationml/2006/main"
A = "http://schemas.openxmlformats.org/drawingml/2006/main"
REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
def slide(text):
    return f'<p:sld xmlns:p="{P}" xmlns:a="{A}"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>'
slides = {
    "[Content_Types].xml": f'<Types xmlns="{CT}"><Default Extension="xml" ContentType="application/xml"/><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/></Types>',
    "_rels/.rels": f'<Relationships xmlns="{R}"><Relationship Id="doc" Type="{REL}/officeDocument" Target="ppt/presentation.xml"/></Relationships>',
    "ppt/presentation.xml": f'<p:presentation xmlns:p="{P}" xmlns:r="{REL}"><p:sldIdLst><p:sldId id="256" r:id="first"/><p:sldId id="257" r:id="second"/><p:sldId id="258" r:id="third"/></p:sldIdLst></p:presentation>',
    "ppt/_rels/presentation.xml.rels": f'<Relationships xmlns="{R}"><Relationship Id="first" Type="{REL}/slide" Target="slides/slide3.xml"/><Relationship Id="second" Type="{REL}/slide" Target="slides/slide1.xml"/><Relationship Id="third" Type="{REL}/slide" Target="slides/slide2.xml"/></Relationships>',
    "ppt/slides/slide3.xml": slide(BODY[0] + " 😀"),
    "ppt/slides/slide1.xml": slide(BODY[1]), "ppt/slides/slide2.xml": slide(BODY[2]),
    "ppt/slides/_rels/slide3.xml.rels": f'<Relationships xmlns="{R}"><Relationship Id="notes" Type="{REL}/notesSlide" Target="../notesSlides/notesSlide1.xml"/></Relationships>',
    "ppt/notesSlides/notesSlide1.xml": f'<p:notes xmlns:p="{P}" xmlns:a="{A}"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>课堂备注：先解释能量如何转变，再比较原料。</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>',
}
archive("lesson.pptx", slides)
ODT = '<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text>' + "".join(f'<text:h text:outline-level="1">第{i+1}章 生物</text:h><text:p>{b} 😀</text:p><text:p>{ENGLISH[i]}</text:p>' for i, b in enumerate(BODY)) + '</office:text></office:body></office:document-content>'
archive("lesson.odt", {"mimetype": "application/vnd.oasis.opendocument.text", "content.xml": ODT})
def rtf_escape(text):
    result = ""
    for char in text:
        if ord(char) < 128:
            result += char.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", "\\par\n")
        else:
            for i in range(0, len(char.encode("utf-16le")), 2):
                value = int.from_bytes(char.encode("utf-16le")[i:i+2], "little", signed=True)
                result += f"\\u{value}?"
    return result
(ROOT / "lesson.rtf").write_text("{\\rtf1\\ansi\\ansicpg1252\\uc1 " + rtf_escape(TEXT) + "}", encoding="ascii")
epub = {
    "mimetype": "application/epub+zip",
    "META-INF/container.xml": '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>',
    "OPS/package.opf": '<package xmlns="http://www.idpf.org/2007/opf"><metadata><title xmlns="http://purl.org/dc/elements/1.1/">自制生物教材</title></metadata><manifest><item id="c" href="chapter.xhtml"/></manifest><spine><itemref idref="c"/></spine></package>',
    "OPS/chapter.xhtml": '<html xmlns="http://www.w3.org/1999/xhtml"><body>' + "".join(f"<h1>第{i+1}章 生物</h1><p>{b}</p><p>{ENGLISH[i]}</p>" for i, b in enumerate(BODY)) + '</body></html>',
}
archive("lesson.epub", epub)
archive("drm.epub", {**epub, "META-INF/encryption.xml": '<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>'})

def vwi(value):
    result = [value & 127 | 128]
    value >>= 7
    while value:
        result.insert(0, value & 127)
        value >>= 7
    return bytes(result)

def index(tags, entries):
    tagx = b"TAGX" + struct.pack(">II", 12 + 4*len(tags), 1) + bytes(x for tag in tags for x in tag)
    data = bytearray(192)
    data[:4] = b"INDX"
    struct.pack_into(">I", data, 4, 192)
    offsets = []
    for label, control, values in entries:
        offsets.append(len(data))
        data += bytes([len(label)]) + label.encode("ascii") + bytes([control]) + b"".join(vwi(v) for v in values)
    struct.pack_into(">II", data, 20, len(data), len(entries))
    data += b"IDXT" + b"".join(struct.pack(">H", p) for p in offsets)
    primary = bytearray(192)
    primary[:4] = b"INDX"
    struct.pack_into(">I", primary, 4, 192)
    struct.pack_into(">I", primary, 24, 1)
    struct.pack_into(">I", primary, 36, len(entries))
    return [primary + tagx, data]

def mobi(name, version=6, compression=1, drm=False, bad_index=False):
    html = ("<html><body>" + "".join(f"<h1>Chapter {i+1} Biology</h1><p>{b} 😀</p><p>{ENGLISH[i]}</p>" for i, b in enumerate(BODY)) + "</body></html>").encode("utf-8")
    record0 = bytearray(16 + 264)
    struct.pack_into(">HHIHHHH", record0, 0, compression, 0, len(html), 1, 4096, int(drm), 0)
    record0[16:20] = b"MOBI"
    struct.pack_into(">I", record0, 20, 264)
    struct.pack_into(">I", record0, 28, 65001)
    struct.pack_into(">I", record0, 36, version)
    struct.pack_into(">I", record0, 108, 0xffffffff)
    encoded = html
    if compression == 2:  # Literal runs: a real PalmDOC stream, deliberately no compressor dependency.
        encoded = b"".join(bytes([len(html[i:i+8])]) + html[i:i+8] for i in range(0, len(html), 8))
    records = [record0, encoded]
    if version == 8:
        flow, skeletons, fragments = bytearray(), [], []
        for i, body in enumerate(BODY):
            shell = f'<html><head><title>Chapter {i+1}</title></head><body></body></html>'.encode()
            a = f'<h1>Chapter {i+1} Biology</h1><p>{body} 😀</p>'.encode()
            b = f'<p>{ENGLISH[i]}</p>'.encode()
            start, insertion = len(flow), shell.index(b'</body>')
            skeletons.append((f'SKEL{i:010d}', 0x0a, [2, 2, start, len(shell), start, len(shell)]))
            fragments.append((f'{start+insertion:010d}', 0x0f, [0, i, 2*i, 0, len(a)]))
            fragments.append((f'{start+insertion+len(a):010d}', 0x0f, [0, i, 2*i+1, len(a), len(b)]))
            flow += shell + a + b
        html = bytes(flow)
        struct.pack_into('>I', record0, 4, len(html))
        records[1] = html
        struct.pack_into('>I', record0, 248, len(records))
        records += index([(2,1,1,0), (3,1,2,0), (4,1,4,0), (6,2,8,0), (0,0,0,1)], fragments)
        struct.pack_into('>I', record0, 252, 999999 if bad_index else len(records))
        records += index([(1,1,3,0), (6,2,12,0), (0,0,0,1)], skeletons)
    if compression == 17480:  # Canonical 8-bit codes; dictionary index is 255 - byte.
        struct.pack_into(">II", record0, 112, 2, 2)
        huff = b"HUFF" + struct.pack(">IIIII", 24, 24, 1048, 0, 0) + b"".join(struct.pack(">I", (255 << 8) | 0x88) for _ in range(256)) + bytes(256)
        cdic = b"CDIC" + struct.pack(">III", 16, 256, 8) + b"".join(struct.pack(">H", 512 + i*3) for i in range(256)) + b"".join(struct.pack(">H", 0x8001) + bytes([255-i]) for i in range(256))
        records += [huff, cdic]
    if version == 8:
        struct.pack_into(">I", record0, 192, len(records))
        records.append(b"FDST" + struct.pack(">IIII", 12, 1, 0, len(html)))
    header = bytearray(78)
    header[:10] = b"Coach test"
    header[60:68] = b"BOOKMOBI"
    struct.pack_into(">H", header, 76, len(records))
    offset = 78 + 8 * len(records) + 2
    table = bytearray()
    for record in records:
        table += struct.pack(">II", offset, 0)
        offset += len(record)
    (ROOT / name).write_bytes(header + table + bytes(2) + b"".join(records))
for name, version, compression in [("lesson.mobi", 6, 1), ("palmdoc.mobi", 6, 2), ("huff.mobi", 6, 17480), ("lesson.azw3", 8, 1)]:
    mobi(name, version, compression)
mobi("drm.mobi", drm=True)
mobi("broken-index.azw3", version=8, bad_index=True)
(ROOT / "empty.txt").write_bytes(b"")
(ROOT / "broken.docx").write_bytes(b"PK\x03\x04truncated")
print("Generated original format fixtures in", ROOT.name)

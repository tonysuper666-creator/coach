"""Original TON-1663 phase-1 fixtures. Python 3 standard library only.

The sentences are written for this test. They are not copied from a book.
Run this file to regenerate bytes/ next to it. Dates and compression are fixed.
"""
from pathlib import Path
import binascii
import struct

ROOT = Path(__file__).resolve().parent / "bytes"

ZH_TITLE_1 = "第1章 窗台"
ZH_BODY_1 = "窗台上有一只白瓷杯。杯里是凉掉的茶。杯沿有一枚😀和𠮷。"
ZH_TITLE_2 = "第2章 石阶"
ZH_BODY_2 = "石阶被雨打湿。鞋底留下浅印。"
ZH = f"{ZH_TITLE_1}\n\n{ZH_BODY_1}\n\n{ZH_TITLE_2}\n\n{ZH_BODY_2}\n"

BIG5_TITLE_1 = "第1章 窗臺"
BIG5_BODY_1 = "窗臺上有一隻茶杯。茶已經涼了。"
BIG5_TITLE_2 = "第2章 石階"
BIG5_BODY_2 = "石階被雨打濕。鞋底留下淺印。"
BIG5 = f"{BIG5_TITLE_1}\n\n{BIG5_BODY_1}\n\n{BIG5_TITLE_2}\n\n{BIG5_BODY_2}\n"

EN = "Chapter 1 Shore\n\nThe tide left a thin line of shells.\n\nChapter 2 Dune\n\nGrass held the loose sand.\n"

CUP = "窗台上有一只白瓷杯。"
STEP = "石阶被雨打湿。"
NOTE = "杯是自己买的。"
SLIDE_NOTE_1 = "先看杯沿的水印。"
SLIDE_NOTE_2 = "记下鞋印的方向。"
NAV = "不要带上这段导航"
SCRIPT = "不要带上这段脚本"
FOOTER = "不要带上这段页脚"
PDF_1 = "The white cup sits on the sill."
PDF_2 = "Rain darkened the stone step."


def write(name, data: bytes):
    path = ROOT / name
    path.write_bytes(data)
    return path


def zip_stored(entries):
    """ZIP of stored entries. Names are kept exactly, including '../'."""
    locals_ = []
    central = []
    offset = 0
    for name, data in entries:
        name_b = name.encode("utf-8")
        crc = binascii.crc32(data) & 0xFFFFFFFF
        local = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0, 0, 0, 0, crc, len(data), len(data), len(name_b), 0)
        local += name_b + data
        central_item = struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, 0, 0, 0, 0, crc, len(data), len(data),
            len(name_b), 0, 0, 0, 0, 0, offset,
        ) + name_b
        locals_.append(local)
        central.append(central_item)
        offset += len(local)
    central_blob = b"".join(central)
    eocd = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries), len(central_blob), offset, 0)
    return b"".join(locals_) + central_blob + eocd


def rtf_escape(text: str) -> str:
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "{":
            out.append("\\{")
        elif ch == "}":
            out.append("\\}")
        elif ch == "\n":
            out.append("\\par\n")
        elif ord(ch) < 128:
            out.append(ch)
        else:
            raw = ch.encode("utf-16le")
            for i in range(0, len(raw), 2):
                value = int.from_bytes(raw[i:i + 2], "little", signed=True)
                out.append(f"\\u{value}?")
    return "".join(out)


def pdf(pages):
    n = len(pages)
    page_nums = [4 + i * 2 for i in range(n)]
    content_nums = [5 + i * 2 for i in range(n)]
    kids = " ".join(f"{num} 0 R" for num in page_nums)
    blobs = [b"%PDF-1.4\n"]
    offsets = [0]

    def obj(body: bytes):
        offsets.append(sum(len(part) for part in blobs))
        number = len(offsets) - 1
        blobs.append(f"{number} 0 obj\n".encode("ascii") + body + b"\nendobj\n")
        return number

    obj(f"<< /Type /Catalog /Pages 2 0 R >>".encode("ascii"))
    obj(f"<< /Type /Pages /Count {n} /Kids [{kids}] >>".encode("ascii"))
    obj(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    streams = []
    for text in pages:
        streams.append(f"BT /F1 24 Tf 72 700 Td ({text}) Tj ET\n".encode("ascii"))
    for index, stream in enumerate(streams):
        obj(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents {content_nums[index]} 0 R "
            f"/Resources << /Font << /F1 3 0 R >> >> >>".encode("ascii")
        )
        obj(f"<< /Length {len(stream)} >>\nstream\n".encode("ascii") + stream + b"endstream")
    xref_at = sum(len(part) for part in blobs)
    xref = [f"xref\n0 {len(offsets)}\n".encode("ascii"), b"0000000000 65535 f \n"]
    for off in offsets[1:]:
        xref.append(f"{off:010d} 00000 n \n".encode("ascii"))
    trailer = (
        f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n".encode("ascii")
    )
    return b"".join(blobs) + b"".join(xref) + trailer


def mobi(html: str, drm: bool) -> bytes:
    text = html.encode("utf-8")
    record0 = bytearray(16 + 264)
    struct.pack_into(">HHIHHHH", record0, 0, 1, 0, len(text), 1, max(4096, len(text)), int(drm), 0)
    record0[16:20] = b"MOBI"
    struct.pack_into(">I", record0, 20, 264)
    struct.pack_into(">I", record0, 28, 65001)
    struct.pack_into(">I", record0, 36, 6)
    struct.pack_into(">I", record0, 108, 0xFFFFFFFF)
    records = [bytes(record0), text]
    header = bytearray(78)
    header[:12] = b"Window notes"
    header[60:68] = b"BOOKMOBI"
    struct.pack_into(">H", header, 76, len(records))
    offset = 78 + 8 * len(records) + 2
    table = bytearray()
    for record in records:
        table += struct.pack(">II", offset, 0)
        offset += len(record)
    return bytes(header) + bytes(table) + bytes(2) + b"".join(records)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for encoding in ("utf-8", "gb18030", "utf-16le"):
        roundtrip = ZH.encode(encoding).decode(encoding)
        if roundtrip != ZH:
            raise SystemExit(f"{encoding} did not round-trip")
    if BIG5.encode("big5").decode("big5") != BIG5:
        raise SystemExit("big5 did not round-trip")

    write("utf8-nobom.txt", ZH.encode("utf-8"))
    write("gb18030.txt", ZH.encode("gb18030"))
    write("utf16le-bom.txt", b"\xff\xfe" + ZH.encode("utf-16le"))
    write("big5.txt", BIG5.encode("big5"))
    write("chapters-en.txt", EN.encode("utf-8"))
    write("window.markdown", f"# 窗台\n\n{CUP}\n\n## 石阶\n\n{STEP}\n".encode("utf-8"))

    html = (
        "<!doctype html><html><head><title>窗边短记</title>"
        f"<script>{SCRIPT}</script></head><body><nav>{NAV}</nav><article>"
        f"<h1>{ZH_TITLE_1}</h1><p>{CUP}</p><h1>{ZH_TITLE_2}</h1><p>{STEP}</p>"
        f"</article><footer>{FOOTER}</footer></body></html>"
    ).encode("utf-8")
    write("window.html", html)
    write("html-as.txt", html)

    w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    def p(text, heading=False, break_page=False):
        style = '<w:pPr><w:pStyle w:val="Heading1"/></w:pPr>' if heading else ""
        br = '<w:r><w:br w:type="page"/></w:r>' if break_page else ""
        body = f"<w:r><w:t>{text}</w:t></w:r>" if text else ""
        return f"<w:p>{style}{br}{body}</w:p>"

    document = (
        f'<w:document xmlns:w="{w}"><w:body>'
        + p(ZH_TITLE_1, heading=True)
        + f'<w:p><w:r><w:t>{ZH_BODY_1}</w:t><w:footnoteReference w:id="1"/></w:r></w:p>'
        + "<w:tbl><w:tr><w:tc>" + p("物品") + "</w:tc><w:tc>" + p("状态") + "</w:tc></w:tr>"
        + "<w:tr><w:tc>" + p("茶") + "</w:tc><w:tc>" + p("已经凉了") + "</w:tc></w:tr></w:tbl>"
        + p("", break_page=True)
        + p(ZH_TITLE_2, heading=True)
        + p(STEP)
        + "</w:body></w:document>"
    )
    footnotes = (
        f'<w:footnotes xmlns:w="{w}"><w:footnote w:id="1">' + p(NOTE) + "</w:footnote></w:footnotes>"
    )
    styles = f'<w:styles xmlns:w="{w}"><w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>'
    word_entries = [
        ("word/document.xml", document.encode("utf-8")),
        ("word/styles.xml", styles.encode("utf-8")),
        ("word/footnotes.xml", footnotes.encode("utf-8")),
    ]
    write("window.docx", zip_stored(word_entries))
    write("traversal.docx", zip_stored(word_entries + [("../ton1877-escaped.txt", b"must-not-escape")]))
    xxe = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<!DOCTYPE w:document [<!ENTITY leak SYSTEM "file:///ton1877-do-not-read">]>'
        f'<w:document xmlns:w="{w}"><w:body><w:p><w:r><w:t>&leak;</w:t></w:r></w:p>'
        f"<w:p><w:r><w:t>{CUP}</w:t></w:r></w:p></w:body></w:document>"
    )
    write("xxe.docx", zip_stored([
        ("word/document.xml", xxe.encode("utf-8")),
        ("word/styles.xml", styles.encode("utf-8")),
    ]))
    write("truncated-archive.docx", b"PK\x03\x04truncated-on-purpose")

    p_ns = "http://schemas.openxmlformats.org/presentationml/2006/main"
    a_ns = "http://schemas.openxmlformats.org/drawingml/2006/main"
    rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    pkg = "http://schemas.openxmlformats.org/package/2006/relationships"

    def slide(text):
        return (
            f'<p:sld xmlns:p="{p_ns}" xmlns:a="{a_ns}"><p:cSld><p:spTree><p:sp><p:txBody>'
            f"<a:p><a:r><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"
        )

    def notes(text):
        return (
            f'<p:notes xmlns:p="{p_ns}" xmlns:a="{a_ns}"><p:cSld><p:spTree><p:sp>'
            f'<p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody>'
            f"<a:p><a:r><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>"
        )

    ct = "http://schemas.openxmlformats.org/package/2006/content-types"
    write("window.pptx", zip_stored([
        ("[Content_Types].xml", (
            f'<Types xmlns="{ct}">'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Override PartName="/ppt/presentation.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>'
            "</Types>"
        ).encode("utf-8")),
        ("_rels/.rels", (
            f'<Relationships xmlns="{pkg}">'
            f'<Relationship Id="doc" Type="{rel}/officeDocument" Target="ppt/presentation.xml"/>'
            "</Relationships>"
        ).encode("utf-8")),
        ("ppt/presentation.xml", (
            f'<p:presentation xmlns:p="{p_ns}" xmlns:r="{rel}"><p:sldIdLst>'
            '<p:sldId id="256" r:id="s1"/><p:sldId id="257" r:id="s2"/>'
            "</p:sldIdLst></p:presentation>"
        ).encode("utf-8")),
        ("ppt/_rels/presentation.xml.rels", (
            f'<Relationships xmlns="{pkg}">'
            f'<Relationship Id="s1" Type="{rel}/slide" Target="slides/slide1.xml"/>'
            f'<Relationship Id="s2" Type="{rel}/slide" Target="slides/slide2.xml"/>'
            "</Relationships>"
        ).encode("utf-8")),
        ("ppt/slides/slide1.xml", slide(CUP).encode("utf-8")),
        ("ppt/slides/slide2.xml", slide(STEP).encode("utf-8")),
        ("ppt/slides/_rels/slide1.xml.rels", (
            f'<Relationships xmlns="{pkg}">'
            f'<Relationship Id="n1" Type="{rel}/notesSlide" Target="../notesSlides/notes1.xml"/>'
            "</Relationships>"
        ).encode("utf-8")),
        ("ppt/slides/_rels/slide2.xml.rels", (
            f'<Relationships xmlns="{pkg}">'
            f'<Relationship Id="n2" Type="{rel}/notesSlide" Target="../notesSlides/notes2.xml"/>'
            "</Relationships>"
        ).encode("utf-8")),
        ("ppt/notesSlides/notes1.xml", notes(SLIDE_NOTE_1).encode("utf-8")),
        ("ppt/notesSlides/notes2.xml", notes(SLIDE_NOTE_2).encode("utf-8")),
    ]))

    odt = (
        '<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
        'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
        'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">'
        "<office:body><office:text>"
        f'<text:h text:outline-level="1">{ZH_TITLE_1}</text:h><text:p>{CUP}</text:p>'
        "<table:table><table:table-row>"
        f"<table:table-cell><text:p>物品</text:p></table:table-cell>"
        f"<table:table-cell><text:p>状态</text:p></table:table-cell>"
        "</table:table-row></table:table>"
        f'<text:h text:outline-level="1">{ZH_TITLE_2}</text:h><text:p>{STEP}</text:p>'
        "</office:text></office:body></office:document-content>"
    )
    write("window.odt", zip_stored([
        ("mimetype", b"application/vnd.oasis.opendocument.text"),
        ("content.xml", odt.encode("utf-8")),
    ]))
    write("window.rtf", ("{\\rtf1\\ansi\\uc1 " + rtf_escape(f"{ZH_TITLE_1}\n{CUP}\n{ZH_TITLE_2}\n{STEP}\n") + "}").encode("ascii"))

    xhtml = (
        '<html xmlns="http://www.w3.org/1999/xhtml"><body>'
        f"<h1>{ZH_TITLE_1}</h1><p>{CUP}</p><h1>{ZH_TITLE_2}</h1><p>{STEP}</p>"
        "</body></html>"
    )
    write("window.epub", zip_stored([
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", (
            '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>'
        ).encode("utf-8")),
        ("OPS/package.opf", (
            '<package xmlns="http://www.idpf.org/2007/opf"><metadata>'
            '<dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">窗边短记</dc:title></metadata>'
            '<manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>'
            '<spine><itemref idref="c"/></spine></package>'
        ).encode("utf-8")),
        ("OPS/chapter.xhtml", xhtml.encode("utf-8")),
    ]))

    kindle_html = f"<html><body><h1>{ZH_TITLE_1}</h1><p>{CUP}</p><h1>{ZH_TITLE_2}</h1><p>{STEP}</p></body></html>"
    write("window.mobi", mobi(kindle_html, drm=False))
    write("window-drm.mobi", mobi(kindle_html, drm=True))
    write("window.pdf", pdf([PDF_1, PDF_2]))
    sizes = sorted((path.name, path.stat().st_size) for path in ROOT.iterdir())
    for name, size in sizes:
        print(f"{size:8d} {name}")


if __name__ == "__main__":
    main()

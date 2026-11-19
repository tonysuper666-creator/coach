"""Author the fresh multi-format set (2026-11-19 Windows round).

Different text from books/formats/*: the earlier round reused the checked-in fixtures,
this round writes new self-authored coastal-biology notes and rebuilds the Kindle
containers from that same text. Standard library only; deterministic output.

Run: python3 desktopApp/src/test/resources/books/formats/fresh/author.py
Writes into this directory.
"""
from pathlib import Path
import struct
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent

def write(name, text, encoding="utf-8"):
    # Bytes, not write_text: the repository keeps LF (`.gitattributes`), and the checked-in
    # file must be exactly what the test read, on Windows too.
    (ROOT / name).write_bytes(text.encode(encoding))

CHAPTERS = [
    ("潮水留下的温度",
     "退潮后的半小时里，礁石表面能升高六到八摄氏度。深色藻类吸热更快，浅色壳面慢一些。",
     "The rock surface warms by six to eight degrees Celsius within half an hour of the tide leaving."),
    ("藤壶怎样站稳",
     "藤壶的腺体分泌一种蛋白质胶，固化后能承受波浪的剪切力。它先试探一小块，再逐步扩大 😀",
     "Barnacles secrete a protein glue that cures into a bond strong enough for breaking waves."),
    ("退潮之后怎样呼吸",
     "贻贝闭壳，把海水留在壳内，靠鳃继续交换气体。壳缝一开一合，节奏比涨潮时慢。",
     "Mussels close their shells and keep seawater inside, so the gills can still exchange gases."),
]
TEXT = "\n\n".join(f"第{i+1}章 {title}\n\n{body}\n\n{english}\n\n读完后请用自己的话说明这个过程。" for i, (title, body, english) in enumerate(CHAPTERS))
TABLE_ROW = ("附着面积", "六平方毫米")
FOOTNOTE = "这个数字来自我们自己的一次退潮测量，不是教材原文。"

# ---- HTML / Markdown / RTF --------------------------------------------------------------
HTML = (
    "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>潮间带观察笔记</title>"
    "<style>body{font-family:serif}nav{display:block}</style></head><body>"
    "<nav>首页 · 标签 · 订阅</nav><script>window.ads = [];</script>"
    "<header>站点页头不要导入</header><article>"
    + "".join(f"<h1>第{i+1}章 {title}</h1><p>{body}</p><p>{english}</p>" for i, (title, body, english) in enumerate(CHAPTERS))
    + "</article><form><input name=\"q\"></form><footer>页脚不要导入</footer></body></html>"
)
write("fresh-notes.html", HTML)
# Same article "saved from a browser as .txt": distinct bytes (own title / nav line) so the
# library does not content-dedupe it against the .html, but the header is still HTML markup, so
# the suffix must lose to content detection and it must import as html.
TXT_HTML = (
    HTML.replace("<title>潮间带观察笔记</title>", "<title>潮间带观察笔记（另存为 txt）</title>")
    .replace("<nav>首页 · 标签 · 订阅</nav>", "<nav>这份是浏览器另存出来的纯文本</nav>")
)
write("fresh-notes.txt", TXT_HTML)

write("fresh-observation.md",
      "\n\n".join(f"## 第{i+1}章 {title}\n\n{body}\n\n{english}" for i, (title, body, english) in enumerate(CHAPTERS)))

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
write("fresh-lab.rtf", "{\\rtf1\\ansi\\ansicpg1252\\uc1 " + rtf_escape(TEXT) + "}", encoding="ascii")

# ---- ODT with a table row and a footnote ------------------------------------------------
def archive(name, entries):
    with ZipFile(ROOT / name, "w", compression=ZIP_DEFLATED) as z:
        for path, content in entries.items():
            info = ZipInfo(path, date_time=(2026, 11, 19, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            z.writestr(info, content)

ODT_NS = ('xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" '
          'xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" '
          'xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"')
def odt_body():
    out = []
    for i, (title, body, english) in enumerate(CHAPTERS):
        out.append(f'<text:h text:outline-level="1">第{i+1}章 {title}</text:h><text:p>{body}</text:p><text:p>{english}</text:p>')
        if i == 1:
            out.append(
                '<table:table table:name="测量"><table:table-row>'
                f'<table:table-cell><text:p>{TABLE_ROW[0]}</text:p></table:table-cell>'
                f'<table:table-cell><text:p>{TABLE_ROW[1]}</text:p></table:table-cell>'
                '</table:table-row></table:table>')
            out.append(
                '<text:p>测量值见脚注'
                '<text:note text:note-class="footnote"><text:note-body>'
                f'<text:p>{FOOTNOTE}</text:p>'
                '</text:note-body></text:note>。</text:p>')
    return "".join(out)
archive("fresh-lab.odt", {
    "mimetype": "application/vnd.oasis.opendocument.text",
    "content.xml": f'<office:document-content {ODT_NS}><office:body><office:text>{odt_body()}</office:text></office:body></office:document-content>',
})

# ---- MOBI (PalmDOC) and KF8/AZW3 rebuilt from the same new text -------------------------
def vwi(value):
    result = [value & 127 | 128]
    value >>= 7
    while value:
        result.insert(0, value & 127)
        value >>= 7
    return bytes(result)

def index(tags, entries):
    tagx = b"TAGX" + struct.pack(">II", 12 + 4 * len(tags), 1) + bytes(x for tag in tags for x in tag)
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

def mobi(name, version=6, compression=1, drm=False):
    html = ("<html><body>" + "".join(
        f"<h1>第{i+1}章 {title}</h1><p>{body}</p><p>{english}</p>" for i, (title, body, english) in enumerate(CHAPTERS)
    ) + "</body></html>").encode("utf-8")
    record0 = bytearray(16 + 264)
    struct.pack_into(">HHIHHHH", record0, 0, compression, 0, len(html), 1, 4096, int(drm), 0)
    record0[16:20] = b"MOBI"
    struct.pack_into(">I", record0, 20, 264)
    struct.pack_into(">I", record0, 28, 65001)
    struct.pack_into(">I", record0, 36, version)
    struct.pack_into(">I", record0, 108, 0xffffffff)
    records = [record0, html]
    if version == 8:
        flow, skeletons, fragments = bytearray(), [], []
        for i, (title, body, english) in enumerate(CHAPTERS):
            shell = f'<html><head><title>{title}</title></head><body></body></html>'.encode()
            a = f'<h1>第{i+1}章 {title}</h1><p>{body}</p>'.encode()
            b = f'<p>{english}</p>'.encode()
            start, insertion = len(flow), shell.index(b'</body>')
            skeletons.append((f'SKEL{i:010d}', 0x0a, [2, 2, start, len(shell), start, len(shell)]))
            fragments.append((f'{start+insertion:010d}', 0x0f, [0, i, 2*i, 0, len(a)]))
            fragments.append((f'{start+insertion+len(a):010d}', 0x0f, [0, i, 2*i+1, len(a), len(b)]))
            flow += shell + a + b
        html = bytes(flow)
        struct.pack_into('>I', record0, 4, len(html))
        records[1] = html
        struct.pack_into('>I', record0, 248, len(records))
        records += index([(2, 1, 1, 0), (3, 1, 2, 0), (4, 1, 4, 0), (6, 2, 8, 0), (0, 0, 0, 1)], fragments)
        struct.pack_into('>I', record0, 252, len(records))
        records += index([(1, 1, 3, 0), (6, 2, 12, 0), (0, 0, 0, 1)], skeletons)
    if version == 8:
        struct.pack_into(">I", record0, 192, len(records))
        records.append(b"FDST" + struct.pack(">IIII", 12, 1, 0, len(html)))
    header = bytearray(78)
    # Name field is exactly 32 bytes: an 11-byte name leaves zero padding and keeps the record
    # count at offset 76 aligned (a longer string here shifts the whole PalmDB header).
    header[:11] = b"Coach fresh"
    header[60:68] = b"BOOKMOBI"
    struct.pack_into(">H", header, 76, len(records))
    offset = 78 + 8 * len(records) + 2
    table = bytearray()
    for record in records:
        table += struct.pack(">II", offset, 0)
        offset += len(record)
    (ROOT / name).write_bytes(header + table + bytes(2) + b"".join(records))

mobi("fresh-coast.mobi", version=6, compression=1)
mobi("fresh-coast.azw3", version=8)
mobi("fresh-locked.mobi", drm=True)

for file in sorted(ROOT.glob("fresh-*")):
    print(f"{file.name}\t{file.stat().st_size}")

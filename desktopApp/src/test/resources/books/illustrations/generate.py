"""Self-authored bilingual fixtures. No accounts, textbooks or third-party artwork."""
from pathlib import Path
import base64
import struct
import zlib
import zipfile
ROOT = Path(__file__).parent

def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 32, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(b''.join(b'\0' + b''.join(bytes((30, 160 if x < 32 else 70, 200)) for x in range(64)) for y in range(32)))) + chunk(b'IEND', b'')
(ROOT / 'diagram.png').write_bytes(png)
html = '<html><title>原创新图</title><body><h1>Chapter 1</h1><p>甲😀<img src="diagram.png" alt="双色示意图">乙 Biological diagram.</p><h1>Chapter 2</h1><p>甲😀<img src="data:image/png;base64,' + base64.b64encode(png).decode() + '" alt="重复图">乙 Second.</p></body></html>'
(ROOT / 'article.html').write_text(html, encoding='utf-8')
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
P = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'
image = '<w:r><w:drawing><wp:inline><wp:docPr id="1" name="diagram" descr="双色示意图"/><a:graphic><a:blip r:embed="picture"/></a:graphic></wp:inline></w:drawing></w:r>'
p = '<w:p><w:r><w:t>甲😀</w:t></w:r>' + image + '<w:r><w:t>乙 Biology.</w:t></w:r></w:p>'
parts = {
    'word/document.xml': f'<w:document xmlns:w="{W}" xmlns:r="{R}" xmlns:a="{A}" xmlns:wp="{P}"><w:body>' + p + '<w:p><w:r><w:br w:type="page"/></w:r></w:p><w:tbl><w:tr><w:tc>' + p + '</w:tc><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>',
    'word/_rels/document.xml.rels': f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="picture" Type="{R}/image" Target="media/diagram.png"/></Relationships>',
    'word/media/diagram.png': png,
}
with zipfile.ZipFile(ROOT / 'illustrated.docx', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in parts.items():
        info = zipfile.ZipInfo(name, (2026, 11, 16, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, data)
for version, suffix in [(6, 'mobi'), (8, 'azw3')]:
    img = '<img recindex="00001" alt="Kindle图">' if version == 6 else '<img src="kindle:embed:0001?mime=image/png" alt="Kindle图">'
    text = ('<html><body><h1>Chapter 1</h1><p>甲😀' + img + '乙 Biology.</p><h1>Chapter 2</h1><p>甲😀' + img + '乙 Again.</p></body></html>').encode()
    record0 = bytearray(280)
    struct.pack_into('>HHIHHHH', record0, 0, 1, 0, len(text), 1, 4096, 0, 0)
    record0[16:20] = b'MOBI'
    struct.pack_into('>I', record0, 20, 264)
    struct.pack_into('>I', record0, 28, 65001)
    struct.pack_into('>I', record0, 36, version)
    struct.pack_into('>I', record0, 108, 2)
    records = [record0, text, png]
    header = bytearray(78); header[60:68] = b'BOOKMOBI'
    struct.pack_into('>H', header, 76, len(records))
    offset = 78 + 8 * len(records) + 2; table = bytearray()
    for record in records:
        table += struct.pack('>II', offset, 0); offset += len(record)
    (ROOT / ('illustrated.' + suffix)).write_bytes(header + table + b'\0\0' + b''.join(records))

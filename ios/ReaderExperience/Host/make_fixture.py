"""Generate a tiny original EPUB without any private material."""
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

output = Path(__file__).parent / "Resources" / "synthetic.epub"
with ZipFile(output, "w") as book:
    book.writestr("mimetype", "application/epub+zip", compress_type=ZIP_STORED)
    book.writestr("META-INF/container.xml", '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>''', compress_type=ZIP_DEFLATED)
    book.writestr("book.opf", '''<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:60961098-fac5-4dc2-968a-fac86a000001</dc:identifier><dc:title>Synthetic River</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>''', compress_type=ZIP_DEFLATED)
    book.writestr("nav.xhtml", '''<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">River</a></li></ol></nav></body></html>''', compress_type=ZIP_DEFLATED)
    paragraphs = ''.join(f'<p>Invented paragraph {i}: A quiet trail follows the water through a wide green valley.</p>' for i in range(1, 24))
    book.writestr("chapter.xhtml", f'''<html xmlns="http://www.w3.org/1999/xhtml"><head><title>River</title></head><body><h1>Synthetic River</h1>{paragraphs}<p>The synthetic river bends north.</p>{paragraphs}</body></html>''', compress_type=ZIP_DEFLATED)

"""Disposable invented PDF/catalog for the joint mainline/reader acceptance."""
import hashlib
import json


def write_library(root, course):
    root.mkdir()
    texts = ['Synthetic pears grow in the orchard.', 'Synthetic second page.']
    objects = ['<< /Type /Catalog /Pages 2 0 R >>',
               '<< /Type /Pages /Kids [4 0 R 5 0 R] /Count 2 >>',
               '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    objects += [f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 500 700] /Resources << /Font << /F1 3 0 R >> >> /Contents {6+i} 0 R >>' for i in range(2)]
    for text in texts:
        stream = f'BT /F1 16 Tf 30 600 Td ({text}) Tj ET'
        objects.append(f'<< /Length {len(stream)} >>\nstream\n{stream}\nendstream')
    document = '%PDF-1.4\n'
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(document))
        document += f'{i} 0 obj\n{obj}\nendobj\n'
    xref = len(document)
    document += f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'
    document += ''.join(f'{offset:010d} 00000 n \n' for offset in offsets)
    document += f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'
    data = document.encode('ascii')
    (root / 'synthetic.pdf').write_bytes(data)
    (root / 'catalog.json').write_text(json.dumps(dict(schema=1, documents=[dict(
        id='joint-book', course=course, title='Synthetic joint reader', file='synthetic.pdf',
        sha256=hashlib.sha256(data).hexdigest(), pages=2, kind='textbook', printedPageOffset=0)])))

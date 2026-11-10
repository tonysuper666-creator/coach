"""Create a new, synthetic-only 24-page ReaderLibrary for before/after measurements."""

import argparse
import hashlib
import json
from pathlib import Path


def write_library(root: Path, course: str) -> None:
    # Refuse an existing location, including an existing catalog or private library.
    root.mkdir(parents=True, exist_ok=False)
    count = 24
    page_ids = [4 + page * 2 for page in range(count)]
    objects = [
        "<< /Type /Catalog /Pages 2 0 R >>",
        f"<< /Type /Pages /Kids [{' '.join(f'{page} 0 R' for page in page_ids)}] /Count {count} >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    for page, page_id in enumerate(page_ids, 1):
        # Matches the desktop fixture's distinctive page background at 1200 x 1600 px.
        r, g, b = ((210 + page) / 255, (230 - page) / 255, (190 + page) / 255)
        stream = (
            f"{r:.8f} {g:.8f} {b:.8f} rg 0 0 600 800 re f "
            f"0 0 0 rg BT /F1 18 Tf 40 750 Td (SYNTHETIC PAGE {page}) Tj ET"
        )
        objects.extend([
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {page_id + 1} 0 R >>",
            f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream",
        ])
    document = bytearray(b"%PDF-1.4\n")
    offsets = []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(document))
        document.extend(f"{index} 0 obj\n{obj}\nendobj\n".encode("ascii"))
    xref = len(document)
    document.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii"))
    document.extend("".join(f"{offset:010d} 00000 n \n" for offset in offsets).encode("ascii"))
    document.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode("ascii")
    )
    (root / "synthetic.pdf").write_bytes(document)
    catalog = {"schema": 1, "documents": [{
        "id": "synthetic-baseline", "course": course, "title": "Synthetic baseline",
        "file": "synthetic.pdf", "sha256": hashlib.sha256(document).hexdigest(),
        "pages": count, "kind": "textbook", "printedPageOffset": 0,
    }]}
    (root / "catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New isolated library directory")
    parser.add_argument("--course", required=True, help="Isolated synthetic course ID")
    args = parser.parse_args()
    if not args.course.strip():
        parser.error("--course must not be blank")
    write_library(args.output, args.course)
    print("Created 24 synthetic pages; no production material was read.")

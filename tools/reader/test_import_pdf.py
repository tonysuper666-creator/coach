import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from import_pdf import import_pdf


def synthetic_pdf():
    stream = "BT /F1 18 Tf 20 100 Td (Invented orchard) Tj ET"
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
               f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream"]
    pdf = "%PDF-1.4\n"
    offsets = []
    for i, obj in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf += f"{i} 0 obj\n{obj}\nendobj\n"
    xref = len(pdf)
    pdf += f"xref\n0 6\n0000000000 65535 f \n"
    pdf += "".join(f"{n:010} 00000 n \n" for n in offsets)
    pdf += f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    return pdf.encode("ascii")


class PrivateImportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="coach-reader-import-")
        self.root = Path(self.temp.name)
        self.source = self.root / "invented.pdf"
        self.source.write_bytes(synthetic_pdf())

    def tearDown(self):
        self.temp.cleanup()

    def import_one(self, **kwargs):
        options = dict(document_id="orchard", course="C1", title="Synthetic orchard", kind="textbook", offset=0)
        options.update(kwargs)
        return import_pdf(self.source, self.root / "library", **options)

    def test_copy_is_content_addressed_private_and_reimport_is_idempotent(self):
        entry = self.import_one()
        self.assertEqual(1, entry["pages"])
        self.assertEqual(hashlib.sha256(self.source.read_bytes()).hexdigest(), entry["sha256"])
        self.assertEqual(self.source.read_bytes(), (self.root / "library" / entry["file"]).read_bytes())
        self.assertEqual(0, (self.root / "library").stat().st_mode & 0o077)
        self.assertEqual(0, (self.root / "library" / entry["file"]).stat().st_mode & 0o077)
        self.import_one(title="Renamed synthetic book")
        docs = json.loads((self.root / "library/catalog.json").read_text())["documents"]
        self.assertEqual(1, len(docs))
        self.assertEqual("Renamed synthetic book", docs[0]["title"])
        self.assertEqual(1, len(list((self.root / "library/books").glob("*.pdf"))))

    def test_never_puts_private_pdf_in_checkout_or_linked_worktree(self):
        for name, is_directory in [("repo", True), ("worktree", False)]:
            checkout = self.root / name
            checkout.mkdir()
            if is_directory:
                (checkout / ".git").mkdir()
            else:
                (checkout / ".git").write_text("gitdir: /invented/common")
            with self.assertRaises(ValueError):
                import_pdf(self.source, checkout / "private", document_id="book", course="C1", title="Synthetic", kind="reading")
            self.assertFalse((checkout / "private").exists())

    def test_bad_metadata_cannot_replace_existing_catalog(self):
        self.import_one()
        before = (self.root / "library/catalog.json").read_bytes()
        for kwargs in [dict(document_id="../oops"), dict(kind="slides", week=None), dict(offset=31)]:
            with self.assertRaises(ValueError):
                self.import_one(**kwargs)
        self.assertEqual(before, (self.root / "library/catalog.json").read_bytes())

    def test_corrupt_existing_pdf_cannot_be_blessed_by_reimport(self):
        entry = self.import_one()
        (self.root / "library" / entry["file"]).write_bytes(b"bad pdf")
        with self.assertRaises(ValueError):
            self.import_one()


if __name__ == "__main__":
    unittest.main()

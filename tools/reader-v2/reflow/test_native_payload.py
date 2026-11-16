"""Self-authored packages; no real books or remote resources."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

import native_payload as np


class NativeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def package(self, extension, data):
        source = self.root / ("synthetic." + extension)
        with ZipFile(source, "w") as z:
            for name, raw in data.items():
                z.writestr(name, raw)
        return source

    def test_epub_retains_spine_order_aside_table_mathml_and_resource_bytes(self):
        xhtml = b'''<html xmlns="http://www.w3.org/1999/xhtml"><body><p>before</p>
        <aside role="note">definition <em>important</em> tail</aside>
        <table><tr><th colspan="2">head</th></tr><tr><td>A</td><td>B</td></tr></table>
        <math xmlns="http://www.w3.org/1998/Math/MathML"><mfrac><mi>x</mi><mi>y</mi></mfrac></math>
        <img src="../images/plot.png"/></body></html>'''
        data = {"META-INF/container.xml": b'''<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>''',
                "OEBPS/content.opf": b'''<package xmlns="http://www.idpf.org/2007/opf"><manifest>
          <item id="a" href="one.xhtml"/><item id="b" href="two.xhtml"/></manifest>
          <spine><itemref idref="b"/><itemref idref="a"/></spine></package>''',
                "OEBPS/one.xhtml": xhtml, "OEBPS/two.xhtml": xhtml,
                "images/plot.png": b"synthetic resource bytes", "OEBPS/styles.css": b"aside {border:1px solid}"}
        source = self.package("epub", data)
        out, blobs = np.read_package(source)
        self.assertEqual(out["entry_documents"], ["OEBPS/two.xhtml", "OEBPS/one.xhtml"])
        body = next(m["tree"]["children"][0] for m in out["members"] if m["name"] == "OEBPS/one.xhtml")
        aside = body["children"][1]
        self.assertEqual(aside["tag"], "{http://www.w3.org/1999/xhtml}aside")
        self.assertEqual(aside["children"][0]["tail"], " tail")
        self.assertEqual(body["children"][2]["children"][0]["children"][0]["attributes"], {"colspan": "2"})
        self.assertEqual(body["children"][3]["children"][0]["tag"], "{http://www.w3.org/1998/Math/MathML}mfrac")
        self.assertEqual({m["name"]: blobs[m["blob_sha256"]] for m in out["members"]}, data)
        self.assertEqual(out["offset_mapping_status"], "requires_import_integration")

    def test_word_keeps_styles_merged_cells_omml_and_relationships(self):
        doc = b'''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:body>
        <w:p><w:pPr><w:pStyle w:val="KnowledgeBox"/></w:pPr><w:r><w:t>definition</w:t></w:r></w:p>
        <w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val="2"/>
        <w:vMerge w:val="restart"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>
        <m:oMath><m:f><m:num><m:r><m:t>x</m:t></m:r></m:num><m:den/></m:f></m:oMath>
        </w:body></w:document>'''
        data = {"word/document.xml": doc,
                "word/styles.xml": b'<styles><style id="KnowledgeBox"/></styles>',
                "word/_rels/document.xml.rels": b'<Relationships><Relationship Target="media/a.png"/></Relationships>',
                "word/media/a.png": b"synthetic image"}
        out, blobs = np.read_package(self.package("docx", data))
        source_tree = next(m["tree"] for m in out["members"] if m["name"] == "word/document.xml")
        serialized = json.dumps(source_tree)
        for preserved in ("KnowledgeBox", "gridSpan", "vMerge", "tblHeader", "oMath"):
            self.assertIn(preserved, serialized)
        self.assertEqual({m["name"]: blobs[m["blob_sha256"]] for m in out["members"]}, data)

    def test_member_names_are_never_used_as_output_paths(self):
        source = self.package("docx", {"word/document.xml": b"<document/>", "../outside": b"payload"})
        out = self.root / "output"
        np.run(argparse.Namespace(source=source, out=out))
        self.assertFalse((self.root / "outside").exists())
        record = json.loads((out / "package.json").read_text())
        raw = next(m for m in record["members"] if m["name"] == "../outside")
        self.assertEqual((out / "blobs" / raw["blob_sha256"]).read_bytes(), b"payload")

    def test_missing_main_document_does_not_create_partial_output(self):
        source = self.package("docx", {"word/styles.xml": b"<styles/>"})
        out = self.root / "output"
        with self.assertRaises(ValueError):
            np.run(argparse.Namespace(source=source, out=out))
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()

"""Review declarations must survive the real offline export boundary unchanged."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import review_workspace as rw


class ReviewWorkspaceTests(unittest.TestCase):
    def setUp(self):
        from PIL import Image, ImageDraw
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.previews = self.root / "previews"
        self.previews.mkdir()
        page = Image.new("RGB", (120, 160), "white")
        draw = ImageDraw.Draw(page)
        draw.rectangle((10, 10, 49, 49), outline="black")
        draw.text((12, 20), "A", fill="black")
        draw.rectangle((70, 90, 109, 129), outline="black")
        page.save(self.previews / "page-1.png")
        for name, edges in [("first", [5, 5, 55, 55]), ("second", [65, 85, 115, 135])]:
            image = self.previews / f"{name}.png"
            page.crop(edges).save(image)
            review = {"schema": 1, "documentSha256": "a" * 64, "pageTextSha256": "b" * 64,
                      "pngSha256": hashlib.sha256(image.read_bytes()).hexdigest(),
                      "selection": dict(zip(["left", "top", "right", "bottom"], edges), page=1, kind="TABLE"),
                      "decision": "UNREVIEWED"}
            (self.previews / f"{name}.review.json").write_text(json.dumps(review))
        self.bundle = rw.load_previews(self.previews)

    def decisions(self):
        return {"schema": 1, "items": [dict(id=r["id"], review=copy.deepcopy(r["review"]),
            decision="UNREVIEWED", checks={k: False for k in rw.CHECKS}) for r in self.bundle["items"]]}

    def approve(self, row):
        row.update(decision="COMPLETE_OBJECT_WITH_LABELS", checks={k: True for k in rw.CHECKS})

    def test_export_only_explicit_approvals_and_keep_exact_production_fields(self):
        saved = self.decisions()
        self.approve(saved["items"][0])
        saved["items"][1]["decision"] = "REJECTED"
        path = self.root / "decisions.json"
        path.write_text(json.dumps(saved))
        result = rw.export(self.previews, path, self.root / "approved")
        self.assertEqual(result, {"approved": 1, "not_exported": 1})
        files = list((self.root / "approved").iterdir())
        self.assertEqual(len(files), 1)
        self.assertEqual(json.loads(files[0].read_text()), dict(self.bundle["items"][0]["review"],
                         decision="COMPLETE_OBJECT_WITH_LABELS"))
        with self.assertRaises(FileExistsError):
            rw.export(self.previews, path, self.root / "approved")
        self.assertEqual(len(list((self.root / "approved").iterdir())), 1)

    def test_opening_or_checking_without_deciding_never_approves(self):
        saved = self.decisions()
        saved["items"][0]["checks"] = {k: True for k in rw.CHECKS}
        self.assertEqual(rw.approved_reviews(self.bundle, saved), [])
        self.approve(saved["items"][0])
        for key in rw.CHECKS:
            changed = copy.deepcopy(saved)
            changed["items"][0]["checks"][key] = False
            with self.assertRaises(ValueError):
                rw.approved_reviews(self.bundle, changed)

    def test_stale_unknown_duplicate_and_partial_batch_refused(self):
        saved = self.decisions()
        self.approve(saved["items"][0])
        changes = [lambda r: r["items"][0]["review"].update(documentSha256="c" * 64),
                   lambda r: r["items"][0]["review"]["selection"].update(right=54),
                   lambda r: r["items"][0]["review"].update(schema=True),
                   lambda r: r["items"][0].update(id="../other"),
                   lambda r: r["items"].__setitem__(1, r["items"][0]),
                   lambda r: r["items"].pop(), lambda r: r.update(schema=True)]
        for change in changes:
            modified = copy.deepcopy(saved)
            change(modified)
            with self.assertRaises(ValueError):
                rw.approved_reviews(self.bundle, modified)

    def test_nested_duplicate_and_crossing_approvals_rejected_but_touching_allowed(self):
        for edges in ([5, 5, 55, 55], [10, 10, 50, 50], [45, 45, 80, 80]):
            bundle = copy.deepcopy(self.bundle)
            bundle["items"][1]["review"]["selection"].update(zip(["left", "top", "right", "bottom"], edges))
            saved = self.decisions()
            saved["items"][1]["review"] = bundle["items"][1]["review"]
            for row in saved["items"]:
                self.approve(row)
            with self.assertRaises(ValueError):
                rw.approved_reviews(bundle, saved)
        saved = self.decisions()
        for row in saved["items"]:
            self.approve(row)
        self.assertEqual(len(rw.approved_reviews(self.bundle, saved)), 2)
        self.assertFalse(rw.overlap(dict(page=1, left=0, top=0, right=10, bottom=10),
                                    dict(page=1, left=10, top=0, right=20, bottom=10)))

    def test_changed_png_or_full_page_refused_before_any_output(self):
        from PIL import Image
        for target in ("first.png", "page-1.png"):
            path = self.previews / target
            original = path.read_bytes()
            with Image.open(path) as source:
                source.putpixel((12, 20), (255, 0, 0))
                source.save(path)
            with self.assertRaises(ValueError):
                rw.prepare(self.previews, self.root / "bad")
            self.assertFalse((self.root / "bad").exists())
            path.write_bytes(original)

    def test_already_approved_receipt_and_mixed_source_refused(self):
        path = self.previews / "first.review.json"
        original = json.loads(path.read_text())
        for changes in ({"decision": "COMPLETE_OBJECT_WITH_LABELS"}, {"documentSha256": "c" * 64},
                        {"pageTextSha256": "c" * 64}, {"schema": True}):
            path.write_text(json.dumps(dict(original, **changes)))
            with self.assertRaises(ValueError):
                rw.load_previews(self.previews)
        path.write_text(json.dumps(original))
        with patch.object(rw, "MAX_PIXELS", 10):
            with self.assertRaises(ValueError):
                rw.load_previews(self.previews)

    def test_html_is_self_contained_and_untrusted_names_are_inert(self):
        for suffix in (".png", ".review.json"):
            (self.previews / ("first" + suffix)).rename(self.previews / ("<img onerror=alert(1)>" + suffix))
        result = rw.prepare(self.previews, self.root / "bundle")
        self.assertEqual(result, {"objects": 2, "pages": 1, "approved": 0})
        html = (self.root / "bundle/review.html").read_text()
        self.assertNotIn("<img onerror", html)
        self.assertIn("\\u003cimg onerror", html)
        self.assertNotIn("__SCRIPT_HASH__", html)
        script = html.split('<script id="app">')[1].split("</script>")[0]
        self.assertIn("sha256-" + __import__("base64").b64encode(hashlib.sha256(script.encode()).digest()).decode(), html)
        self.assertIn("connect-src 'none'", html)

    def test_git_and_symlink_outputs_refused_without_touching_old_files(self):
        repo = self.root / "repo"
        repo.mkdir()
        (repo / ".git").write_text("gitdir: elsewhere")
        link = self.root / "link"
        link.symlink_to(repo, target_is_directory=True)
        for destination in (repo / "new", link / "new"):
            with self.assertRaises(ValueError):
                rw.prepare(self.previews, destination)
            self.assertFalse(destination.exists())
        out = self.root / "existing"
        out.mkdir()
        (out / "keep").write_text("original")
        with self.assertRaises(FileExistsError):
            rw.prepare(self.previews, out)
        self.assertEqual((out / "keep").read_text(), "original")


if __name__ == "__main__":
    unittest.main()

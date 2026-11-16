"""A changed rectangle must return through production preview and fresh review."""
import copy
import json
import unittest

import review_workspace as rw
import test_review_workspace as fixtures


class SelectionEditTests(unittest.TestCase):
    setUp = fixtures.ReviewWorkspaceTests.setUp

    def saved(self):
        rows = [dict(id=r["id"], review=copy.deepcopy(r["review"]),
                     pageSha256=self.bundle["pages"][str(r["review"]["selection"]["page"])]["sha256"],
                     selection=None) for r in self.bundle["items"]]
        rows[0]["selection"] = dict(rows[0]["review"]["selection"], left=0, top=0, right=60, bottom=60)
        return dict(schema=1, purpose="selection-edits", items=rows)

    def export(self, saved, name="new"):
        path = self.root / "edits.json"
        path.write_text(json.dumps(saved), encoding="utf-8")
        return rw.selections(self.previews, path, self.root / name)

    def test_requests_only_cannot_be_imported_as_approvals(self):
        saved = self.saved()
        self.assertEqual(self.export(saved), dict(selections=1, approved=0, requires_fresh_preview=True))
        files = list((self.root / "new").iterdir())
        self.assertEqual([p.name for p in files], ["edited-0001.selection.json"])
        self.assertEqual(json.loads(files[0].read_text()), saved["items"][0]["selection"])
        with self.assertRaises(ValueError):
            rw.approved_reviews(self.bundle, saved)
        with self.assertRaises(FileExistsError):
            self.export(saved)

    def test_stale_partial_duplicate_unknown_or_approval_bearing_edits_refused(self):
        changes = [lambda s: s.update(schema=True), lambda s: s.update(purpose="approved"),
                   lambda s: s["items"].pop(), lambda s: s["items"].append(s["items"][0]),
                   lambda s: s["items"].__setitem__(1, s["items"][0]),
                   lambda s: s["items"][0].update(id="../foreign"),
                   lambda s: s["items"][0]["review"].update(documentSha256="c" * 64),
                   lambda s: s["items"][0]["review"].update(schema=True),
                   lambda s: s["items"][0].update(pageSha256="d" * 64),
                   lambda s: s["items"][0].update(decision="COMPLETE_OBJECT_WITH_LABELS")]
        for i, change in enumerate(changes):
            saved = self.saved()
            change(saved)
            with self.subTest(i=i), self.assertRaises(ValueError):
                self.export(saved)
            self.assertFalse((self.root / "new").exists())

    def test_non_integer_empty_outside_changed_page_and_kind_refused(self):
        for changed in (dict(left=True), dict(top=0.5), dict(left=-1), dict(right=121),
                        dict(bottom=161), dict(left=60), dict(bottom=0), dict(page=2),
                        dict(kind="FORMULA"), dict(kind=[]), dict(extra=1)):
            saved = self.saved()
            saved["items"][0]["selection"].update(changed)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                self.export(saved)
            self.assertFalse((self.root / "new").exists())

    def test_no_edits_or_unchanged_selection_refused(self):
        for selection in (None, self.bundle["items"][0]["review"]["selection"]):
            saved = self.saved()
            saved["items"][0]["selection"] = selection
            with self.assertRaises(ValueError):
                self.export(saved)

    def test_edited_overlap_refused_even_when_original_crops_disjoint(self):
        saved = self.saved()
        saved["items"][1]["selection"] = dict(saved["items"][1]["review"]["selection"], left=59, top=59)
        with self.assertRaises(ValueError):
            self.export(saved)
        self.assertFalse((self.root / "new").exists())
        saved["items"][1]["selection"]["left"] = 60  # Touching is permitted.
        self.assertEqual(self.export(saved)["selections"], 2)

    def test_original_receipt_cannot_approve_new_rectangle(self):
        saved = fixtures.ReviewWorkspaceTests.decisions(self)
        fixtures.ReviewWorkspaceTests.approve(self, saved["items"][0])
        saved["items"][0]["review"]["selection"] = self.saved()["items"][0]["selection"]
        with self.assertRaises(ValueError):
            rw.approved_reviews(self.bundle, saved)

    def test_full_page_changes_outside_old_crops_invalidate_edit_context(self):
        from PIL import Image
        saved = self.saved()
        path = self.previews / "page-1.png"
        with Image.open(path) as page:
            page.putpixel((0, 0), (0, 0, 0))  # Old crop and its receipt still match.
            page.save(path)
        rw.load_previews(self.previews)
        with self.assertRaisesRegex(ValueError, "full-page context changed"):
            self.export(saved)
        self.assertFalse((self.root / "new").exists())

    def test_git_and_symlink_outputs_remain_refused(self):
        repo = self.root / "repo"
        repo.mkdir()
        (repo / ".git").write_text("gitdir: elsewhere")
        (self.root / "alias").symlink_to(repo, target_is_directory=True)
        for name in ("repo/new", "alias/new"):
            with self.assertRaises(ValueError):
                self.export(self.saved(), name)
            self.assertFalse((self.root / name).exists())


if __name__ == "__main__":
    unittest.main()

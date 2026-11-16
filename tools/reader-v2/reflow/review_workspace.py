#!/usr/bin/env python3
"""Private, offline full-size PDF crop review. This is not the reader UI.

prepare consumes production ReviewedPdfVisualsProbe previews, never raw B/C
candidates. export revalidates those previews and writes only explicit approvals
for the existing production importer to re-render and check again.
selections exports changed rectangles only; they need a fresh production preview
and another explicit review before import.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import re
import shutil

HERE = Path(__file__).resolve().parent
MAX_BYTES = 64 * 1024 * 1024
MAX_PIXELS = 64 * 1024 * 1024
DECISIONS = {"UNREVIEWED", "COMPLETE_OBJECT_WITH_LABELS", "REJECTED"}
CHECKS = {"wholeObject", "labels", "noProseOrDuplicate"}
KINDS = {"ILLUSTRATION", "TABLE", "FORMULA", "CALLOUT", "SIDEBAR", "DIAGRAM", "COMPLEX_REGION"}


def private(path):
    path = Path(path).expanduser().resolve()
    if any((parent / ".git").exists() for parent in (path, *path.parents)):
        raise ValueError("review content must stay outside Git")
    return path


def read_json(path):
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("review JSON budget exceeded")
    return json.loads(path.read_text(encoding="utf-8"))


def validate_review(review):
    if not isinstance(review, dict) or set(review) != {
        "schema", "documentSha256", "pageTextSha256", "pngSha256", "selection", "decision"
    } or type(review["schema"]) is not int or review["schema"] != 1:
        raise ValueError("unsupported review schema")
    if review["decision"] != "UNREVIEWED":
        raise ValueError("prepare requires untouched UNREVIEWED previews")
    for key in ("documentSha256", "pageTextSha256", "pngSha256"):
        if not isinstance(review[key], str) or not re.fullmatch(r"[0-9a-f]{64}", review[key]):
            raise ValueError("invalid source digest")
    validate_selection(review["selection"])


def validate_selection(s):
    if not isinstance(s, dict) or set(s) != {"page", "left", "top", "right", "bottom", "kind"}:
        raise ValueError("invalid selection")
    if any(type(s[k]) is not int for k in ("page", "left", "top", "right", "bottom")):
        raise ValueError("selection needs integer edges")
    if not (s["page"] >= 1 and 0 <= s["left"] < s["right"] <= 4096 and
            0 <= s["top"] < s["bottom"] <= 4096 and isinstance(s["kind"], str) and s["kind"] in KINDS):
        raise ValueError("invalid selection geometry or kind")


def image(path):
    from PIL import Image
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("image byte budget exceeded")
    data = path.read_bytes()
    with Image.open(io.BytesIO(data)) as opened:
        if opened.format != "PNG" or opened.n_frames != 1 or not (
            0 < opened.width <= 4096 and 0 < opened.height <= 4096
        ):
            raise ValueError("expected a bounded single-frame PNG")
        rgba = opened.convert("RGBA")
    return data, rgba


def load_previews(directory):
    directory = private(directory)
    names = sorted(directory.glob("*.review.json"))
    if not 1 <= len(names) <= 128:
        raise ValueError("review needs 1–128 objects")
    pages, items, count, pixels = {}, [], 0, 0
    for path in names:
        review = read_json(private(path))
        validate_review(review)
        name = path.name.removesuffix(".review.json")
        s = review["selection"]
        if s["page"] not in pages:
            raw, raster = image(private(directory / f'page-{s["page"]}.png'))
            count += len(raw)
            pixels += raster.width * raster.height
            pages[s["page"]] = (raw, raster)
        raw, crop = image(private(directory / f"{name}.png"))
        count += len(raw)
        pixels += crop.width * crop.height
        if count > MAX_BYTES or pixels > MAX_PIXELS:
            raise ValueError("batch image budget exceeded")
        if hashlib.sha256(raw).hexdigest() != review["pngSha256"]:
            raise ValueError("crop bytes changed; regenerate production preview")
        page = pages[s["page"]][1]
        edges = tuple(s[k] for k in ("left", "top", "right", "bottom"))
        if s["right"] > page.width or s["bottom"] > page.height or crop.size != (
            s["right"] - s["left"], s["bottom"] - s["top"]
        ) or page.crop(edges).tobytes() != crop.tobytes():
            raise ValueError("crop does not match its full-page context")
        items.append({"id": name, "review": review, "image": data_url(raw)})
    if len({r["review"]["documentSha256"] for r in items}) != 1:
        raise ValueError("mixed PDF documents")
    for page, occurrences in Counter(r["review"]["selection"]["page"] for r in items).items():
        if occurrences > 32:
            raise ValueError("page object budget exceeded")
        if len({r["review"]["pageTextSha256"] for r in items if r["review"]["selection"]["page"] == page}) != 1:
            raise ValueError("mixed canonical text on one page")
    return {"schema": 1, "items": items, "pages": {
        str(n): {"image": data_url(raw), "width": raster.width, "height": raster.height,
                 "sha256": hashlib.sha256(raw).hexdigest()}
        for n, (raw, raster) in pages.items()
    }}


def data_url(raw):
    return "data:image/png;base64," + base64.b64encode(raw).decode("ascii")


def overlap(a, b):
    return (a["page"] == b["page"] and a["left"] < b["right"] and
            b["left"] < a["right"] and a["top"] < b["bottom"] and b["top"] < a["bottom"])


def approved_reviews(bundle, decisions):
    """Saved decisions bind to exact draft receipts; opening a page grants nothing."""
    if (not isinstance(decisions, dict) or set(decisions) != {"schema", "items"} or
            type(decisions["schema"]) is not int or decisions["schema"] != 1):
        raise ValueError("invalid saved decisions")
    drafts = {row["id"]: row["review"] for row in bundle["items"]}
    rows = decisions["items"]
    if not isinstance(rows, list) or len(rows) != len(drafts):
        raise ValueError("saved decisions must cover this exact batch")
    seen, approved = set(), []
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"id", "review", "decision", "checks"}:
            raise ValueError("invalid decision row")
        identity = row["id"]
        validate_review(row["review"])
        if not isinstance(identity, str) or identity in seen or identity not in drafts or row["review"] != drafts[identity]:
            raise ValueError("stale, unknown or duplicate decision")
        seen.add(identity)
        checks = row["checks"]
        if row["decision"] not in DECISIONS or not isinstance(checks, dict) or set(checks) != CHECKS or any(
            type(v) is not bool for v in checks.values()
        ):
            raise ValueError("invalid explicit decision or checklist")
        if row["decision"] == "COMPLETE_OBJECT_WITH_LABELS":
            if not all(checks.values()):
                raise ValueError("approval requires all three explicit checks")
            review = dict(row["review"], decision=row["decision"])
            if any(overlap(review["selection"], old["selection"]) for old in approved):
                raise ValueError("approved crops overlap; retain one complete version")
            approved.append(review)
    return approved


def write_new(directory, files):
    """Validate everything first, refuse overwrites, remove only our partial output."""
    directory = private(directory)
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    try:
        for name, data in files.items():
            (directory / name).write_text(data, encoding="utf-8")
    except BaseException:
        shutil.rmtree(directory)
        raise


def prepare(previews, output):
    bundle = load_previews(previews)
    template = (HERE / "review_workspace.html").read_text(encoding="utf-8")
    # Even a malicious filename must remain inert JSON, never a closing script tag.
    payload = json.dumps(bundle, ensure_ascii=True).replace("<", "\\u003c")
    script = template.split('<script id="app">', 1)[1].split("</script>", 1)[0]
    digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
    html = template.replace("__SCRIPT_HASH__", digest).replace("__BATCH__", payload)
    write_new(output, {"review.html": html})
    return {"objects": len(bundle["items"]), "pages": len(bundle["pages"]), "approved": 0}


def export(previews, decisions, output):
    bundle = load_previews(previews)  # Reload bytes; never trust the HTML's approvals alone.
    reviews = approved_reviews(bundle, read_json(private(decisions)))
    if not reviews:
        raise ValueError("no explicitly approved objects")
    write_new(output, {f"object-{i:04d}.review.json": json.dumps(r, ensure_ascii=False, indent=2) + "\n"
                       for i, r in enumerate(reviews, 1)})
    return {"approved": len(reviews), "not_exported": len(bundle["items"]) - len(reviews)}


def selection_edits(bundle, saved):
    """Edits request a fresh production preview; they never carry approval."""
    if (not isinstance(saved, dict) or set(saved) != {"schema", "purpose", "items"} or
            type(saved["schema"]) is not int or saved["schema"] != 1 or
            saved["purpose"] != "selection-edits" or not isinstance(saved["items"], list) or
            len(saved["items"]) != len(bundle["items"])):
        raise ValueError("invalid selection edit batch")
    drafts = {row["id"]: row["review"] for row in bundle["items"]}
    seen, changed = set(), []
    for row in saved["items"]:
        if not isinstance(row, dict) or set(row) != {"id", "review", "pageSha256", "selection"}:
            raise ValueError("invalid selection edit row")
        identity = row["id"]
        if (not isinstance(identity, str) or identity in seen or identity not in drafts or
                row["review"] != drafts[identity]):
            raise ValueError("stale, unknown or duplicate edit")
        validate_review(row["review"])
        seen.add(identity)
        original = drafts[identity]["selection"]
        page = bundle["pages"][str(original["page"])]
        if row["pageSha256"] != page["sha256"]:
            raise ValueError("full-page context changed; regenerate workspace")
        selection = row["selection"]
        if selection is None:
            continue
        validate_selection(selection)
        if (selection["page"] != original["page"] or selection["kind"] != original["kind"] or
                selection["right"] > page["width"] or
                selection["bottom"] > page["height"] or selection == original):
            raise ValueError("edit must change the crop within its original page")
        changed.append(selection)
    if not changed:
        raise ValueError("no changed selections")
    if any(overlap(s, old) for i, s in enumerate(changed) for old in changed[:i]):
        raise ValueError("edited selections overlap; retain one complete version")
    return changed


def selections(previews, edits, output):
    bundle = load_previews(previews)
    changed = selection_edits(bundle, read_json(private(edits)))
    write_new(output, {f"edited-{i:04d}.selection.json": json.dumps(s, indent=2) + "\n"
                       for i, s in enumerate(changed, 1)})
    return {"selections": len(changed), "approved": 0, "requires_fresh_preview": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "export", "selections"):
        command = commands.add_parser(name)
        command.add_argument("--previews", type=Path, required=True)
        command.add_argument("--out", type=Path, required=True)
        if name == "export":
            command.add_argument("--decisions", type=Path, required=True)
        if name == "selections":
            command.add_argument("--edits", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        result = prepare(args.previews, args.out)
    elif args.command == "export":
        result = export(args.previews, args.decisions, args.out)
    else:
        result = selections(args.previews, args.edits, args.out)
    print(json.dumps(result))


if __name__ == "__main__":
    main()

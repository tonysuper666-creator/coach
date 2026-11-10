#!/usr/bin/env python3
"""Import ONE explicitly selected PDF into a private local library. No network."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def import_pdf(source, library, *, document_id, course, title, kind, offset=None, week=None):
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", document_id):
        raise ValueError("invalid document ID")
    if not 1 <= len(course) <= 128 or not 1 <= len(title) <= 300:
        raise ValueError("invalid course/title")
    if kind not in {"textbook", "slides", "reading"} or (kind == "slides" and not (week and 1 <= week <= 100)):
        raise ValueError("slides require an explicit week")
    source = Path(source).resolve(strict=True)
    library = Path(library).expanduser().resolve()
    for parent in (library, *library.parents):
        if (parent / ".git").exists():
            raise ValueError("private library must be outside a Git checkout")
    library.mkdir(parents=True, exist_ok=True, mode=0o700)
    if library.stat().st_mode & 0o077:
        raise ValueError("private library must have permissions 0700")
    catalog_file = library / "catalog.json"
    with (library / ".import.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        catalog = json.loads(catalog_file.read_text()) if catalog_file.exists() else {"schema": 1, "documents": []}
        if catalog.get("schema") != 1:
            raise ValueError("unsupported catalog")
        books = library / "books"
        books.mkdir(mode=0o700, exist_ok=True)
        if not books.resolve().is_relative_to(library):
            raise ValueError("book directory outside private library")
        fd, pending = tempfile.mkstemp(prefix="import-", suffix=".pdf", dir=books)
        try:
            digest = hashlib.sha256()
            with os.fdopen(fd, "wb") as output, source.open("rb") as input_file:
                for chunk in iter(lambda: input_file.read(65536), b""):
                    output.write(chunk)
                    digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
            info = subprocess.run(["pdfinfo", pending], check=True, capture_output=True, text=True, timeout=20).stdout
            count = re.search(r"^Pages:\s+(\d+)\s*$", info, re.M)
            if not count or not 1 <= int(count[1]) <= 100000:
                raise ValueError("invalid page count")
            pages = int(count[1])
            if offset is not None and not 0 <= offset < pages:
                raise ValueError("invalid printed page offset")
            if not re.search(r"^Encrypted:\s+no\s*$", info, re.M):
                raise ValueError("encrypted PDFs are not supported")
            version = digest.hexdigest()
            target = books / (version + ".pdf")
            if target.exists():
                if not target.resolve().is_relative_to(library) or hashlib.sha256(target.read_bytes()).hexdigest() != version:
                    raise ValueError("existing content-addressed PDF is corrupt")
            else:
                os.replace(pending, target)
            entry = dict(id=document_id, course=course, title=title, file=f"books/{version}.pdf",
                         sha256=version, pages=pages, kind=kind)
            if offset is not None:
                entry["printedPageOffset"] = offset
            if week is not None:
                entry["week"] = week
            docs = [d for d in catalog["documents"] if (d["course"], d["id"]) != (course, document_id)]
            docs.append(entry)
            if len(docs) > 500:
                raise ValueError("library is full")
            fd, manifest = tempfile.mkstemp(prefix="catalog-", suffix=".json", dir=library)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as output:
                    json.dump({"schema": 1, "documents": docs}, output, ensure_ascii=False, indent=2)
                    output.write("\n")
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(manifest, catalog_file)
            finally:
                Path(manifest).unlink(missing_ok=True)
        finally:
            Path(pending).unlink(missing_ok=True)
    return entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--course", required=True, help="actual ledger course ID")
    parser.add_argument("--title", required=True)
    parser.add_argument("--kind", required=True, choices=["textbook", "slides", "reading"])
    parser.add_argument("--printed-page-offset", type=int)
    parser.add_argument("--week", type=int)
    args = parser.parse_args()
    try:
        entry = import_pdf(args.source, args.library, document_id=args.id, course=args.course,
                           title=args.title, kind=args.kind, offset=args.printed_page_offset, week=args.week)
    except (ValueError, OSError, subprocess.SubprocessError):
        parser.exit(1, "Private import failed: check the explicit PDF, catalog and permissions locally.\n")
    print(f"Imported {entry['id']}: {entry['pages']} PDF pages. Restart the development server to reload catalog.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Preserve EPUB/Word source trees and package resources for later import.

No rendering, network fetch or canonical-text rewrite. Private output only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import posixpath
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from benchmark import outside_repo


def tree(node):
    # Expanded namespace names retain aside/table/MathML and Word styles,
    # merged-cell properties and OMML. Mixed text/tails are not flattened.
    return {"tag": node.tag, "attributes": dict(node.attrib), "text": node.text,
            "tail": node.tail, "children": [tree(c) for c in node]}


def read_package(source):
    kind = source.suffix.lower().lstrip(".")
    if kind not in ("epub", "docx"):
        raise ValueError("expected EPUB or DOCX package")
    with ZipFile(source) as archive:
        members = [i for i in archive.infolist() if not i.is_dir()]
        if len({i.filename for i in members}) != len(members):
            raise ValueError("duplicate package member names")
        data = {i.filename: archive.read(i) for i in members}
    roots = {}
    for name, raw in data.items():
        if name.lower().endswith((".xml", ".xhtml", ".html", ".opf", ".ncx", ".rels")):
            try:
                roots[name] = ET.fromstring(raw)
            except ET.ParseError:
                # Keep exact bytes even when a legacy HTML member is not XML.
                roots[name] = None
    if kind == "docx":
        entries = ["word/document.xml"]
    else:
        container = roots.get("META-INF/container.xml")
        if container is None:
            raise ValueError("missing EPUB container")
        rootfiles = list(container.iter("{urn:oasis:names:tc:opendocument:xmlns:container}rootfile"))
        if not rootfiles:
            raise ValueError("missing EPUB rootfile")
        opf_name = rootfiles[0].attrib["full-path"]
        opf = roots.get(opf_name)
        if opf is None:
            raise ValueError("missing EPUB package document")
        ns = "{http://www.idpf.org/2007/opf}"
        manifest = {i.attrib["id"]: i.attrib["href"] for i in opf.iter(ns + "item")}
        # Package-local lookup only. Preserve the original href in the raw OPF;
        # never dereference external resources or rewrite links in source trees.
        from urllib.parse import unquote, urlsplit
        entries = []
        for item in opf.iter(ns + "itemref"):
            href = urlsplit(manifest[item.attrib["idref"]])
            if href.scheme or href.netloc:
                raise ValueError("EPUB spine must be package-local")
            entries.append(posixpath.normpath(posixpath.join(posixpath.dirname(opf_name), unquote(href.path))))
    if not entries or any(e not in data or roots.get(e) is None for e in entries):
        raise ValueError("missing or unparseable main document")
    payload = {"schema_version": 1, "format": kind,
               "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
               "entry_documents": entries, "offset_mapping_status": "requires_import_integration",
               "members": []}
    blobs = {}
    for name, raw in data.items():
        digest = hashlib.sha256(raw).hexdigest()
        blobs[digest] = raw
        node = roots.get(name)
        payload["members"].append({"name": name, "blob_sha256": digest,
                                   "tree": tree(node) if node is not None else None})
    return payload, blobs


def run(args):
    out = outside_repo(args.out)
    if out.exists():
        raise ValueError("output must be new; refusing stale payloads")
    payload, blobs = read_package(args.source)
    (out / "blobs").mkdir(parents=True)
    # Hash names, never archive paths: no ZIP member is extracted as a path.
    for digest, raw in blobs.items():
        (out / "blobs" / digest).write_bytes(raw)
    (out / "package.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    print(f'native payload: {len(payload["members"])} members; private output written')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    run(parser.parse_args())

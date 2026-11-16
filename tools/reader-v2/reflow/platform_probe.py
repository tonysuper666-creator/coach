#!/usr/bin/env python3
"""Measure an existing local Docling environment, not a clean installation.

Input PDFs, model locations and all output stay private. Use a self-authored PDF
when comparing machines; a synthetic timing is not a real-textbook quality score.
"""
from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import socket
import sys
import time

from benchmark import outside_repo


def footprint(root: Path) -> dict:
    """Logical file bytes; do not call this filesystem allocation or MSI size."""
    total = files = 0
    seen = set()
    for directory, _, names in os.walk(root):
        for name in names:
            path = Path(directory) / name
            resolved = path.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            total += path.stat().st_size
            files += 1
    return {"logical_bytes": total, "files": files}


def run(args) -> None:
    out = outside_repo(args.out)
    out.mkdir(parents=True, exist_ok=True)
    models = args.models_dir.resolve()
    pdf = args.pdf.resolve()
    pages = [int(p) for p in args.pages.split(",")]
    if not pages or min(pages) < 1 or len(set(pages)) != len(pages):
        raise ValueError("Use distinct positive physical page numbers")
    if not models.is_dir() or not pdf.is_file():
        raise ValueError("Pre-provision models and PDF before running offline")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    attempts = []

    def deny_network(sock, address):
        attempts.append(str(address))
        raise OSError("Network disabled by local measurement")

    # Blocks Python socket connects, including lazy model-download attempts.
    # This is not an OS firewall and does not sandbox native extensions.
    socket.socket.connect = deny_network
    socket.socket.connect_ex = deny_network
    started = time.perf_counter()
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "layout"))
    import layout_pipeline as lp
    import torch

    engine = lp.DoclingEngine(str(models))
    constructed = time.perf_counter() - started
    meta = lp.pdf_page_meta(pdf)
    sha = lp.sha256_file(pdf)
    results = []
    for iteration in range(args.repeats):
        for number in pages:
            started = time.perf_counter()
            raw = engine.convert_range(pdf, number, number)
            page = lp.build_page_json(number, raw.get(number), meta[number - 1], sha)
            seconds = time.perf_counter() - started
            results.append({"iteration": iteration, "page": number, "seconds": seconds,
                            "blocks": len(page["blocks"]), "status": page["status"],
                            "order_gate": page["reading_order_status"]})
            (out / f"page-{iteration}-{number}.json").write_text(
                json.dumps(page, ensure_ascii=False), encoding="utf-8")
    receipt = {
        "schema": 1, "scope": "existing_environment_not_clean_install",
        "platform": platform.platform(), "machine": platform.machine(),
        "python": platform.python_version(), "cpu_logical_count": os.cpu_count(),
        "packages": {name: importlib.metadata.version(name) for name in
                     ("docling", "torch", "transformers", "pypdfium2")},
        "torch_cuda_build": torch.version.cuda, "cuda_available": torch.cuda.is_available(),
        "venv": footprint(Path(sys.prefix)), "models": footprint(models),
        "source_sha256": sha, "physical_pages": pages,
        "engine_import_and_construction_seconds": constructed,
        "offline": {"hf_hub": True, "transformers": True,
                    "python_socket_connect_blocked": True, "attempts": attempts,
                    "native_network_sandboxed": False}, "pages": results,
    }
    (out / "platform-metrics.json").write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Measured {len(results)} page conversions; output remains outside repository")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--pages", required=True)
    parser.add_argument("--models-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--repeats", type=int, choices=range(1, 6), default=2)
    run(parser.parse_args())

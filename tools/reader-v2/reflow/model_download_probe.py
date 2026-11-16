#!/usr/bin/env python3
"""Download the default PDF pipeline's local models into empty private caches.

This measures a model download on an existing OS/Python, not product installation.
No document is read. Optional formula, picture-classifier and VLM models are off.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time

from benchmark import outside_repo
from platform_probe import footprint


def model_manifest(root: Path) -> list[dict]:
    """Keep exact downloaded file identities in a private, portable manifest."""
    records = []
    for path in sorted(root.rglob("*")):
        if path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            records.append({"path": path.relative_to(root).as_posix(),
                            "bytes": path.stat().st_size, "sha256": digest.hexdigest()})
    return records


def run(args) -> None:
    out = outside_repo(args.out)
    out.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for name in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HF_ENDPOINT",
                 "HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE"):
        env.pop(name, None)
    env.update({
        "HF_HOME": str(out / "hf"),
        "HF_HUB_CACHE": str(out / "hf" / "hub"),
        "HUGGINGFACE_HUB_CACHE": str(out / "hf" / "hub"),
        "HF_ASSETS_CACHE": str(out / "hf" / "assets"),
        "HF_XET_CACHE": str(out / "hf" / "xet"),
        "TRANSFORMERS_CACHE": str(out / "transformers"),
        "TORCH_HOME": str(out / "torch"),
        "XDG_CACHE_HOME": str(out / "cache"),
        "HF_HUB_DISABLE_IMPLICIT_TOKEN": "1",
        "HF_HUB_ETAG_TIMEOUT": "10",
        "HF_HUB_DOWNLOAD_TIMEOUT": "30",
    })
    receipt = {
        "schema": 1, "scope": "model_download_empty_application_caches_existing_os",
        "platform": platform.platform(), "python": platform.python_version(),
        "docling_version": importlib.metadata.version("docling"),
        "huggingface_hub_version": importlib.metadata.version("huggingface-hub"),
        "initial_output": footprint(out), "document_input": False,
        "implicit_auth_disabled": True, "clean_os": False,
        "models": ["layout_all_default_engine_variants", "tableformer", "rapidocr"],
        "timeout_seconds": args.timeout,
    }
    receipt_path = out / "download-metrics.json"

    def save():
        receipt_path.write_text(json.dumps(receipt, indent=2), encoding="utf-8")

    save()
    code = (
        "from pathlib import Path; import sys; "
        "from docling.utils.model_downloader import download_models; "
        "download_models(output_dir=Path(sys.argv[1]), "
        "with_layout=True, with_tableformer=True, with_rapidocr=True, "
        "with_code_formula=False, with_picture_classifier=False, progress=False)"
    )
    started = time.perf_counter()
    try:
        with (out / "download-private.log").open("wb") as log:
            result = subprocess.run(
                [sys.executable, "-I", "-c", code, str(out / "models")],
                env=env, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
        receipt["exit_code"] = result.returncode
    except subprocess.TimeoutExpired:
        receipt["exit_code"] = 124
        receipt["timed_out"] = True
    finally:
        receipt["wall_seconds"] = time.perf_counter() - started
        receipt["downloaded_models"] = footprint(out / "models")
        receipt["download_caches"] = {
            name: footprint(out / name) for name in ("hf", "transformers", "torch", "cache")}
        (out / "model-files-private.json").write_text(
            json.dumps(model_manifest(out / "models"), indent=2), encoding="utf-8")
        save()
    print("Model download receipt saved outside repository; exit", receipt["exit_code"])
    raise SystemExit(receipt["exit_code"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="New private directory")
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    run(args)

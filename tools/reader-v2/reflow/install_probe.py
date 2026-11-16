#!/usr/bin/env python3
"""Measure a fresh isolated venv on an existing machine, not a clean OS/MSI.

Downloads packages with pip's cache disabled. Models are copied from an existing
local directory; their download time is deliberately not measured. All receipts
and pip logs remain private, outside the checkout.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time
import venv

from benchmark import outside_repo
from platform_probe import footprint


def run(args) -> None:
    out = outside_repo(args.out)
    models = args.models_dir.expanduser().resolve()
    if not models.is_dir():
        raise ValueError("Supply pre-provisioned local models")
    if out.is_relative_to(models):
        raise ValueError("Output cannot be inside the model source directory")
    # Never reuse an environment or overwrite an earlier measurement.
    out.mkdir(parents=True, exist_ok=False)
    env_dir = out / "venv"
    receipt = {
        "schema": 1, "scope": "fresh_venv_on_existing_os",
        "platform": platform.platform(), "python": platform.python_version(),
        "requested_packages": args.packages,
        "base_python": footprint(Path(sys.base_prefix)),
        "model_download_measured": False,
        "pip_cache_enabled": False,
    }
    receipt_file = out / "install-metrics.json"

    def save():
        receipt_file.write_text(json.dumps(receipt, indent=2), encoding="utf-8")

    save()
    started = time.perf_counter()
    venv.EnvBuilder(with_pip=True, system_site_packages=False).create(env_dir)
    receipt["venv_creation_seconds"] = time.perf_counter() - started
    receipt["empty_venv"] = footprint(env_dir)
    save()
    python = env_dir / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    command = [str(python), "-I", "-m", "pip", "--isolated",
               "--disable-pip-version-check", "--no-cache-dir", "install",
               *args.packages]
    started = time.perf_counter()
    with (out / "pip-private.log").open("wb") as log:
        installed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    receipt["pip_install_seconds"] = time.perf_counter() - started
    receipt["pip_exit_code"] = installed.returncode
    receipt["installed_venv"] = footprint(env_dir)
    receipt["dependency_increment_logical_bytes"] = (
        receipt["installed_venv"]["logical_bytes"] - receipt["empty_venv"]["logical_bytes"])
    save()
    if installed.returncode:
        raise SystemExit(installed.returncode)
    versions = subprocess.check_output([
        str(python), "-I", "-c",
        "import importlib.metadata as m,json; "
        "print(json.dumps(sorted((d.metadata['Name'],d.version) for d in m.distributions())))",
    ], text=True)
    receipt["installed_packages"] = json.loads(versions)
    started = time.perf_counter()
    shutil.copytree(models, out / "models")
    receipt["model_local_copy_seconds"] = time.perf_counter() - started
    receipt["models"] = footprint(out / "models")
    receipt["venv_and_models_logical_bytes"] = (
        receipt["installed_venv"]["logical_bytes"] + receipt["models"]["logical_bytes"])
    save()
    print("Fresh venv installed; private receipt written outside repository")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True, help="New private directory")
    parser.add_argument("--models-dir", type=Path, required=True)
    parser.add_argument("--packages", nargs="+", required=True, help="Exact package==version pins")
    args = parser.parse_args()
    if any("==" not in pin or pin.startswith("-") for pin in args.packages):
        parser.error("Use explicit package==version pins")
    run(args)

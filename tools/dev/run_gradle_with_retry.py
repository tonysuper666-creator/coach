#!/usr/bin/env python3
"""Run a Gradle command with bounded exponential-backoff retry on plugin/repo unavailability."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from typing import Callable, Sequence

DEFAULT_DELAYS = (10, 25, 60)

PLUGIN_NOT_FOUND_RE = re.compile(
    r"Plugin \[id:\s*'[^']+'[^\]]*\] was not found in any of the following sources:",
    re.IGNORECASE,
)
ERROR_RESOLVING_PLUGIN_RE = re.compile(r"Error resolving plugin \[id:", re.IGNORECASE)
TEST_TASK_FAILED_RE = re.compile(r"^> Task :[^\n]*\btest\b[^\n]* FAILED\s*$", re.MULTILINE)
NETWORK_TRANSFER_RE = re.compile(
    r"Could not (?:GET|HEAD|download|transfer)\b", re.IGNORECASE
)

CONFIG_ERROR_MARKERS = (
    "is a core gradle plugin",
    "cannot be specified with a version number",
    "available for offline mode",
    "invalid plugin request",
)

REPO_NETWORK_MARKERS = (
    "plugins.gradle.org",
    "repo.maven.apache.org",
    "dl.google.com",
    "services.gradle.org",
    "gradle central plugin repository",
    "could not resolve plugin artifact",
    "timed out",
    "connection reset",
    "unknownhostexception",
    "connectexception",
    "noroutetohostexception",
    "sockettimeoutexception",
    "received status code 5",
    "received status code 429",
)


def is_plugin_repo_unavailable(output: str) -> bool:
    """Return True only when Gradle failed due to plugin/dependency repo unavailability."""
    if "There were failing tests" in output or TEST_TASK_FAILED_RE.search(output):
        return False

    lower = output.lower()
    if any(marker in lower for marker in CONFIG_ERROR_MARKERS):
        return False

    has_repo_network_marker = any(marker in lower for marker in REPO_NETWORK_MARKERS)

    if PLUGIN_NOT_FOUND_RE.search(output) and (
        "could not resolve plugin artifact" in lower
        or ("plugin repositories" in lower and ("could not resolve" in lower or has_repo_network_marker))
    ):
        return True

    if ERROR_RESOLVING_PLUGIN_RE.search(output) and (
        has_repo_network_marker
        or NETWORK_TRANSFER_RE.search(output)
        or ("plugin repositories" in lower and "could not resolve" in lower)
    ):
        return True

    if "could not resolve plugin artifact" in lower:
        return True

    if "services.gradle.org/distributions/" in lower and "Welcome to Gradle" not in output:
        return True

    if NETWORK_TRANSFER_RE.search(output) and has_repo_network_marker:
        return True

    return False


def parse_delays(raw: str | None) -> tuple[int, ...]:
    if not raw:
        return DEFAULT_DELAYS
    parts = [int(p.strip()) for p in raw.split(",") if p.strip()]
    if not parts:
        return DEFAULT_DELAYS
    return tuple(parts)


def run_once(cmd: Sequence[str], stream: Callable[[str], None]) -> tuple[int, str]:
    proc = subprocess.Popen(
        list(cmd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    assert proc.stdout is not None
    lines: list[str] = []
    for line in proc.stdout:
        stream(line)
        lines.append(line)
    proc.wait()
    return proc.returncode, "".join(lines)


def run_with_retry(
    cmd: Sequence[str],
    delays: Sequence[int] = DEFAULT_DELAYS,
    runner: Callable[[Sequence[str], Callable[[str], None]], tuple[int, str]] = run_once,
    sleeper: Callable[[float], None] = time.sleep,
    out: Callable[[str], None] = lambda s: (sys.stdout.write(s), sys.stdout.flush())[0],
) -> int:
    max_retries = len(delays)
    for attempt in range(max_retries + 1):
        code, output = runner(cmd, out)
        if code == 0:
            return 0
        if not is_plugin_repo_unavailable(output):
            return code
        if attempt < max_retries:
            delay = delays[attempt]
            out(
                f"::warning::Gradle 插件/依赖仓库解析失败，将在 {delay}s 后自动重试"
                f"（第 {attempt + 1}/{max_retries} 次）。\n"
            )
            sleeper(delay)
        else:
            out(
                f"::error::插件仓库不可用，不是代码问题（自动重试 {max_retries} 次后仍无法解析 Gradle 插件或依赖）。\n"
            )
            out("插件仓库不可用，不是代码问题\n")
            return code
    return 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run a Gradle command with retries on plugin repository unavailability."
    )
    parser.add_argument(
        "--delays",
        default=os.environ.get("GRADLE_RETRY_DELAYS"),
        help="Comma-separated retry backoff seconds (default: 10,25,60)",
    )
    parser.add_argument("cmd", nargs=argparse.REMAINDER, help="Command to execute")
    args = parser.parse_args(list(argv) if argv is not None else None)
    cmd = list(args.cmd)
    if cmd and cmd[0] == "--":
        cmd = cmd[1:]
    if not cmd:
        parser.error("Missing command to run")
    return run_with_retry(cmd, delays=parse_delays(args.delays))


if __name__ == "__main__":
    raise SystemExit(main())

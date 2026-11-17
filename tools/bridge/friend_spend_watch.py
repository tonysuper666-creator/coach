#!/usr/bin/env python3
"""LaunchAgent entry: publish content-free Coach spend; ask once per month at 80%."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess

from api_budget import CreditLedger, Denied, MONTHLY_CAPS, atomic_json, micros


def snapshot(ledger, now):
    month = now.strftime("%Y-%m")
    data = ledger.read()
    rows, warning = [], False
    for provider, cap in MONTHLY_CAPS.items():
        used = data["months"].get(month, {}).get(provider, {}).get("spent_microusd", 0)
        pending = sum(sum(c["reserved"].values()) for key, c in data["cycles"].items() if key.startswith(provider + ":"))
        warning |= (used + pending) * 5 >= micros(cap) * 4
        rows.append(f"{provider} 本月已用 ${used / 1_000_000:.2f}，预留 ${pending / 1_000_000:.2f}，上限 ${cap}")
    return {"status": "warn" if warning else "ok", "since": now.isoformat(), "detail": "；".join(rows)}


def publish(state, issue="TON-1686", panel_support=False, run=subprocess.run, now=None):
    now = now or datetime.now(timezone.utc)
    ledger = CreditLedger(state)
    try:
        with ledger.locked():
            value = snapshot(ledger, now)
    except (Denied, OSError):
        value = {"status": "error", "since": now.isoformat(), "detail": "花费记录暂时无法核对，请检查 Mac 服务。"}
    cli = os.environ["MULTICA_CLI_PATH"]
    def put(target, key, value):
        run([cli, "issue", "metadata", "set", target, "--key", key, "--type", "string", "--value",
             value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)], check=True, capture_output=True, text=True)
    marker = state / "friend-spend-watch.json"
    try:
        previous = json.loads(marker.read_text()) if marker.exists() else {}
        if not isinstance(previous, dict):
            previous = {}
    except (OSError, ValueError):
        previous = {}
    # Preserve the onset time while a state stays the same.
    if previous.get("status") == value["status"]:
        value["since"] = previous.get("since", value["since"])
    put("TON-740", "coach.api-spend", value)
    month = now.strftime("%Y-%m")
    if value["status"] == "warn" and not panel_support and previous.get("asked_month") != month:
        # Butler's generic system-state key support has not been confirmed. The
        # supported issue ask is therefore the default; no direct messages.
        current = run([cli, "issue", "metadata", "list", issue, "--output", "json"],
                      check=True, capture_output=True, text=True)
        metadata = json.loads(current.stdout)
        require_ask = "朋友讲解花到本月上限的八成了，要不要继续？"
        existing = metadata.get("ask")
        if existing:
            try:
                if json.loads(existing).get("q") == require_ask:
                    previous["asked_month"] = month
            except (ValueError, AttributeError, TypeError):
                pass
        else:
            put(issue, "ask", {"q": require_ask,
                "why": value["detail"], "options": ["继续，到上限自动停", "现在停掉朋友讲解"], "recommend": 0})
            put(issue, "ask_since", now.isoformat())
            previous["asked_month"] = month
    atomic_json(marker, {**previous, **value})
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--state", type=Path, default=Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))))
    parser.add_argument("--issue", default="TON-1686")
    parser.add_argument("--panel-support", action="store_true", help="Only after verifying Butler displays coach.api-spend")
    args = parser.parse_args()
    print(json.dumps(publish(args.state, args.issue, args.panel_support), ensure_ascii=False))

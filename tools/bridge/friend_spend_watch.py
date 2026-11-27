#!/usr/bin/env python3
"""Publish Coach spend and request a decision at the grant boundary."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess

from api_budget import CreditLedger, Denied, MONTHLY_GRANTS, atomic_json, grant_limit_micros

DECISION_ISSUE = "TON-1691"
PERIOD = re.compile(r"(anthropic|openai)-[0-9]+-[0-9]+")


def pull_grant_guards(host, inbox, run=subprocess.run):
    """The existing spend relay's SSH identity reads content-free event names."""
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", host):
        raise ValueError("invalid SSH alias")
    try:
        result = run(["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5", host,
                      "cd ~/Coach/state && find . -maxdepth 1 -type f -name 'grant-guard-*.json' -printf '%f\\n'"],
                     check=True, capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return False  # Still publish local and previously received alerts.
    for name in result.stdout.splitlines():
        period = name.removeprefix("grant-guard-").removesuffix(".json")
        if not PERIOD.fullmatch(period) or name != f"grant-guard-{period}.json":
            raise ValueError("invalid remote grant guard")
        atomic_json(inbox / name, {"period": period})
    return True


def publish_grant_decisions(state, inbox, issue=DECISION_ISSUE, run=subprocess.run, now=None):
    """One receiver for both writers. Its lock is independent of model admission."""
    receipt = inbox / "grant-decisions.json"
    cli = os.environ["MULTICA_CLI_PATH"]
    with CreditLedger(inbox).locked():
        delivered = set(json.loads(receipt.read_text()) if receipt.exists() else [])
        periods = set()
        for directory in (state, inbox):
            for path in directory.glob("grant-guard-*.json"):
                period = json.loads(path.read_text())["period"]
                if (not PERIOD.fullmatch(period)
                        or path.name != f"grant-guard-{period}.json"):
                    raise ValueError("invalid grant guard receipt")
                periods.add(period)
        for period in sorted(periods - delivered):
            current = run([cli, "issue", "metadata", "list", issue, "--output", "json"],
                          check=True, capture_output=True, text=True)
            existing = json.loads(current.stdout).get("ask")
            if existing:
                if json.loads(existing).get("grant_period") != period:
                    break  # Keep the new event pending until the other question clears.
            else:
                question = {"q": "这个月的免费额度用完了，是否保持停发？",
                            "why": f"{period.split('-')[0]} 的 Coach 免费额度已用满或不足以预留下一次请求。",
                            "options": ["保持赠额保护", "另行决定自费预算"], "recommend": 0,
                            "grant_period": period}
                run([cli, "issue", "metadata", "set", issue, "--key", "ask", "--type", "string",
                     "--value", json.dumps(question, ensure_ascii=False)],
                    check=True, capture_output=True, text=True)
            timestamp = (now or datetime.now(timezone.utc)).isoformat()
            run([cli, "issue", "metadata", "set", issue, "--key", "ask_since", "--type", "string",
                 "--value", timestamp], check=True, capture_output=True, text=True)
            delivered.add(period)
            atomic_json(receipt, sorted(delivered))
        return {"delivered": len(delivered), "pending": len(periods - delivered)}


def snapshot(ledger, now):
    month = now.strftime("%Y-%m")
    data = ledger.read()
    rows, warning = [], False
    for provider in MONTHLY_GRANTS:
        limit = grant_limit_micros(provider)
        used = data["months"].get(month, {}).get(provider, {}).get("spent_microusd", 0)
        pending = sum(sum(c["reserved"].values()) for key, c in data["cycles"].items() if key.startswith(provider + ":"))
        reached = used + pending >= limit or (ledger.state / f"credit-guard-{month}-{provider}.json").is_file()
        warning |= reached
        detail = f"{provider} 本月已用 ${used / 1_000_000:.2f}，预留 ${pending / 1_000_000:.2f}，免费额度 ${limit / 1_000_000:.2f}"
        rows.append(detail + ("（已触线）" if reached else ""))
    return {"status": "warn" if warning else "ok", "since": now.isoformat(), "detail": "；".join(rows)}


def publish(state, issue=DECISION_ISSUE, panel_support=False, run=subprocess.run, now=None):
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
    if value["status"] == "warn" and not panel_support and previous.get("asked_guard_month") != month:
        # Butler's generic system-state key support has not been confirmed. The
        # supported issue ask is therefore the default; no direct messages.
        current = run([cli, "issue", "metadata", "list", issue, "--output", "json"],
                      check=True, capture_output=True, text=True)
        metadata = json.loads(current.stdout)
        require_ask = "这个月的免费额度用完了，是否另行授权自费？"
        existing = metadata.get("ask")
        if existing:
            try:
                if json.loads(existing).get("q") == require_ask:
                    previous["asked_guard_month"] = month
            except (ValueError, AttributeError, TypeError):
                pass
        else:
            put(issue, "ask", {"q": require_ask,
                "why": value["detail"], "options": ["保持赠额保护", "另行决定自费预算"], "recommend": 0})
            put(issue, "ask_since", now.isoformat())
            previous["asked_guard_month"] = month
    atomic_json(marker, {**previous, **value})
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--state", type=Path, default=Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))))
    parser.add_argument("--issue", default=DECISION_ISSUE)
    parser.add_argument("--guard-inbox", type=Path, help="Publish shared grant events from the local ledger and relay inbox")
    parser.add_argument("--pull-guards-from", help="SSH alias for the hermes spend recorder")
    parser.add_argument("--panel-support", action="store_true", help="Only after verifying Butler displays coach.api-spend")
    args = parser.parse_args()
    if args.pull_guards_from and not args.guard_inbox:
        parser.error("--pull-guards-from requires --guard-inbox")
    reachable = pull_grant_guards(args.pull_guards_from, args.guard_inbox) if args.pull_guards_from else None
    result = (publish_grant_decisions(args.state, args.guard_inbox, args.issue) if args.guard_inbox
              else publish(args.state, args.issue, args.panel_support))
    print(json.dumps(result, ensure_ascii=False))
    if reachable is False:
        raise SystemExit("remote grant events pending reconnect")

#!/usr/bin/env python3
"""TON-740, run once on hermes after the release carrying it is live.

  python3 -I ~/Coach/releases/<rel12>/tools/bridge/ton740_raise_coach_share.py --state ~/Coach/state check|apply

In order, under the ledger lock (backups first):
1. Back-charge November OpenAI spend recorded under the old price table, up to the worst case.
   The provider's bill needs the owner's own login, so it is not read: everything recorded at the
   old rates is taken as if it were all gpt-6.2-astra (the most under-priced model, new/old = 10/3).
   For the $12.974038 recorded when this was reviewed that is $43.25, a $30.28 back-charge; spend
   recorded since is scaled the same way, never less than $30.28. It is debited as an estimate
   (estimated_microusd), identifiable at reconciliation.
2. Re-price every hold still open from the old price table at the new rates (each provider's
   worst new/old ratio), so no later settlement can exceed its hold and lock the cycle.
3. Raise Coach's share in each allocation plan to the whole free grant minus other writers
   (80 / 100), and re-pin the plan digest so the bridge accepts it.

Steps 1 and 2 happen once: a marker file in the state directory makes a rerun skip them.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import api_budget as ab  # noqa: E402
from api_route import CONFIG  # noqa: E402

M = 1_000_000
MARKER = "ton740-price-correction-2026-11.json"
CORRECTION = {"provider": "openai", "month": "2026-11", "minimum_microusd": 30_280_000}
# The price table before TON-740 (USD per million tokens); every open hold was reserved with it.
OLD_PRICES = {
    "claude-haiku-5-5": {"input": 0.10, "output": 0.50, "cached": 0.01},
    "claude-sonnet-5-5": {"input": 2.00, "output": 10.00, "cached": 0.20},
    "claude-opus-5-5": {"input": 10.00, "output": 50.00, "cached": 1.00},
    "gpt-6.1-terra": {"input": 1.00, "output": 5.00, "cached": 0.05},
    "gpt-6.1-sol": {"input": 2.00, "output": 10.00, "cached": 0.10},
    "gpt-6.2-sol": {"input": 2.00, "output": 10.00, "cached": 0.10},
    "gpt-6.2-astra": {"input": 3.00, "output": 12.00, "cached": 0.15},
}


def digest(plan):
    return hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()


def worst_ratio(provider):
    """The largest new/old price ratio among a provider's models; never below 1."""
    ratios = [1.0]
    for model, old in OLD_PRICES.items():
        if not model.startswith("claude-" if provider == "anthropic" else "gpt-"):
            continue
        new = CONFIG["prices_usd_per_million"][model]
        ratios += [new[k] / old[k] for k in ("input", "output", "cached")]
    return max(ratios)


def reprice_holds(data, provider, ratio):
    """Raise every open hold of `provider` to its worst-case new-rate bound. Returns the added microdollars."""
    added = 0
    for cycle_id, cycle in data["cycles"].items():
        if not cycle_id.startswith(provider + ":"):
            continue
        month = cycle_id.split(":", 1)[1]
        for token, amount in list(cycle["reserved"].items()):
            new = math.ceil(amount * ratio)
            if new == amount:
                continue
            cycle["reserved"][token] = new
            summary = data["months"].get(month, {}).get(provider)
            if summary is not None:
                summary["reserved_microusd"] += new - amount
            for days in data.get("friends", {}).values():
                for daily in days.values():
                    if token in daily["reserved"]:
                        daily["reserved"][token]["microusd"] = new
            added += new - amount
    return added


def run(state: Path, apply: bool, now=None, out=print):
    now = now or time.time()
    ledger = ab.CreditLedger(state, lambda: now)
    if apply:
        backup = state.parent / "backups" / ("coach-share-100-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
        backup.mkdir(mode=0o700, parents=True, exist_ok=True)
        for name in ("api-credit-ledger.json", "grant-allocation-anthropic.json", "grant-allocation-openai.json",
                     "grant-window-anthropic.json", "grant-window-openai.json"):
            if (state / name).exists():
                shutil.copy2(state / name, backup / name)
        out(f"备份 {backup}")
    with ledger.locked():
        data = ledger.read()
        if (state / MARKER).exists():
            out("补记与重估：已经做过，跳过")
        else:
            p, month = CORRECTION["provider"], CORRECTION["month"]
            cycle = data["cycles"].setdefault(f"{p}:{month}", {"spent": 0, "reserved": {}, "limit": ab.grant_limit_micros(p)})
            summary = data["months"].setdefault(month, {}).setdefault(p, {"spent_microusd": 0, "reserved_microusd": 0, "calls": 0})
            recorded = max(cycle["spent"], summary["spent_microusd"])
            amount = max(CORRECTION["minimum_microusd"], math.ceil(recorded * worst_ratio(p)) - recorded)
            out(f"补记：{p} {month} 旧费率已记 ${recorded / M:.4f}，按全部是 gpt-6.2-astra 的最坏情况"
                f"共 ${(recorded + amount) / M:.4f}，补记 ${amount / M:.4f}（估算扣款）")
            cycle["spent"] += amount
            summary["spent_microusd"] += amount
            summary["estimated_microusd"] = summary.get("estimated_microusd", 0) + amount
            for provider in ("anthropic", "openai"):
                ratio = worst_ratio(provider)
                added = reprice_holds(data, provider, ratio)
                out(f"在途预留重估：{provider} 按新费率最坏比 ×{ratio:.4f}，预留增加 ${added / M:.4f}")
            if apply:
                ledger.save(data)
                ab.atomic_json(state / MARKER, {"correction_microusd": amount, "at_utc": int(now)})
        for provider in ("anthropic", "openai"):
            plan = json.loads((state / f"grant-allocation-{provider}.json").read_text())
            pin = json.loads((state / f"grant-window-{provider}.json").read_text())
            if pin["digest"] != digest(plan) or len(plan["ledgers"]) != 1 or plan["ledgers"][0]["state"] != str(state):
                raise SystemExit(f"{provider}：计划和钉子对不上，或计划里不止一份 Coach，未改")
            new = copy.deepcopy(plan)
            new["ledgers"][0]["limit_microusd"] = ab.micros(ab.MONTHLY_GRANTS[provider]) - plan["other_writers_microusd"]
            out(f"{provider}：Coach 总额 ${plan['ledgers'][0]['limit_microusd'] / M:.2f} → "
                f"${new['ledgers'][0]['limit_microusd'] / M:.2f}（赠额 ${ab.MONTHLY_GRANTS[provider]} − other_writers "
                f"${plan['other_writers_microusd'] / M:.2f}）")
            if apply:
                ab.atomic_json(state / f"grant-allocation-{provider}.json", new)
                ab.atomic_json(state / f"grant-window-{provider}.json", {"digest": digest(new), "end_utc": new["end_utc"]})
        allowance = {}
        if apply:
            data = ledger.read()
            for provider in ("anthropic", "openai"):
                share, settled, _ = ledger.allocation(provider, now, data)
                pending = sum(sum(c["reserved"].values()) for cid, c in data["cycles"].items() if cid.startswith(provider + ":"))
                allowance[provider] = share - settled - pending
                out(f"{provider}：Coach 总额 ${share / M:.2f}，已结算 ${settled / M:.4f}，在途 ${pending / M:.4f}，"
                    f"还能放行 ${allowance[provider] / M:.4f}")
    return allowance


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("mode", choices=("check", "apply"))
    parser.add_argument("--state", type=Path, default=Path.home() / "Coach/state")
    parser.add_argument("--no-service-check", action="store_true", help="temporary copies only")
    args = parser.parse_args()
    if not args.no_service_check:
        exec_start = subprocess.run(["systemctl", "--user", "show", "coach.service", "-p", "ExecStart"],
                                    capture_output=True, text=True).stdout
        if str(HERE.parent.parent) not in exec_start:
            raise SystemExit(f"正式服务跑的不是这个 release（{HERE.parent.parent}），先升级再提额")
    run(args.state.resolve(), args.mode == "apply")


if __name__ == "__main__":
    main()

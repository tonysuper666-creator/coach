#!/usr/bin/env python3
"""TON-740: write and check each UTC month's whole-grant plan ahead of the first call.

  python3 -I <release>/tools/bridge/monthly_grant_plan.py --state ~/Coach/state check|apply

李政 (2026-11-27): no internal limits, use it until the whole grant is gone. Each month the plan for
each provider is `api_budget.month_plan`: one Coach share equal to that month's whole grant, nothing for
other writers, measured against the month's own spend and holds.

The bridge does not depend on this. On a month's first reservation it writes the same plan itself, under
the ledger lock, in one atomic write. `coach-grant-plan.timer` only writes it a little earlier and reports
what is left. Both use the same function under the same lock, so whichever comes first writes
byte-identical files and the other changes nothing. A plan shared by several ledgers is never rewritten.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
import time

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import api_budget as ab  # noqa: E402

M = 1_000_000


def run(state: Path, apply: bool, now=None, out=print) -> dict:
    now = now or time.time()
    state = state.resolve()
    ledger = ab.CreditLedger(state, lambda: now)
    report = {}
    with ledger.locked():
        data = ledger.read()
        for provider in ("anthropic", "openai"):
            expected = ab.month_plan(provider, now, state)
            if not apply:
                try:
                    step = ab.plan_step(json.loads((state / f"grant-allocation-{provider}.json").read_text()), expected)
                except (OSError, ValueError, KeyError, TypeError):
                    step = "missing"
                out(f"{provider}：" + {
                    "current": "本月整份赠额的计划已写好",
                    "explicit": "本月用的是另写的计划，照原样保留",
                    "replace": "本月计划还没写，apply 或桥的第一笔预留会当场写入",
                    "shared": "计划分给了几个账本，不会自动改写，到期后拒绝",
                    "missing": "没有可读的计划，拒绝调用",
                }[step])
                continue
            share, used, _, pending = ledger.allocation(provider, now, data)   # writes plan and pin if missing
            report[provider] = share - used - pending
            out(f"{provider}：Coach 总额 ${share / M:.2f}，已用 ${used / M:.4f}，"
                f"在途 ${pending / M:.4f}，还能放行 ${report[provider] / M:.4f}")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("mode", choices=("check", "apply"))
    parser.add_argument("--state", type=Path, default=Path.home() / "Coach/state")
    args = parser.parse_args()
    run(args.state, args.mode == "apply")


if __name__ == "__main__":
    main()

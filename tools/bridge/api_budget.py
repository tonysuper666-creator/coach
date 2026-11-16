"""Conservative credit reservations. Billing authority remains the provider, not this ledger.

The owner authorized existing keys with local monthly limits. This ledger records
Coach usage, not the shared account's remaining grant or provider billing period.
All amounts on disk are integer microdollars, never prompt/response contents.
"""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
from decimal import Decimal, ROUND_CEILING
import fcntl
import json
import os
from pathlib import Path
import secrets
import time

MONTHLY_CAPS = json.loads(Path(__file__).with_name("model_routes.json").read_text())["monthly_caps_usd"]


class Denied(Exception):
    """A content-free reason safe for diagnostics."""


def require(condition):
    # Safety checks must remain enabled under python -O.
    if not condition:
        raise ValueError("invalid credit ledger")


def micros(value) -> int:
    return int((Decimal(str(value)) * 1_000_000).to_integral_value(rounding=ROUND_CEILING))


def atomic_json(path: Path, value: dict):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = path.with_name(path.name + "." + secrets.token_hex(8) + ".tmp")
    try:
        with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as out:
            json.dump(value, out, ensure_ascii=False, allow_nan=False)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, path)
        fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        tmp.unlink(missing_ok=True)


class CreditLedger:
    def __init__(self, state: Path, clock=time.time):
        self.state, self.clock = state, clock

    @contextmanager
    def locked(self):
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = os.open(self.state / "api-spend.lock", os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)

    def read(self) -> dict:
        path = self.state / "api-credit-ledger.json"
        if not path.exists():
            return {"version": 1, "cycles": {}, "months": {}}
        try:
            data = json.loads(path.read_text())
            require(data["version"] == 1 and isinstance(data["cycles"], dict) and isinstance(data["months"], dict))
            for cycle in data["cycles"].values():
                require(isinstance(cycle["reserved"], dict))
                require(all(type(n) is int and n >= 0 for n in [cycle["spent"], cycle["limit"], *cycle["reserved"].values()]))
            for providers in data["months"].values():
                require(isinstance(providers, dict))
                for summary in providers.values():
                    require(all(type(summary[k]) is int and summary[k] >= 0 for k in ("spent_microusd", "reserved_microusd", "calls")))
            return data
        except (OSError, ValueError, KeyError, AttributeError, TypeError):
            raise Denied("ledger_unreadable") from None

    def save(self, data):
        # The authoritative reservation is committed first. A failed summary write
        # cannot make an already-started or ambiguous request disappear on restart.
        atomic_json(self.state / "api-credit-ledger.json", data)
        for month, summary in data["months"].items():
            atomic_json(self.state / f"api-spend-{month}.json", summary)

    def reserve(self, provider: str, key: str, maximum: int) -> tuple[str, str, str]:
        if not key:
            raise Denied("missing_key")
        if type(maximum) is not int or maximum <= 0 or provider not in MONTHLY_CAPS:
            raise Denied("invalid_reservation")
        with self.locked():
            data = self.read()
            month = datetime.fromtimestamp(self.clock(), timezone.utc).strftime("%Y-%m")
            # Caps are per Coach/provider, independent of key changes. Keep 5% of
            # the nominal grant (200/500) and the owner's stricter Coach caps.
            limit = min(micros(MONTHLY_CAPS[provider]), micros({"anthropic": 200, "openai": 500}[provider]) * 95 // 100)
            cycle_id = f"{provider}:{month}"
            cycle = data["cycles"].setdefault(cycle_id, {"spent": 0, "reserved": {}, "limit": limit})
            cycle["limit"] = min(cycle["limit"], limit)
            # Unknown usage survives month rollover and migration from the earlier
            # evidence-based ledger; starting a new month must not erase a call.
            pending = sum(sum(c["reserved"].values()) for cid, c in data["cycles"].items() if cid.startswith(provider + ":"))
            summary = data["months"].setdefault(month, {}).setdefault(provider, {"spent_microusd": 0, "reserved_microusd": 0, "calls": 0})
            if (cycle["spent"] + pending + maximum > cycle["limit"] or
                    summary["spent_microusd"] + pending + maximum > limit):
                raise Denied("credit_limit_95_percent")
            token = secrets.token_hex(16)
            cycle["reserved"][token] = maximum
            summary["reserved_microusd"] += maximum
            self.save(data)
            return cycle_id, month, token

    def settle(self, provider: str, reservation, actual: int, *, idempotent=False):
        with self.locked():
            data = self.read()
            cycle_id, month, token = reservation
            cycle = data["cycles"][cycle_id]
            if idempotent and token not in cycle["reserved"]:
                # A Batch poll can replay after a crash following the ledger commit.
                # Rewrite summaries too if their previous write was interrupted.
                self.save(data)
                return
            reserved = cycle["reserved"].pop(token)
            if actual < 0:
                raise Denied("invalid_usage")
            cycle["spent"] += actual
            summary = data["months"][month][provider]
            summary["reserved_microusd"] -= reserved
            summary["spent_microusd"] += actual
            summary["calls"] += 1
            # An unexpected price/usage change fails closed for subsequent calls.
            if actual > reserved:
                cycle["limit"] = 0
            self.save(data)

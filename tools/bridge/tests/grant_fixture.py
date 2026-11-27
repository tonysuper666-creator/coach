"""Single-writer synthetic grants for existing offline routing contracts."""
import os
import socket

from api_budget import MONTHLY_GRANTS, atomic_json, grant_limit_micros, micros


# Offline tests never queue behind holds unless a test asks for it explicitly.
os.environ.setdefault("BRIDGE_RESERVATION_WAIT_SEC", "0")


def use_up(ledger, provider, amount, key="synthetic-not-a-real-key"):
    """Record `amount` as settled spend (not a temporary hold)."""
    reservation = ledger.reserve(provider, key, amount)
    ledger.settle(provider, reservation, amount)


def fund(state, *, start=1793491200, end=1798761600, prior_anthropic=0):
    ledger = state / "api-credit-ledger.json"
    if not ledger.exists():
        atomic_json(ledger, {"version": 1, "cycles": {}, "months": {}})
    for provider, gross in MONTHLY_GRANTS.items():
        atomic_json(state / f"grant-allocation-{provider}.json", {
            "version": 1, "provider": provider, "start_utc": start, "end_utc": end,
            "grant_microusd": micros(gross), "other_writers_microusd": 0,
            "ledgers": [{"host": socket.gethostname(), "state": str(state.resolve()),
                         "limit_microusd": grant_limit_micros(provider),
                         "prior_settled_microusd": prior_anthropic if provider == "anthropic" else 0}]})

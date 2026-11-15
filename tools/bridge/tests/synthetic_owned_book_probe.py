"""Synthetic-only live probe of the existing Codex bridge process adapter.

Run with the Coach bridge venv's Python. This does not call the Kotlin provider
or authorize using this adapter for any user's private textbook.
"""
import asyncio
import importlib.util
import json
import sys
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "subscription_bridge.py"
spec = importlib.util.spec_from_file_location("synthetic_coach_bridge", source)
bridge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bridge
spec.loader.exec_module(bridge)
sys.modules["subscription_bridge"] = bridge
adapter_spec = importlib.util.spec_from_file_location("synthetic_owned_book_cli", source.parent / "owned_book_cli.py")
adapter = importlib.util.module_from_spec(adapter_spec)
adapter_spec.loader.exec_module(adapter)

original = bridge.run_cli


async def observed(argv, cwd, env, stdin_data=None, timeout=bridge.TIMEOUT):
    result = await original(argv, cwd, env, stdin_data, timeout)
    print("argv:", json.dumps(argv[:-2] + ["<output-file>", "-"]))
    print("exit:", result[0])
    return result


bridge.run_cli = observed


async def main():
    for action, question in (("explain", None), ("ask", "Why does turning off the lamp stop circulation?")):
        payload = {"action": action, "excerpts": [{"id": 0, "text":
            "In a sealed toy ecosystem, a lamp powers a pump that circulates water to a model tree."}]}
        if question:
            payload["question"] = question
        # This test policy permits only this exact fictional payload, never a user book.
        answer = await adapter.complete_book(payload, policy=lambda candidate: candidate == payload)
        print("action:", action, "outcome: complete")
        print("synthetic answer:", json.dumps(answer, ensure_ascii=False))


asyncio.run(main())

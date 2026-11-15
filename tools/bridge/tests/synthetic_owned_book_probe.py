"""Synthetic-only live probe of the existing Codex bridge process adapter.

Run with the Coach bridge venv's Python. This does not call the Kotlin provider
or authorize using this adapter for any user's private textbook.
"""
import asyncio
import importlib.util
import json
import sys
import time
from pathlib import Path

source = Path(__file__).resolve().parents[1] / "subscription_bridge.py"
spec = importlib.util.spec_from_file_location("synthetic_coach_bridge", source)
bridge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bridge
spec.loader.exec_module(bridge)

original = bridge.run_cli


async def observed(argv, cwd, env, stdin_data=None, timeout=bridge.TIMEOUT):
    result = await original(argv, cwd, env, stdin_data, timeout)
    print("argv:", json.dumps(argv[:-2] + ["<output-file>", "-"]))
    print("exit:", result[0])
    return result


bridge.run_cli = observed


async def main():
    body = {"messages": [
        {"role": "system", "content": "Explain only the supplied imaginary textbook. Give a useful explanation and answer the question; distinguish these two parts."},
        {"role": "user", "content": "Synthetic book, page 1: In a sealed toy ecosystem, a lamp powers a pump that circulates water to a model tree. Question: why does turning off the lamp stop circulation?"},
    ]}
    model = "gpt-6.1-sol"
    request = bridge.ReqCtx("synthetic-book-probe", model, bridge.ModelSpec(model, "codex", False), body,
                            bridge.build_spec(body), False, False, False, "low", time.monotonic(), 0)
    result = await bridge.call_codex(request, model, "low")
    print("outcome:", result.status)
    print("synthetic answer:", result.text)


asyncio.run(main())

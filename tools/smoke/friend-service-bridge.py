#!/usr/bin/env python3
"""Explicit Codex subscription route for the friend-service test instance only.

The server explicitly requests Codex. This adapter rejects other models;
terminal served_by records the actual model. No API transport or fallback.
"""
import json
import os
from pathlib import Path
import sys


class SubscriptionOnly:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["path"] not in {"/v1/chat/completions", "/chat/completions"}:
            return await self.app(scope, receive, send)
        headers = dict(scope["headers"])
        payload = bytearray()
        while True:
            part = await receive()
            if part["type"] == "http.disconnect":
                return
            payload.extend(part.get("body", b""))
            if len(payload) > 512_000 or headers.get(b"x-coach-traffic") != b"test":
                await send({"type": "http.response.start", "status": 403, "headers": []})
                return await send({"type": "http.response.body", "body": b"Isolated test traffic only"})
            if not part.get("more_body"):
                break
        try:
            body = json.loads(payload)
            if not isinstance(body, dict) or body.get("model") != "gpt-6.1-sol":
                raise ValueError("Only explicit Codex requests are supported")
            if headers.get(b"x-coach-task") == b"READER_SELECTION":
                body["response_format"] = {"type": "json_object"}
            payload = json.dumps(body).encode()
        except (ValueError, TypeError):
            await send({"type": "http.response.start", "status": 400, "headers": []})
            return await send({"type": "http.response.body", "body": b"Invalid request"})
        headers.update({b"content-length": str(len(payload)).encode(), b"x-coach-model-policy": b"exact",
            b"x-coach-fallback": b"off", b"x-coach-background": b"off", b"x-coach-batch-key": b""})
        scope = scope | {"headers": list(headers.items())}
        delivered = False
        async def replay():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(payload), "more_body": False}
            return await receive()
        await self.app(scope, replay, send)


def main():
    port = int(os.environ.get("BRIDGE_PORT", "0"))
    if not 1024 <= port <= 65535 or port in {18791, 8790, 8791}:
        raise SystemExit("An isolated bridge port is required")
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bridge"))
    import subscription_bridge as bridge
    # Disables the API routing branch altogether, including background batch requests.
    bridge.api_model = lambda *args: None
    # The shared CLI note asks for plain text after the reader's JSON contract.
    # Preserve the no-tools boundary while honoring the caller's output format.
    bridge.NO_TOOLS_NOTE = (
        "Follow the requested response format exactly, including JSON when requested. "
        "Do not use any built-in tools (shell, files, browser, web search); "
        "you have no access to the user's machine."
    )
    bridge.uvicorn.run(SubscriptionOnly(bridge.app), host="127.0.0.1", port=port,
                       log_level="warning", access_log=False)


if __name__ == "__main__":
    main()

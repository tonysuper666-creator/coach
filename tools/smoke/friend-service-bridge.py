#!/usr/bin/env python3
"""Explicit subscription or opt-in gift API route for the private test instance."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class SubscriptionOnly:
    json_response_format = True

    def configure(self, body, headers):
        if body.get("model") != "gpt-6.1-sol":
            raise ValueError("Only explicit Codex requests are supported")

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
            if not isinstance(body, dict):
                raise ValueError("Invalid request")
            self.configure(body, headers)
            if self.json_response_format and headers.get(b"x-coach-task") == b"READER_SELECTION":
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


class FriendApiOnly(SubscriptionOnly):
    """Only authenticated server friend requests can consume the existing gift."""
    # The reader prompt supplies its JSON contract. The shared friend API route
    # deliberately rejects response_format/tools; do not rewrite that boundary.
    json_response_format = False

    def configure(self, body, headers):
        if (body.get("model") not in {"claude-sonnet-5-5", "claude-haiku-5-5"} or
                body.get("response_format") or body.get("tools") or
                headers.get(b"x-coach-task") != b"READER_SELECTION" or
                not re.fullmatch(rb"[a-f0-9]{64}", headers.get(b"x-coach-friend", b"")) or
                not re.fullmatch(rb"[A-Za-z0-9_-]{1,128}", headers.get(b"x-coach-friend-device", b""))):
            raise ValueError("Explicit friend reader request required")


def existing_gift_key():
    # Same existing Keychain item as tools/bridge/run.sh. Never a command argument,
    # inherited key, file, diagnostic or environment variable of the server JVM.
    env = os.environ.copy()
    if sys.platform == "darwin":
        import pwd
        # A test HOME must not redirect security's per-user Keychain search list.
        # Only this reader uses the account home; bridge/data isolation stays put.
        env["HOME"] = pwd.getpwuid(os.getuid()).pw_dir
    result = subprocess.run(["security", "find-generic-password", "-s", "coach-anthropic-api", "-w"],
                            capture_output=True, timeout=20, env=env)
    if result.returncode != 0 or not result.stdout.strip():
        raise ValueError("Existing gift key unavailable")
    return result.stdout.decode().strip()


def main():
    port = int(os.environ.get("BRIDGE_PORT", "0"))
    if not 1024 <= port <= 65535 or port in {18791, 8790, 8791}:
        raise SystemExit("An isolated bridge port is required")
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "bridge"))
    friend_api = os.environ.get("COACH_FRIEND_API_ONLY") == "1"
    if friend_api:
        os.environ["COACH_ANTHROPIC_API_KEY"] = existing_gift_key()
    import subscription_bridge as bridge
    if not friend_api:
        # Legacy owner snapshot mode never consumes API credit.
        bridge.api_model = lambda *args: None
    # The shared CLI note asks for plain text after the reader's JSON contract.
    # Preserve the no-tools boundary while honoring the caller's output format.
    bridge.NO_TOOLS_NOTE = (
        "Follow the requested response format exactly, including JSON when requested. "
        "Do not use any built-in tools (shell, files, browser, web search); "
        "you have no access to the user's machine."
    )
    application = FriendApiOnly(bridge.app) if friend_api else SubscriptionOnly(bridge.app)
    bridge.uvicorn.run(application, host="127.0.0.1", port=port,
                       log_level="warning", access_log=False)


if __name__ == "__main__":
    main()

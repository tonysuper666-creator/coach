"""Run the fictional-only Windows provider -> real Mac CLI -> provider test.

Requires the existing winbox SSH configuration and the Coach bridge venv.
The short-lived reverse tunnel accepts only the two exact fictional requests;
it is test infrastructure, not a production transport or private-book policy.
"""
import asyncio
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/bridge"))
import owned_book_cli as adapter
import subscription_bridge as bridge

TEXT = "Photosynthesis is the process by which plants use light to make sugars."
QUESTION = "Why does the plant need light?"
APPROVED = [
    {"action": "explain", "excerpts": [{"id": 0, "text": TEXT}]},
    {"action": "ask", "question": QUESTION, "excerpts": [{"id": 0, "text": TEXT}]},
]
original_run = bridge.run_cli


async def observed(argv, cwd, env, stdin_data=None, timeout=bridge.TIMEOUT):
    result = await original_run(argv, cwd, env, stdin_data, timeout)
    print("CLI argv:", json.dumps(argv[:-2] + ["<output-file>", "-"]), "exit:", result[0], flush=True)
    return result


bridge.run_cli = observed


async def handle(reader, writer):
    call = disconnect = None
    try:
        raw = await reader.readline()
        request = json.loads(raw)
        if request not in APPROVED:
            raise PermissionError("not the exact synthetic fixture")
        call = asyncio.create_task(adapter.complete_book(request, policy=lambda candidate: candidate in APPROVED))
        disconnect = asyncio.create_task(reader.read(1))
        done, _ = await asyncio.wait((call, disconnect), return_when=asyncio.FIRST_COMPLETED)
        if disconnect in done:
            call.cancel()
            await asyncio.gather(call, return_exceptions=True)
            return
        result = await call
        print("ADAPTER_RECEIPT action=", request["action"], "citations=", result["citations"], flush=True)
        writer.write(json.dumps(result, ensure_ascii=False).encode() + b"\n")
        await writer.drain()
    except Exception as exc:
        print("relay failed:", type(exc).__name__, flush=True)
    finally:
        if disconnect:
            disconnect.cancel()
        writer.close()
        await writer.wait_closed()


async def main():
    fixture = ROOT / "tools/bridge/tests/OwnedBookSyntheticLiveTest.kt"
    target = ROOT / "desktopApp/src/test/kotlin/com/coach/desktop/books/OwnedBookSyntheticLiveTest.kt"
    if target.exists():
        raise RuntimeError("refusing to overwrite an existing test")
    server = await asyncio.start_server(handle, "127.0.0.1", 0, limit=1_048_577)
    port = server.sockets[0].getsockname()[1]
    tunnel = None
    try:
        target.write_bytes(fixture.read_bytes())
        tunnel = await asyncio.create_subprocess_exec(
            "ssh", "-o", "BatchMode=yes", "-o", "ExitOnForwardFailure=yes", "-N",
            "-R", f"127.0.0.1:38273:127.0.0.1:{port}", "winbox")
        await asyncio.sleep(1)
        if tunnel.returncode is not None:
            raise RuntimeError(f"synthetic tunnel failed: {tunnel.returncode}")
        proc = await asyncio.create_subprocess_exec(
            sys.executable, "tools/dev/winbox.py", "--ssh", "./gradlew", ":desktopApp:test",
            "--tests", "com.coach.desktop.books.OwnedBookSyntheticLiveTest", "--rerun-tasks", cwd=ROOT)
        code = await proc.wait()
        print("LIVE_TEST_EXIT", code, flush=True)
        if code:
            raise SystemExit(code)
    finally:
        target.unlink(missing_ok=True)
        if tunnel and tunnel.returncode is None:
            tunnel.terminate()
            await tunnel.wait()
        server.close()
        await server.wait_closed()


if __name__ == "__main__":
    asyncio.run(main())

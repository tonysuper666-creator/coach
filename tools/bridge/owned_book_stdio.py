"""One-request stdio entry point for the book adapter; default policy denies.

No command-line switch can authorize private material. A future trusted caller
must supply an actual book policy to serve(); running this file alone denies.
"""
import asyncio
import json
import signal
import sys

from owned_book_cli import complete_book

MAX_INPUT = 1_048_576


async def serve(policy=lambda _: False):
    raw = sys.stdin.buffer.read(MAX_INPUT + 1)
    if len(raw) > MAX_INPUT:
        raise ValueError("book request too large")
    request = json.loads(raw.decode("utf-8"))
    result = await complete_book(request, policy)
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


async def main():
    task = asyncio.current_task()
    loop = asyncio.get_running_loop()
    try:
        loop.add_signal_handler(signal.SIGTERM, task.cancel)
    except (NotImplementedError, RuntimeError):
        pass
    await serve()


if __name__ == "__main__":
    asyncio.run(main())

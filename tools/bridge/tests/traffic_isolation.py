#!/usr/bin/env python3
"""Flood a private user-only bridge copy with test requests while real-shaped users enter.
Only stub CLIs and an OS-assigned private port; no production bridge or database.
"""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import statistics
import tempfile
import time
from fault_injection import Bridge


def main():
    with tempfile.TemporaryDirectory(prefix="coach-isolation-") as tmp:
        root = Path(tmp)
        stub = root / "stub"
        stub.mkdir()
        for name in ("claude", "codex", "agy"):
            (stub / name).symlink_to(Path(__file__).resolve().with_name("stub_cli.py"))
        (stub / "mode").write_text("ok")
        bridge = Bridge(stub, root / "bridge", {"BRIDGE_USER_ONLY": "1"})
        try:
            body = {"model":"claude-sonnet-5-5", "messages":[{"role":"user", "content":"synthetic"}], "stream":False}
            # fault harness req always marks test traffic; user requests explicitly override it.
            def test(_):
                status, _, result = bridge.http("POST", "/v1/chat/completions", body)
                result = json.loads(result)
                assert status == 403, (status, result)
                assert result["x_coach"]["physical_calls"] == 0
                return status
            def user(_):
                status, _, result = bridge.http("POST", "/v1/chat/completions", body, {"X-Coach-Traffic":"user"})
                result = json.loads(result)
                assert status == 200, (status,result)
                return result["x_coach"]["queue_ms"]
            queues = []
            # One learner at a time (same as the Fold); test flood remains concurrent.
            with ThreadPoolExecutor(max_workers=16) as pool:
                for i in range(20):
                    tests = [pool.submit(test,j) for j in range(15)]
                    queues.append(pool.submit(user,i).result())
                    for future in tests: future.result()
            p95 = sorted(queues)[18]
            assert p95 <= 200, queues
            print(json.dumps({"test_rejected":300,"user_completed":20,"user_queue_p95_ms":p95,"user_queue_max_ms":max(queues),"port":bridge.port,"production_touched":False}))
        finally:
            bridge.stop()

if __name__ == "__main__": main()

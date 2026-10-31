#!/usr/bin/env python3
"""Check the authorized UTC pause without starting a CLI or changing the host clock."""
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import subscription_bridge as bridge

class ExamProtection(unittest.TestCase):
    def test_utc_boundaries(self):
        stamp = lambda hour: datetime(2026, 11, 2, hour, tzinfo=timezone.utc).timestamp()
        self.assertFalse(bridge.exam_protection_active(stamp(0)-.001))
        self.assertTrue(bridge.exam_protection_active(stamp(0)))
        self.assertTrue(bridge.exam_protection_active(stamp(15)-.001))
        self.assertFalse(bridge.exam_protection_active(stamp(15)))

    def test_every_backend_and_stream_mode_stops_before_dispatch(self):
        class Request:
            headers = {}
            def __init__(self, model, stream): self.model,self.stream=model,stream
            async def json(self): return {"model":self.model,"messages":[{"role":"user","content":"synthetic"}],"stream":self.stream}
        with patch.object(bridge, "exam_protection_active", return_value=True), patch.object(bridge,"log_req"), patch.object(bridge,"dispatch", side_effect=AssertionError("CLI dispatch")), patch.object(bridge,"claude_stream", side_effect=AssertionError("CLI stream")):
            for model in bridge.MODELS:
                if bridge.MODELS[model].backend == "agy": continue
                for stream in (False,True):
                    response=asyncio.run(bridge.chat(Request(model,stream)))
                    self.assertEqual(503,response.status_code)
                    terminal=json.loads(response.body)["x_coach"]
                    self.assertEqual("exam_protection",terminal["reason"])
                    self.assertEqual(0,terminal["physical_calls"])

if __name__ == "__main__": unittest.main()

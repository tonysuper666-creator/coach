"""Offline tests; all book text in this file is invented."""
import asyncio
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import owned_book_cli as adapter


class OwnedBookCliTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.request = {"action": "ask", "question": "Why?", "excerpts": [{"id": 0, "text": "A toy lamp runs a toy pump."}]}

    async def test_default_denies_before_cli(self):
        with patch.object(adapter.bridge, "call_codex", new_callable=AsyncMock) as call:
            with self.assertRaises(PermissionError):
                await adapter.complete_book(self.request)
            call.assert_not_awaited()

    async def test_accepts_only_valid_structured_response(self):
        output = SimpleNamespace(status="complete", text='{"answer":"The lamp powers it.","citations":[0]}')
        with patch.object(adapter.bridge, "call_codex", new_callable=AsyncMock, return_value=output):
            result = await adapter.complete_book(self.request, lambda _: True)
            self.assertEqual(result["citations"], [0])
        for text in ('{"answer":"x","citations":[]}', '{"answer":"x","citations":[1]}',
                     '{"answer":"x","citations":[true]}', 'not json'):
            output.text = text
            with patch.object(adapter.bridge, "call_codex", new_callable=AsyncMock, return_value=output):
                with self.assertRaises(ValueError):
                    await adapter.complete_book(self.request, lambda _: True)
        output.status = "failed"
        with patch.object(adapter.bridge, "call_codex", new_callable=AsyncMock, return_value=output):
            with self.assertRaises(RuntimeError):
                await adapter.complete_book(self.request, lambda _: True)

    async def test_cancel_propagates(self):
        with patch.object(adapter.bridge, "call_codex", new_callable=AsyncMock, side_effect=asyncio.CancelledError):
            with self.assertRaises(asyncio.CancelledError):
                await adapter.complete_book(self.request, lambda _: True)


if __name__ == "__main__":
    unittest.main()

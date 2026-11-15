import asyncio
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import owned_book_stdio


class StdioTests(unittest.TestCase):
    def run_serve(self, payload, policy=None):
        source = type("Input", (), {"buffer": io.BytesIO(payload)})()
        output = io.StringIO()
        with patch.object(sys, "stdin", source), patch.object(sys, "stdout", output):
            asyncio.run(owned_book_stdio.serve() if policy is None else owned_book_stdio.serve(policy))
        return output.getvalue()

    def test_default_denies_before_calling_cli(self):
        payload = json.dumps({"action": "explain", "excerpts": [{"id": 0, "text": "fiction"}]}).encode()
        with patch("owned_book_cli.bridge.call_codex") as call:
            with self.assertRaises(PermissionError):
                self.run_serve(payload)
            call.assert_not_called()

    def test_oversized_request_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_serve(b"x" * (owned_book_stdio.MAX_INPUT + 1))


if __name__ == "__main__":
    unittest.main()

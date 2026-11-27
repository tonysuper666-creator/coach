"""TON-1943: the real friend admission in build_req accepts every request the server sends for a friend's study features.

The rows live in fixtures/friend-study-requests.json; FriendBridgeContractTest (server) checks the server still sends them.
"""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import subscription_bridge as bridge  # noqa: E402

FRIEND = "b" * 64
ROWS = json.loads((Path(__file__).resolve().parent / "fixtures" / "friend-study-requests.json").read_text())["requests"]


def request(row, friend=FRIEND, task=None):
    headers = {"x-coach-task": task or row["task"], "x-coach-friend": friend, "x-coach-friend-device": "friend-pc",
               "x-coach-format-attempt": row["format_attempt"]}
    if row["background"]:
        headers["x-coach-background"] = "1"
    if row["batch_key"]:
        headers["x-coach-batch-key"] = "a" * 64
    body = {"model": row["model"], "stream": row["stream"], "max_tokens": 100,
            "messages": [{"role": "user", "content": "synthetic"}]}
    return body, headers


class FriendStudyContract(unittest.TestCase):
    def test_every_friend_study_request_is_admitted_with_friend_identity(self):
        self.assertEqual({"concept_sayings", "course_essay_note", "course_essay_check"}, {row["name"] for row in ROWS})
        for row in ROWS:
            with self.subTest(row["name"]):
                req = bridge.build_req(*request(row))
                self.assertEqual(FRIEND, req.friend_id)
                self.assertEqual("friend-pc", req.friend_device)
                self.assertFalse(req.fallback_enabled)

    def test_the_shapes_the_server_used_to_send_are_still_refused(self):
        essay = next(row for row in ROWS if row["name"] == "course_essay_check")
        for task in ("course_essay_note", "course_essay_check"):
            with self.subTest(task), self.assertRaises(bridge.BridgeError):
                bridge.build_req(*request(essay, task=task))
        gpt = dict(essay, model="gpt-6.2-astra")
        with self.assertRaises(bridge.BridgeError):
            bridge.build_req(*request(gpt))

    def test_friend_concept_sayings_must_stay_a_haiku_batch(self):
        sayings = next(row for row in ROWS if row["name"] == "concept_sayings")
        for change in ({"background": False}, {"model": "claude-sonnet-5-5"}, {"batch_key": False}):
            with self.subTest(change), self.assertRaises(bridge.BridgeError):
                bridge.build_req(*request(dict(sayings, **change)))


if __name__ == "__main__":
    unittest.main()

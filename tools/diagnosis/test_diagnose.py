import unittest

from diagnose import diagnose, project_attempts


NOW = 1_800_000_000_000


def make_events(specs):
    events = []
    seq = 1
    for i, spec in enumerate(specs, 1):
        aid, kp, ok = f"a{i}", spec.get("kp", "kp-a"), spec.get("ok", False)
        qid = spec.get("qid", f"q{i}")
        posed = NOW - spec.get("age_days", 1) * 86400000 - spec.get("minutes", 4) * 60000
        base = {"attemptId": aid, "questionId": qid, "kpIds": [kp], "purpose": spec.get("purpose", "PRACTICE_STRICT"),
                "policyVersion": spec.get("policy", 1), "format": spec.get("format", "SHORT_ANSWER"),
                "keyState": spec.get("key", "VERIFIED"), "fingerprint": spec.get("fp", f"fp{i}"),
                "posedAt": posed}
        events.append({"seq": seq, "type": "question_posed", "recorded_at": posed, "occurred_at": posed,
                       "thread_id": f"t{i}", "payload": base}); seq += 1
        events.append({"seq": seq, "type": "answer_submitted", "recorded_at": posed + spec.get("minutes", 4) * 60000,
                       "occurred_at": posed + spec.get("minutes", 4) * 60000, "thread_id": f"t{i}",
                       "payload": {"attemptId": aid, "offline": False}}); seq += 1
        if spec.get("grade", True):
            events.append({"seq": seq, "type": "attempt_graded", "recorded_at": NOW, "occurred_at": NOW,
                           "thread_id": f"t{i}", "payload": {"attemptId": aid, "score": int(ok), "max": 1,
                                                                      "status": "EXACT", "feedback": spec.get("feedback", "")}}); seq += 1
        if spec.get("void"):
            events.append({"seq": seq, "type": "attempt_voided", "recorded_at": NOW, "occurred_at": NOW,
                           "thread_id": f"t{i}", "payload": {"attemptId": aid}}); seq += 1
    return events


class DiagnosisTests(unittest.TestCase):
    def test_filters_invalid_and_repeated_evidence(self):
        events = make_events([
            {"ok": False},
            {"ok": False, "fp": "fp1"},  # repeated fingerprint within 14 days
            {"ok": False, "key": "UNREVIEWED"},
            {"ok": False, "policy": 2},
            {"ok": False, "void": True},
            {"ok": False, "purpose": "QUICK"},
        ])
        valid, _ = project_attempts(events, NOW)
        self.assertEqual(1, len(valid))

    def test_three_distinct_passes_match_projected_mastery(self):
        events = make_events([{"ok": True, "purpose": "UNSEEN_VARIANT", "age_days": 40 + 15 * i} for i in range(3)])
        valid, passed = project_attempts(events, NOW)
        self.assertEqual(3, len(valid))
        self.assertIn("kp-a", passed)

    def test_legacy_pass_does_not_retroactively_create_repair(self):
        events = make_events([{"ok": False}, {"ok": False}])
        events.append({"seq": max(e["seq"] for e in events) + 1, "type": "legacy_passed",
                       "recorded_at": NOW, "occurred_at": NOW, "thread_id": None,
                       "payload": {"kpId": "kp-a", "passed": True}})
        result = diagnose(events, [{"kp_id": "kp-a", "title": "W1 test", "retired": 0}], {}, NOW)
        self.assertEqual(1, result["excluded_mastered"])
        self.assertEqual([], result["all_course_top3"])

    def test_ranking_explains_evidence_and_separates_weeks(self):
        events = make_events([
            {"kp": "kp-w1", "ok": False, "feedback": "错因：概念混淆"},
            {"kp": "kp-w1", "ok": True, "age_days": 2},
            {"kp": "kp-w4", "ok": False, "feedback": "错因：计算错误", "age_days": 1, "minutes": 18},
            {"kp": "kp-w8", "ok": False, "age_days": 10},
        ])
        outline = [{"kp_id": "kp-w1", "title": "W1 demand", "retired": 0},
                   {"kp_id": "kp-w4", "title": "W4 information", "retired": 0},
                   {"kp_id": "kp-w8", "title": "W8 HTA", "retired": 0}]
        result = diagnose(events, outline, {"kp-w4": 9}, NOW)
        self.assertEqual(3, len(result["all_course_top3"]))
        self.assertEqual("kp-w4", result["all_course_top3"][0]["kp_id"])
        self.assertEqual({"concept": 1}, result["weekly_top3"]["1"][0]["causes"])
        self.assertEqual(["q3"], result["weekly_top3"]["4"][0]["question_ids"])


if __name__ == "__main__":
    unittest.main()

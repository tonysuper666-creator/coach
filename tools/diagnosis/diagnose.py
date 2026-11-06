#!/usr/bin/env python3
"""Rank weak course topics from Coach ledger events using only the stdlib.

The prototype consumes a read-only SQLite ledger and emits aggregate evidence.
It deliberately does not expose learner answers, question text, or raw grader
feedback. See README.md for scoring and projection assumptions.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

WEEK_WEIGHT = {1: 3, 2: 2, 3: 2, 4: 3, 5: 3, 6: 2, 7: 2, 8: 3, 9: 3, 10: 3, 11: 3}
CAUSES = {
    "concept": re.compile(r"错因\s*[:：]\s*(概念|概念混淆)", re.I),
    "calculation": re.compile(r"错因\s*[:：]\s*(计算|计算错误)", re.I),
    "definition": re.compile(r"错因\s*[:：]\s*(定义|术语)", re.I),
    "application": re.compile(r"错因\s*[:：]\s*(应用|迁移)", re.I),
}
KNOWN_FORMATS = {"SINGLE_CHOICE", "SHORT_ANSWER", "ESSAY", "CALCULATION"}
STRICT_PURPOSES = {"CLOSED_BOOK", "UNSEEN_VARIANT", "TIMED", "FULL_MOCK"}
TIMED_PURPOSES = {"TIMED", "FULL_MOCK"}


def _week(title: str) -> int | None:
    found = re.search(r"\bW(\d+)\b", title, re.I)
    return int(found.group(1)) if found else None


def _cause(feedback: str) -> str:
    for name, pattern in CAUSES.items():
        if pattern.search(feedback or ""):
            return name
    return "unclassified"


def _is_grade_valid(grade: dict[str, Any] | None) -> bool:
    return bool(grade and grade.get("max", 0) > 0 and
                (grade.get("status") in {"EXACT", "VERIFIED"} or grade.get("reviewed")))


def project_attempts(events: Iterable[dict[str, Any]], now_ms: int) -> tuple[list[dict[str, Any]], set[str]]:
    """Apply Projector's assessment eligibility/firstness/freshness rules.

    Returns valid, first, fresh attempts plus legacy/manual passed KP ids.
    """
    ordered = sorted(events, key=lambda e: e["seq"])
    posed: dict[str, dict[str, Any]] = {}
    submitted: dict[str, tuple[int, dict[str, Any]]] = {}
    voided: set[str] = set()
    hinted: set[str] = set()
    assisted: set[str] = set()
    passed_override: set[str] = set()
    legacy_passed: set[str] = set()
    quick_threads: set[str] = set()
    key_states: dict[str, str] = {}
    for e in ordered:
        p, typ = e["payload"], e["type"]
        if e.get("quick_thread"):
            quick_threads.add(e.get("thread_id"))
        aid = p.get("attemptId")
        if typ == "question_posed":
            posed[aid] = {**e, "payload": p, "answer_time": None, "grade": None}
            key_states[p.get("questionId")] = p.get("keyState", "UNREVIEWED")
            if p.get("purpose") == "QUICK":
                quick_threads.add(e.get("thread_id"))
        elif typ == "answer_submitted" and aid in posed and aid not in submitted:
            submitted[aid] = (e["seq"], e)
            posed[aid]["answer_time"] = min(e["occurred_at"], e["recorded_at"])
        elif typ == "attempt_graded" and aid in posed:
            posed[aid]["grade"] = p
            posed[aid]["grade_seq"] = e["seq"]
        elif typ == "grade_reviewed" and aid in posed:
            if p.get("decision") in {"CONFIRM", "REVISE"}:
                grade = dict(posed[aid].get("grade") or {})
                grade.update(p.get("revised") or {})
                grade["reviewed"] = True
                posed[aid]["grade"] = grade
                posed[aid]["grade_seq"] = e["seq"]
        elif typ == "attempt_voided":
            voided.add(aid)
        elif typ == "question_key_changed":
            key_states[p.get("questionId")] = p.get("keyState", "UNREVIEWED")
        elif typ == "hint_revealed":
            if aid in posed and posed[aid].get("answer_time") is None:
                hinted.add(aid)
        elif typ == "followup_asked" and p.get("pendingAttemptId"):
            pending_id = p["pendingAttemptId"]
            if pending_id in posed and posed[pending_id].get("answer_time") is None:
                assisted.add(pending_id)
        elif typ == "kp_overridden" and p.get("kind") == "MARK_PASSED":
            passed_override.add(p.get("kpId"))
        elif typ == "override_cleared":
            passed_override.discard(p.get("kpId"))
        elif typ == "legacy_passed" and p.get("passed", True):
            legacy_passed.add(p.get("kpId") or p.get("kp_id"))

    # Projector computes firstness and freshness in submitted sequence order.
    seen_question: set[tuple[str, str]] = set()
    fingerprints: dict[str, list[int]] = defaultdict(list)
    accepted = []
    for aid, (submit_seq, submit_event) in sorted(submitted.items(), key=lambda x: x[1][0]):
        a = posed[aid]
        p = a["payload"]
        if aid in voided or a.get("thread_id") in quick_threads or p.get("purpose") == "QUICK":
            continue
        kps = p.get("kpIds", [])
        first_kps = [kp for kp in kps if (kp, p.get("questionId")) not in seen_question]
        for kp in kps:
            seen_question.add((kp, p.get("questionId")))
        at = a["answer_time"]
        fp = p.get("fingerprint", "")
        fresh = all(abs(at - other) >= 14 * 86400000 for other in fingerprints[fp])
        fingerprints[fp].append(at)
        grade = a.get("grade")
        eligible = (aid not in voided and aid not in hinted and bool(first_kps) and fresh and
                    p.get("policyVersion", 1) == 1 and p.get("format") in KNOWN_FORMATS and
                    p.get("origin") != "LEGACY" and key_states.get(p.get("questionId"), p.get("keyState")) == "VERIFIED" and
                    _is_grade_valid(grade) and p.get("purpose") != "QUICK" and
                    a.get("thread_id") not in quick_threads)
        if not eligible:
            continue
        score, maximum = grade["score"], grade["max"]
        ratio = score / maximum
        a.update({"seq": submit_seq, "kps": first_kps, "ratio": ratio,
                  "grade_seq": a.get("grade_seq", submit_seq),
                  "passed": ratio >= .8, "cause": _cause(grade.get("feedback", "")),
                  "duration_min": max(0, (at - p.get("posedAt", a["recorded_at"])) / 60000),
                  "strict": aid not in assisted and p.get("purpose") in STRICT_PURPOSES and
                            (p.get("format") != "SINGLE_CHOICE" or p.get("purpose") in TIMED_PURPOSES) and
                            not (submit_event["payload"].get("offline") and p.get("purpose") in TIMED_PURPOSES)})
        accepted.append(a)

    passed = legacy_passed | passed_override
    by_kp: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for attempt in accepted:
        for kp in attempt["kps"]:
            by_kp[kp].append(attempt)
    for kp, attempts in by_kp.items():
        attempts.sort(key=lambda a: a["seq"])
        tail = attempts[-3:]
        if len(tail) == 3 and all(a["passed"] for a in tail) and len({a["payload"].get("fingerprint") for a in tail}) == 3:
            passed.add(kp)
        solids = [a for a in attempts if a["passed"] and a["strict"]]
        constructed = [a for a in solids if a["payload"].get("format") != "SINGLE_CHOICE"]
        latest_solid_context = [a for a in attempts if a["strict"]]
        if (len({a["payload"].get("fingerprint") for a in solids}) >= 2 and constructed and
                len({a["answer_time"] // 86400000 for a in solids}) >= 2 and
                sum(a["ratio"] for a in solids) / len(solids) >= .85 and
                latest_solid_context and latest_solid_context[-1]["passed"]):
            passed.add(kp)
    return accepted, passed


def diagnose(events: list[dict[str, Any]], outline: list[dict[str, Any]], weights: dict[str, int],
             now_ms: int, exam_week_weights: dict[int, int] | None = None) -> dict[str, Any]:
    attempts, mastered = project_attempts(events, now_ms)
    kp_info = {x["kp_id"]: x for x in outline if not x.get("retired")}
    grouped: dict[str, dict[str, Any]] = {}
    for kp, info in kp_info.items():
        evidence = [a for a in attempts if kp in a["kps"]]
        if not evidence:
            continue
        errors = [a for a in evidence if not a["passed"]]
        causes: dict[str, int] = defaultdict(int)
        for a in errors:
            causes[a["cause"]] += 1
        last = max(a["answer_time"] for a in evidence)
        age_days = max(0, (now_ms - last) / 86400000)
        avg_minutes = sum(a["duration_min"] for a in evidence) / len(evidence)
        week = _week(info.get("title", ""))
        weight = weights.get(kp, (exam_week_weights or WEEK_WEIGHT).get(week, 0))
        # Transparent score: observed error share (50%), recency (20%), effort (10%),
        # exam relevance (20%). All factors are normalized to [0,1].
        factors = {"error_rate": len(errors) / len(evidence), "recency": max(0.0, 1 - age_days / 30),
                   "effort": min(avg_minutes / 20, 1), "exam_weight": min(weight / 10, 1)}
        score = .5 * factors["error_rate"] + .2 * factors["recency"] + .1 * factors["effort"] + .2 * factors["exam_weight"]
        grouped[kp] = {"kp_id": kp, "title": info.get("title", kp), "week": week,
                       "attempt_count": len(evidence), "error_count": len(errors),
                       "error_rate": factors["error_rate"], "causes": dict(sorted(causes.items())),
                       "last_answer_age_days": round(age_days, 2), "mean_duration_min": round(avg_minutes, 2),
                       "exam_weight": weight, "mastered": kp in mastered, "score": round(score, 4),
                       "question_ids": sorted({a["payload"].get("questionId", "") for a in evidence})}
    # Replay mastery at each failure point so an older pass does not create a
    # repair flag unless the projector had already marked the KP passed then.
    for kp, row in grouped.items():
        chronology = [a for a in attempts if kp in a["kps"]]
        signals = [(e["seq"], e["payload"].get("kind") == "MARK_PASSED")
                   for e in events if e["payload"].get("kpId") == kp and e["type"] in {"kp_overridden", "override_cleared"}]
        legacy_signals = [e["seq"] for e in events if e["type"] == "legacy_passed" and
                          (e["payload"].get("kpId") or e["payload"].get("kp_id")) == kp]

        def passed_before(seq: int) -> bool:
            history = [a for a in chronology if a["grade_seq"] < seq]
            if any(s < seq for s in legacy_signals):
                return True
            override = False
            for signal_seq, value in signals:
                if signal_seq < seq:
                    override = value
            if override:
                return True
            tail = history[-3:]
            if len(tail) == 3 and all(a["passed"] for a in tail) and len({a["payload"].get("fingerprint") for a in tail}) == 3:
                return True
            solids = [a for a in history if a["passed"] and a["strict"]]
            constructed = [a for a in solids if a["payload"].get("format") != "SINGLE_CHOICE"]
            latest_solid_context = [a for a in history if a["strict"]]
            return bool(len({a["payload"].get("fingerprint") for a in solids}) >= 2 and constructed and
                        len({a["answer_time"] // 86400000 for a in solids}) >= 2 and
                        sum(a["ratio"] for a in solids) / len(solids) >= .85 and latest_solid_context and
                        latest_solid_context[-1]["passed"])

        row["needs_repair"] = any(not a["passed"] and passed_before(a["grade_seq"]) for a in chronology)
    rankable = [r for r in grouped.values() if not r["mastered"] or r["needs_repair"]]
    def rank(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return sorted(rows, key=lambda r: (-r["score"], -r["error_rate"], -r["exam_weight"], r["kp_id"]))[:3]
    weekly = {}
    for week in sorted({r["week"] for r in rankable if r["week"] is not None}):
        weekly[str(week)] = rank([r for r in rankable if r["week"] == week])
    return {"attempts_used": len(attempts), "topics_with_evidence": len(grouped),
            "excluded_mastered": sum(1 for r in grouped.values() if r["mastered"] and not r["needs_repair"]),
            "all_course_top3": rank(rankable), "weekly_top3": weekly,
            "note": "Rankings are descriptive; low evidence volume lowers confidence. Cause is unclassified unless grader feedback carries a recognized tag."}


def read_ledger(path: str, course_id: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, int]]:
    db = sqlite3.connect(f"file:{Path(path).resolve()}?mode=ro&immutable=1", uri=True)
    db.row_factory = sqlite3.Row
    quick_threads = {r[0] for r in db.execute("SELECT thread_id FROM threads WHERE course_id=? AND kind='QUICK'", (course_id,))}
    events = []
    for r in db.execute("SELECT seq,type,recorded_at,occurred_at,thread_id,payload FROM events WHERE course_id=? ORDER BY seq", (course_id,)):
        events.append({"seq": r["seq"], "type": r["type"], "recorded_at": r["recorded_at"],
                       "occurred_at": r["occurred_at"], "thread_id": r["thread_id"],
                       "quick_thread": r["thread_id"] in quick_threads, "payload": json.loads(r["payload"])})
    outline = [dict(r) for r in db.execute("SELECT kp_id,module_id,ord,title,retired FROM outline_kp WHERE course_id=? ORDER BY ord", (course_id,))]
    weights: dict[str, int] = {}
    for e in events:
        if e["type"] == "exam_weights_set":
            weights.update(e["payload"].get("weights", {}))
    db.close()
    return events, outline, weights


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database")
    parser.add_argument("course_id")
    parser.add_argument("--now", help="ISO timestamp for reproducible analysis")
    args = parser.parse_args()
    now = datetime.fromisoformat(args.now).replace(tzinfo=timezone.utc) if args.now else datetime.now(timezone.utc)
    events, outline, weights = read_ledger(args.database, args.course_id)
    result = diagnose(events, outline, weights, int(now.timestamp() * 1000))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

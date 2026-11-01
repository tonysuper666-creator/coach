#!/usr/bin/env python3
"""Independent verification of a real rehearsal SQLite database. Opens copies only, read-only."""
import argparse
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
from pathlib import Path
import sqlite3
from zoneinfo import ZoneInfo

from importlib.util import module_from_spec, spec_from_file_location


def load(path):
    return json.loads(path.read_text())


def check(value, reason):
    if not value:
        raise ValueError(reason)


def connect(path):
    path = path.resolve(strict=True)
    check(not path.is_relative_to((Path.home() / "DeepTutor").resolve()), "Refusing live DeepTutor")
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA query_only=ON")
    check(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "Integrity failure")
    return db


def rows(db, table):
    return sorted((dict(r) for r in db.execute('SELECT * FROM "' + table.replace('"', '""') + '"')), key=lambda r: repr(sorted(r.items())))


def day(seconds):
    at = datetime.fromtimestamp(seconds, ZoneInfo("Europe/London"))
    # Subtract on the local calendar, matching the domain's DST-safe 04:00 boundary.
    date = at.date() - timedelta(days=int(at.hour < 4))
    return (date - datetime(1970, 1, 1).date()).days


def verify(snapshot, out, seed_path):
    mapping, plan, seed = (load(p) for p in (out / "mapping.json", out / "import-plan.json", seed_path))
    before, after, rollback = (connect(out / p) for p in ("before.sqlite", "coach.sqlite", "rollback.sqlite"))
    try:
        course = plan["course_id"]
        baseline_projection = load(out / "before-state.json")["projection"]
        baseline = {r["id"]: dict(r) for r in before.execute("SELECT * FROM events")}
        actual = {r["id"]: dict(r) for r in after.execute("SELECT * FROM events")}
        check(all(actual.get(k) == v for k, v in baseline.items()), "Pre-existing learner events changed")
        content_tables = ("outline_kp", "source_docs", "source_pages", "blobs", "course_content", "course_modules")
        tables = {r[0] for r in before.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        # Explicit event seq inserts advance SQLite's AUTOINCREMENT bookkeeping.
        for table in tables - {"events", "questions", "threads", "messages", "projection_cache", "migration_records", "migration_batches", "sqlite_sequence"}:
            check(rows(before, table) == rows(after, table), "Unrelated baseline table changed: " + table)
        for table in ("questions", "threads", "messages"):
            baseline_rows = {repr(sorted(r.items())) for r in rows(before, table)}
            check(baseline_rows <= {repr(sorted(r.items())) for r in rows(after, table)}, "Existing rows changed: " + table)
        for table in content_tables:
            if table in tables:
                check(rows(before, table) == rows(after, table), "Course content changed: " + table)
        for table in tables:
            check(rows(before, table) == rows(rollback, table), "Rollback differs: " + table)
        imported = {r["source_id"]: dict(r) for r in after.execute("SELECT * FROM migration_records")}
        records = {r["id"]: r for r in plan["records"]}
        check(set(imported) == set(records), "Missing/extra imported identities")
        originals = {}
        def collect(value):
            if isinstance(value, dict):
                if "source_record" in value and str(value.get("id", "")).startswith("legacy:"):
                    originals[value["id"]] = value["source_record"]
                for child in value.values():
                    collect(child)
            elif isinstance(value, list):
                for child in value:
                    collect(child)
        collect(mapping)
        for key, row in imported.items():
            record = records[key]
            check(row["source_hash"] == record["source_hash"] and row["payload_hash"] == record["payload_hash"], "Source revision changed")
            check(json.loads(row["payload_json"]) == record["payload"], "Persisted payload differs")
            check(json.loads(row["source_json"]) == originals[key], "Original source value lost")
        delta = [v for k, v in actual.items() if k not in baseline]
        events = [json.loads(v["event_json"]) for v in delta]
        check({e["payload"]["sourceId"] for e in events} == set(records), "Source lacks real ledger events")
        check(all(e["deviceId"] == "deeptutor-migration" and e["actor"] == "SYSTEM" for e in events), "Migration event isolation failed")
        by_source = {}
        for e in events:
            by_source.setdefault(e["payload"]["sourceId"], []).append(e)
        for key, r in records.items():
            kinds = Counter(e["type"] for e in by_source[key])
            if r["kind"] in ("legacy_event_archive", "legacy_journal_metadata"):
                check(kinds == {"legacy_imported": 1}, "Old event executed as a current fact")
            if r["kind"] == "legacy_attempt":
                check(kinds == {"legacy_attempt": 1, "answer_submitted": 1, **({"attempt_voided": 1} if r["payload"]["reset"] else {})}, "Legacy answer/reset events differ")
                e = next(e for e in by_source[key] if e["type"] == "legacy_attempt")
                p = e["payload"]
                check(p["origin"] == "LEGACY" and not p["window_eligible"] and p["kpIds"] == [] and p["keyState"] == "UNREVIEWED", "Legacy answer could contribute evidence")
                check(p["questionId"].startswith("legacy-attempt-question:") and p["fingerprint"].startswith("legacy-attempt-fingerprint:"), "Legacy could consume native first-answer eligibility")
            if r["kind"] == "legacy_schedule":
                p = r["payload"]
                if p["queue_eligible"] and p["kp_id"] not in baseline_projection["review"]:
                    e = next(e for e in by_source[key] if e["type"] == "legacy_schedule")
                    check(e["payload"]["dueDay"] == day(p["dueAt"]), "Review time/day differs")
                    check(e["payload"]["original_state"] == p["original_state"], "Review raw state lost")
                else:
                    check(kinds == {"legacy_imported": 1}, "Ineligible history replaced a Coach review")
        expected_q = {k: r for k, r in records.items() if r["kind"] == "legacy_question"}
        options_5 = 0
        for key, r in expected_q.items():
            q = dict(after.execute("SELECT * FROM questions WHERE question_id=?", (key,)).fetchone())
            check(q["origin"] == "LEGACY" and q["key_state"] == "UNREVIEWED", "Question activated")
            for table in ("question_kp", "answer_keys", "pool_details", "constructed_details"):
                check(after.execute("SELECT count(*) FROM " + table + " WHERE question_id=?", (key,)).fetchone()[0] == 0, "Question admitted to " + table)
            if q["options"]:
                check(json.loads(q["options"]) == r["payload"]["question"]["options"], "Option dropped or changed")
                options_5 += len(json.loads(q["options"])) == 5
        for key, r in records.items():
            if r["kind"] != "legacy_message":
                continue
            m = dict(after.execute("SELECT * FROM messages WHERE message_id=?", (key,)).fetchone())
            check(m["content_md"] == r["payload"]["content"], "Message body changed")
            check(set(json.loads(imported[key]["source_json"])) <= {"id", "session_id", "role", "content", "created_at", "parent_message_id"}, "Message metadata retained")
            check(after.execute("SELECT kind FROM threads WHERE thread_id=?", (m["thread_id"],)).fetchone()[0] == "LEGACY", "History bound to a live teaching thread")
        for thread in after.execute("SELECT thread_id FROM threads WHERE kind='LEGACY'"):
            times = [r[0] for r in after.execute("SELECT created_at FROM messages WHERE thread_id=? ORDER BY mseq", (thread[0],))]
            check(times == sorted(times), "Historical conversation order changed")
        report = load(out / "rehearsal.json")
        check(report["first"]["events_added"] == len(delta), "Reported event count differs from SQLite")
        check(report["second"]["events_added"] == report["second"]["records_inserted"] == 0, "Second import changed data")
        first_bytes, second_bytes = ((out / f).read_bytes() for f in ("first-state.json", "second-state.json"))
        check(first_bytes == second_bytes, "Normalized database changed on second import")
        check(hashlib.sha256(first_bytes).hexdigest() == report["state_sha256"], "State hash mismatch")
        projection = json.loads(first_bytes)["projection"]
        passed_ids = {r["payload"]["kp_id"] for r in records.values() if r["kind"] == "legacy_passed"}
        check(all(projection["mastery"][kp]["passed"] for kp in passed_ids), "Historical passed fact missing from replay")
        for r in records.values():
            p = r["payload"]
            if r["kind"] == "legacy_schedule" and p["queue_eligible"] and p["kp_id"] not in baseline_projection["review"]:
                review = projection["review"][p["kp_id"]]
                check(review["dueDay"] == day(p["dueAt"]) and review["lapses"] == p["original_state"].get("lapse_count", 0), "Imported review replay differs")
        note_delta = sum(j["notes"] for j in projection["journal"].values()) - sum(j["notes"] for j in baseline_projection["journal"].values())
        check(note_delta == sum(r["kind"] == "notes" for r in records.values()), "Historical notes missing from replay")
        check(all(not any(a.startswith("legacy-attempt:") for a in [*m["window"], *m["support"], *m["solidLatch"]]) for m in projection["mastery"].values()), "Legacy entered mastery/solid")
        check(projection["position"]["pending"] == baseline_projection["position"]["pending"], "Migration displaced a live pending answer")
        for kp, review in baseline_projection["review"].items():
            check(projection["review"][kp] == review, "Migration replaced an existing Coach review")
        for kp, m in baseline_projection["mastery"].items():
            check(all(projection["mastery"][kp][k] == m[k] for k in ("window", "support", "solid", "solidLatch")), "Existing evidence changed")
        weights = after.execute("SELECT payload FROM events WHERE course_id=? AND type='exam_weights_set' ORDER BY seq DESC LIMIT 1", (course,)).fetchone()[0]
        check(json.loads(weights)["weights"] == seed["exam_weights"], "Weights changed")
        batch = json.loads(after.execute("SELECT manifest_json FROM migration_batches WHERE course_id=?", (course,)).fetchone()[0])
        check(batch["unknown_types"] == plan["unknown_types"] and batch["proposals"] == plan["proposals"], "Unknown types or pending proposal lost")
        # Reuse the independent source SQL checks, without the obsolete simulation requirement.
        spec = spec_from_file_location("plan_verifier", Path(__file__).with_name("verify-plan.py"))
        module = module_from_spec(spec)
        spec.loader.exec_module(module)
        source_report = module.verify(snapshot, out, out, seed_path, rehearsal=True)
        return {"source_counts": source_report["source_counts"], "record_kinds": dict(Counter(r["kind"] for r in records.values())),
                "events_before": len(baseline), "events_after": len(actual), "events_added": len(delta), "second_events_added": 0,
                "second_records_inserted": report["second"]["records_inserted"], "passed_facts_replayed": len(passed_ids), "notes_replayed": note_delta,
                "state_sha256": report["state_sha256"], "projection_sha256": report["projection_sha256"],
                "unknown_types_preserved": len(batch["unknown_types"]), "five_option_questions_quarantined": options_5,
                "unresolved_answers_preserved": source_report["unresolved_choice_answers_preserved"],
                "course_content_unchanged": True, "legacy_evidence_excluded": True, "existing_pending_preserved": True,
                "rollback_matches_baseline": True, "integrity_check": "ok"}
    finally:
        before.close(); after.close(); rollback.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("snapshot", "out", "seed"):
        parser.add_argument("--" + flag, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.snapshot.resolve(strict=True), args.out, args.seed), indent=2, sort_keys=True))

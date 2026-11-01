#!/usr/bin/env python3
"""Independent SQL/entity/journal reconciliation of two isolated incremental rounds.

Every SQLite connection is read-only. Full states remain private; stdout contains counts/hashes.
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("history_verifier", Path(__file__).with_name("verify-import.py"))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
check, load, connect, rows = v.check, v.load, v.connect, v.rows


def by_id(plan):
    return {r["id"]: r for r in plan["records"]}


def effective_facts(db, course):
    """Independently reduce source revisions without using Kotlin's projector."""
    facts = {}
    for row in db.execute("SELECT event_json FROM events WHERE course_id=? AND device_id='deeptutor-migration' ORDER BY seq", (course,)):
        event = json.loads(row[0])
        source = event["payload"]["sourceId"]
        if event["type"] == "legacy_source_revised":
            check(source in facts, "Revision without an original source")
            facts[source] = event["payload"]["facts"]
        else:
            facts.setdefault(source, []).append(event)
    return facts


def verify_round(out, previous, initial, seed_path):
    plan, oldplan = load(out / "import-plan.json"), load(previous / "import-plan.json")
    current, old = by_id(plan), by_id(oldplan)
    check(set(old) <= set(current), "Snapshot silently removed source identities")
    changed = {key for key in old if old[key]["source_hash"] != current[key]["source_hash"]}
    new = set(current) - set(old)
    before, after, rollback, native = (connect(p) for p in (out / "before.sqlite", out / "coach.sqlite", out / "rollback.sqlite", initial / "before.sqlite"))
    try:
        course = plan["course_id"]
        old_events = {r["id"]: r for r in rows(before, "events")}
        all_events = {r["id"]: r for r in rows(after, "events")}
        check(all(all_events.get(k) == value for k, value in old_events.items()), "Immutable ledger changed")
        delta = [json.loads(row["event_json"]) for key, row in all_events.items() if key not in old_events]
        revisions = [e for e in delta if e["type"] == "legacy_source_revised"]
        check(Counter(e["payload"]["sourceId"] for e in revisions) == Counter({key: 1 for key in changed}), "Expected exactly one revision per changed source")
        check({e["payload"]["sourceId"] for e in delta if e["type"] != "legacy_source_revised"} == new, "Unchanged source emitted events or new source missing")
        first_state = load(out / "first-state.json")
        check((out / "first-state.json").read_bytes() == (out / "second-state.json").read_bytes(), "Repeat changed full normalized state")
        check((out / "rollback.sqlite").read_bytes() == (out / "before.sqlite").read_bytes(), "Rollback bytes differ")
        v.verify_normalized_tables(after, first_state)
        for e in revisions:
            p, key = e["payload"], e["payload"]["sourceId"]
            check(p["old_content_hash"] == old[key]["source_hash"] and p["new_content_hash"] == current[key]["source_hash"], "Revision content hash chain differs")
            check(p["old_payload_hash"] == old[key]["payload_hash"] and p["new_payload_hash"] == current[key]["payload_hash"], "Revision payload hash chain differs")
            check(p["snapshot_at"] == plan["history_time"]["snapshot_at"] <= e["recordedAt"], "Revision clock/snapshot differs")
            before_record = before.execute("SELECT source_json FROM migration_records WHERE source_id=?", (key,)).fetchone()
            after_record = after.execute("SELECT source_json FROM migration_records WHERE source_id=?", (key,)).fetchone()
            check(p["old_source"] == json.loads(before_record[0]) and p["new_source"] == json.loads(after_record[0]), "Raw revision values lost")
        imported = {r["source_id"]: r for r in rows(after, "migration_records")}
        check(set(imported) == set(current), "Latest source identities differ")
        for key, r in current.items():
            check(imported[key]["source_hash"] == r["source_hash"] and imported[key]["payload_hash"] == r["payload_hash"] and json.loads(imported[key]["payload_json"]) == r["payload"], "Latest record differs from plan")
        tables = {r[0] for r in native.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for table in tables - {"events", "questions", "threads", "messages", "projection_cache", "migration_records", "migration_batches", "sqlite_sequence"}:
            check(rows(native, table) == rows(after, table), "Native/course table changed: " + table)
        for table in ("questions", "threads", "messages", "events"):
            key = {"questions": "question_id", "threads": "thread_id", "messages": "message_id", "events": "id"}[table]
            actual = {r[key]: r for r in rows(after, table)}
            check(all(actual.get(r[key]) == r for r in rows(native, table)), "Native row changed: " + table)
        facts = effective_facts(after, course)
        baseline = load(initial / "before-state.json")["projection"]
        projection = first_state["projection"]
        expected = {key: {"answers": j["answers"], "notes": j["notes"], "legacyLogs": j["legacyLogs"]} for key, j in baseline["journal"].items()}
        passed = set()
        options_5 = 0
        for key, record in current.items():
            p, kind = record["payload"], record["kind"]
            check(key in facts, "Latest source has no effective history")
            for fact in facts[key]:
                d = str(v.day(fact["occurredAt"] / 1000))
                journal = expected.setdefault(d, {"answers": 0, "notes": 0, "legacyLogs": []})
                if fact["type"] == "answer_submitted":
                    journal["answers"] += 1
                    check(fact["payload"]["answer"] == p["answer"], "Corrected answer not replayed")
                if fact["type"] == "note_saved":
                    journal["notes"] += 1
                    check(fact["payload"] == {**p, "sourceId": key}, "Corrected note body not replayed")
                if fact["type"] == "legacy_journal_imported":
                    check(json.loads(fact["payload"]["text"]) == p["entry"], "Corrected old log not replayed")
                    journal["legacyLogs"] = journal["legacyLogs"] + [{"sourceId": key, "text": fact["payload"]["text"]}]
            if kind == "legacy_question":
                q = dict(after.execute("SELECT * FROM questions WHERE question_id=?", (key,)).fetchone())
                check(q["origin"] == "LEGACY" and q["key_state"] == "UNREVIEWED", "Question activated")
                question = p["question"]
                check(q["stem_md"] == (question.get("question") or question.get("stem") or question.get("prompt") or ""), "Visible question stem stale")
                options = question.get("options")
                check((json.loads(q["options"]) if q["options"] else None) == options, "Visible options stale")
                options_5 += bool(options) and len(options) == 5
                for table in ("question_kp", "answer_keys", "pool_details", "constructed_details"):
                    check(after.execute("SELECT count(*) FROM " + table + " WHERE question_id=?", (key,)).fetchone()[0] == 0, "Quarantine lost: " + table)
            if kind == "legacy_message":
                m = dict(after.execute("SELECT * FROM messages WHERE message_id=?", (key,)).fetchone())
                check(m["content_md"] == p["content"] and m["role"] == p["role"], "Visible message stale")
                check(m["created_at"] == int(p["created_at"] * 1000), "Message time differs")
                check(set(json.loads(imported[key]["source_json"])) <= {"id", "session_id", "role", "content", "created_at", "parent_message_id"}, "Hidden message metadata leaked")
            if kind == "legacy_passed":
                passed.add(p["kp_id"])
                check(projection["mastery"][p["kp_id"]]["passed"], "Legacy passed lost")
            if kind == "legacy_schedule" and p["queue_eligible"] and p["kp_id"] not in baseline["review"]:
                check(projection["review"][p["kp_id"]]["dueDay"] == v.day(p["dueAt"]), "Visible corrected review stale")
        for d in set(expected) | set(projection["journal"]):
            actual = projection["journal"].get(d, {})
            wanted = expected.get(d, {"answers": 0, "notes": 0, "legacyLogs": []})
            check(all(actual.get(k, 0) == wanted[k] for k in ("answers", "notes")), "Visible journal counted a correction twice: " + d)
            check(actual.get("legacyLogs", []) == wanted["legacyLogs"], "Visible legacy log differs: " + d)
        for kp, m in baseline["mastery"].items():
            check(all(projection["mastery"][kp][k] == m[k] for k in ("window", "support", "solid", "solidLatch")), "Native mastery evidence changed")
        check(projection["position"]["pending"] == baseline["position"]["pending"], "Native pending displaced")
        check(all(projection["review"][kp] == r for kp, r in baseline["review"].items()), "Native review changed")
        for event in delta:
            d = str(v.day(event["recordedAt"] / 1000))
            old_j, new_j = baseline["journal"].get(d, {}), projection["journal"].get(d, {})
            check(all(old_j.get(k, 0) == new_j.get(k, 0) for k in ("answers", "notes")) and old_j.get("newlyPassed", []) == new_j.get("newlyPassed", []), "Import day gained historical learning")
        batch = after.execute("SELECT * FROM migration_batches WHERE course_id=? ORDER BY snapshot_at DESC LIMIT 1", (course,)).fetchone()
        check(batch["snapshot_at"] == plan["history_time"]["snapshot_at"] and batch["recorded_at"] >= batch["snapshot_at"], "Batch reused old import clock")
        report = load(out / "rehearsal.json")
        check(report["first"] == {"records_inserted": len(new), "records_revised": len(changed), "records_unchanged": len(current)-len(new)-len(changed), "events_added": len(delta)}, "SQL counts differ from report")
        check(all(report["second"][k] == 0 for k in ("records_inserted", "records_revised", "events_added")), "Repeat added records/events")
        check(hashlib.sha256((out / "first-state.json").read_bytes()).hexdigest() == report["state_sha256"], "State hash differs")
        for table in tables:
            check(rows(before, table) == rows(rollback, table), "Rollback logical rows differ")
        return {"records_inserted": len(new), "records_revised": len(changed), "events_added": len(delta), "repeat": "0/0/0", "state_sha256": report["state_sha256"], "projection_sha256": report["projection_sha256"], "visible_entities_and_journal_match": True, "native_state_preserved": True, "rollback_bytes_equal": True, "five_option_questions": options_5, "passed": len(passed)}
    finally:
        for db in (before, after, rollback, native):
            db.close()


def verify(snapshot, out, seed, next_snapshot, second_snapshot):
    initial = out / "initial"
    result = {"initial": v.verify(snapshot, initial, seed)}
    previous = initial
    result["rounds"] = []
    for n, source in enumerate((next_snapshot, second_snapshot), 1):
        current = out / ("round-" + str(n))
        source_spec = importlib.util.spec_from_file_location("source_verifier", Path(__file__).with_name("verify-plan.py"))
        source_verifier = importlib.util.module_from_spec(source_spec)
        source_spec.loader.exec_module(source_verifier)
        source_report = source_verifier.verify(source.resolve(strict=True), current, current, seed, rehearsal=True)
        report = verify_round(current, previous, initial, seed)
        report["source_counts"] = source_report["source_counts"]
        report["unresolved_answers"] = source_report["unresolved_choice_answers_preserved"]
        report["synthetic_snapshot"] = (source / "SYNTHETIC-INCREMENTAL-FIXTURE.json").exists()
        result["rounds"].append(report)
        previous = current
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("snapshot", "out", "seed", "next-snapshot", "second-snapshot"):
        parser.add_argument("--" + flag, type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.snapshot.resolve(strict=True), args.out.resolve(strict=True), args.seed, args.next_snapshot, args.second_snapshot), indent=2, sort_keys=True))

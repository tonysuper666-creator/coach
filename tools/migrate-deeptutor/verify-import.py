#!/usr/bin/env python3
"""Independent verification of a real rehearsal SQLite database. Opens copies only, read-only."""
import argparse
import base64
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import json
import math
from pathlib import Path
import sqlite3
from zoneinfo import ZoneInfo

from importlib.util import module_from_spec, spec_from_file_location


def load(path):
    return json.loads(path.read_text())


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, indent=4) + "\n"


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def projection_bytes(state_bytes):
    # Preserve JVM numeric lexemes (for example 1.3E-4) rather than reformatting
    # floats with Python. Remove only the enclosing state's four-space indent.
    text = state_bytes.decode()
    start = text.index('    "projection": ') + len('    "projection": ')
    _, length = json.JSONDecoder().raw_decode(text[start:])
    lines = text[start:start + length].splitlines()
    return ("\n".join(line[4:] if i else line for i, line in enumerate(lines)) + "\n").encode()


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


def source_millis(value):
    try:
        seconds = float(value)
        if math.isfinite(seconds) and 0 < seconds < 1e11:
            return int(seconds * 1000)
    except (TypeError, ValueError):
        pass
    try:
        at = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return int(at.timestamp() * 1000) if at.tzinfo and at.timestamp() > 0 else None
    except (AttributeError, TypeError, ValueError):
        return None


def verify_normalized_tables(db, state):
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    check(tables == set(state["tables"]), "Normalized state omits a business table")
    for table in tables:
        actual = []
        for row in rows(db, table):
            row = {k: base64.b64encode(v).decode() if isinstance(v, bytes) else v for k, v in row.items()}
            if table == "migration_batches":
                row["recorded_at"] = 0
            if table == "events" and row["device_id"] == "deeptutor-migration":
                row["recorded_at"] = 0
                row["event_json"] = json.loads(row["event_json"])
                row["event_json"]["recordedAt"] = 0
            actual.append(row)
        saved = []
        for row in state["tables"][table]:
            row = dict(row)
            if table == "events" and row["device_id"] == "deeptutor-migration":
                row["event_json"] = json.loads(row["event_json"])
            saved.append(row)
        key = lambda r: json.dumps(r, sort_keys=True, ensure_ascii=False)
        check(sorted(actual, key=key) == sorted(saved, key=key), "Normalized SQL table differs: " + table)


def verify(snapshot, out, seed_path, confirmation_path=None, proposal_path=None):
    mapping, plan, seed = (load(p) for p in (out / "mapping.json", out / "import-plan.json", seed_path))
    decision = plan["human_confirmation"] if isinstance(plan["human_confirmation"], dict) else None
    accepted = {}
    curriculum = None
    if decision is not None:
        check(confirmation_path and proposal_path, "Confirmed imports require independent --confirmation and --proposal inputs")
        check(load(confirmation_path) == decision and load(proposal_path) == plan["proposals"], "Decision or reviewed proposal differs from supplied inputs")
        proposal = plan["proposals"]
        inputs = {"knowledge_points": [{k: row[k] for k in ("id", "name", "module_id", "content_hash")} for row in mapping["knowledge_points"]],
                  "questions": [{"id": row["id"], "content_hash": row["content_hash"], "stem": row["source_record"].get("question") or row["source_record"].get("prompt", ""), "tags": row["source_record"].get("tags", [])} for row in mapping["questions"]]}
        check(decision["input_hash"] == proposal["input_hash"] == digest(inputs) and decision["proposal_hash"] == digest(proposal), "Decision hash binding failed")
        check(proposal["review"]["proposal_hash"] == digest({k: v for k, v in proposal.items() if k != "review"}) and proposal["review"]["status"] == "reviewed"
              and proposal["review"]["vendor"] != proposal["proposer"]["vendor"], "Reviewed proposal provenance failed")
        for k, v in {"question_associations": "accept_reviewed_proposal", "curriculum": "accept_reviewed_proposal", "ambiguous_answers": "preserve_verbatim_unscored",
                     "choice_explanations": "archive_only", "passed_times": "approximate", "grading": "unreviewed_no_keys"}.items():
            check(decision[k] == v, "Decision policy differs: " + k)
        short = [q for q in mapping["questions"] if q["source_record"].get("question_type") == "short_answer"]
        sample = short if len(short) <= 10 else [short[i * (len(short) - 1) // 9] for i in range(10)]
        samples = []
        for q in sample:
            field = next((k for k in ("rubric", "grading_rubric", "marking_criteria", "explanation") if k in q["source_record"]), None)
            samples.append({"id": q["id"], "content_hash": q["content_hash"], "field": field, "value": q["source_record"].get(field)})
        check(samples == decision["short_answer_samples"], "Short-answer source material differs")
        accepted = {q["id"]: q["kp_ids"] for q in proposal["questions"]}
        check(set(accepted) == {q["id"] for q in mapping["questions"]}, "Accepted question coverage differs")
        curriculum = {k: proposal[k] for k in ("concepts", "knowledge_points")}
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
        for table in tables - {"events", "questions", "question_kp", "outline_kp", "threads", "messages", "projection_cache", "migration_records", "migration_batches", "sqlite_sequence"}:
            check(rows(before, table) == rows(after, table), "Unrelated baseline table changed: " + table)
        for table in ("questions", "question_kp", "threads", "messages"):
            baseline_rows = {repr(sorted(r.items())) for r in rows(before, table)}
            check(baseline_rows <= {repr(sorted(r.items())) for r in rows(after, table)}, "Existing rows changed: " + table)
        for table in content_tables:
            if table in tables:
                expected_rows = rows(before, table)
                if table == "outline_kp" and curriculum is not None:
                    for row in expected_rows:
                        kp = next((k for k in curriculum["knowledge_points"] if k["id"] == row["kp_id"] and row["course_id"] == course), None)
                        if kp:
                            row["prereqs"] = canonical(kp["prerequisite_ids"])
                check(expected_rows == rows(after, table), "Course content changed unexpectedly: " + table)
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
        curriculum_events = [e for e in events if e["payload"]["sourceId"] == "confirmation:curriculum"]
        check({e["payload"]["sourceId"] for e in events if e not in curriculum_events} == set(records), "Source lacks real ledger events")
        check(len(curriculum_events) == int(curriculum is not None), "Curriculum acceptance event missing or duplicated")
        if curriculum_events:
            e = curriculum_events[0]
            check(e["type"] == "outline_revised" and e["payload"]["curriculum"] == curriculum and e["payload"]["decision"] == decision, "Curriculum acceptance differs")
            check(e["payload"]["ops"] == [{"op": "setPrereq", "kpId": k["id"], "conceptIds": k["concept_ids"], "prereqs": k["prerequisite_ids"]} for k in curriculum["knowledge_points"]], "Curriculum operations differ")
        check(all(e["deviceId"] == "deeptutor-migration" and e["actor"] == "SYSTEM" for e in events), "Migration event isolation failed")
        by_source = {}
        for e in events:
            by_source.setdefault(e["payload"]["sourceId"], []).append(e)
        snapshot_times = [datetime.fromisoformat(v.split(":", 1)[1].replace("Z", "+00:00"))
                          for v in load(snapshot / "copy-stats.json")["before"].values() if v != "absent"]
        cutoff = int(max(snapshot_times).timestamp() * 1000)
        check(plan["history_time"] == mapping["history_time"] and plan["history_time"]["snapshot_at"] == cutoff,
              "Historical cutoff differs from captured source mtimes")
        schedules = {r["payload"]["kp_id"]: r["payload"] for r in records.values() if r["kind"] == "legacy_schedule"}
        anchors = {k["id"]: k.get("anchors") or {} for k in mapping["knowledge_points"]}
        for key, r in records.items():
            p, kind = r["payload"], r["kind"]
            if kind in ("legacy_passed", "legacy_schedule"):
                schedule = schedules.get(p["kp_id"], {}) if kind == "legacy_passed" else p
                candidates = [schedule.get("original_state", {}).get("last_review_at"),
                              anchors.get(p["kp_id"], {}).get("created_at"), plan["history_time"]["mastery_updated_at"]]
            else:
                candidates = [p.get("created_at"), p.get("updated_at")]
            times = [t for v in candidates if (t := source_millis(v)) is not None and t <= cutoff]
            expected_time = times[0] if times else cutoff
            if kind == "legacy_journal":
                historical_noon = datetime.fromisoformat(p["day"] + "T12:00:00").replace(tzinfo=ZoneInfo("Europe/London"))
                if int(historical_noon.timestamp() * 1000) <= cutoff:
                    expected_time = int(historical_noon.timestamp() * 1000)
            check(all(e["occurredAt"] == expected_time for e in by_source[key]), "Event time differs from historical source")
            if kind == "legacy_attempt":
                check(next(e for e in by_source[key] if e["type"] == "legacy_attempt")["payload"]["posedAt"] == expected_time,
                      "Historical attempt posed time differs")
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
            ids = accepted.get(key, [])
            check(r["payload"]["kp_ids"] == ids and r["payload"]["association_confirmed"] == bool(ids), "Planned association differs from decision")
            actual_ids = [dict(row) for row in after.execute("SELECT kp_id,is_primary FROM question_kp WHERE question_id=? ORDER BY kp_id", (key,))]
            check(actual_ids == sorted([{"kp_id": kp, "is_primary": int(i == 0)} for i, kp in enumerate(ids)], key=lambda r: r["kp_id"]), "SQL associations differ from decision")
            if decision is not None:
                check(r["payload"]["confirmation"] == {**{k: decision[k] for k in ("decision_ref", "input_hash", "proposal_hash")}, "decision_hash": digest(decision)}, "Question acceptance provenance differs")
                sample = next((s for s in decision["short_answer_samples"] if s["id"] == key), None)
                check(r["payload"].get("accepted_short_answer_material") == sample, "Accepted short-answer archive differs")
            for table in ("answer_keys", "pool_details", "constructed_details"):
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
        check(report["second"]["events_added"] == report["second"]["records_inserted"] == report["second"]["records_revised"] == 0, "Second import changed data")
        first_bytes, second_bytes = ((out / f).read_bytes() for f in ("first-state.json", "second-state.json"))
        check(first_bytes == second_bytes, "Normalized database changed on second import")
        check(hashlib.sha256(first_bytes).hexdigest() == report["state_sha256"], "State hash mismatch")
        check((out / "before-state.json").read_bytes() == (out / "rollback-state.json").read_bytes(), "Rollback normalized state differs byte for byte")
        projection = json.loads(first_bytes)["projection"]
        check(hashlib.sha256(projection_bytes(first_bytes)).hexdigest() == report["projection_sha256"], "Projection hash mismatch")
        check(hashlib.sha256((out / "before-state.json").read_bytes()).hexdigest() == report["baseline_sha256"], "Baseline hash mismatch")
        verify_normalized_tables(after, json.loads(first_bytes))
        import_days = {str(day(e["recordedAt"] / 1000)) for e in events}
        for import_day in import_days:
            old = baseline_projection["journal"].get(import_day, {})
            new = projection["journal"].get(import_day, {})
            check(all(new.get(k, 0) == old.get(k, 0) for k in ("answers", "notes"))
                  and new.get("newlyPassed", []) == old.get("newlyPassed", []), "Import day gained legacy learning facts")
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
                "confirmed_questions": sum(bool(v) for v in accepted.values()), "unmapped_questions": sum(not v for v in accepted.values()) if decision else len(expected_q),
                "accepted_short_answer_samples": len(decision["short_answer_samples"]) if decision else 0,
                "textbook_and_exam_weights_unchanged": True, "legacy_evidence_excluded": True, "existing_pending_preserved": True,
                "rollback_matches_baseline": True, "integrity_check": "ok"}
    finally:
        before.close(); after.close(); rollback.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("snapshot", "out", "seed"):
        parser.add_argument("--" + flag, type=Path, required=True)
    for flag in ("confirmation", "proposal"):
        parser.add_argument("--" + flag, type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.snapshot.resolve(strict=True), args.out, args.seed, args.confirmation, args.proposal), indent=2, sort_keys=True))

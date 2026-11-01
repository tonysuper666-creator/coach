#!/usr/bin/env python3
"""Independent SQLite/JSON checks of a dry-run. Never opens the running source DB."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
from urllib.parse import unquote


def load(path):
    return json.loads(path.read_text())


def check(condition, message):
    if not condition:
        raise ValueError(message)


def copied_database(snapshot, relative):
    path = (snapshot / relative).resolve(strict=True)
    check(path.is_relative_to(snapshot), "Database escapes snapshot")
    check(not path.is_relative_to((Path.home() / "DeepTutor").resolve()), "Refusing source database")
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA query_only=ON")
    check(db.execute("PRAGMA integrity_check").fetchall()[0][0] == "ok", "Snapshot integrity failure")
    db.execute("BEGIN")
    return db


def inventory(root):
    return {str(p.relative_to(root)): [p.lstat().st_size, p.lstat().st_mtime_ns]
            for p in [root, *root.rglob("*")]}


def verify(snapshot, out, repeat, seed_path, before_inventory=None, source_data=None, rehearsal=False):
    mapping = load(out / "mapping.json")
    plan = load(out / "import-plan.json")
    seed = load(seed_path)
    check((snapshot / "data-before.stat").read_bytes() == (snapshot / "data-after.stat").read_bytes(), "Capture stat mismatch")
    check((snapshot / "data-before.ls").read_bytes() == (snapshot / "data-after.ls").read_bytes(), "Capture ls mismatch")
    stats = load(snapshot / "copy-stats.json")
    check(stats["data_unchanged"] and stats["before"] == stats["after"], "Copy evidence mismatch")
    mastery = copied_database(snapshot, "data/user/workspace/learning/mastery/mastery.sqlite3")
    chat = copied_database(snapshot, "data/user/chat_history.db")
    try:
        state = json.loads(mastery.execute("SELECT state_json FROM mastery_paths WHERE path_id=?", (mapping["path_id"],)).fetchone()[0])
        points = [k for m in state["modules"] for k in m["knowledge_points"]]
        kp_ids = {k["id"] for k in points}
        passed = {k for k, v in state["mastery_levels"].items() if k in kp_ids and v >= 1}
        source_attempts = [dict(r) for r in mastery.execute("SELECT * FROM mastery_interactions WHERE path_id=? AND length(trim(user_answer))>0", (mapping["path_id"],))
                           if json.loads(r["question_json"]).get("question_type") != "group"]
        counts = {
            "knowledge_points": len(points), "modules": len(state["modules"]),
            "passed": len(passed), "schedules": len(state["repetition_states"]), "attempts": len(source_attempts),
            "retained_evidence": mastery.execute("SELECT count(*) FROM mastery_learning_evidence WHERE path_id=?", (mapping["path_id"],)).fetchone()[0],
            "events": mastery.execute("SELECT count(*) FROM mastery_events WHERE path_id=?", (mapping["path_id"],)).fetchone()[0],
            "assessment_attempts": chat.execute("SELECT count(*) FROM assessment_attempts WHERE mastery_path_id=?", (mapping["path_id"],)).fetchone()[0],
        }
        bank = load(snapshot / "data/digests/questions/ECNM10119-期中练习题.json")
        bank = bank if isinstance(bank, list) else bank["questions"]
        counts.update(questions=len(bank), mcq=sum(q.get("question_type") in ("mcq", "multiple_choice", "single_choice", "choice") for q in bank),
                      short_answer=sum(q.get("question_type") == "short_answer" for q in bank))
        counts["notes"] = sum(len(load(p).get("records", [])) for p in (snapshot / "data/user/workspace/notebook").rglob("*.json") if p.name != "notebooks_index.json")
        check(all(mapping["counts"][key] == n for key, n in counts.items()), "SQL/source counts differ from mapping")
        check([k["id"] for k in points] == [k["id"] for k in seed["knowledge_points"]], "Seed order differs")
        check(all(k["name"] == s["name"] for k, s in zip(points, seed["knowledge_points"])), "Seed titles differ")
        records = {r["id"]: r for r in plan["records"]}
        check(len(records) == len(plan["records"]), "Duplicate source identity")
        check(plan["content_action"] == "reference_existing_only" and plan["exam_weights_action"] == "reuse_existing_event", "Content would be reseeded")
        check(plan["exam_weights"] == seed["exam_weights"], "Existing weights changed")
        total = sum(r["source_record"]["points"] for r in mapping["exam_weights_set"])
        check(sum(plan["exam_weights"].values()) == total, "Weight total differs from source table")
        check({r["payload"]["kp_id"] for r in records.values() if r["kind"] == "legacy_passed"} == passed, "Passed set differs")
        check(all(r["payload"]["queue_eligible"] == (r["payload"]["kp_id"] in passed) for r in records.values() if r["kind"] == "legacy_schedule"), "Unpassed review admitted")
        gates = load(snapshot / "tools/patch_backups/gate_types_before.json")
        unknown = {k["id"]: k for k in plan["unknown_types"]}
        check(set(unknown) == kp_ids - set(gates), "Unknown original types lost")
        check(all(unknown[k["id"]]["type"] is None and unknown[k["id"]]["current_source_type"] == k.get("type") for k in points if k["id"] in unknown), "Unknown type guessed or current type changed")
        source_by_id = {r["interaction_id"]: r for r in source_attempts}
        checked_attempts = 0
        unresolved = 0
        for record in records.values():
            if record["kind"] != "legacy_attempt":
                continue
            raw = source_by_id[unquote(record["id"].split(":", 3)[3])]
            q = json.loads(raw["question_json"])
            payload = record["payload"]
            check(payload["origin"] == "LEGACY" and payload["window_eligible"] is False, "Legacy entered a window")
            if q.get("question_type") in ("choice", "single_choice", "mcq", "multiple_choice"):
                options = q.get("options", {})
                pairs = list(options.items()) if isinstance(options, dict) else [(o.get("label"), o.get("body", o.get("text", ""))) for o in options]
                selected = [text for label, text in pairs if label == raw["user_answer"].strip()]
                expected = "option:" + hashlib.sha256(selected[0].encode()).hexdigest() if len(selected) == 1 and selected[0] else None
                check(payload["answer"] == expected, "Option answer differs from original")
                check(not any(k in payload["question"] for k in ("correct_answer", "expected_answer", "key", "answer")), "Structured letter key leaked")
                if expected is None:
                    unresolved += 1
                    check(payload["unresolved_answer_text"] == raw["user_answer"], "Unknown response text lost")
            checked_attempts += 1
        check(checked_attempts == len(source_attempts), "Legacy attempt lost")
        message_fields = {"id", "session_id", "role", "content", "created_at", "parent_message_id"}
        checked_messages = 0
        for message in mapping["chat_archive"]["messages"]:
            row = message["source_record"]
            source = dict(chat.execute("SELECT * FROM messages WHERE id=?", (row["id"],)).fetchone())
            check(set(row) <= message_fields and row == {k: v for k, v in source.items() if k in message_fields}, "Message metadata retained or body changed")
            checked_messages += 1
        check(sum(r["kind"] == "legacy_message" for r in records.values()) == checked_messages, "Message body lost from plan")
        hashes = {}
        if not rehearsal:
            filenames = {p.name for p in out.iterdir() if p.is_file()}
            check(filenames == {p.name for p in repeat.iterdir() if p.is_file()}, "Repeat file set differs")
            for name in sorted(filenames):
                data = (out / name).read_bytes()
                check(data == (repeat / name).read_bytes(), "Repeat bytes differ: " + name)
                hashes[name] = hashlib.sha256(data).hexdigest()
        simulation = None if rehearsal else load(out / "simulation.json")
        if simulation is not None:
            check(simulation["second_inserted"] == simulation["second_revised"] == 0, "Second simulation changed records")
        report = {"source_counts": counts, "unknown_original_types_preserved": len(unknown),
                  "source_attempts_checked": checked_attempts, "unresolved_choice_answers_preserved": unresolved,
                  "message_bodies_checked": checked_messages, "source_exam_weight_total": total,
                  "plan_counts": plan["counts"], "simulation": simulation, "byte_identical_files": len(hashes), "sha256": hashes}
        if before_inventory:
            after = inventory(source_data)
            check(load(before_inventory) == after, "Running source inventory changed during this run")
            report.update(source_entries=len(after), source_stat_unchanged=True)
        return report
    finally:
        mastery.close()
        chat.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("snapshot", "out", "repeat", "seed"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--before-inventory", type=Path)
    parser.add_argument("--source-data", type=Path, default=Path.home() / "DeepTutor/data")
    args = parser.parse_args()
    print(json.dumps(verify(args.snapshot.resolve(), args.out, args.repeat, args.seed, args.before_inventory, args.source_data), indent=2, sort_keys=True))

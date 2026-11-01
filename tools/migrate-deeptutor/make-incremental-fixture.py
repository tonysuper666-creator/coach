#!/usr/bin/env python3
"""Create labelled synthetic corrections to a captured snapshot, never a new live capture.

All copies/output must stay under ~/Coach/corpus. No network/model calls or live source access.
Round 1 corrects one bank question. Round 2 corrects an answer, note, message and review,
and adds one old journal entry (plus its containing metadata revision).
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import shutil
import sqlite3


def load(p):
    return json.loads(p.read_text())


def save(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def make(snapshot, out):
    snapshot = snapshot.resolve(strict=True)
    out = out.resolve()
    corpus = (Path.home() / "Coach/corpus").resolve()
    if out.exists() or not out.is_relative_to(corpus) or snapshot.is_relative_to((Path.home() / "DeepTutor").resolve()) or out.is_relative_to(snapshot):
        raise ValueError("Use a captured snapshot and a NEW private corpus directory")
    out.mkdir(mode=0o700)
    initial_stats = load(snapshot / "copy-stats.json")
    changes = []
    for n in (1, 2):
        copy = out / ("snapshot-" + str(n))
        shutil.copytree(snapshot if n == 1 else out / "snapshot-1", copy)
        if n == 1:
            bank_path = copy / "data/digests/questions/ECNM10119-期中练习题.json"
            bank = load(bank_path)
            questions = bank if isinstance(bank, list) else bank["questions"]
            questions[0]["question"] += " [isolated correction fixture 1]"
            save(bank_path, bank)
            changes.append({"round": n, "change": "one bank stem, same source ID", "synthetic": True})
        else:
            mastery_path = copy / "data/user/workspace/learning/mastery/mastery.sqlite3"
            gates = load(copy / "tools/patch_backups/gate_types_before.json")
            with sqlite3.connect(mastery_path) as db:
                paths = [(r, json.loads(r[1])) for r in db.execute("SELECT path_id,state_json,owner_session_id FROM mastery_paths")]
                row, state = max(paths, key=lambda pair: sum(k["id"] in gates for m in pair[1]["modules"] for k in m["knowledge_points"]))
                passed = {k for k, value in state["mastery_levels"].items() if value >= 1}
                kp = sorted(set(state["repetition_states"]) & passed)[0]
                state["repetition_states"][kp]["next_review_at"] += 86400
                db.execute("UPDATE mastery_paths SET state_json=? WHERE path_id=?", (json.dumps(state, ensure_ascii=False), row[0]))
                attempts = list(db.execute("SELECT interaction_id,question_json,user_answer,session_id FROM mastery_interactions WHERE path_id=? AND length(trim(user_answer))>0 ORDER BY interaction_id", (row[0],)))
                for aid, qjson, answer, _ in attempts:
                    q = json.loads(qjson)
                    options = q.get("options")
                    labels = list(options) if isinstance(options, dict) else [o.get("label") for o in options if isinstance(o, dict)] if isinstance(options, list) else []
                    if answer.strip() in labels and len(labels) > 1:
                        replacement = next(label for label in labels if label != answer.strip())
                        db.execute("UPDATE mastery_interactions SET user_answer=? WHERE interaction_id=?", (replacement, aid))
                        break
                else:
                    raise ValueError("No resolved choice answer available for the fixture")
                sessions = sorted({row[2]} | {a[3] for a in attempts})
            chat_path = copy / "data/user/chat_history.db"
            with sqlite3.connect(chat_path) as db:
                message = db.execute("SELECT id,content FROM messages WHERE session_id IN (" + ",".join("?" for _ in sessions) + ") ORDER BY created_at,id LIMIT 1", sessions).fetchone()
                if message is None:
                    raise ValueError("No scoped message")
                db.execute("UPDATE messages SET content=? WHERE id=?", (message[1] + " [isolated correction fixture 2]", message[0]))
            for book in sorted((copy / "data/user/workspace/notebook").rglob("*.json")):
                content = load(book)
                if book.name != "notebooks_index.json" and content.get("records"):
                    record = content["records"][0]
                    record["output"] = record.get("output", "") + " [isolated correction fixture 2]"
                    save(book, content)
                    break
            else:
                raise ValueError("No note")
            journal_path = copy / "data/learning_journal/journal.json"
            journal = load(journal_path)
            journal["records"].append({"id": "isolated-increment-fixture-log", "created_at": "2026-10-28T12:00:00Z", "text": "Synthetic isolated journal addition; never a learner fact from a new capture."})
            save(journal_path, journal)
            changes.append({"round": n, "change": "answer/note/message/review/root-metadata corrections plus a new old log", "synthetic": True})
        # Advance the fixture capture cutoff deterministically. The provenance marker below
        # distinguishes this manufactured clock from a genuine source inventory capture.
        stats = json.loads(json.dumps(initial_stats))
        for side in ("before", "after"):
            for key, value in stats[side].items():
                if value != "absent":
                    size, stamp = value.split(":", 1)
                    shifted = datetime.fromisoformat(stamp.replace("Z", "+00:00")) + timedelta(days=n)
                    stats[side][key] = size + ":" + shifted.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        save(copy / "copy-stats.json", stats)
        save(copy / "SYNTHETIC-INCREMENTAL-FIXTURE.json", {"synthetic": True, "source": str(snapshot), "round": n, "changes": changes[-1], "capture_inventory": "Inherited historical capture, not evidence of a new source capture; only the listed copy was edited."})
    save(out / "fixture.json", {"synthetic": True, "changes": changes})
    print(json.dumps({"synthetic": True, "snapshots_created": 2, "production_written": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    make(args.snapshot, args.out)

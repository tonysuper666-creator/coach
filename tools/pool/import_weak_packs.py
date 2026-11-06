#!/usr/bin/env python3
"""Import variant practice packs into a stopped Coach database.

Dry-run by default. Apply takes the same POSIX record lock as the server and
writes question rows only. It refuses the production data directory and never
opens a network connection.

Pack JSON follows the practice-pack shape: mcq items become verified
single-choice questions, analytical items become verified calculation questions.
Content files stay outside the repository.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import fcntl
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import unicodedata
import uuid


WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
CJK_OR_DIGIT = re.compile(r"[\u4e00-\u9fff0-9]+")
EXAM_NAMES = ("01-Dec2024真题详解.md", "02-Dec2025真题详解.md")


def fail(message):
    raise SystemExit(message)


def normalized(text):
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text).strip()).lower()


def fingerprint(stem, options):
    """Same length-delimited hash the server uses for a single-choice question."""
    values = [normalized(stem)] + sorted(normalized(option["text"]) for option in options)
    payload = "".join(f"{len(value.encode('utf-16-le')) // 2}:{value}" for value in values)
    return hashlib.sha256(payload.encode()).hexdigest()


def sha256_text(text):
    return hashlib.sha256(text.encode()).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def production_dir():
    return (Path.home() / "Coach" / "data").resolve()


def refuse_production(target):
    resolved = Path(target).resolve()
    if resolved == production_dir() or production_dir() in resolved.parents:
        fail("refusing the production data directory")
    live = production_dir() / "coach.sqlite"
    copy = resolved / "coach.sqlite"
    if live.exists() and copy.exists() and live.samefile(copy):
        fail("refusing a database that is the production file")


def eightgrams(text):
    grams = set()
    for part in CJK_OR_DIGIT.split(text):
        words = [word.lower() for word in WORD.findall(part)]
        grams.update(" ".join(words[i:i + 8]) for i in range(len(words) - 7))
    return grams


def pack_corpus(pack):
    blobs = []
    for section in pack["sections"]:
        blobs.append(section.get("instructions", ""))
        for item in section["items"]:
            blobs.extend([item.get("stem", ""), item.get("explanation", ""), item.get("source", "")])
            blobs.extend(item.get("options") or [])
            for row in item.get("parts") or []:
                blobs.extend([row.get("prompt", ""), str(row.get("answer", ""))])
    return "\n".join(blobs)


def overlap_hits(packs, exam_dir):
    banned = set()
    for name in EXAM_NAMES:
        path = Path(exam_dir) / name
        if not path.is_file():
            fail(f"missing exam writeup {name}")
        banned |= eightgrams(path.read_text())
    hits = []
    for pack in packs:
        for gram in sorted(eightgrams(pack_corpus(pack)) & banned):
            hits.append({"pack": pack["id"], "gram": gram})
    return hits


def load_pack(path):
    pack = json.loads(Path(path).read_text())
    if pack.get("kind") != "pack" or not pack.get("id") or not pack.get("kp_id"):
        fail(f"{path} is not a practice pack")
    items = [item for section in pack.get("sections", []) for item in section.get("items", [])]
    if len(items) < 8:
        fail(f"{pack['id']} has fewer than 8 items")
    for item in items:
        if item.get("type") == "mcq":
            options = item.get("options") or []
            if len(options) != 4 or len({normalized(option) for option in options}) != 4:
                fail(f"{item.get('id')} needs four distinct options")
            if item.get("answer") not in range(4):
                fail(f"{item.get('id')} has an answer outside the options")
            if not str(item.get("stem", "")).strip() or not str(item.get("explanation", "")).strip():
                fail(f"{item.get('id')} is missing a stem or explanation")
        elif item.get("type") == "analytical":
            parts = item.get("parts") or []
            if not parts or not str(item.get("stem", "")).strip():
                fail(f"{item.get('id')} is missing a stem or parts")
            if any(not str(part.get("answer", "")).strip() for part in parts):
                fail(f"{item.get('id')} has a part without an answer")
        else:
            fail(f"{item.get('id')} has an unsupported type")
    pack["_items"] = items
    return pack


def learner_stem(item):
    lines = [item["stem"].strip()]
    if item.get("figure_svg"):
        lines.append(f"Figure: {item['figure_svg']}")
    for part in item.get("parts") or []:
        lines.append(f"({part['label']}) {part['prompt'].strip()}")
    return "\n".join(lines)


def option_rows(pack_id, item):
    return [{"optionId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{pack_id}:{item['id']}:{index}")),
             "text": text} for index, text in enumerate(item["options"])]


def rows_for(pack):
    built = []
    for item in pack["_items"]:
        question_id = f"{pack['id']}-{item['id']}"
        if item["type"] == "mcq":
            options = option_rows(pack["id"], item)
            stem = learner_stem(item)
            built.append({
                "question_id": question_id,
                "format": "SINGLE_CHOICE",
                "stem_md": stem,
                "options": json.dumps(options, ensure_ascii=False),
                "fingerprint": fingerprint(stem, options),
                "kp_id": pack["kp_id"],
                "key_json": json.dumps({"correctOptionId": options[item["answer"]]["optionId"]}, ensure_ascii=False),
                "pool": {
                    "pack_hash": sha256_text(f"{pack['id']}\0{item['id']}"),
                    "explanation": item["explanation"].strip(),
                    "source_quote": item.get("source", "original variant").strip(),
                    "generator": "ton1249-variant",
                    "reviewer": "ton1249-recompute",
                },
            })
            continue
        stem = learner_stem(item)
        rubric = []
        for part in item["parts"]:
            rubric.extend(part.get("rubric") or [{
                "criterion": part["prompt"], "marks": part.get("marks", 1), "look_for": part["answer"]}])
        reference = " ".join(f"({part['label']}) {part['answer']}" for part in item["parts"])
        numeric = {part["label"]: part["answer"] for part in item["parts"] if "tolerance" in part}
        rubric_fingerprint = sha256_text(canonical(rubric))
        key = {
            "stem": stem,
            "reference_answer": reference,
            "numeric_final": numeric,
            "parts": [{"label": part["label"], "answer": part["answer"], "marks": part.get("marks", 1)}
                      for part in item["parts"]],
            "rubric": rubric,
        }
        built.append({
            "question_id": question_id,
            "format": "CALCULATION" if numeric else "SHORT_ANSWER",
            "stem_md": stem,
            "options": None,
            "fingerprint": sha256_text(stem + reference + rubric_fingerprint),
            "kp_id": pack["kp_id"],
            "key_json": json.dumps(key, ensure_ascii=False),
            "constructed": {
                "pack_hash": sha256_text(f"{pack['id']}\0{item['id']}"),
                "reference_answer": reference,
                "rubric": canonical(rubric),
                "rubric_fingerprint": rubric_fingerprint,
                "generator": "ton1249-variant",
                "reviewer": "ton1249-recompute",
            },
        })
    return built


def connect(path, readonly):
    mode = "ro" if readonly else "rw"
    connection = sqlite3.connect(f"{path.resolve().as_uri()}?mode={mode}", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def classify(db, course, rows):
    known = {row["kp_id"] for row in db.execute(
        "SELECT kp_id FROM outline_kp WHERE course_id=? AND retired=0", (course,))}
    missing = sorted({row["kp_id"] for row in rows} - known)
    if missing:
        fail("unknown knowledge point " + ", ".join(missing))
    insert, skip = [], []
    for row in rows:
        existing = db.execute(
            "SELECT fingerprint FROM questions WHERE question_id=?", (row["question_id"],)).fetchone()
        if existing:
            if existing["fingerprint"] != row["fingerprint"]:
                fail(f"{row['question_id']} already exists with different content")
            skip.append(row["question_id"])
            continue
        if db.execute("SELECT 1 FROM questions WHERE course_id=? AND fingerprint=?",
                      (course, row["fingerprint"])).fetchone():
            skip.append(row["question_id"])
            continue
        insert.append(row)
    return insert, skip


def insert_rows(db, course, rows):
    reviewed_at = int(time.time() * 1000)
    for row in rows:
        db.execute(
            """INSERT INTO questions(question_id,course_id,format,stem_md,options,origin,fingerprint,key_state,created_seq)
               VALUES(?,?,?,?,?,'COACH',?,'VERIFIED',NULL)""",
            (row["question_id"], course, row["format"], row["stem_md"], row["options"], row["fingerprint"]))
        db.execute(
            "INSERT INTO question_kp(question_id,kp_id,is_primary) VALUES(?,?,1)",
            (row["question_id"], row["kp_id"]))
        db.execute(
            "INSERT INTO answer_keys(question_id,key_json,reviewed_by,reviewed_at) VALUES(?,?,?,?)",
            (row["question_id"], row["key_json"], "ton1249-recompute", reviewed_at))
        if "pool" in row:
            pool = row["pool"]
            db.execute(
                """INSERT INTO pool_details(question_id,pack_hash,explanation,source_quote,generator,reviewer)
                   VALUES(?,?,?,?,?,?)""",
                (row["question_id"], pool["pack_hash"], pool["explanation"], pool["source_quote"],
                 pool["generator"], pool["reviewer"]))
        else:
            detail = row["constructed"]
            db.execute(
                """INSERT INTO constructed_details(question_id,pack_hash,reference_answer,rubric,rubric_fingerprint,generator,reviewer)
                   VALUES(?,?,?,?,?,?,?)""",
                (row["question_id"], detail["pack_hash"], detail["reference_answer"], detail["rubric"],
                 detail["rubric_fingerprint"], detail["generator"], detail["reviewer"]))


def servable(db, course, kp_id):
    mcq = db.execute(
        """SELECT count(*) FROM questions q JOIN question_kp k USING(question_id)
           WHERE q.course_id=? AND k.kp_id=? AND q.format='SINGLE_CHOICE' AND q.key_state='VERIFIED'""",
        (course, kp_id)).fetchone()[0]
    analytical = db.execute(
        """SELECT count(*) FROM questions q JOIN question_kp k USING(question_id)
           JOIN constructed_details d USING(question_id)
           WHERE q.course_id=? AND k.kp_id=? AND q.key_state='VERIFIED'""",
        (course, kp_id)).fetchone()[0]
    return {"mcq": mcq, "analytical": analytical}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", action="append", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path, help="Data directory that contains coach.sqlite")
    parser.add_argument("--course", required=True)
    parser.add_argument("--exam-dir", type=Path)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--backup", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    refuse_production(args.target)
    database = args.target / "coach.sqlite"
    if not database.is_file():
        fail("target database is missing")
    packs = [load_pack(path) for path in args.pack]
    hits = overlap_hits(packs, args.exam_dir) if args.exam_dir else []
    if hits:
        fail(f"overlap hits {len(hits)}")
    planned = [row for pack in packs for row in rows_for(pack)]
    if args.apply:
        if args.backup and args.backup.exists():
            fail("backup path already exists")
        lock_file = args.target / "server.lock"
        with lock_file.open("a+b") as lock:
            try:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                fail("target server is running")
            with closing(connect(database, readonly=False)) as db:
                insert, skip = classify(db, args.course, planned)
                if args.backup:
                    args.backup.parent.mkdir(parents=True, exist_ok=True)
                    with closing(sqlite3.connect(args.backup)) as backup:
                        db.backup(backup)
                with db:
                    insert_rows(db, args.course, insert)
                    violation = db.execute("PRAGMA foreign_key_check").fetchone()
                    if violation is not None:
                        fail("foreign key violation")
                report = summary(args.course, packs, insert, skip, db, applied=True)
    else:
        with closing(connect(database, readonly=True)) as db:
            insert, skip = classify(db, args.course, planned)
            report = summary(args.course, packs, insert, skip, db, applied=False)
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.report:
        args.report.write_text(text + "\n")
    print(json.dumps({key: report[key] for key in ("applied", "inserted", "skipped", "mcq", "analytical", "overlapHits")}))


def summary(course, packs, insert, skip, db, applied):
    by_kp = {}
    for pack in packs:
        by_kp[pack["kp_id"]] = servable(db, course, pack["kp_id"])
    return {
        "applied": applied,
        "course": course,
        "packs": [pack["id"] for pack in packs],
        "inserted": len(insert),
        "skipped": len(skip),
        "mcq": sum(row["format"] == "SINGLE_CHOICE" for row in insert),
        "analytical": sum(row["format"] != "SINGLE_CHOICE" for row in insert),
        "ids": [row["question_id"] for row in insert],
        "servable": by_kp,
        "overlapHits": 0,
    }


if __name__ == "__main__":
    main()

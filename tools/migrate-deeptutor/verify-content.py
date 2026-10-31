#!/usr/bin/env python3
"""Independent SQL/source comparison for a fresh content-only Coach directory.

Only copied DeepTutor databases may be supplied. Does not import the Kotlin mapper.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3


def readonly(path):
    path = path.resolve(strict=True)
    if path.is_relative_to((Path.home() / "DeepTutor").resolve()):
        raise ValueError("Never connect to the live DeepTutor database")
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    db.execute("PRAGMA query_only=ON")
    assert db.execute("PRAGMA integrity_check").fetchall() == [("ok",)]
    return db


def verify(snapshot, database, weights):
    with readonly(snapshot / "data/user/workspace/learning/mastery/mastery.sqlite3") as source, readonly(database / "coach.sqlite") as coach:
        courses = json.loads((snapshot / "data/user/workspace/courses/courses.json").read_text())["courses"]
        course, = [c for c in courses if "ECNM10119" in c["name"]]
        path, = [r["ref_id"] for r in course["resources"] if r["kind"] == "mastery_path"]
        state = json.loads(source.execute("SELECT state_json FROM mastery_paths WHERE path_id=?", (path,)).fetchone()[0])
        expected_modules = [(m["id"], i, m["name"]) for i, m in enumerate(state["modules"])]
        actual_modules = coach.execute("SELECT module_id,ord,title FROM course_modules WHERE course_id=? ORDER BY ord", (course["id"],)).fetchall()
        assert expected_modules == actual_modules, "Module ID/order/name difference"
        expected_kps = [(k["id"], m["id"], k["name"]) for m in state["modules"] for k in m["knowledge_points"]]
        actual_kps = coach.execute("SELECT kp_id,module_id,title FROM outline_kp WHERE course_id=? AND retired=0 ORDER BY ord", (course["id"],)).fetchall()
        assert expected_kps == actual_kps, "KP ID/order/name difference"
        for raw, in coach.execute("SELECT anchors FROM outline_kp WHERE course_id=?", (course["id"],)):
            anchors = json.loads(raw)
            assert anchors, "KP has no textbook anchors"
            for a in anchors:
                text, book_page = coach.execute("SELECT text,book_page FROM source_pages WHERE doc_id=? AND pdf_page=?", (a["docId"], a["pdfPage"])).fetchone()
                assert text.strip() and book_page == a["bookPage"]
        metadata = json.loads(coach.execute("SELECT content_json FROM course_content WHERE course_id=?", (course["id"],)).fetchone()[0])
        weight_rows = []
        for line in weights.read_text().splitlines():
            cells = [c.strip() for c in line.split("|")[1:-1]]
            if cells and cells[0].isdigit():
                weight_rows.append((int(cells[0]), cells[1], cells[2]))
        groups = metadata["course"]["examWeightGroups"]
        assert [(g["points"], g["question"]) for g in groups] == [(n, q) for n, q, _ in weight_rows]
        # Independently check the ten published hints against original KP titles.
        selectors = [
            [("05", None)], [("07", "RAND HIE 设计"), ("06", "CRHIE")],
            [("03", "differentiated product oligopoly")], [("03", "Hill-Burton")],
            [("02", "HHI=")], [("06", "CRHIE")], [("02", "NPV=")],
            [("09", "三个预测")], [("11", "假说对应 Grossman 参数")], [("07", "实证证据归类")],
        ]
        assert len(selectors) == len(groups), "Exam table changed; review the verification selectors"
        for group, rules in zip(groups, selectors):
            expected = []
            for number, term in rules:
                module, = [m for m in state["modules"] if m["name"].startswith(number + " ")]
                matches = [k["id"] for k in module["knowledge_points"] if term is None or term.lower() in k["name"].lower()]
                assert matches and (term is None or len(matches) == 1)
                expected.extend(matches)
            assert expected == group["kpIds"], "Exam row mapped to different source knowledge points"
        aggregate = {k[0]: 0 for k in expected_kps}
        for group in groups:
            ids = group["kpIds"]
            assert group["allocations"] == {key: group["points"] // len(ids) + (i < group["points"] % len(ids)) for i, key in enumerate(ids)}
            assert sum(group["allocations"].values()) == group["points"]
            for key, value in group["allocations"].items():
                aggregate[key] += value
        assert aggregate == metadata["course"]["examWeights"]
        events = coach.execute("SELECT type,payload FROM events WHERE course_id=? ORDER BY seq", (course["id"],)).fetchall()
        assert [e[0] for e in events] == ["outline_revised", "exam_weights_set"], "Fresh database contains unexpected learning events or duplicate seeds"
        assert json.loads(events[1][1])["weights"] == aggregate
        for table in ("questions", "question_kp", "answer_keys", "messages"):
            assert coach.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0
        for sha, relative in coach.execute("SELECT sha256,path FROM blobs"):
            assert hashlib.sha256((database / "blobs" / relative).read_bytes()).hexdigest() == sha
        docs, = coach.execute("SELECT count(*) FROM source_docs WHERE course_id=? AND kind='TEXTBOOK' AND tutor_visible=1", (course["id"],)).fetchone()
        pages, = coach.execute("SELECT count(*) FROM source_pages p JOIN source_docs d ON p.doc_id=d.doc_id WHERE d.course_id=?", (course["id"],)).fetchone()
        expected_index = coach.execute("SELECT doc_id,pdf_page,text FROM source_pages ORDER BY doc_id,pdf_page,text").fetchall()
        for table in ("fts_en", "fts_zh"):
            actual_index = coach.execute(f"SELECT doc_id,pdf_page,text FROM {table} ORDER BY doc_id,pdf_page,text").fetchall()
            assert actual_index == expected_index, "Textbook search index has missing or duplicate pages"
        return dict(modules=len(actual_modules), knowledge_points=len(actual_kps), name_order_differences=0,
                    missing_anchors=0, documents=docs, pages=pages, exam_points=sum(aggregate.values()),
                    weight_rows=len(weight_rows), learning_records=0, seed_events=len(events), indexed_pages=pages)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--weights", type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.snapshot, args.database, args.weights or args.snapshot / "exam-weights.md"), sort_keys=True))

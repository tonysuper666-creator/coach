#!/usr/bin/env python3
"""把本机语料装配成 `~/Coach/corpus/gradegen/inputs/`（DESIGN §12.1：语料不进仓库）。

仓库里只放这个装配脚本；课程原文、真题、他本人的作答都留在本机语料目录。

    tools/corpus/prepare-gradegen.py kps         # 每章一个 KP（章节小节 + 原文窗口）
    tools/corpus/prepare-gradegen.py mcq         # 108 题里的 60 道选择题
    tools/corpus/prepare-gradegen.py real        # 他本人的 5 条中文作答（读只读库拷贝）
    tools/corpus/prepare-gradegen.py planted     # 植入错键题（选择题面 + 原文）
    tools/corpus/prepare-gradegen.py check       # 只检查装配齐不齐

路径来源（都可用环境变量覆盖）：
    COACH_CORPUS_DIR     默认 ~/Coach/corpus/gradegen
    COACH_TEXTBOOK_DIR   默认读 ~/Coach/config/local.properties 的 coach.textbookDir
    COACH_QUESTION_DIGEST 默认 ~/DeepTutor/data/digests/questions/ECNM10119-期中练习题.json
    COACH_CHAT_DB        默认 ~/Coach/migrate-staging/ 下最新的 chat_history.db 拷贝
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

HOME = Path.home()
CORPUS = Path(os.environ.get("COACH_CORPUS_DIR", HOME / "Coach/corpus/gradegen"))
INPUTS = CORPUS / "inputs"
EXCERPT_BUDGET = 8000
REAL_ANSWER_IDS = ["71", "93", "95", "99", "139"]


def local_property(key: str) -> str | None:
    path = HOME / "Coach/config/local.properties"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip()
    return None


def textbook_dir() -> Path:
    raw = os.environ.get("COACH_TEXTBOOK_DIR") or local_property("coach.textbookDir")
    if not raw:
        sys.exit("缺少教材目录：设 COACH_TEXTBOOK_DIR，或在 ~/Coach/config/local.properties 里写 coach.textbookDir")
    return Path(raw).expanduser()


def digest_path() -> Path:
    raw = os.environ.get("COACH_QUESTION_DIGEST")
    if raw:
        return Path(raw).expanduser()
    return HOME / "DeepTutor/data/digests/questions/ECNM10119-期中练习题.json"


def chat_db_path() -> Path:
    raw = os.environ.get("COACH_CHAT_DB")
    if raw:
        return Path(raw).expanduser()
    staging = HOME / "Coach/migrate-staging"
    candidates = sorted(staging.glob("*/chat_history.db"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not candidates:
        sys.exit("找不到 ~/Coach/migrate-staging/*/chat_history.db：按 ADR-0006 先把库复制出来")
    return candidates[0]


def section_excerpt(text: str, heading_index: int) -> str:
    lines = text.splitlines()
    buf: list[str] = []
    size = 0
    for line in lines[heading_index:]:
        if buf and line.startswith("### "):
            break
        buf.append(line)
        size += len(line) + 1
        if size >= EXCERPT_BUDGET:
            break
    return "\n".join(buf).strip()


def build_kps() -> None:
    root = textbook_dir()
    kps = []
    for path in sorted(root.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        heading_index = next((i for i, line in enumerate(lines) if line.startswith("### ")), None)
        if heading_index is None:
            continue
        heading = lines[heading_index].strip("# ").strip()
        title = next((line.strip("# ").strip() for line in lines if line.startswith("# ")), path.stem)
        kp_id = re.sub(r"[^0-9a-zA-Z]+", "-", path.stem.split("-")[0] + "-" + heading.split(" ")[0]).strip("-").lower()
        kps.append(
            {
                "id": kp_id,
                "chapter": path.name,
                "objective": f"{title} · {heading}",
                "excerpt": section_excerpt(text, heading_index),
            }
        )
    (INPUTS).mkdir(parents=True, exist_ok=True)
    (INPUTS / "kps.json").write_text(json.dumps(kps, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"kps: {len(kps)} 个（每章一个），写入 {INPUTS / 'kps.json'}")


def build_mcq() -> None:
    data = json.loads(digest_path().read_text(encoding="utf-8"))
    single = [q for q in data if q.get("question_type") == "single_choice"]
    rows = []
    for index, question in enumerate(single[:60]):
        rows.append(
            {
                "n": index,
                "stem": question["question"],
                "options": question["options"],
                "key": question["correct_answer"],
            }
        )
    (INPUTS).mkdir(parents=True, exist_ok=True)
    (INPUTS / "mcq60.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"mcq: {len(rows)} 道选择题，写入 {INPUTS / 'mcq60.json'}")


def build_real() -> None:
    import sqlite3

    path = chat_db_path()
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        answers = {}
        for message_id in REAL_ANSWER_IDS:
            row = connection.execute("select content from messages where id = ?", (int(message_id),)).fetchone()
            if row is None:
                sys.exit(f"库里没有 message id={message_id}")
            answers[message_id] = row[0]
    finally:
        connection.close()
    (INPUTS).mkdir(parents=True, exist_ok=True)
    (INPUTS / "real_answers.json").write_text(json.dumps(answers, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"real: {len(answers)} 条作答，写入 {INPUTS / 'real_answers.json'}（只读库拷贝 {path}）")


def build_planted() -> None:
    plan_path = INPUTS / "planted_plan.json"
    if not plan_path.exists():
        sys.exit(f"缺少 {plan_path}（植入题的题干与人工标注）")
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    chapter = textbook_dir() / plan["chapter"]
    anchor = plan["anchor"]
    text = chapter.read_text(encoding="utf-8")
    lines = text.splitlines()
    index = next((i for i, line in enumerate(lines) if anchor.lower() in line.lower()), None)
    if index is None:
        sys.exit(f"{chapter.name} 里找不到锚点 {anchor!r}")
    payload = {"excerpt": section_excerpt(text, max(0, index - 8)), "items": plan["items"]}
    (INPUTS).mkdir(parents=True, exist_ok=True)
    (INPUTS / "planted.json").write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"planted: {len(plan['items'])} 道，写入 {INPUTS / 'planted.json'}")


def check() -> None:
    required = ["kps.json", "mcq60.json", "planted.json", "real_answers.json", "real_evidence.json"]
    ok = True
    for name in required:
        path = INPUTS / name
        if not path.exists():
            print(f"缺 {path}")
            ok = False
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(data, list):
            print(f"{name}: {len(data)} 条")
        elif isinstance(data, dict) and "items" in data:
            print(f"{name}: {len(data['items'])} 条 (+excerpt {len(data.get('excerpt', ''))} 字)")
        else:
            print(f"{name}: {len(data)} 项")
    if not ok:
        sys.exit(1)


def main() -> None:
    what = sys.argv[1] if len(sys.argv) > 1 else "check"
    {"kps": build_kps, "mcq": build_mcq, "real": build_real, "planted": build_planted, "check": check}[what]()


if __name__ == "__main__":
    main()

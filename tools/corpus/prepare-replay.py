#!/usr/bin/env python3
"""Prepare local replay inputs; never connect SQLite to the live DeepTutor tree."""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sqlite3
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HOME = Path.home()
PROTECTED = HOME / "DeepTutor"
RULE_VERSION = 1
ASSERTIONS = {
    "1": ["letters_balanced_1000", "three_consecutive_correct_letters_distinct"],
    "2": ["taught_continue_yields_question_only", "concept_full_lesson_at_most_once"],
    "3": ["invalid_generation_uses_pool_and_records_fallback"],
    "4": ["visible_stream_has_no_protocol_text"],
    "5": ["every_kp_type_can_pass_with_mcq_only"],
    "6": ["lesson_before_question_or_untaught", "feedback_matches_letter_map",
          "leak_recorded_before_letter_stripping"],
    "7": ["default_group_three_setting_one_or_three"],
    "8": ["progress_first_reply_is_projection_and_matches_today"],
}


def dump(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def utc(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat()


def private_dir(path):
    path.mkdir(parents=True, exist_ok=True)
    path.chmod(0o700)
    return path


def outside(path, roots):
    resolved = path.expanduser().resolve()
    if any(resolved.is_relative_to(root.resolve()) for root in roots):
        raise ValueError("拒绝在 DeepTutor 或仓库内存放真实语料/输出")
    return resolved


def unaliased(path):
    if path.absolute() != path.resolve():
        raise ValueError("拒绝符号链接或别名路径")
    return path


def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def write(path, text):
    # Replace rather than following an existing output-file symlink.
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        os.replace(tmp, path)
    finally:
        Path(tmp).unlink(missing_ok=True)


def inventory(root):
    return {str(p.relative_to(root)): [p.lstat().st_size, p.lstat().st_mtime_ns]
            for p in sorted(root.rglob("*"))}


def idle(source):
    log = unaliased(source / "data/user/runtime/bridge.log")
    if not log.is_file():
        raise ValueError("缺少桥活动日志，无法确认十分钟空闲窗口")
    last = next((line for line in reversed(log.read_text().splitlines()) if line.strip()), "")
    moment = datetime.fromisoformat(last.split(" ", 1)[0])
    if moment.tzinfo is None:
        raise ValueError("桥日志时间缺少时区")
    now = datetime.now(timezone.utc).timestamp()
    if not (600 <= now - moment.timestamp() and 600 <= now - log.stat().st_mtime):
        raise ValueError("DeepTutor 十分钟内活跃，请在空闲窗口重试")


def open_copy(path):
    path = unaliased(path.expanduser().absolute())
    if path.resolve().is_relative_to(PROTECTED.resolve()):
        raise ValueError("禁止连接 DeepTutor 原库；先 snapshot")
    if not path.is_file():
        raise ValueError("缺语料，未运行：找不到库拷贝")
    db = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)
    try:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA query_only=ON")
        db.execute("BEGIN")
        if [tuple(r) for r in db.execute("PRAGMA integrity_check")] != [("ok",)]:
            raise ValueError("库拷贝完整性检查失败")
        return db
    except BaseException:
        db.close()
        raise


def snapshot(source, output, command):
    """Snapshot.kt practice: idle, cp db+WAL (no SHM), compare data stats, read copy."""
    source = unaliased(source.expanduser().absolute())
    output = outside(output, [PROTECTED, ROOT, source])
    if not (source / "data/user/chat_history.db").is_file():
        raise ValueError("缺语料，未运行：找不到 DeepTutor chat_history.db")
    idle(source)
    before = inventory(source / "data")
    target = Path(tempfile.mkdtemp(prefix=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-"),
                                   dir=private_dir(output / "source")))
    copied_at = datetime.now(timezone.utc).isoformat()
    try:
        hashes = {}
        for name in ("data/user/chat_history.db", "data/user/chat_history.db-wal",
                     "data/user/workspace/courses/courses.json"):
            src = unaliased(source / name)
            if src.is_file():
                dest = target / src.name
                shutil.copy2(src, dest)
                dest.chmod(0o600)
                hashes[src.name] = sha(dest)
        idle(source)
        if before != inventory(source / "data"):
            raise ValueError("源数据在拷贝期间变化；已丢弃快照")
        # VACUUM INTO the copied DB+WAL. Never connect SQLite to live DeepTutor.
        db = open_copy(target / "chat_history.db")
        try:
            db.rollback()
            db.execute("PRAGMA query_only=OFF")
            db.execute("VACUUM INTO ?", (str(target / "chat.snapshot.db"),))
        finally:
            db.close()
        with closing(open_copy(target / "chat.snapshot.db")) as check:
            check.rollback()
        if before != inventory(source / "data"):
            raise ValueError("源数据在完整性检查期间变化；已丢弃快照")
        hashes["chat.snapshot.db"] = sha(target / "chat.snapshot.db")
        write(target / "copy-stats.json", dump({"before": before, "after": inventory(source / "data"),
              "data_unchanged": True, "copied_at": copied_at, "sha256": hashes}) + "\n")
        write(target / "SOURCE.md", f"来源：{source}\n\n拷贝时间（UTC）：{copied_at}\n\n"
              f"生成命令：`{command}`\n\n复用 Snapshot.kt：十分钟空闲，先拷 db 和存在的 WAL，"
              "不拷 SHM；复制前后 data 的 size/mtime 一致；仅对副本 integrity_check=ok。"
              "chat.snapshot.db 由副本 VACUUM INTO 生成并合并副本 WAL；sha256 和逐文件 stat 见 copy-stats.json。\n")
        return target
    except BaseException:
        shutil.rmtree(target)
        raise


def parse_object(value):
    obj = json.loads(value or "{}")
    if not isinstance(obj, dict):
        raise ValueError("会话/消息元数据应为对象")
    return obj


def course_match(context, course, course_ids):
    kb = context.get("knowledgeBases", context.get("knowledge_bases", []))
    cid = context.get("courseId", context.get("course_id", ""))
    explicit = bool(kb or cid)
    matched = cid in course_ids or bool(re.search(
        r"(?<![A-Za-z0-9])" + re.escape(course) + r"(?![A-Za-z0-9])", str(cid)))
    matched |= any(bool(re.search(r"(?<![A-Za-z0-9])" + re.escape(course) + r"(?![A-Za-z0-9])", str(k)))
                   for k in kb)
    return explicit, matched


def intent(message):
    text = message["content"].strip()
    compact = re.sub(r"[\s。！？!?,，.]+", "", text).lower()
    request = parse_object(message.get("metadata_json", "{}")).get("request_snapshot", {})
    if request.get("masteryAnswer"):
        return "answer", "structured_mastery_answer"
    if re.fullmatch(r"[A-Da-d](?:[\s,，、]+[A-Da-d])*|[-+]?\d+(?:\.\d+)?%?", text):
        return "answer", "literal_choice_or_number"
    if re.search(r"学了多少|学了百分之|进度|还差多少|还剩多少|今天复习什么|复习安排|这个点过了没|分值覆盖", compact):
        return "progress", "explicit_progress_phrase"
    if re.search(r"不想.{0,5}(写|自述|复述)|只[用做答].{0,4}选择题|不要.{0,4}(自述|复述)", compact):
        return "mcq_only", "explicit_mcq_preference"
    if re.search(r"一次.{0,4}(多[道题]|[三3]道)|[三3]道一组|批量出题", compact):
        return "request_batch", "explicit_batch_request"
    if compact in {"出题", "考我", "来道题", "来一题", "再出题"}:
        return "request_question", "exact_question_command"
    if compact in {"继续", "快点", "下一步", "下一题", "往下", "继续吧"}:
        return "continue", "exact_next_step_command"
    if re.search(r"[?？]", text) or re.match(r"^(为什么|怎么|什么是|请解释|能解释)", text):
        return "question", "explicit_question_form"
    return "unknown", "no_deterministic_rule"


def load_sessions(path):
    db = open_copy(path)
    try:
        return [{"session": dict(s), "messages": [dict(m) for m in db.execute(
            "SELECT * FROM messages WHERE session_id=? ORDER BY created_at,id", (s["id"],))]}
            for s in db.execute("SELECT * FROM sessions ORDER BY created_at,id")]
    finally:
        db.rollback()
        db.close()


def prepare(sessions, course, course_ids, since):
    selected, replay = [], []
    for item in sessions:
        session, messages = item["session"], item["messages"]
        prefs = parse_object(session.get("preferences_json", "{}"))
        _, session_match = course_match(prefs, course, course_ids)
        rows = []
        for index, msg in enumerate(messages):
            if msg["role"] != "user" or msg["created_at"] < since:
                continue
            request = parse_object(msg.get("metadata_json", "{}")).get("request_snapshot", {})
            explicit, matches = course_match(request, course, course_ids)
            if not (matches if explicit else session_match):
                continue
            kind, rule = intent(msg)
            # Preserve all assistant replies within adjacent user turns, including retries.
            lo = index - 1
            while lo >= 0 and messages[lo]["role"] != "user":
                lo -= 1
            hi = index + 1
            while hi < len(messages) and messages[hi]["role"] != "user":
                hi += 1
            issues = {"continue": [2, 3, 4, 6, 7], "request_question": [1, 2, 3, 4, 6, 7],
                      "request_batch": [1, 3, 4, 6, 7], "answer": [1, 4, 5, 6],
                      "mcq_only": [5], "progress": [8], "question": [4, 6], "unknown": []}[kind]
            rows.append({"id": f"legacy:chat_history.db:messages:{msg['id']}", "session_id": session["id"],
                         "timestamp": utc(msg["created_at"]), "user": msg,
                         "assistant_before": [m for m in messages[lo + 1:index] if m["role"] == "assistant"],
                         "assistant_after": [m for m in messages[index + 1:hi] if m["role"] == "assistant"],
                         "intent": kind, "intent_rule": rule, "rule_version": RULE_VERSION,
                         "course_evidence": "request_snapshot" if explicit else "session_preferences",
                         "assertion_candidates": [a for n in issues for a in ASSERTIONS[str(n)]],
                         "requires_coach_state": True})
        if rows:
            selected.append(item)
            replay.extend(rows)
    return selected, replay


def export(args, command):
    output = outside(args.output, [PROTECTED, ROOT])
    if args.synthetic:
        source = args.synthetic.expanduser().resolve()
        sessions = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]
        source_hash = sha(source)
        provenance = f"合成样例：{source}（不是真实语料）"
        copied_at = "不适用（合成）"
        course_ids = set()
    else:
        if args.snapshot:
            source = snapshot(args.deeptutor, output, command)
        else:
            candidates = sorted((output / "source").glob("*/chat.snapshot.db"))
            if not candidates:
                raise ValueError("缺语料，未运行：先运行 --snapshot，或显式使用 --synthetic")
            source = candidates[-1].parent
        sessions = load_sessions(source / "chat.snapshot.db")
        source_hash = sha(source / "chat.snapshot.db")
        provenance = f"本机库拷贝：{source}/chat.snapshot.db"
        manifest = json.loads((source / "copy-stats.json").read_text())
        copied_at = manifest["copied_at"]
        courses_file = source / "courses.json"
        courses = json.loads(courses_file.read_text())["courses"] if courses_file.exists() else []
        course_ids = {c["id"] for c in courses if course_match(
            {"knowledge_bases": [c.get("name", "")]}, args.course, set())[1]}
    since = datetime.fromisoformat(args.since).timestamp()
    selected, replay = prepare(sessions, args.course, course_ids, since)
    private_dir(output)
    for name, rows in (("sessions.jsonl", selected), ("replay-input.jsonl", replay)):
        write(output / name, "".join(dump(row) + "\n" for row in rows))
    counts = Counter(row["intent"] for row in replay)
    summary = {"sessions": len(selected), "messages": sum(len(s["messages"]) for s in selected),
               "user_utterances": len(replay), "intents": dict(sorted(counts.items())),
               "unknown_fraction": counts["unknown"] / len(replay) if replay else 0,
               "source_sha256": source_hash, "synthetic": bool(args.synthetic)}
    write(output / "summary.json", dump(summary) + "\n")
    write(output / "SOURCE.md", f"来源：{provenance}\n\n拷贝时间（UTC）：{copied_at}\n\n"
          f"生成命令：`{command}`\n\n筛选：课程 {args.course}；用户发言 >= {args.since}。"
          "sessions.jsonl 保留入选会话的完整上下文（含截止时间前的消息和软删除标记）；"
          "replay-input.jsonl 每条包含时间、会话、相邻用户轮次之间的全部助手回复。"
          "所有输出共用本 SOURCE.md。summary.json 报计数与源 hash。"
          "assertion_candidates 仅为下游回放候选，不代表 Coach 验收通过，也不推断已讲/已通过状态。\n")
    print(dump(summary))


def export_coach(args, command):
    """Read a VACUUM INTO snapshot, never infer missing user speech from events."""
    output = outside(args.output, [PROTECTED, ROOT])
    source = unaliased(args.coach_copy.expanduser().absolute())
    if not source.is_relative_to((HOME / "Coach/corpus/replay/source").resolve()):
        raise ValueError("Coach 输入必须是 replay/source 下预先 VACUUM INTO 的副本")
    since = datetime.fromisoformat(args.since).timestamp() * 1000
    with closing(open_copy(source)) as db:
        sessions = []
        for thread in db.execute("SELECT * FROM threads ORDER BY thread_id"):
            messages = []
            for row in db.execute("SELECT * FROM messages WHERE thread_id=? ORDER BY mseq", (thread['thread_id'],)):
                message = dict(row)
                message.update(id=row['message_id'], session_id=thread['thread_id'], coach_row=dict(row),
                               content=row['content_md'] or '', created_at=(row['created_at'] or 0) / 1000)
                messages.append(message)
            sessions.append({'session': {'id': thread['thread_id'], 'preferences_json': dump({'course_id': thread['course_id']})},
                             'messages': messages})
        events = [json.loads(row['event_json']) for row in db.execute(
            "SELECT event_json FROM events WHERE occurred_at>=? ORDER BY seq", (since,))]
        selected, replay = prepare(sessions, args.course, set(), since / 1000)
        for row in replay:
            row['id'] = 'coach:messages:' + str(row['user']['id'])
            row['course_evidence'] = 'coach_thread'
        recent = [m for s in sessions for m in s['messages'] if m['created_at'] * 1000 >= since]
    private_dir(output)
    for name, rows in [('coach-sessions.jsonl', selected), ('coach-replay-input.jsonl', replay), ('coach-events.jsonl', events)]:
        write(output / name, ''.join(dump(row) + '\n' for row in rows))
    summary = {'sessions': len(selected), 'user_utterances': len(replay), 'messages_since_cutoff': len(recent),
               'events_since_cutoff': len(events), 'source_sha256': sha(source), 'synthetic': False,
               'missing_user_text': not any(m['role'] == 'user' for s in sessions for m in s['messages'])}
    write(output / 'coach-summary.json', dump(summary) + '\n')
    write(output / 'coach-SOURCE.md', f'来源：VACUUM INTO 副本 {source}\n\n生成命令：`{command}`\n\n'
          f'截止时间：{args.since}。不从状态事件补造用户发言；0 条必须报未覆盖。\n')
    print(dump(summary))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", action="store_true", help="先安全拷贝当前库，再导出")
    parser.add_argument("--deeptutor", type=Path, default=Path(os.environ.get("COACH_DEEPTUTOR_DIR", PROTECTED)))
    parser.add_argument("--output", type=Path, default=Path(os.environ.get("COACH_REPLAY_DIR", HOME / "Coach/corpus/replay")))
    parser.add_argument("--synthetic", type=Path, help="显式指定合成 JSONL，不回退冒充真实语料")
    parser.add_argument("--coach-copy", type=Path, help="导出预先 VACUUM INTO 的 Coach 副本（单独输出 coach-*）")
    parser.add_argument("--course", default="ECNM10119")
    parser.add_argument("--since", default="2026-10-23T00:00:00+01:00", help="带时区 ISO 时间；默认伦敦 10-23 零时")
    args = parser.parse_args()
    if args.snapshot and args.synthetic:
        parser.error("--snapshot 与 --synthetic 互斥")
    if datetime.fromisoformat(args.since).tzinfo is None:
        parser.error("--since 必须带时区")
    os.umask(0o077)
    try:
        command = shlex.join(["python3", "tools/corpus/prepare-replay.py", *sys.argv[1:]])
        if args.coach_copy:
            if args.snapshot or args.synthetic:
                parser.error("--coach-copy 与 --snapshot/--synthetic 互斥")
            export_coach(args, command)
        else:
            export(args, command)
    except (ValueError, OSError, sqlite3.Error, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

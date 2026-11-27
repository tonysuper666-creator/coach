#!/usr/bin/env python3
"""Compare a five-persona retest with the previous run.

Numbers and closed reason codes only. Same failure buckets and rounding as
tools/dev/version-rates.py (TON-1917). This file does not read books, tokens,
service addresses, or pairing codes.

    python tools/smoke/persona_retest_table.py --run result.json [--baseline baseline.json]
"""
import argparse
import json
import sqlite3
from datetime import date, timedelta
from pathlib import Path

REASONS = ("timeout", "quota", "refusal", "source", "other")
NAMES = {
    "timeout": "超时",
    "quota": "额度",
    "refusal": "拒答",
    "source": "出处",
    "other": "其他",
}
EMPTY = {reason: 0 for reason in ("ok",) + REASONS}
CHECKS = (
    ("countdown", "首页倒计时"),
    ("dateshift", "拨日期重排"),
    ("mock", "计时模考到点交卷"),
    ("cite", "对比表出处高亮"),
    ("offtopic", "长篇跑题不判答到"),
)


def rate(bucket):
    data = bucket or {}
    ok = int(data.get("ok") or 0)
    fail = sum(int(data.get(reason) or 0) for reason in REASONS)
    total = ok + fail
    if total == 0:
        return "n/a"
    tenths = (fail * 1000 + total // 2) // total
    return f"{tenths / 10:.1f}% ({fail}/{total})"


def add_buckets(left, right):
    out = dict(EMPTY)
    for source in (left or {}, right or {}):
        for key in out:
            out[key] += int(source.get(key) or 0)
    return out


def top_reasons(explain, ask):
    totals = []
    for index, reason in enumerate(REASONS):
        count = int((explain or {}).get(reason) or 0) + int((ask or {}).get(reason) or 0)
        if count:
            totals.append((count, index, reason))
    totals.sort(key=lambda item: (-item[0], item[1]))
    return "，".join(f"{NAMES[reason]} {count}" for count, _, reason in totals[:3]) or "-"


def cell(value):
    text = "" if value is None else str(value)
    return text.replace("|", "/").replace("\n", " ")


def previous_persona(baseline, persona_id):
    if not baseline:
        return None
    for persona in baseline.get("personas") or []:
        if persona.get("id") == persona_id:
            return persona
    return None


def step_cell(persona):
    done = int(persona.get("steps_done") or 0)
    total = int(persona.get("steps_total") or 0)
    return f"{done}/{total}"


def crash_cell(persona):
    crash = int(persona.get("crash") or 0)
    opened = 1 if persona.get("opened") else 0
    return f"{crash}/{opened}"


def render(run, baseline=None):
    lines = []
    version = cell(run.get("version") or "unknown")
    commit = cell(run.get("commit") or "")
    lines.append(f"版本 {version}" + (f"  提交 {commit}" if commit else ""))
    if baseline and baseline.get("version"):
        lines.append(f"上一版 {cell(baseline.get('version'))}")
    else:
        lines.append("上一版 无（本跑即基线）")
    lines.append("")
    lines.append("| 画像 | 身份 | 做成 | 卡在 | 讲解失败率 | 提问失败率 | 崩溃/窗口 | 上一版做成 | 上一版讲解 | 上一版提问 | 上一版崩溃 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    explain_all = dict(EMPTY)
    ask_all = dict(EMPTY)
    prev_explain = dict(EMPTY)
    prev_ask = dict(EMPTY)
    crashes = 0
    opened = 0
    prev_crashes = 0
    prev_opened = 0
    for persona in run.get("personas") or []:
        previous = previous_persona(baseline, persona.get("id"))
        explain_all = add_buckets(explain_all, persona.get("explain"))
        ask_all = add_buckets(ask_all, persona.get("ask"))
        crashes += int(persona.get("crash") or 0)
        opened += 1 if persona.get("opened") else 0
        if previous:
            prev_explain = add_buckets(prev_explain, previous.get("explain"))
            prev_ask = add_buckets(prev_ask, previous.get("ask"))
            prev_crashes += int(previous.get("crash") or 0)
            prev_opened += 1 if previous.get("opened") else 0
        stuck = persona.get("stuck") or ("—" if int(persona.get("steps_done") or 0) == int(persona.get("steps_total") or 0) else "未记")
        lines.append("| {name} | 已配置 | {done} | {stuck} | {explain} | {ask} | {crash} | {pdone} | {pexplain} | {pask} | {pcrash} |".format(
            name=cell(persona.get("name") or persona.get("id")),
            done=step_cell(persona),
            stuck=cell(stuck),
            explain=rate(persona.get("explain")),
            ask=rate(persona.get("ask")),
            crash=crash_cell(persona),
            pdone=step_cell(previous) if previous else "—",
            pexplain=rate(previous.get("explain")) if previous else "—",
            pask=rate(previous.get("ask")) if previous else "—",
            pcrash=crash_cell(previous) if previous else "—",
        ))
    lines.append("| 合计 | 已配置 |  |  | {explain} | {ask} | {crash} |  | {pexplain} | {pask} | {pcrash} |".format(
        explain=rate(explain_all),
        ask=rate(ask_all),
        crash=f"{crashes}/{opened}",
        pexplain=rate(prev_explain) if baseline else "—",
        pask=rate(prev_ask) if baseline else "—",
        pcrash=f"{prev_crashes}/{prev_opened}" if baseline else "—",
    ))
    lines.append("")
    lines.append(f"前三失败原因（本轮窗口） {top_reasons(explain_all, ask_all)}")
    if baseline:
        lines.append(f"前三失败原因（上一版） {top_reasons(prev_explain, prev_ask)}")
    lines.append("口径：讲解/提问失败率与 TON-1917 相同（超时、额度、拒答、出处、其他；四舍五入到 0.1%）。崩溃写成 次数/打开的窗口，对应崩溃次数/活跃设备天。服务端 counts.json 不在这张表里。")
    lines.append("")
    lines.append("| 检查 | 本版 | 上一版 | 卡在 |")
    lines.append("|---|---|---|---|")
    previous_checks = (baseline or {}).get("checks") or {}
    for key, label in CHECKS:
        current = (run.get("checks") or {}).get(key) or {}
        previous = previous_checks.get(key) or {}
        lines.append("| {label} | {status} | {prev} | {stuck} |".format(
            label=label,
            status=cell(current.get("status") or "未测"),
            prev=cell(previous.get("status")) if previous else "—",
            stuck=cell(current.get("stuck") or "—"),
        ))
    return "\n".join(lines) + "\n"


def set_exam_offset(db_path, days_from_today, today=None):
    """Point one course exam_date at today + days. Returns dates only, never titles."""
    today = today or date.today()
    target = (today + timedelta(days=int(days_from_today))).isoformat()
    connection = sqlite3.connect(db_path)
    try:
        rows = connection.execute(
            "SELECT id, exam_date FROM collections WHERE kind='COURSE' ORDER BY id"
        ).fetchall()
        if not rows:
            return None
        dated = [row for row in rows if row[1] and str(row[1]).strip()]
        course_id, before = dated[0] if dated else rows[0]
        connection.execute("UPDATE collections SET exam_date=? WHERE id=?", (target, course_id))
        connection.commit()
        return {"before": "" if before is None else str(before), "after": target}
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", default="")
    parser.add_argument("--baseline", default="")
    parser.add_argument("--set-exam", default="", help="library.sqlite path; prints dates only")
    parser.add_argument("--days", type=int, default=0)
    parser.add_argument("--out", default="", help="write the table as UTF-8 instead of printing it")
    args = parser.parse_args()
    if args.set_exam:
        print(json.dumps(set_exam_offset(args.set_exam, args.days), ensure_ascii=False))
        return
    if not args.run:
        parser.error("--run is required")
    run = json.loads(Path(args.run).read_text(encoding="utf-8"))
    baseline = None
    if args.baseline:
        path = Path(args.baseline)
        if path.is_file():
            baseline = json.loads(path.read_text(encoding="utf-8"))
    text = render(run, baseline)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()

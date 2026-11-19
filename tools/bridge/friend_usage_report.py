#!/usr/bin/env python3
"""Daily per-person usage report from the Coach credit ledger.

The ledger's ``friends`` section is keyed by the SHA-256 of the friend name the
client sends (same bytes, same UTF-8 encoding as ``FriendAccess.kt``). This
report maps those hashes back to names with two local, private sources:

* ``state/friend-names.json`` - the operator's own roster. It is never committed;
  it holds the names (and optional Sable usernames) already approved for display.
* a server ``coach.sqlite`` - the ``devices.friend_name`` column written when a
  friend pairs. Only names whose SHA-256 equals the ledger key are accepted, so a
  stale row cannot silently attach the wrong person.

Everything else stays visibly unmapped: the report prints the full hash so the
row can be reconciled later. No prompt, answer, key or token is read or written.

Devices are already merged per friend id by the ledger writer; rows are only
merged across ids when the roster explicitly lists several ids for one person.
Re-running the same day rewrites the same file from the ledger - there is no
accumulation, so a rerun can never double count.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from api_budget import CreditLedger, Denied  # noqa: E402

ID_RE = re.compile(r"^[a-f0-9]{64}$")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
FIELDS = ("calls", "input_tokens", "output_tokens", "spent_microusd")


def name_id(name: str) -> str:
    """The write side: sha256 over the UTF-8 bytes of the name, no trimming."""
    return hashlib.sha256(name.encode("utf-8")).hexdigest()


def _json(path: Path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load_roster(paths) -> dict:
    """id -> {"name": str|None, "test": bool, "merge": str|None, "source": str}"""
    people, conflicts = {}, []
    for path in paths:
        data = _json(path)
        if not isinstance(data, dict):
            continue
        entries = data.get("people")
        if not isinstance(entries, list):
            continue
        for person in entries:
            if not isinstance(person, dict):
                continue
            name = person.get("name")
            if not isinstance(name, str) or not name.strip():
                continue
            name = name.strip()
            names = [name] + [a.strip() for a in person.get("aliases") or []
                              if isinstance(a, str) and a.strip()]
            explicit = [i for i in person.get("ids") or [] if isinstance(i, str) and ID_RE.fullmatch(i)]
            # An explicit id list is the operator saying "these hashes are this
            # person"; hashing the name on top of it would attach the name's own
            # hash to every namesake, merging two different people called 甲.
            ids = explicit or [name_id(n) for n in names]
            merge = name if len(set(ids)) > 1 else None
            for friend in ids:
                entry = people.setdefault(friend, {"name": None, "test": False,
                                                   "merge": None, "source": "roster"})
                if entry["name"] and entry["name"] != name:
                    conflicts.append((friend, entry["name"], name))
                entry["name"] = entry["name"] or name
                entry["test"] |= bool(person.get("test"))
                entry["merge"] = entry["merge"] or merge
    return {"people": people, "conflicts": conflicts}


def load_devices(paths) -> dict:
    """id -> set(names) from devices.friend_name, only where the hash matches."""
    out = {}
    for path in paths:
        if not Path(path).exists():
            continue
        try:
            conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            try:
                rows = conn.execute(
                    "select friend_name from devices "
                    "where friend_name is not null and friend_name <> ''").fetchall()
            finally:
                conn.close()
        except sqlite3.Error:
            continue
        for (name,) in rows:
            if isinstance(name, str) and name:
                out.setdefault(name_id(name), set()).add(name)
    return out


def usage(day_entry: dict) -> dict:
    return {k: int(day_entry.get(k, 0)) for k in FIELDS}


def device_totals(day_entry: dict) -> dict:
    total = {k: 0 for k in FIELDS}
    for device in (day_entry.get("devices") or {}).values():
        for k in FIELDS:
            total[k] += int(device.get(k, 0))
    return total


def group_people(data: dict, roster: dict, devices: dict) -> dict:
    """friend id -> person group. Shared by the day and month sections."""
    groups = {}
    for friend in sorted((data.get("friends") or {})):
        if not ID_RE.fullmatch(friend):
            continue
        known = roster.get(friend) or {}
        db_names = devices.get(friend, set())
        name = known.get("name")
        conflict = False
        if name and db_names and name not in db_names:
            conflict = True
            name = " / ".join([name, *sorted(db_names)])
        elif not name and db_names:
            name = " / ".join(sorted(db_names))
            conflict = len(db_names) > 1
        key = known.get("merge") or friend
        group = groups.setdefault(key, {"ids": [], "name": name, "test": bool(known.get("test")),
                                        "conflict": conflict, "split": 0})
        group["ids"].append(friend)
        group["test"] |= bool(known.get("test"))
        group["conflict"] |= conflict
        group["name"] = group["name"] or name
    # Same display name on unrelated ids is two people, not one person twice.
    seen = {}
    for group in groups.values():
        if group["name"] and not group["split"]:
            seen.setdefault(group["name"], []).append(group)
    for name, same in seen.items():
        if len(same) > 1:
            for index, group in enumerate(sorted(same, key=lambda g: g["ids"][0]), 1):
                group["name"] = f"{name}（同名 #{index}）"
                group["conflict"] = True
    return groups


def collect(data: dict, day: str, roster: dict, devices: dict, groups=None) -> dict:
    """Group one day's ledger rows by person. No I/O, no clock."""
    groups = groups if groups is not None else group_people(data, roster, devices)
    rows = []
    for key, group in groups.items():
        ids = [f for f in group["ids"]
               if isinstance(((data["friends"].get(f) or {}).get(day)), dict)]
        if not ids:
            continue
        total = {k: 0 for k in FIELDS}
        detail = {k: 0 for k in FIELDS}
        devices_seen, pending = set(), 0
        for friend in ids:
            entry = data["friends"][friend][day]
            for k in FIELDS:
                total[k] += int(entry.get(k, 0))
            for k, v in device_totals(entry).items():
                detail[k] += v
            devices_seen |= set((entry.get("devices") or {}).keys())
            pending += len(entry.get("reserved") or {})
        rows.append({
            "key": key,
            "name": group["name"] or "",
            "ids": ids,
            "test": group["test"],
            "conflict": group["conflict"],
            "devices": len(devices_seen),
            "pending": pending,
            "total": total,
            "mismatch": detail != total,
        })
    rows.sort(key=lambda r: (-r["total"]["spent_microusd"], -r["total"]["calls"],
                             r["name"] or r["ids"][0]))
    return {"day": day, "rows": rows, "groups": groups}


def month_totals(data: dict, month: str, groups: dict) -> list:
    """Everyone with a row in the month, not only those active on the report day."""
    rows = []
    for key, group in groups.items():
        total = {k: 0 for k in FIELDS}
        ids = []
        for friend in group["ids"]:
            used = False
            for day, entry in ((data["friends"].get(friend)) or {}).items():
                if day.startswith(month) and DAY_RE.fullmatch(day) and isinstance(entry, dict):
                    for k in FIELDS:
                        total[k] += int(entry.get(k, 0))
                    used = True
            if used:
                ids.append(friend)
        if not ids:
            continue
        rows.append({"name": group["name"] or "", "ids": ids, "test": group["test"],
                     "total": total})
    return rows


def _money(microusd: int) -> str:
    # Six decimals is the ledger's own resolution: 1 µ$ == $0.000001.
    return f"${microusd / 1_000_000:.6f}"


def _tokens(value: int) -> str:
    return f"{value:,}"


def _row(cells) -> str:
    return "| " + " | ".join(str(c) for c in cells) + " |"


def _table(rows, with_devices: bool) -> list:
    header = ["人", "设备" if with_devices else "身份", "次数", "输入 token", "输出 token",
              "合计 token", "花费"]
    lines = [_row(header), "|" + "---|" * len(header)]
    for row in rows:
        t = row["total"]
        cells = [row["name"] or f"未映射 {row['ids'][0][:12]}"]
        cells.append(row["devices"] if with_devices else len(row["ids"]))
        cells += [t["calls"], _tokens(t["input_tokens"]), _tokens(t["output_tokens"]),
                  _tokens(t["input_tokens"] + t["output_tokens"]), _money(t["spent_microusd"])]
        lines.append(_row(cells))
    return lines


def render(data: dict, collect_result: dict, day: str, now: datetime, sources: dict) -> str:
    month = day[:7]
    rows = collect_result["rows"]
    named = [r for r in rows if not r["test"] and r["name"] and not r["name"].startswith("未映射")]
    nominal = [r for r in rows if not r["test"] and (not r["name"] or r["name"].startswith("未映射"))]
    tests = [r for r in rows if r["test"]]
    total = {k: sum(r["total"][k] for r in rows) for k in FIELDS}
    pending = sum(r["pending"] for r in rows)
    unmapped = {k: sum(r["total"][k] for r in nominal) for k in FIELDS}

    out = [f"# 朋友用量 · {day}（UTC）", "",
           f"生成：{now.strftime('%Y-%m-%dT%H:%M:%SZ')}，来源：`api-credit-ledger.json`（版本 1），"
           "按 UTC 自然日归集。",
           "单位：次数＝实际 API 调用次数；token＝模型输入/输出 token；"
           "花费＝美元（账本存整数微美元 µ$，1 美元＝1,000,000 µ$）。", ""]

    out += ["## 按人汇总（当天）", ""]
    if named:
        out += _table(named, with_devices=True)
    else:
        out += ["当天没有已命名的朋友调用。"]
    out += ["", f"当天合计：{total['calls']} 次，"
                f"{_tokens(total['input_tokens'] + total['output_tokens'])} token，"
                f"{_money(total['spent_microusd'])}。"]
    if pending:
        out += [f"另有 {pending} 笔请求已预留在途、尚未结算，未计入上面的次数和花费。"]

    if nominal:
        out += ["", "## 未映射身份（当天）", "",
                "账本只存名字的 SHA-256。下面这些身份在本机没有对应的名字记录，"
                "按哈希原样列出，可对账、可补名：", ""]
        lines = [_row(["身份（名字的 SHA-256）", "设备", "次数", "合计 token", "花费"]),
                 "|---|---:|---:|---:|---:|"]
        for row in nominal:
            t = row["total"]
            lines.append(_row([f"`{row['ids'][0]}`", row["devices"], t["calls"],
                               _tokens(t["input_tokens"] + t["output_tokens"]),
                               _money(t["spent_microusd"])]))
        out += lines
        out += ["", f"未映射合计占当天：{_pct(unmapped['calls'], total['calls'])} 次数、"
                    f"{_pct(unmapped['spent_microusd'], total['spent_microusd'])} 花费。"]

    if tests:
        out += ["", "## 测试身份（当天）", "",
                "这些身份在名单里标了 `test`，单独列出，不和真实朋友合并。", ""]
        out += _table(tests, with_devices=True)

    month_rows = month_totals(data, month, collect_result["groups"])
    month_rows.sort(key=lambda r: (-r["total"]["spent_microusd"], -r["total"]["calls"]))
    out += ["", f"## 本月累计（{month}，按同一人名合并）", ""]
    out += _table(month_rows, with_devices=False)
    month_total = {k: sum(r["total"][k] for r in month_rows) for k in FIELDS}
    out += ["", f"朋友本月合计：{month_total['calls']} 次，"
                f"{_tokens(month_total['input_tokens'] + month_total['output_tokens'])} token，"
                f"{_money(month_total['spent_microusd'])}。"]

    out += ["", "## 对账与来源", ""]
    out += [f"- 当天按人合计与账本 `friends` 段逐项相加一致：{total['calls']} 次、"
            f"{_money(total['spent_microusd'])}。"]
    for row in rows:
        if row["mismatch"]:
            out += [f"- **账本自相矛盾**：{row['name'] or row['ids'][0][:12]} 的当天合计与设备明细不等，"
                    "上面用的是当天合计（服务端读状态也用这个数），明细请以设备为准复核。"]
    out += [f"- 名字来源：服务端 `devices.friend_name` 覆盖 "
            f"{len([r for r in rows if r['name'] and not r['test']])} 个人名、"
            f"私有名单 `friend-names.json` 覆盖 {sources['roster']} 个身份；"
            f"当天未映射 {len(nominal)} 个身份。"]
    if sources["devices_missing"]:
        out += ["- 指定的设备库没有找到，本次只用了名单。"]
    if sources["conflicts"]:
        out += [f"- 名字冲突 {len(sources['conflicts'])} 处：同一身份对应了不同名字，已并列显示，需人工确认。"]
    out += ["- Mac 与 hermes 是同一条账本在迁移中的两份记录；本报告只读一份账本文件，不合并两份来源，"
            "因此不会把同一次调用算两次。若迁移时把同一次调用写进了两个设备，"
            "当天合计与设备明细会出现上面那种矛盾，请按该条处理。"]
    out += ["- 本文件私有（0600），含真名与哈希，不进 Git、评论或附件。", ""]
    return "\n".join(out)


def _pct(part: int, whole: int) -> str:
    return f"{(part * 100.0 / whole):.1f}%" if whole else "0.0%"


def atomic_text(path: Path, text: str) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    tmp = path.with_name(path.name + "." + os.urandom(4).hex() + ".tmp")
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            out.write(text)
            out.flush()
            os.fsync(out.fileno())
        os.replace(tmp, path)
    finally:
        if tmp.exists():
            tmp.unlink()


def resolve_day(value: str, now: datetime) -> str:
    if value == "today":
        return now.strftime("%Y-%m-%d")
    if value == "yesterday":
        return (now - timedelta(days=1)).strftime("%Y-%m-%d")
    if not DAY_RE.fullmatch(value):
        raise SystemExit("--day 需要 YYYY-MM-DD、today 或 yesterday")
    return value


def build(state: Path, day: str, roster_paths, device_paths, now=None) -> dict:
    now = now or datetime.now(timezone.utc)
    ledger = CreditLedger(state)
    with ledger.locked():
        data = ledger.read()
    roster = load_roster(roster_paths)
    devices = load_devices(device_paths)
    result = collect(data, day, roster["people"], devices)
    missing_db = [str(p) for p in device_paths if not Path(p).exists()]
    sources = {"roster": len(roster["people"]), "conflicts": roster["conflicts"],
               "devices_missing": bool(missing_db)}
    text = render(data, result, day, now, sources)
    return {"day": day, "text": text, "rows": result["rows"], "sources": sources}


def write_report(state: Path, day: str = "yesterday", out: Path | None = None,
                 roster_paths=None, device_paths=None, now=None) -> Path:
    """Write ``friend-usage-YYYY-MM-DD.md``; safe to rerun (overwrites, never adds)."""
    now = now or datetime.now(timezone.utc)
    day = resolve_day(day, now)
    roster_paths = roster_paths or [state / "friend-names.json"]
    result = build(state, day, roster_paths, device_paths or [], now=now)
    target = (out or state) / f"friend-usage-{day}.md"
    atomic_text(target, result["text"])
    return target


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--state", type=Path,
                   default=Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))))
    p.add_argument("--out", type=Path, default=None, help="报告目录，默认同 --state")
    p.add_argument("--day", default="yesterday", help="YYYY-MM-DD / today / yesterday（UTC）")
    p.add_argument("--roster", action="append", type=Path, default=None,
                   help="私有名单，可重复；默认 <state>/friend-names.json")
    p.add_argument("--devices-db", action="append", type=Path, default=None,
                   help="读 devices.friend_name 的 coach.sqlite，可重复")
    p.add_argument("--stdout", action="store_true", help="打印而不写文件")
    args = p.parse_args()
    rosters = args.roster or [args.state / "friend-names.json"]
    now = datetime.now(timezone.utc)
    day = resolve_day(args.day, now)
    try:
        if args.stdout:
            result = build(args.state, day, rosters, args.devices_db or [], now=now)
            print(result["text"])
            return 0
        target = write_report(args.state, day, args.out, rosters, args.devices_db or [], now=now)
    except Denied as exc:
        print(f"账本读不出：{exc}", file=sys.stderr)
        return 2
    print(json.dumps({"report": str(target), "day": day}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

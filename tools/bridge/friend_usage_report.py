#!/usr/bin/env python3
"""Daily per-person usage report from the Coach credit ledger.

The ledger's ``friends`` section is keyed by the SHA-256 of the friend name the
client sends (same bytes, same UTF-8 encoding as ``FriendAccess.kt``). This
report maps those hashes back to names with two local, private sources:

* ``state/friend-names.json`` - the operator's own roster. It is never committed;
  it holds the names (and optional Sable usernames) already approved for display.
* a server ``coach.sqlite`` - ``devices.friend_name`` for legacy pairing, and
  ``sable_friends(friend_name, username)`` for Sable logins (TON-1740). Only those
  columns are read; the sealed-token table is never touched. A cleared username
  keeps a ``sable-<hash prefix>`` label so old calls never move to a new account.

Everything else stays visibly unmapped: the report prints the full hash so the
row can be reconciled later. No prompt, answer, key or token is read or written.

Devices are already merged per friend id by the ledger writer; rows are only
merged across ids when the roster explicitly lists several ids for one person.
Re-running the same day rewrites the same file from the ledger - there is no
accumulation, so a rerun can never double count. ``--ledger LABEL=PATH`` takes
another machine's ledger. Cumulative counters alone cannot tell an independent
ledger from a copy or a migration fork, so the file itself must say which it is:

* ``"independent": true`` plus a ``source_id`` of its own - a separate instance;
  its settled counts are added, per (source_id, day), so a repeated delivery of
  the same source is counted once and two sources are never max-merged (max
  would drop calls when one person used both instances).
* the same ``source_id`` as the local ledger - a copy/snapshot; field-wise max,
  so a stale copy can never inflate the total.
* no declaration - kept in its own section and left out of the totals, with the
  gap stated, because the relation is unknown.

A source that shares a migration baseline with the local ledger can declare
``inherited_through: YYYY-MM-DD``; days up to and including that date came from
the other side and are not counted again, only its own later days are added.
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
# Same prefix as server/store LedgerStore.sableFriendName (TON-1740). The device
# column carries this internal string for Sable logins; it is not a person's name.
SABLE_PREFIX = "sable.identity.v1:"


def name_id(name: str) -> str:
    """The write side: sha256 over the UTF-8 bytes of the name, no trimming."""
    return hashlib.sha256(name.encode("utf-8")).hexdigest()


def _json(path: Path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load_roster(paths) -> dict:
    """id -> {"name", "test", "person", "source"}

    ``person`` identifies the roster entry, not the display name: two people may
    legitimately be called 甲, and each may have several ids. Grouping on the
    name would merge them, so the group key is the entry itself.
    """
    people, conflicts = {}, []
    for path in paths:
        data = _json(path)
        if not isinstance(data, dict):
            continue
        entries = data.get("people")
        if not isinstance(entries, list):
            continue
        for index, person in enumerate(entries):
            if not isinstance(person, dict):
                continue
            name = person.get("name")
            if not isinstance(name, str) or not name.strip():
                continue
            name = name.strip()
            person_key = f"{path}#{index}"
            names = [name] + [a.strip() for a in person.get("aliases") or []
                              if isinstance(a, str) and a.strip()]
            explicit = [i for i in person.get("ids") or [] if isinstance(i, str) and ID_RE.fullmatch(i)]
            # An explicit id list is the operator saying "these hashes are this
            # person"; hashing the name on top of it would attach the name's own
            # hash to every namesake, merging two different people called 甲.
            ids = explicit or [name_id(n) for n in names]
            for friend in ids:
                entry = people.setdefault(friend, {"name": None, "test": False,
                                                   "person": person_key,
                                                   "source": "roster"})
                if entry["name"] and entry["name"] != name:
                    conflicts.append((friend, entry["name"], name))
                entry["name"] = entry["name"] or name
                entry["test"] |= bool(person.get("test"))
    return {"people": people, "conflicts": conflicts}


def sable_label(fid: str) -> str:
    """A cleared or unclaimed Sable username still needs a reconcilable label."""
    return f"sable-{fid[:8]}"


def load_devices(paths):
    """id -> set(labels), plus how many Sable identities were seen.

    Legacy pairing writes the name into ``devices.friend_name``. A Sable login
    (TON-1740) writes ``sableFriendName(sub)`` there instead and keeps the real
    username in ``sable_friends(friend_name, username)``. So when that table
    exists its two columns are authoritative for Sable identities; the device
    column is only used for those without a row. ``sable_grants`` holds sealed
    tokens and is never read.

    A username can be emptied when the same handle is re-registered to another
    Sable account. The old row keeps its ``friend_name``, so its history stays
    labelled ``sable-<hash prefix>`` instead of moving to the new account.
    """
    names, stats = {}, {"sable": 0, "cleared": 0}
    for path in paths:
        if not Path(path).exists():
            continue
        try:
            conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            try:
                tables = {row[0] for row in conn.execute(
                    "select name from sqlite_master where type='table'")}
                legacy = []
                if "devices" in tables:
                    legacy = conn.execute(
                        "select friend_name from devices "
                        "where friend_name is not null and friend_name <> ''").fetchall()
                sable = []
                if "sable_friends" in tables:
                    sable = conn.execute(
                        "select friend_name, username from sable_friends").fetchall()
            finally:
                conn.close()
        except sqlite3.Error:
            continue
        for (friend,) in legacy:
            if not isinstance(friend, str) or not friend:
                continue
            fid = name_id(friend)
            # Never print the internal identity as if it were a person.
            shown = sable_label(fid) if friend.startswith(SABLE_PREFIX) else friend
            names.setdefault(fid, set()).add(shown)
        for friend, username in sable:
            if not isinstance(friend, str) or not friend:
                continue
            fid = name_id(friend)
            shown = username.strip() if isinstance(username, str) else ""
            names[fid] = {shown or sable_label(fid)}
            stats["sable"] += 1
            stats["cleared"] += 0 if shown else 1
    return names, stats


def usage(day_entry: dict) -> dict:
    return {k: int(day_entry.get(k, 0)) for k in FIELDS}


def device_totals(day_entry: dict) -> dict:
    total = {k: 0 for k in FIELDS}
    for device in (day_entry.get("devices") or {}).values():
        for k in FIELDS:
            total[k] += int(device.get(k, 0))
    return total


def _entry_max(a: dict, b: dict) -> dict:
    """Two copies of one source: same day, keep the larger value per field."""
    out = dict(a)
    for k in FIELDS:
        out[k] = max(int(a.get(k, 0)), int(b.get(k, 0)))
    devices = {}
    for src in (a, b):
        for name, dev in (src.get("devices") or {}).items():
            old = devices.get(name)
            devices[name] = ({k: max(int(old.get(k, 0)), int(dev.get(k, 0))) for k in FIELDS}
                             if old else dev)
    if devices or "devices" in a or "devices" in b:
        out["devices"] = devices
    reserved = dict(a.get("reserved") or {})
    for k, v in (b.get("reserved") or {}).items():
        reserved.setdefault(k, v)
    if reserved or "reserved" in a or "reserved" in b:
        out["reserved"] = reserved
    return out


def _entry_add(a: dict, b: dict) -> dict:
    """Two confirmed independent sources: the same person/device may use both."""
    out = {k: int(a.get(k, 0)) + int(b.get(k, 0)) for k in FIELDS}
    devices = {}
    for src in (a, b):
        for name, dev in (src.get("devices") or {}).items():
            old = devices.get(name) or {k: 0 for k in FIELDS}
            devices[name] = {k: int(old.get(k, 0)) + int(dev.get(k, 0)) for k in FIELDS}
    if devices:
        out["devices"] = devices
    reserved = dict(a.get("reserved") or {})
    reserved.update(b.get("reserved") or {})
    if reserved:
        out["reserved"] = reserved
    return out


def _bill_add(a: dict, b: dict) -> dict:
    """Provider month totals from two independent instances add up."""
    out = dict(a)
    for k, v in b.items():
        old = out.get(k)
        out[k] = old + v if isinstance(old, int) and isinstance(v, int) else v
    return out


def _source_id(payload: dict, fallback: str) -> str:
    sid = payload.get("source_id")
    if isinstance(sid, str) and sid.strip():
        return sid.strip()
    return fallback


def _is_independent(payload: dict) -> bool:
    return payload.get("independent") is True


def _inherited_through(copies) -> str | None:
    days = [c.get("inherited_through") for c in copies
            if isinstance(c.get("inherited_through"), str)
            and DAY_RE.fullmatch(c.get("inherited_through", ""))]
    return max(days) if days else None


def _lineage_days(copies) -> dict:
    """One source, several deliveries: field-wise max per (friend, day)."""
    merged = {}
    for payload in copies:
        for fid, days in (payload.get("friends") or {}).items():
            if not ID_RE.fullmatch(fid) or not isinstance(days, dict):
                continue
            for day, entry_ in days.items():
                if not DAY_RE.fullmatch(day) or not isinstance(entry_, dict):
                    continue
                old = merged.setdefault(fid, {}).get(day)
                merged[fid][day] = _entry_max(old, entry_) if old else entry_
    return merged


def _lineage_months(copies) -> dict:
    merged = {}
    for payload in copies:
        for month, providers in (payload.get("months") or {}).items():
            if not isinstance(providers, dict):
                continue
            for provider, bill in providers.items():
                if not isinstance(bill, dict):
                    continue
                old = merged.setdefault(month, {}).get(provider)
                if old:
                    combined = dict(old)
                    for k, v in bill.items():
                        a, b = old.get(k), v
                        combined[k] = (max(int(a), int(b))
                                       if isinstance(a, int) and isinstance(b, int) else b)
                    merged[month][provider] = combined
                else:
                    merged[month][provider] = bill
    return merged


def merge_ledgers(base: dict, extras, day: str):
    """Combine the local ledger with ``--ledger`` files, by source relation.

    Returns ``(data, info)``. Only sources that declare themselves independent
    are added; copies of the local source are max-merged into it; anything
    undeclared stays out (``info["separate"]``) so the report can list it with
    the gap instead of guessing.
    """
    base_sid = _source_id(base, "local")
    lineages = {base_sid: {"label": "本机", "copies": [base]}}
    separate = []
    for label, payload in extras:
        declared = isinstance(payload.get("source_id"), str) and payload["source_id"].strip()
        sid = _source_id(payload, label)
        if _is_independent(payload) and declared and sid == base_sid:
            lineages[base_sid]["copies"].append(payload)   # same source, fresh copy
        elif _is_independent(payload) and declared:
            lineages.setdefault(sid, {"label": label, "copies": []})["copies"].append(payload)
        else:
            separate.append((label, payload))

    friends, months = {}, {}
    merged, inherited = [], []
    for sid, lineage in lineages.items():
        through = _inherited_through(lineage["copies"])
        dropped, calls, spent = 0, 0, 0
        for fid, days in _lineage_days(lineage["copies"]).items():
            for when, entry_ in days.items():
                if through and when <= through:
                    dropped += 1
                    continue
                tagged = dict(entry_)
                tagged["reserved"] = {f"{sid}:{k}": v
                                      for k, v in (entry_.get("reserved") or {}).items()}
                current = friends.setdefault(fid, {}).get(when)
                friends[fid][when] = _entry_add(current, tagged) if current else tagged
                if when == day:
                    calls += int(entry_.get("calls", 0))
                    spent += int(entry_.get("spent_microusd", 0))
        for month, providers in _lineage_months(lineage["copies"]).items():
            target = months.setdefault(month, {})
            for provider, bill in providers.items():
                target[provider] = _bill_add(target[provider], bill) if provider in target else dict(bill)
        if sid != base_sid:
            merged.append({"source_id": sid, "label": lineage["label"],
                           "calls": calls, "spent_microusd": spent})
        if dropped:
            inherited.append({"source_id": sid, "through": through, "days": dropped})

    data = dict(base)
    data["friends"] = friends
    data["months"] = months
    data["source_id"] = base_sid
    return data, {"merged": merged, "inherited": inherited, "separate": separate}


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
        key = known.get("person") or friend
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

    merged_names = [m["source_id"] for m in sources.get("merged_sources") or []]
    out = [f"# 朋友用量 · {day}（UTC）", "",
           f"生成：{now.strftime('%Y-%m-%dT%H:%M:%SZ')}，来源：`api-credit-ledger.json`（版本 1）"
           + (f" + 独立来源 {', '.join(f'`{n}`' for n in merged_names)}" if merged_names else "")
           + "，按 UTC 自然日归集。",
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
    # Test identities are visible as such in the month table too, not folded in.
    for row in month_rows:
        if row["test"]:
            row["name"] = f"{row['name']}（测试）" if row["name"] else "（测试身份）"
    out += ["", f"## 本月累计（{month}，按同一人合并）", ""]
    out += _table(month_rows, with_devices=False)
    friends_month = {k: sum(r["total"][k] for r in month_rows if not r["test"]) for k in FIELDS}
    tests_month = {k: sum(r["total"][k] for r in month_rows if r["test"]) for k in FIELDS}
    out += ["", f"朋友本月合计：{friends_month['calls']} 次，"
                f"{_tokens(friends_month['input_tokens'] + friends_month['output_tokens'])} token，"
                f"{_money(friends_month['spent_microusd'])}。"]
    if tests_month["calls"]:
        out += [f"测试本月合计：{tests_month['calls']} 次，"
                f"{_tokens(tests_month['input_tokens'] + tests_month['output_tokens'])} token，"
                f"{_money(tests_month['spent_microusd'])}（单独列出，不计入上面的朋友合计）。"]

    for label, other in sources.get("extra_results") or []:
        extra_rows = other["rows"]
        extra_total = {k: sum(r["total"][k] for r in extra_rows) for k in FIELDS}
        out += ["", f"## 另一来源 `{label}`（当天，单独列出，未计入上面的合计）", ""]
        if extra_rows:
            out += _table(extra_rows, with_devices=True)
        else:
            out += ["这份账本当天没有调用。"]
        out += ["", f"`{label}` 当天：{extra_total['calls']} 次，"
                    f"{_tokens(extra_total['input_tokens'] + extra_total['output_tokens'])} token，"
                    f"{_money(extra_total['spent_microusd'])}。",
                "缺口：这份账本和本机账本是否独立（还是副本、迁移分叉）尚未确认，"
                "所以不和本机相加，也没有给出两边合计。"]

    out += ["", "## 对账与来源", ""]
    out += [f"- 当天按人合计与账本 `friends` 段逐项相加一致：{total['calls']} 次、"
            f"{_money(total['spent_microusd'])}。"]
    provider = (data.get("months") or {}).get(month) or {}
    bills = [v for v in provider.values() if isinstance(v, dict)] if isinstance(provider, dict) else []
    if bills:
        pcalls = sum(int(v.get("calls", 0)) for v in bills)
        pmoney = sum(int(v.get("spent_microusd", 0)) for v in bills)
        out += [f"- 供应商月度汇总（{len(bills)} 个来源）：{pcalls} 次、{_money(pmoney)}；"
                f"减去朋友本月合计后差 {pcalls - friends_month['calls']} 次、"
                f"{_money(pmoney - friends_month['spent_microusd'])} 不在 `friends` 段，"
                "归属未核实（多为维护者/内部调用），本报告不猜。"]
    for row in rows:
        if row["mismatch"]:
            out += [f"- **账本自相矛盾**：{row['name'] or row['ids'][0][:12]} 的当天合计与设备明细不等，"
                    "上面用的是当天合计（服务端读状态也用这个数），明细请以设备为准复核。"]
    out += [f"- 名字来源：配对名 `devices.friend_name` 与 Sable 的 "
            f"`sable_friends(friend_name, username)` 覆盖 "
            f"{len([r for r in rows if r['name'] and not r['test']])} 个人名、"
            f"私有名单 `friend-names.json` 覆盖 {sources['roster']} 个身份；"
            f"当天未映射 {len(nominal)} 个身份。"]
    if sources.get("sable"):
        out += [f"- Sable 身份 {sources['sable']} 个：用 `friend_name` 算 SHA-256 对账、"
                f"用 `username` 显示；其中 {sources['sable_cleared']} 个用户名为空"
                "（换绑、重注册或清理），显示为 `sable-<哈希前 8 位>`，历史仍归原身份，"
                "不会并到后来接手该用户名的新账号。"]
    if sources["devices_missing"]:
        out += ["- 指定的设备库没有找到，本次只用了名单。"]
    if sources["conflicts"]:
        out += [f"- 名字冲突 {len(sources['conflicts'])} 处：同一身份对应了不同名字，已并列显示，需人工确认。"]
    if sources.get("merged_sources"):
        parts = [f"`{m['source_id']}`（当天 {m['calls']} 次、{_money(m['spent_microusd'])}）"
                 for m in sources["merged_sources"]]
        out += [f"- 已并入独立来源：{'、'.join(parts)}。依据：文件自带 `source_id` 与 "
                "`independent: true`，是与本机账本分开的实例；重复投递按（来源, 日）只算一次，"
                "两个人各自用过两个实例时按来源相加，不用最大值压掉其中一份。"]
    if sources.get("inherited"):
        for item in sources["inherited"]:
            out += [f"- `{item['source_id']}` 声明继承基线截至 {item['through']}："
                    f"该日及以前的 {item['days']} 条记录来自共同历史，只算一次，不重复累计；"
                    "之后的天数按独立来源相加。"]
    if sources.get("ledgers"):
        out += [f"- 另有 {len(sources['ledgers'])} 份账本来源关系未声明，单独列出、未并入合计："
                f"{', '.join(sources['ledgers'])}。"]
    if sources.get("ledgers_missing"):
        out += [f"- 有 {len(sources['ledgers_missing'])} 份账本尚未收到，本次未计入："
                f"{', '.join(sources['ledgers_missing'])}。"]
    if sources.get("ledgers_unreadable"):
        out += [f"- 有 {len(sources['ledgers_unreadable'])} 份账本读不出，未计入："
                f"{', '.join(sources['ledgers_unreadable'])}。"]
    basis = ("本机 `api-credit-ledger.json` 与上面已确认的独立来源" if merged_names else
             "本机 `api-credit-ledger.json`")
    out += [f"- 上面的合计来自{basis}。备份、回滚、staging 里的文件是快照，"
            "不是独立来源，不要当 `--ledger` 传入。"]
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


def build(state: Path, day: str, roster_paths, device_paths, now=None, extras=None) -> dict:
    now = now or datetime.now(timezone.utc)
    ledger = CreditLedger(state)
    with ledger.locked():
        data = ledger.read()
    loaded, unreadable, missing = [], [], []
    for label, path in extras or []:
        if not Path(path).exists():
            missing.append(label)
            continue
        payload = _json(path)
        if isinstance(payload, dict) and isinstance(payload.get("friends"), dict):
            loaded.append((label, payload))
        else:
            unreadable.append(label)
    roster = load_roster(roster_paths)
    devices, stats = load_devices(device_paths)
    data, merge_info = merge_ledgers(data, loaded, day)
    result = collect(data, day, roster["people"], devices)
    extra_results = [(label, collect(payload, day, roster["people"], devices))
                     for label, payload in merge_info["separate"]]
    missing_db = [str(p) for p in device_paths if not Path(p).exists()]
    sources = {"roster": len(roster["people"]), "conflicts": roster["conflicts"],
               "devices_missing": bool(missing_db),
               "sable": stats["sable"], "sable_cleared": stats["cleared"],
               "ledgers": [label for label, _ in merge_info["separate"]],
               "ledgers_unreadable": unreadable, "ledgers_missing": missing,
               "merged_sources": merge_info["merged"], "inherited": merge_info["inherited"],
               "extra_results": extra_results}
    text = render(data, result, day, now, sources)
    return {"day": day, "text": text, "rows": result["rows"], "sources": sources}


def write_report(state: Path, day: str = "yesterday", out: Path | None = None,
                 roster_paths=None, device_paths=None, now=None, extras=None) -> Path:
    """Write ``friend-usage-YYYY-MM-DD.md``; safe to rerun (overwrites, never adds)."""
    now = now or datetime.now(timezone.utc)
    day = resolve_day(day, now)
    roster_paths = roster_paths or [state / "friend-names.json"]
    result = build(state, day, roster_paths, device_paths or [], now=now, extras=extras)
    target = (out or state) / f"friend-usage-{day}.md"
    atomic_text(target, result["text"])
    return target


def parse_ledger(value: str):
    """``LABEL=PATH``; a bare path is labelled by its file name."""
    label, sep, path = value.partition("=")
    if not sep:
        return Path(value).name, Path(value)
    if not label.strip() or not path.strip():
        raise SystemExit("--ledger 需要 LABEL=PATH 或 PATH")
    return label.strip(), Path(path.strip())


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--state", type=Path,
                   default=Path(os.environ.get("COACH_API_STATE_DIR", str(Path.home() / "Coach/state"))))
    p.add_argument("--out", type=Path, default=None, help="报告目录，默认同 --state")
    p.add_argument("--day", default="yesterday", help="YYYY-MM-DD / today / yesterday（UTC）")
    p.add_argument("--roster", action="append", type=Path, default=None,
                   help="追加私有名单，可重复；<state>/friend-names.json 始终会读")
    p.add_argument("--devices-db", action="append", type=Path, default=None,
                   help="读 devices.friend_name / sable_friends 的 coach.sqlite，可重复")
    p.add_argument("--ledger", action="append", default=None, metavar="LABEL=PATH",
                   help="另一台机器的账本，可重复；文件自带 source_id + independent=true 才并入合计")
    p.add_argument("--stdout", action="store_true", help="打印而不写文件")
    args = p.parse_args()
    rosters = [args.state / "friend-names.json"]
    for extra_roster in args.roster or []:
        if extra_roster not in rosters:
            rosters.append(extra_roster)
    extras = [parse_ledger(v) for v in args.ledger or []]
    now = datetime.now(timezone.utc)
    day = resolve_day(args.day, now)
    try:
        if args.stdout:
            result = build(args.state, day, rosters, args.devices_db or [], now=now,
                           extras=extras)
            print(result["text"])
            return 0
        target = write_report(args.state, day, args.out, rosters, args.devices_db or [],
                              now=now, extras=extras)
    except Denied as exc:
        print(f"账本读不出：{exc}", file=sys.stderr)
        return 2
    print(json.dumps({"report": str(target), "day": day}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

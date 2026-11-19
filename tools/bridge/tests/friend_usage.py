"""Daily per-person report: names, device merge, dates, totals, rerun, gaps."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from api_budget import Denied, atomic_json  # noqa: E402
from friend_usage_report import ID_RE, SABLE_PREFIX, build, name_id, write_report  # noqa: E402

NOW = datetime(2026, 11, 20, 0, 10, tzinfo=timezone.utc)
A, B, C = name_id("甲"), name_id("乙"), name_id("丙")


def entry(calls, inp, out, spent, devices=None, reserved=None):
    return {"calls": calls, "input_tokens": inp, "output_tokens": out,
            "spent_microusd": spent, "reserved": reserved or {},
            "devices": devices or {}}


def dev(calls, inp, out, spent):
    return {"calls": calls, "input_tokens": inp, "output_tokens": out,
            "spent_microusd": spent}


def ledger(friends):
    return {"version": 1, "cycles": {}, "months": {}, "friends": friends}


class FriendUsageReportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.state = Path(self.tmp.name)
        self.roster = self.state / "friend-names.json"

    def tearDown(self):
        self.tmp.cleanup()

    def save(self, data):
        atomic_json(self.state / "api-credit-ledger.json", data)

    def roster_of(self, people):
        atomic_json(self.roster, {"version": 1, "people": people})

    def test_name_hash_matches_write_side(self):
        import hashlib
        self.assertEqual(A, hashlib.sha256("甲".encode("utf-8")).hexdigest())
        self.assertTrue(ID_RE.fullmatch(A))

    def test_merges_devices_of_one_person(self):
        self.save(ledger({A: {"2026-11-19": entry(
            3, 900, 120, 2500,
            devices={"pc": dev(2, 600, 80, 1600), "phone": dev(1, 300, 40, 900)})}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        row = out["rows"][0]
        self.assertEqual(row["total"]["calls"], 3)
        self.assertEqual(row["total"]["input_tokens"] + row["total"]["output_tokens"], 1020)
        self.assertEqual(row["devices"], 2)
        self.assertFalse(row["mismatch"])
        self.assertIn("| 3 | 900 | 120 | 1,020 | $0.002500 |", out["text"])

    def test_roster_name_alias_and_device_name(self):
        self.roster_of([{"name": "甲", "aliases": ["Jia"]}])
        self.save(ledger({
            name_id("Jia"): {"2026-11-19": entry(1, 10, 2, 500)},
            B: {"2026-11-19": entry(2, 20, 4, 700)},
        }))
        db = self.state / "coach.sqlite"
        import sqlite3
        conn = sqlite3.connect(db)
        conn.execute("create table devices (device_id text, friend_name text)")
        conn.executemany("insert into devices values (?,?)", [("d1", "乙"), ("d2", "老数据")])
        conn.commit()
        conn.close()
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        names = {row["name"] for row in out["rows"]}
        self.assertIn("甲", names)
        self.assertIn("乙", names)
        self.assertEqual(len(out["rows"]), 2)

    def test_explicit_ids_merge_two_friend_hashes(self):
        self.roster_of([{"name": "甲", "ids": [A, C]}])
        self.save(ledger({A: {"2026-11-19": entry(1, 10, 1, 100)},
                          C: {"2026-11-19": entry(2, 20, 2, 200)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertEqual(len(out["rows"]), 1)
        self.assertEqual(out["rows"][0]["total"]["calls"], 3)

    def test_same_display_name_is_split_not_merged(self):
        self.roster_of([{"name": "甲", "ids": [A]}, {"name": "甲", "ids": [C]}])
        self.save(ledger({A: {"2026-11-19": entry(1, 10, 1, 100)},
                          C: {"2026-11-19": entry(2, 20, 2, 200)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertEqual(len(out["rows"]), 2)
        self.assertIn("同名 #1", out["text"])
        self.assertIn("同名 #2", out["text"])

    def test_unmapped_identity_keeps_hash_and_is_counted(self):
        self.roster_of([{"name": "甲"}])
        self.save(ledger({A: {"2026-11-19": entry(1, 10, 1, 100)},
                          B: {"2026-11-19": entry(3, 30, 3, 900)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertIn(f"`{B}`", out["text"])
        self.assertIn("未映射合计占当天：75.0% 次数、90.0% 花费", out["text"])

    def test_days_are_separate_and_month_sums_both(self):
        self.save(ledger({A: {"2026-11-18": entry(1, 10, 1, 100),
                              "2026-11-19": entry(2, 20, 2, 200)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        day_total = out["rows"][0]["total"]
        self.assertEqual(day_total["calls"], 2)
        self.assertIn("朋友本月合计：3 次，33 token，$0.000300。", out["text"])

    def test_month_includes_people_idle_on_the_report_day(self):
        self.roster_of([{"name": "甲"}])
        self.save(ledger({A: {"2026-11-18": entry(1, 10, 1, 100)},
                          B: {"2026-11-19": entry(3, 30, 3, 300)}}))
        out = build(self.state, "2026-11-18", [self.roster], [], now=NOW)
        self.assertEqual(len(out["rows"]), 1)  # B has no row on the reported day
        self.assertIn("当天合计：1 次，11 token，$0.000100。", out["text"])
        self.assertIn("朋友本月合计：4 次，44 token，$0.000400。", out["text"])

    def test_rerun_overwrites_without_double_counting(self):
        self.save(ledger({A: {"2026-11-19": entry(2, 20, 2, 200)}}))
        first = write_report(self.state, "2026-11-19", now=NOW)
        body = "\n".join(l for l in first.read_text(encoding="utf-8").splitlines()
                         if not l.startswith("生成："))
        second = write_report(self.state, "2026-11-19", now=NOW)
        again = "\n".join(l for l in second.read_text(encoding="utf-8").splitlines()
                          if not l.startswith("生成："))
        self.assertEqual(first, second)
        self.assertEqual(body, again)
        self.assertIn("当天合计：2 次", again)
        if os.name == "posix":
            self.assertEqual(first.stat().st_mode & 0o777, 0o600)

    def test_device_detail_mismatch_is_flagged(self):
        self.save(ledger({A: {"2026-11-19": entry(
            2, 20, 2, 200, devices={"pc": dev(1, 10, 1, 100)})}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertTrue(out["rows"][0]["mismatch"])
        self.assertIn("账本自相矛盾", out["text"])

    def test_test_identity_stays_separate(self):
        self.roster_of([{"name": "甲", "test": True}])
        self.save(ledger({A: {"2026-11-19": entry(5, 50, 5, 500)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertIn("## 测试身份", out["text"])
        self.assertIn("当天没有已命名的朋友调用。", out["text"])

    def test_pending_reservations_are_reported_not_counted(self):
        self.save(ledger({A: {"2026-11-19": entry(
            1, 10, 1, 100, reserved={"t" * 32: {"tokens": 100, "device": "pc", "microusd": 50}})}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertIn("另有 1 笔请求已预留在途", out["text"])
        self.assertEqual(out["rows"][0]["total"]["calls"], 1)

    def test_malformed_ledger_is_refused(self):
        (self.state / "api-credit-ledger.json").write_text("{not json")
        with self.assertRaises(Denied):
            build(self.state, "2026-11-19", [self.roster], [], now=NOW)

    def test_report_never_mentions_raw_ledger_paths_of_other_hosts(self):
        self.save(ledger({A: {"2026-11-19": entry(1, 1, 1, 1)}}))
        out = build(self.state, "2026-11-19", [self.roster], [], now=NOW)
        self.assertNotIn("Mac/state", out["text"])
        self.assertIn("不进 Git、评论或附件", out["text"])

    # --- Sable identities (TON-1740): sable_friends(friend_name, username) ---

    def make_db(self, devices=(), sable=(), grants=(), sable_table=True):
        import sqlite3
        db = self.state / "coach.sqlite"
        conn = sqlite3.connect(db)
        conn.execute("create table devices (device_id text, friend_name text)")
        if sable_table:
            conn.execute("create table sable_friends (friend_name text primary key, "
                         "username text not null, disabled integer not null default 0)")
        conn.execute("create table sable_grants (device_id text, sealed_token blob)")
        conn.executemany("insert into devices values (?,?)", list(devices))
        if sable_table:
            conn.executemany("insert into sable_friends values (?,?,0)", list(sable))
        conn.executemany("insert into sable_grants values (?,?)", list(grants))
        conn.commit()
        conn.close()
        return db

    @staticmethod
    def sable_id(tag):
        return SABLE_PREFIX + name_id(tag)

    def test_sable_username_is_shown_instead_of_the_internal_identity(self):
        friend = self.sable_id("sable-subject-1")
        fid = name_id(friend)
        self.save(ledger({fid: {"2026-11-19": entry(2, 20, 2, 200)}}))
        db = self.make_db(devices=[("d1", friend)], sable=[(friend, "friend-a")])
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        self.assertIn("friend-a", out["text"])
        self.assertNotIn(SABLE_PREFIX, out["text"])
        self.assertEqual(out["sources"]["sable"], 1)
        self.assertEqual(out["rows"][0]["name"], "friend-a")

    def test_cleared_username_keeps_its_own_history(self):
        old, new = self.sable_id("old-sub"), self.sable_id("new-sub")
        self.save(ledger({name_id(old): {"2026-11-19": entry(5, 50, 5, 500)},
                          name_id(new): {"2026-11-19": entry(1, 10, 1, 100)}}))
        db = self.make_db(devices=[("d1", old), ("d2", new)],
                          sable=[(old, ""), (new, "friend-a")])
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        by_name = {row["name"]: row["total"]["calls"] for row in out["rows"]}
        self.assertEqual(len(out["rows"]), 2)
        self.assertEqual(by_name["friend-a"], 1)
        self.assertEqual(by_name[f"sable-{name_id(old)[:8]}"], 5)
        self.assertEqual(out["sources"]["sable_cleared"], 1)

    def test_sealed_grants_are_never_read_into_the_report(self):
        friend = self.sable_id("sable-subject-2")
        self.save(ledger({name_id(friend): {"2026-11-19": entry(1, 10, 1, 100)}}))
        db = self.make_db(sable=[(friend, "friend-a")],
                          grants=[("d1", b"TOPSECRET-TOKEN-BYTES")])
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        self.assertIn("friend-a", out["text"])
        self.assertNotIn("TOPSECRET", out["text"])

    def test_internal_sable_id_without_the_table_is_labelled_not_shown(self):
        friend = self.sable_id("sable-subject-3")
        fid = name_id(friend)
        self.save(ledger({fid: {"2026-11-19": entry(1, 10, 1, 100)}}))
        db = self.make_db(devices=[("d1", friend)], sable_table=False)
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        self.assertIn(f"sable-{fid[:8]}", out["text"])
        self.assertNotIn(SABLE_PREFIX, out["text"])

    def test_legacy_pairing_names_still_work_alongside_sable(self):
        friend = self.sable_id("sable-subject-4")
        self.save(ledger({name_id(friend): {"2026-11-19": entry(1, 10, 1, 100)},
                          A: {"2026-11-19": entry(2, 20, 2, 200)}}))
        db = self.make_db(devices=[("d1", friend), ("d2", "甲")],
                          sable=[(friend, "friend-a")])
        out = build(self.state, "2026-11-19", [self.roster], [db], now=NOW)
        self.assertEqual({row["name"] for row in out["rows"]}, {"friend-a", "甲"})

    # --- extra ledgers from another machine (Mac) ---

    def test_copied_ledger_is_counted_once(self):
        row = entry(2, 20, 2, 200, devices={"pc": dev(2, 20, 2, 200)})
        self.save(ledger({A: {"2026-11-19": row}}))
        copy = self.state / "mac.json"
        atomic_json(copy, ledger({A: {"2026-11-19": row}}))
        out = build(self.state, "2026-11-19", [self.roster], [],
                    now=NOW, extras=[("mac", copy)])
        self.assertEqual(out["rows"][0]["total"]["calls"], 2)
        self.assertEqual(out["rows"][0]["total"]["spent_microusd"], 200)
        self.assertIn("额外合并账本 1 份：mac", out["text"])

    def test_devices_from_two_machines_add_up(self):
        self.save(ledger({A: {"2026-11-19": entry(
            2, 20, 2, 200, devices={"pc": dev(2, 20, 2, 200)})}}))
        other = self.state / "mac.json"
        atomic_json(other, ledger({A: {"2026-11-19": entry(
            1, 10, 1, 100, devices={"laptop": dev(1, 10, 1, 100)})}}))
        out = build(self.state, "2026-11-19", [self.roster], [],
                    now=NOW, extras=[("mac", other)])
        row = out["rows"][0]
        self.assertEqual(row["total"]["calls"], 3)
        self.assertEqual(row["total"]["spent_microusd"], 300)
        self.assertEqual(row["devices"], 2)

    def test_unreadable_extra_ledger_is_named_not_silently_dropped(self):
        self.save(ledger({A: {"2026-11-19": entry(1, 10, 1, 100)}}))
        broken = self.state / "mac.json"
        broken.write_text("{not json")
        out = build(self.state, "2026-11-19", [self.roster], [],
                    now=NOW, extras=[("mac", broken)])
        self.assertEqual(out["rows"][0]["total"]["calls"], 1)
        self.assertIn("有 1 份账本读不出", out["text"])


if __name__ == "__main__":
    unittest.main()

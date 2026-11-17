#!/usr/bin/env python3
"""Offline friend credential revocation; default is read-only inspection.

The future cutover executor must stop the service and its children and hold its
operation lock throughout this call and any old-release startup. SQLite's write
lock alone does not prove that a WAL reader or service process has stopped.
"""
import argparse
import json
from pathlib import Path
import sqlite3
import stat
import sys
import time


class GateError(Exception):
    pass


def require(condition):
    if not condition:
        raise GateError()


def load_binding(path):
    binding = json.loads(Path(path).read_text(encoding="utf-8"))
    require(isinstance(binding, dict))
    require(set(binding) == {"database", "device", "inode", "epoch"})
    require(isinstance(binding["database"], str))
    require(all(type(binding[key]) is int and binding[key] >= 0
                for key in ("device", "inode")))
    require(isinstance(binding["epoch"], str) and bool(binding["epoch"]))
    return binding


def check_file(binding):
    path = Path(binding["database"])
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode))
    require((info.st_dev, info.st_ino) == (binding["device"], binding["inode"]))
    return path


def connect(binding, writable=False):
    path = check_file(binding)
    # Neither mode can create a missing database. Do not use immutable=1: WAL
    # contents must be included. URI escaping comes from Path.as_uri().
    db = sqlite3.connect(path.as_uri() + ("?mode=rw" if writable else "?mode=ro"),
                         uri=True, timeout=0, isolation_level=None)
    try:
        db.execute("PRAGMA foreign_keys=ON")
        if not writable:
            db.execute("PRAGMA query_only=ON")
        check_file(binding)
        return db
    except BaseException:
        db.close()
        raise


def validate(db, binding):
    require(db.execute("PRAGMA quick_check").fetchall() == [("ok",)])
    required = {
        "ledger_meta": {"singleton", "epoch"},
        "devices": {"device_id", "name", "token_sha256", "created_at", "revoked_at", "friend_name"},
        "pairing_code": {"singleton", "code_sha256", "expires_at", "attempts", "friend_name"},
    }
    for table, columns in required.items():
        require(db.execute("SELECT type FROM sqlite_master WHERE name=?", (table,)).fetchall()
                == [("table",)])
        require(columns <= {row[1] for row in db.execute(f"PRAGMA table_info({table})")})
    require(db.execute("SELECT singleton,epoch FROM ledger_meta").fetchall()
            == [(1, binding["epoch"])])
    # Unknown triggers could mutate owner rows, unrelated data or resurrect a
    # credential. No trigger is present on these tables in LedgerStore.
    require(not db.execute("SELECT 1 FROM sqlite_master WHERE type='trigger' "
                           "AND tbl_name IN ('devices','pairing_code')").fetchall())
    check_file(binding)


def counts(db):
    return {
        "active_friends": db.execute("SELECT count(*) FROM devices WHERE friend_name IS NOT NULL "
                                     "AND revoked_at IS NULL").fetchone()[0],
        # Delete expired/exhausted friend codes too; none may survive rollback.
        "pending_friend_codes": db.execute("SELECT count(*) FROM pairing_code "
                                           "WHERE friend_name IS NOT NULL").fetchone()[0],
    }


def snapshot(db, table):
    return db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall()


def revoke(binding, apply=False):
    # Always open read-only first, even for --apply. Revalidate under the write
    # transaction: the preflight snapshot is not authority for the mutation.
    db = connect(binding)
    try:
        db.execute("BEGIN")
        validate(db, binding)
        result = counts(db)
    finally:
        db.close()
    if not apply:
        return {**result, "revoked": 0, "invalidated_codes": 0}

    db = connect(binding, writable=True)
    try:
        db.execute("PRAGMA synchronous=FULL")
        db.execute("BEGIN EXCLUSIVE")
        validate(db, binding)
        devices = snapshot(db, "devices")
        codes = snapshot(db, "pairing_code")
        device_columns = [row[1] for row in db.execute("PRAGMA table_info(devices)")]
        code_columns = [row[1] for row in db.execute("PRAGMA table_info(pairing_code)")]
        friend_index = device_columns.index("friend_name")
        revoked_index = device_columns.index("revoked_at")
        now = time.time_ns() // 1_000_000
        require(now > 0)
        expected_devices = []
        for row in devices:
            values = list(row)
            if values[friend_index] is not None and values[revoked_index] is None:
                values[revoked_index] = now
            expected_devices.append(tuple(values))
        expected_codes = [row for row in codes if row[code_columns.index("friend_name")] is None]

        revoked = db.execute("UPDATE devices SET revoked_at=? WHERE friend_name IS NOT NULL "
                             "AND revoked_at IS NULL", (now,)).rowcount
        invalidated = db.execute("DELETE FROM pairing_code WHERE friend_name IS NOT NULL").rowcount
        result = counts(db)
        require(result == {"active_friends": 0, "pending_friend_codes": 0})
        require(snapshot(db, "devices") == expected_devices)
        require(snapshot(db, "pairing_code") == expected_codes)
        check_file(binding)
        db.execute("COMMIT")
    except BaseException:
        if db.in_transaction:
            db.execute("ROLLBACK")
        raise
    finally:
        db.close()

    # Verify through a fresh read-only connection after the durable commit.
    # Failure is nonzero even if revocation already committed: never start old
    # code based on a partially observed outcome.
    db = connect(binding)
    try:
        db.execute("BEGIN")
        validate(db, binding)
        require(counts(db) == result)
        require(snapshot(db, "devices") == expected_devices)
        require(snapshot(db, "pairing_code") == expected_codes)
    finally:
        db.close()
    return {**result, "revoked": revoked, "invalidated_codes": invalidated}


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes invalid arguments, including private paths.
        raise GateError()


def main(argv=None):
    try:
        parser = PrivateParser(description=__doc__, allow_abbrev=False)
        parser.add_argument("--binding", required=True,
                            help="private JSON identity record captured by the stopped-service executor")
        parser.add_argument("--apply", action="store_true")
        args = parser.parse_args(argv)
        result = revoke(load_binding(args.binding), apply=args.apply)
    except (GateError, OSError, sqlite3.Error, ValueError, TypeError, KeyError):
        print('{"failed":1}')
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())

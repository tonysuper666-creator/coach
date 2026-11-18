"""Offline control-plane fixtures. Neither CLI nor a network listener runs here."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import anonymous_window_adapter as adapter

window = adapter.window
F = "a" * 40
REPO = adapter.REPOSITORY
RUN = 123
ATTEMPT = 2


def encoded(obj):
    return json.dumps(obj).encode()


class Hosted(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name).resolve() / "window"
        window.initialize(self.path)
        adapter.bind_hosted(self.path, F, RUN, ATTEMPT)
        with window.transaction(self.path) as db:
            db.execute("UPDATE window SET state='open'")
        self.ref = {"ref": "refs/heads/main", "object": {"type": "commit", "sha": F}}
        self.run = dict(id=RUN, run_attempt=ATTEMPT, status="in_progress", head_sha=F,
                        head_branch="main", event="workflow_dispatch", path=adapter.WORKFLOW,
                        repository=dict(full_name=REPO), head_repository=dict(full_name=REPO))
        self.env = dict(GITHUB_REPOSITORY=REPO, GITHUB_REF=adapter.REF, GITHUB_SHA=F,
                        GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_WORKFLOW_SHA=F,
                        GITHUB_WORKFLOW_REF=f"{REPO}/{adapter.WORKFLOW}@refs/heads/main",
                        GITHUB_RUN_ID="123", GITHUB_RUN_ATTEMPT="2",
                        RUNNER_OS="Windows", RUNNER_ENVIRONMENT="github-hosted")
        self.checkout = F

    def read(self, args):
        self.assertEqual(args[:4], ("gh", "api", "--hostname", "github.com"))
        if args[4] == f"repos/{REPO}/actions/runs/123/attempts/2":
            return encoded(self.run)
        self.assertEqual(args[4], f"repos/{REPO}/git/ref/heads/main")
        return encoded(self.ref)

    def grant(self, read=None):
        def authenticate(frozen, run_id, attempt):
            self.assertEqual((frozen, run_id, attempt), (F, RUN, ATTEMPT))
            return self.env, self.checkout
        return adapter.grant_hosted(self.path, authenticate=authenticate, read=read or self.read)

    def test_without_authenticated_source_no_read_or_slot_consumption(self):
        with self.assertRaises(window.Refused):
            adapter.grant_hosted(self.path, read=lambda _: self.fail("unexpected API read"))
        self.assertTrue(self.grant())

    def test_bound_grant_once_and_no_local_substitute_for_e(self):
        with self.assertRaises(window.Refused):
            window.grant(self.path, "H")
        with self.assertRaises(window.Refused):
            window.grant(self.path, "E")
        nonce = self.grant()
        with self.assertRaises(window.Refused):
            self.grant()
        self.assertEqual(window.finish(self.path, "H", nonce, "tls-eof"), (False, True))
        with self.assertRaises(window.Refused):
            window.grant(self.path, "E")

    def test_reject_api_mismatch_without_spending_slot(self):
        for key, bad in dict(status="completed", event="push", head_sha="b"*40, head_branch="other",
                             path=".github/workflows/friend-public.yml", id="123", run_attempt=1,
                             head_repository={"full_name": "fixture/fork"},
                             repository={"full_name": "fixture/fork"}).items():
            with self.subTest(key=key):
                old = self.run[key]
                self.run[key] = bad
                with self.assertRaises(window.Refused):
                    self.grant()
                self.run[key] = old
        self.assertTrue(self.grant())

    def test_frozen_main_and_checkout(self):
        self.ref["object"]["sha"] = "b"*40
        with self.assertRaises(window.Refused):
            self.grant()
        self.ref["object"]["sha"] = F
        self.checkout = "b"*40
        with self.assertRaises(window.Refused):
            self.grant()

    def test_runner_context_fields_required(self):
        for key in list(self.env):
            with self.subTest(key=key):
                value = self.env.pop(key)
                with self.assertRaises(window.Refused):
                    self.grant()
                self.env[key] = value

    def test_cancellation_during_readback(self):
        def read(args):
            window.cancel(self.path)
            return self.read(args)
        with self.assertRaises(window.Refused):
            self.grant(read)
        with window.transaction(self.path) as db:
            self.assertEqual(db.execute("SELECT state FROM slots WHERE role='H'").fetchone(), ("revoked",))

    def test_expiry_during_readback(self):
        def read(args):
            with window.transaction(self.path) as db:
                db.execute("UPDATE window SET utc=0")
            return self.read(args)
        with self.assertRaises(window.Refused):
            self.grant(read)

    def test_late_granted_worker_after_cancel(self):
        nonce = self.grant()
        window.cancel(self.path)
        with self.assertRaises(window.Refused):
            window.once(self.path, "H", nonce, "fixture.invalid", "127.0.0.1")
        with window.transaction(self.path) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM attempts").fetchone(), (0,))

    def test_cannot_rebind_after_open(self):
        with self.assertRaises(window.Refused):
            adapter.bind_hosted(self.path, "b"*40, RUN, 3)


class RuleStore:
    def __init__(self):
        self.config = {}
        self.generation = 0
        self.views = {}
        self.reads = []

    def read(self, args):
        binary, view, status, flag = args
        assert (binary, status, flag) == ("tailscale", "status", "--json")
        assert view in ("serve", "funnel")
        self.reads.append(view)
        return self.views.get(view, encoded(self.config))

    def create_if_empty(self, config):
        window.require(self.config == {})
        self.config = copy.deepcopy(config)
        self.generation += 1
        return self.generation

    def remove_if_owned(self, handle, config):
        if self.generation != handle or self.config != config:
            return False
        self.generation += 1
        self.config = {}
        return True


class Rules(unittest.TestCase):
    def setUp(self):
        self.store = RuleStore()
        self.rules = adapter.WindowRules("fixture.example.ts.net", self.store.read, self.store)

    def watch(self, ready):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp).resolve() / "window"
            window.initialize(path)
            try:
                code = window.watch(path, self.rules, lambda: (ready(), window.cancel(path)))
            except window.Refused:
                code = 1
            with window.transaction(path) as db:
                return code, db.execute("SELECT state FROM window").fetchone()[0]

    def test_owned_rule_removed_and_both_views_back_to_empty(self):
        self.assertEqual(self.watch(lambda: None), (0, "rule-cleared"))
        self.assertEqual(self.store.reads, ["funnel", "serve"] * 4)
        self.assertEqual(self.store.config, {})

    def test_no_conditional_writer_no_open(self):
        self.rules.lease = None
        self.assertEqual(self.watch(lambda: self.fail("opened")), (1, "alarm"))
        self.assertEqual(self.store.generation, 0)

    def test_identical_rule_recreated_by_another_session_preserved(self):
        def replace():
            self.store.config = {}
            self.store.create_if_empty(self.rules.config)
        self.assertEqual(self.watch(replace), (1, "alarm"))
        self.assertEqual(self.store.config, self.rules.config)

    def test_race_after_reads_before_remove(self):
        remove = self.store.remove_if_owned
        def race(handle, config):
            self.store.generation += 1
            return remove(handle, config)
        self.store.remove_if_owned = race
        self.assertEqual(self.watch(lambda: None), (1, "alarm"))
        self.assertTrue(self.store.config)

    def test_stale_post_remove_view_alarms(self):
        remove = self.store.remove_if_owned
        def stale(handle, config):
            self.store.views["serve"] = encoded(config)
            return remove(handle, config)
        self.store.remove_if_owned = stale
        self.assertEqual(self.watch(lambda: None), (1, "alarm"))

    def test_change_during_conditional_create(self):
        create = self.store.create_if_empty
        def race(config):
            self.store.config = {"foreign": True}
            return create(config)
        self.store.create_if_empty = race
        self.assertEqual(self.watch(lambda: self.fail("opened")), (1, "alarm"))
        self.assertEqual(self.store.config, {"foreign": True})

    def test_stale_read_after_create_unwinds_only_ours_and_alarms(self):
        create = self.store.create_if_empty
        def stale(config):
            handle = create(config)
            self.store.views["serve"] = b"{}"
            return handle
        self.store.create_if_empty = stale
        self.assertEqual(self.watch(lambda: self.fail("opened")), (1, "alarm"))
        self.assertEqual(self.store.config, {})

    def test_extra_rule_stops_removal(self):
        def extra():
            self.store.config["TCP"]["18791"] = {"HTTPS": True}
        self.assertEqual(self.watch(extra), (1, "alarm"))
        self.assertIn("18791", self.store.config["TCP"])

    def test_malformed_and_ambiguous_views_refused(self):
        for raw in (b"", b"null", b"invalid", b'{"TCP":{},"TCP":{}}', b'{"TCP":1}'):
            with self.subTest(raw=raw):
                self.store.views["serve"] = raw
                self.assertEqual(self.watch(lambda: self.fail("opened")), (1, "alarm"))
                self.assertEqual(self.store.generation, 0)

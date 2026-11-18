"""Offline coordinator boundary for a single anonymous diagnostic window.

The caller owns source authentication. A runner-supplied copy of GitHub's run
response is not a trusted coordinator observation. No remote transport, job
dispatch, Funnel writer or public probe is installed by this module.
"""
import json
import re
import subprocess

try:
    from . import anonymous_window as window
except ImportError:
    import anonymous_window as window

REPOSITORY = "tonysuper666-creator/coach"
WORKFLOW = ".github/workflows/anonymous-window.yml"
REF = "refs/heads/main"
TARGET = "http://127.0.0.1:41243"


def bind_hosted(path, frozen, run_id, attempt):
    window.require(isinstance(frozen, str) and re.fullmatch("[0-9a-f]{40}", frozen))
    window.require(all(type(value) is int and 0 < value < 2**63 for value in (run_id, attempt)))
    with window.transaction(path) as db:
        window.require(db.execute("SELECT state FROM window").fetchone() == ("armed",))
        db.execute("CREATE TABLE hosted_binding (sha TEXT, run_id INTEGER, attempt INTEGER)")
        db.execute("INSERT INTO hosted_binding VALUES (?,?,?)", (frozen, run_id, attempt))


def read_command(argv):
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, timeout=3)
    except (OSError, subprocess.TimeoutExpired):
        raise window.Refused() from None
    window.require(result.returncode == 0 and 0 < len(result.stdout) <= 65536)
    return result.stdout


def grant_hosted(path, *, authenticate=None, read=read_command):
    """Grant H after checking the source and independently reading run/main.

    The coordinator supplies authenticate. It verifies the requesting runner
    for the supplied binding and returns its environment claims and checkout.
    There is no default authenticated transport.
    """
    window.require(callable(authenticate))
    # Never hold a write lock while waiting on the control plane. Re-check the
    # immutable binding and the local grant deadline in the issuing transaction.
    with window.transaction(path) as db:
        binding = db.execute("SELECT * FROM hosted_binding").fetchone()
        window.require(binding is not None and window.remaining(db) >= 40)
    frozen, run_id, attempt = binding
    runner_env, checkout = authenticate(frozen, run_id, attempt)
    window.require(isinstance(runner_env, dict))
    api = ("gh", "api", "--hostname", "github.com")
    repo_path = f"repos/{REPOSITORY}"
    api_run = parse_view(read((*api, f"{repo_path}/actions/runs/{run_id}/attempts/{attempt}")))
    main = parse_view(read((*api, f"{repo_path}/git/ref/heads/main")))
    window.require(isinstance(main, dict) and main.get("ref") == REF
                   and isinstance(main.get("object"), dict)
                   and main["object"].get("type") == "commit")
    main_head = main["object"].get("sha")
    with window.transaction(path) as db:
        window.require(db.execute("SELECT * FROM hosted_binding").fetchone() == binding)
        window.require(main_head == checkout == frozen)
        expected = dict(id=run_id, run_attempt=attempt, head_sha=frozen, head_branch="main",
                        path=WORKFLOW, event="workflow_dispatch", status="in_progress")
        window.require(isinstance(api_run, dict) and all(
            type(api_run.get(key)) is type(value) and api_run[key] == value
            for key, value in expected.items()))
        for key in ("repository", "head_repository"):
            repo = api_run.get(key)
            window.require(isinstance(repo, dict) and repo.get("full_name") == REPOSITORY)
        expected_env = dict(GITHUB_REPOSITORY=REPOSITORY, GITHUB_REF=REF,
                            GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_SHA=frozen,
                            GITHUB_WORKFLOW_REF=f"{REPOSITORY}/{WORKFLOW}@{REF}",
                            GITHUB_WORKFLOW_SHA=frozen, GITHUB_RUN_ID=str(run_id),
                            GITHUB_RUN_ATTEMPT=str(attempt), RUNNER_OS="Windows",
                            RUNNER_ENVIRONMENT="github-hosted")
        window.require(all(runner_env.get(k) == v for k, v in expected_env.items()))
        return window.grant_in_transaction(db, "H")


def unique_keys(pairs):
    obj = dict(pairs)
    window.require(len(obj) == len(pairs))
    return obj


def parse_view(raw):
    window.require(isinstance(raw, bytes) and 0 < len(raw) <= 65536)
    try:
        return json.loads(raw, object_pairs_hook=unique_keys)
    except (ValueError, RecursionError):
        raise window.Refused() from None


class WindowRules:
    """Read both CLI views; require an atomic backend lease for any mutation.

    read_status takes an argv tuple and returns successful stdout bytes. It must
    raise on command failure. lease.create_if_empty must atomically claim the
    empty configuration and return a generation-specific handle; remove must
    compare that handle AND the exact configuration at mutation time. The
    ordinary CLI status/off sequence provides no such guarantee.
    """

    def __init__(self, host, read_status=read_command, lease=None):
        window.require(isinstance(host, str) and re.fullmatch(
            r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+ts\.net", host))
        key = host + ":443"
        self.config = {"TCP": {"443": {"HTTPS": True}},
                       "Web": {key: {"Handlers": {"/": {"Proxy": TARGET}}}},
                       "AllowFunnel": {key: True}}
        self.read_status = read_status
        self.lease = lease
        self.handle = None

    def both_match(self, expected):
        matches = []
        for service in ("funnel", "serve"):
            value = parse_view(self.read_status(("tailscale", service, "status", "--json")))
            # JSON true must not compare equal to numeric 1.
            matches.append(json.dumps(value, sort_keys=True) == json.dumps(expected, sort_keys=True))
        return all(matches)

    def open_if_empty(self):
        window.require(self.lease is not None and self.handle is None and self.views_empty())
        self.handle = self.lease.create_if_empty(self.config)
        window.require(self.handle is not None)
        try:
            window.require(self.both_match(self.config))
        except BaseException:
            # watch has not reached ready and does not yet know about this
            # lease. Unwind only our generation. A mismatch still raises and
            # leaves the supervisor in alarm even if this cleanup succeeds.
            if self.lease.remove_if_owned(self.handle, self.config) is True:
                self.handle = None
                window.require(self.views_empty())
            raise

    def remove_if_owned(self):
        if self.handle is None or not self.both_match(self.config):
            return False
        if self.lease.remove_if_owned(self.handle, self.config) is not True:
            return False
        self.handle = None
        return True

    def views_empty(self):
        return self.both_match({})

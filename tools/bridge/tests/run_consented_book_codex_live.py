"""Synthetic-only local Codex smoke; --local selects Mac, --interactive the existing Windows console."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
fixture = ROOT / "tools/bridge/tests/ConsentedBookCodexLiveTest.kt"
target = ROOT / "desktopApp/src/test/kotlin/com/coach/desktop/books/ConsentedBookCodexLiveTest.kt"
if target.exists():
    raise RuntimeError("refusing to overwrite a test source")
try:
    target.write_bytes(fixture.read_bytes())
    # The extra live case probes the old CLI's real file-read capability (negative control),
    # then challenges the production transport with the same test-owned canary file.
    local = "--local" in sys.argv[1:]
    session = "--interactive" if "--interactive" in sys.argv[1:] else "--ssh"
    prefix = [] if local else ["python3", "tools/dev/winbox.py", session]
    security = "--security-only" in sys.argv[1:]
    selectors = [
        "--tests", "com.coach.desktop.books.ConsentedBookCodexLiveTest.collaborationIsAbsentAndStderrOnlyAttemptIsRejected",
        "--tests", "com.coach.desktop.books.ConsentedBookCodexLiveTest.syntheticUploaderConsentLocalCodexAndRevoke",
        "--tests", "com.coach.desktop.books.LocalCodexBookTransportTest",
    ] if security else [
        "--tests", "com.coach.desktop.books.ConsentedBookCodexLiveTest",
        "--tests", "com.coach.desktop.books.OwnedBookConsentTest",
        "--tests", "com.coach.desktop.books.LocalCodexBookTransportTest",
        "--tests", "com.coach.desktop.books.OwnedBookCliExecutorTest",
        "--tests", "com.coach.desktop.books.OwnedBookLearningProviderTest",
        "--tests", "com.coach.desktop.books.OwnedBookProcess*Test",
    ]
    result = subprocess.run(prefix + [
        "./gradlew", "--no-daemon", ":desktopApp:test",
    ] + selectors + [
        "--rerun-tasks", "--console=plain",
    ] + (["--tests", "com.coach.desktop.books.*", "--tests", "com.coach.desktop.Windows*Book*Test",
          "--tests", "com.coach.desktop.WindowsCitedProviderHostTest"] if local and not security else []), cwd=ROOT,
        # Exercise inherited log suppression on Mac. The Windows runner deliberately does
        # not forward caller environment; its child-environment assertion still runs.
        env=dict(os.environ, RUST_LOG="off") if local else None)
    raise SystemExit(result.returncode)
finally:
    target.unlink(missing_ok=True)

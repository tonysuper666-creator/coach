"""Explicit synthetic-only Windows local Codex smoke. No server, relay, or private course materials."""
from pathlib import Path
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
    prefix = [] if local else ["python3", "tools/dev/winbox.py", "--ssh"]
    result = subprocess.run(prefix + [
        "./gradlew", ":desktopApp:test",
        "--tests", "com.coach.desktop.books.ConsentedBookCodexLiveTest",
        "--tests", "com.coach.desktop.books.OwnedBookConsentTest",
        "--tests", "com.coach.desktop.books.LocalCodexBookTransportTest",
        "--tests", "com.coach.desktop.books.OwnedBookCliExecutorTest",
        "--tests", "com.coach.desktop.books.OwnedBookLearningProviderTest",
        "--tests", "com.coach.desktop.books.OwnedBookProcess*Test",
        "--rerun-tasks", "--console=plain",
    ] + (["--tests", "com.coach.desktop.books.*", "--tests", "com.coach.desktop.Windows*Book*Test",
          "--tests", "com.coach.desktop.WindowsCitedProviderHostTest"] if local else []), cwd=ROOT)
    raise SystemExit(result.returncode)
finally:
    target.unlink(missing_ok=True)

"""Explicit synthetic-only Windows local Codex smoke. No server, relay, or private course materials."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
fixture = ROOT / "tools/bridge/tests/ConsentedBookCodexLiveTest.kt"
target = ROOT / "desktopApp/src/test/kotlin/com/coach/desktop/books/ConsentedBookCodexLiveTest.kt"
if target.exists():
    raise RuntimeError("refusing to overwrite a test source")
try:
    target.write_bytes(fixture.read_bytes())
    result = subprocess.run([
        "python3", "tools/dev/winbox.py", "--ssh", "./gradlew", ":desktopApp:test",
        "--tests", "com.coach.desktop.books.ConsentedBookCodexLiveTest",
        "--tests", "com.coach.desktop.books.OwnedBookConsentTest",
        "--tests", "com.coach.desktop.books.LocalCodexBookTransportTest",
        "--tests", "com.coach.desktop.books.OwnedBookCliExecutorTest",
        "--tests", "com.coach.desktop.books.OwnedBookLearningProviderTest",
        "--tests", "com.coach.desktop.books.OwnedBookProcess*Test",
        "--rerun-tasks", "--console=plain",
    ], cwd=ROOT)
    raise SystemExit(result.returncode)
finally:
    target.unlink(missing_ok=True)

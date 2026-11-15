"""Opt-in native headless button test; only the generated fictional book reaches local Codex."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[3]
fixture = Path(__file__).with_name("BookConsentUiCodexLiveTest.kt")
target = root / "desktopApp/src/test/kotlin/com/coach/desktop/books/BookConsentUiCodexLiveTest.kt"
if target.exists():
    raise RuntimeError("refusing to overwrite a test source")
env = os.environ.copy()
if os.name == "nt":
    env.setdefault("JAVA_HOME", "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot")
    env.setdefault("GRADLE_USER_HOME", "D:/agent/gradle-home")
    env.setdefault("ANDROID_HOME", "D:/agent/android-sdk")
    env.setdefault("COACH_HEAVY_LOCK", str(root / ".coach-local-heavy.lock"))
    env["SKIKO_RENDER_API"] = "SOFTWARE_FAST"
    bash = "C:/Program Files/Git/bin/bash.exe"
else:
    bash = "bash"
try:
    target.write_bytes(fixture.read_bytes())
    command = [bash, "tools/dev/heavy", "--local", "./gradlew", ":desktopApp:test",
               "--tests", "com.coach.desktop.books.BookConsentUiCodexLiveTest", "--console=plain"]
    raise SystemExit(subprocess.run(command, cwd=root, env=env).returncode)
finally:
    target.unlink(missing_ok=True)

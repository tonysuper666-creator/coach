"""Synthetic-only real local CLI + typed source + current workspace return probe."""
from pathlib import Path
import os
import subprocess
import sys
import shutil
import uuid
import json
import time

root = Path(__file__).resolve().parents[3]
regression = "--regression" in sys.argv[1:]
boundaries = "--consent-boundaries" in sys.argv[1:]
target = root / "desktopApp/src/test/kotlin/com/coach/desktop/books/BookAskCitationCodexLiveTest.kt"
fixture = Path(__file__).with_name(target.name)
created = False
env = os.environ.copy()
if os.name == "nt":
    env.setdefault("JAVA_HOME", "C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot")
    env.setdefault("GRADLE_USER_HOME", "D:/agent/gradle-home")
    env.setdefault("ANDROID_HOME", "D:/agent/android-sdk")
    env["SKIKO_RENDER_API"] = "SOFTWARE_FAST"
    bash = "C:/Program Files/Git/bin/bash.exe"
else:
    bash = "bash"
try:
    if not regression and not boundaries:
        if target.exists():
            raise RuntimeError("refusing to overwrite a test source")
        target.write_bytes(fixture.read_bytes())
        created = True
    selectors = []
    if boundaries:
        for name in ["withdrawingDuringAnUncooperativeAnswerHidesItImmediatelyAndDiscardsItsLateReply", "oneApprovalIsOneOutboundRequestEveryTime"]:
            selectors += ["--tests", "com.coach.desktop.books.BookConsentUiTest." + name]
    else:
        selectors = ["--tests", "com.coach.desktop.books." + ("BookAskConsentSessionTest" if regression else "BookAskCitationCodexLiveTest")]
    command = [bash, "tools/dev/heavy", "--local", "./gradlew", ":desktopApp:test", "--rerun"] + selectors + [
        "--priority=low", "--max-workers=2", "--console=plain"]
    output = root / "desktopApp/build/reports/book-ask-citation-live"
    output.mkdir(parents=True, exist_ok=True)
    mode = "regression" if regression else "boundaries" if boundaries else "live"
    (output / (mode + "-command.json")).write_text(json.dumps({"argv": command, "cwd": str(root),
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "renderer": env.get("SKIKO_RENDER_API"), "input_lease": "none, headless"}, indent=2), encoding="utf8")
    started = time.monotonic()
    with (output / (mode + "-gradle.log")).open("wb") as log:
        with subprocess.Popen(command, cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as child:
            for line in child.stdout:
                log.write(line); log.flush(); sys.stdout.buffer.write(line); sys.stdout.buffer.flush()
            code = child.wait()
    (output / (mode + "-result.json")).write_text(json.dumps({"exit": code, "seconds": round(time.monotonic()-started,3)}), encoding="utf8")
    raise SystemExit(code)
finally:
    if created:
        if target.exists() and os.name == "nt":
            trash = Path("D:/agent/_trash") / ("ton1450-live-fixture-" + uuid.uuid4().hex)
            trash.mkdir()
            shutil.move(str(target), str(trash / target.name))
        elif target.exists():
            target.unlink()

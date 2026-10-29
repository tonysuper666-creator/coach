import os, subprocess
from pathlib import Path

r = Path(__file__).resolve().parents[2]
out = r / ".s0-evidence"
out.mkdir(exist_ok=True)
capture = out / "desktop-app.png"
app = r / "desktopApp/build/compose/binaries/main/app/Coach.app/Contents/MacOS/Coach"
env = dict(
    os.environ,
    JAVA_TOOL_OPTIONS=f"-Dcoach.autoExitSeconds=3 -Dcoach.capturePath={capture}",
)
result = subprocess.run([str(app)], env=env, timeout=30, check=True)
assert capture.exists()
print("Packaged .app exited 0; live window surface captured")

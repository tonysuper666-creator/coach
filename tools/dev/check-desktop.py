import os, subprocess
from pathlib import Path

r = Path(__file__).resolve().parents[2]
out = r / ".s0-evidence"
out.mkdir(exist_ok=True)
capture = out / "desktop-app.png"
app = r / "desktopApp/build/compose/binaries/main/app/Coach.app/Contents/MacOS/Coach"
# A capture run is a development launch: it must not use the production defaults (server, device token, ledger copy). Nothing listens on this port.
isolated = r / ".s0-evidence/isolated"
env = dict(
    os.environ,
    COACH_URL="http://127.0.0.1:18701", COACH_TOKEN_FILE=str(isolated / "device-token"), COACH_CLIENT_DIR=str(isolated / "client"),
    JAVA_TOOL_OPTIONS=f"-Dcoach.autoExitSeconds=3 -Dcoach.capturePath={capture}",
)
result = subprocess.run([str(app)], env=env, timeout=30, check=True)
assert capture.exists()
print("Packaged .app exited 0; live window surface captured")

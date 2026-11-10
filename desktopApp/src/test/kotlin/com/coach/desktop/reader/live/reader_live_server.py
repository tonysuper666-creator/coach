"""Disposable real Ktor server for the desktop reader live test (TON-1431).

Reuses tools/ios-contract/run.py (the merged contract probe's Server class): random loopback port, private temp
directory with an invented PDF library, isolated user.home, no production data/ports. This file is only a launcher.

  python reader_live_server.py --build   # installDist through the repo's low-priority Windows queue; exit code is gradle's
  python reader_live_server.py           # start the server, print one "READY {json}" line, then serve stdin commands:
                                         #   revoke  -> DELETE the paired device, prints "REVOKED"
                                         #   stop / EOF -> stop the server, delete the temp directory, exit 0
"""
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[9]
sys.path.insert(0, str(ROOT / "tools" / "ios-contract"))
import run  # noqa: E402


def main():
    if "--build" in sys.argv:
        return run.build_server()
    if not run.server_binary().exists():
        print("NOT_BUILT", flush=True)
        return 3
    # The page-image route needs pdftoppm. A PATH that only has pdftotext (e.g. mingw64) gives 503 there, so also look in the
    # winbox Poppler install; the probe's own Server still prepends whichever pdftotext it finds.
    if shutil.which("pdftoppm") is None:
        root = Path("D:/agent/tools/poppler")
        found = sorted(root.glob("**/pdftoppm.exe")) if root.is_dir() else []
        if found:
            os.environ["PATH"] = str(found[-1].parent) + os.pathsep + os.environ.get("PATH", "")
    directory = Path(tempfile.mkdtemp(prefix="coach-reader-live-"))
    server = run.Server(directory)
    try:
        server.start()
        device, token = server.pair("Synthetic desktop reader")
        print("READY " + json.dumps(dict(port=server.port, course=run.COURSE, deviceId=device, token=token,
                                         documents=[run.TEXT_ID, run.READ_ID])), flush=True)
        for line in sys.stdin:
            command = line.strip()
            if command == "revoke":
                server.request("/v1/devices/" + device, server.admin, "DELETE", expected=204)
                print("REVOKED", flush=True)
            elif command == "stop":
                break
        return 0
    finally:
        server.stop()
        shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())

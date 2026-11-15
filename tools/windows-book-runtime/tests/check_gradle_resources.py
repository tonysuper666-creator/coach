#!/usr/bin/env python3
"""Run under tools/dev/heavy --local. Build-resource checks only; never launch an app.

Seed stale non-key sentinel bytes, withdraw them, and reject a degenerate input.
No signing keys, approved assets, books, credentials, or installers are used.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
GRADLE = "gradlew.bat" if __import__("os").name == "nt" else "gradlew"


def main():
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="runtime-resource-check-", dir=ROOT / "build") as tmp:
        tmp = Path(tmp)
        init = tmp / "paths.gradle"
        init.write_text('''
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':desktopApp')
    if (app == null) return // Included build-logic has no desktop application.
    app.tasks.register('runtimeBuildPaths') {
        doLast {
            println('RUNTIME_PATHS=' + groovy.json.JsonOutput.toJson([
                generated: app.layout.buildDirectory.dir('generated/windows-book-runtime').get().asFile.path,
                processed: app.tasks.named('processResources').get().destinationDir.path,
                composed: app.tasks.named('prepareAppResources').get().destinationDir.path]))
        }
    }
}
''', encoding="utf-8")

        def run(*extra, success=True):
            command = [str(ROOT / GRADLE), "-I", str(init), ":desktopApp:processResources",
                ":desktopApp:prepareAppResources", ":desktopApp:runtimeBuildPaths", "--console=plain", *extra]
            result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            print(result.stdout, end="", flush=True)
            assert (result.returncode == 0) == success, f"Unexpected Gradle exit {result.returncode}"
            return result

        first = run()
        paths = json.loads(next(line.removeprefix("RUNTIME_PATHS=") for line in first.stdout.splitlines()
            if line.startswith("RUNTIME_PATHS=")))
        generated, processed, composed = (Path(paths[key]) for key in ("generated", "processed", "composed"))
        stale = [generated / "verified/classpath/windows-book-runtime-trust.der",
            generated / "compose/windows/windows-book-runtime/stale.exe",
            processed / "windows-book-runtime-trust.der", composed / "windows-book-runtime/stale.exe"]
        for path in stale:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"STALE SENTINEL: NOT A KEY OR EXECUTABLE")
        # No process is launched with these sentinel files present.
        run()
        assert all(not path.exists() for path in stale), "Withdrawn runtime assets survived"
        assert not (composed / "windows-book-runtime").exists()
        print("WITHDRAWAL: 4/4 stale reserved files removed before any app startup", flush=True)

        bad = tmp / "degenerate"
        key = bad / "classpath/windows-book-runtime-trust.der"
        runtime = bad / "resources/windows-book-runtime"
        key.parent.mkdir(parents=True)
        runtime.mkdir(parents=True)
        key.write_bytes(bytes.fromhex("302a300506032b6570032100") + bytes(32))
        (runtime / "manifest.json").write_bytes(b"{}")
        (runtime / "manifest.sig").write_bytes(bytes(64))
        failed = run(f"-Pcoach.windowsBookRuntimeDir={bad}",
            "-Pcoach.windowsBookRuntimeKeySha256=" + hashlib.sha256(key.read_bytes()).hexdigest(), success=False)
        assert "small-order Ed25519" in failed.stdout
        assert not any(generated.rglob("*")), "Refused inputs left a generated tree"
        assert not (processed / "windows-book-runtime-trust.der").exists()
        assert not (composed / "windows-book-runtime").exists()
        run()
        print(json.dumps({"withdrawnFilesRemoved": 4, "degenerateInput": "refused", "recovery": "absent",
            "componentLaunches": 0, "approvedInputStartup": "NOT VERIFIED: independent approval unavailable"}))


if __name__ == "__main__":
    main()

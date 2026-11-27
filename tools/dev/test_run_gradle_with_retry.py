#!/usr/bin/env python3
"""Unit and regression tests for tools/dev/run_gradle_with_retry.py."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_gradle_with_retry as mod


PLUGIN_FAILURE_LOG = """
FAILURE: Build failed with an exception.

* Where:
Build file '/home/runner/work/coach/coach/build.gradle.kts' line: 1

* What went wrong:
Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.0', apply: false] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:2.4.0')
  Searched in the following repositories:
    Google
    MavenRepo
    Gradle Central Plugin Repository
"""

CORE_PLUGIN_VERSION_ERROR_LOG = """
FAILURE: Build failed with an exception.

* Where:
Build file '/tmp/demo/build.gradle' line: 1

* What went wrong:
Error resolving plugin [id: 'java', version: '1']
> Plugin 'java' is a core Gradle plugin, which cannot be specified with a version number. Such plugins are versioned as part of Gradle. Please remove the version number from the declaration.
"""

TEST_FAILURE_LOG = """
> Task :server:app:test

FriendAccessTest > friendCannotReadOwnerRoute() FAILED
    org.opentest4j.AssertionFailedError: expected: <403> but was: <200>

> Task :server:app:test FAILED

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':server:app:test'.
> There were failing tests. See the report at: file:///home/runner/work/coach/coach/server/app/build/reports/tests/test/index.html
"""


class RunGradleWithRetryTests(unittest.TestCase):
    def test_succeeds_first_attempt_without_retry(self):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            stream("BUILD SUCCESSFUL\n")
            return 0, "BUILD SUCCESSFUL\n"

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        self.assertEqual(code, 0)
        self.assertEqual(calls, 1)
        self.assertEqual(sleeps, [])

    def _run_always_failing(self, log):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            stream(log)
            return 1, log

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        return code, calls, sleeps, "".join(logs)

    def test_plugin_not_found_without_network_evidence_retries_but_keeps_gradle_reason(self):
        # Shape of the real incident logs (runs 46020209331 / 46033429740):
        # identical to a typo, so retry, but do not claim the repo was down.
        code, calls, sleeps, joined = self._run_always_failing(PLUGIN_FAILURE_LOG)
        self.assertEqual(code, 1)
        self.assertEqual(calls, 4)  # 1 initial + 3 retries
        self.assertEqual(sleeps, [10, 25, 60])
        self.assertIn("Gradle 插件解析失败，不是测试失败", joined)
        self.assertIn("以上方 Gradle 报错为准", joined)
        self.assertNotIn("插件仓库不可用", joined)

    def test_misspelled_plugin_in_empty_local_repo_is_not_blamed_on_repo(self):
        # QA repro: plugin repo points at a readable, empty local Maven dir.
        log = """
* What went wrong:
Plugin [id: 'qa.misspelled', version: '1.0'] was not found in any of the following sources:

- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'qa.misspelled:qa.misspelled.gradle.plugin:1.0')
  Searched in the following repositories:
    maven(file:/tmp/empty-repo/)
"""
        code, calls, sleeps, joined = self._run_always_failing(log)
        self.assertEqual(code, 1)
        self.assertEqual(calls, 4)
        self.assertIn("Gradle 插件解析失败，不是测试失败", joined)
        self.assertNotIn("插件仓库不可用", joined)

    def test_plugin_failure_with_network_evidence_reports_repo_unavailable(self):
        log = PLUGIN_FAILURE_LOG + """
   > Could not GET 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/multiplatform/org.jetbrains.kotlin.multiplatform.gradle.plugin/2.4.0/org.jetbrains.kotlin.multiplatform.gradle.plugin-2.4.0.pom'.
      > Connect to plugins.gradle.org:443 failed: Connect timed out
"""
        code, calls, sleeps, joined = self._run_always_failing(log)
        self.assertEqual(code, 1)
        self.assertEqual(calls, 4)
        self.assertEqual(sleeps, [10, 25, 60])
        self.assertIn("插件仓库不可用，不是代码问题", joined)

    def test_recovers_on_second_retry(self):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            if calls < 3:
                stream(PLUGIN_FAILURE_LOG)
                return 1, PLUGIN_FAILURE_LOG
            stream("BUILD SUCCESSFUL\n")
            return 0, "BUILD SUCCESSFUL\n"

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        self.assertEqual(code, 0)
        self.assertEqual(calls, 3)
        self.assertEqual(sleeps, [10, 25])
        self.assertNotIn("插件仓库不可用，不是代码问题", "".join(logs))

    def test_test_failure_fails_immediately_without_retry_or_plugin_message(self):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            stream(TEST_FAILURE_LOG)
            return 1, TEST_FAILURE_LOG

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        self.assertEqual(code, 1)
        self.assertEqual(calls, 1)
        self.assertEqual(sleeps, [])
        self.assertNotIn("插件仓库不可用", "".join(logs))

    def test_core_plugin_version_config_error_fails_immediately_without_retry(self):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            stream(CORE_PLUGIN_VERSION_ERROR_LOG)
            return 1, CORE_PLUGIN_VERSION_ERROR_LOG

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", "help", "--offline"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        self.assertEqual(code, 1)
        self.assertEqual(calls, 1)
        self.assertEqual(sleeps, [])
        self.assertNotIn("插件仓库不可用", "".join(logs))

    def test_real_gradlew_core_plugin_version_error_does_not_retry(self):
        repo_root = Path(__file__).resolve().parents[2]
        script = repo_root / "tools" / "dev" / "run_gradle_with_retry.py"
        with tempfile.TemporaryDirectory() as tmp:
            proj = Path(tmp)
            (proj / "settings.gradle").write_text('rootProject.name = "tmp"\n', encoding="utf-8")
            (proj / "build.gradle").write_text("plugins { id 'java' version '1' }\n", encoding="utf-8")
            wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
            shutil.copy2(repo_root / wrapper, proj / wrapper)
            shutil.copytree(repo_root / "gradle", proj / "gradle")
            env = dict(os.environ)
            env["GRADLE_RETRY_DELAYS"] = "1,2,3"
            res = subprocess.run(
                [sys.executable, str(script), str(proj / wrapper), "help", "--offline"],
                cwd=proj,
                env=env,
                capture_output=True,
                text=True,
            )
            combined = res.stdout + res.stderr
            self.assertEqual(res.returncode, 1)
            self.assertIn("core Gradle plugin", combined)
            self.assertNotIn("自动重试", combined)
            self.assertNotIn("插件仓库不可用", combined)

    def test_wrapper_download_timeout_is_retried(self):
        calls = 0
        sleeps = []
        wrapper_err = (
            "Fetching distribution.\n"
            "Downloading https://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n"
            'Exception in thread "main" java.net.SocketTimeoutException: Read timed out\n'
        )

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            if calls == 1:
                return 1, wrapper_err
            return 0, "Welcome to Gradle 9.5.0!\nBUILD SUCCESSFUL\n"

        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=lambda _: None,
        )
        self.assertEqual(code, 0)
        self.assertEqual(calls, 2)
        self.assertEqual(sleeps, [10])


if __name__ == "__main__":
    unittest.main()

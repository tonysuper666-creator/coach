#!/usr/bin/env python3
"""Unit tests for tools/dev/run_gradle_with_retry.py."""

import sys
from pathlib import Path
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

    def test_retries_plugin_failure_with_increasing_delays_and_reports_repo_unavailable(self):
        calls = 0
        sleeps = []

        def fake_runner(cmd, stream):
            nonlocal calls
            calls += 1
            stream(PLUGIN_FAILURE_LOG)
            return 1, PLUGIN_FAILURE_LOG

        logs = []
        code = mod.run_with_retry(
            ["./gradlew", ":server:app:test"],
            delays=(10, 25, 60),
            runner=fake_runner,
            sleeper=sleeps.append,
            out=logs.append,
        )
        self.assertEqual(code, 1)
        self.assertEqual(calls, 4)  # 1 initial + 3 retries
        self.assertEqual(sleeps, [10, 25, 60])
        joined = "".join(logs)
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

    def test_wrapper_download_timeout_is_retried(self):
        calls = 0
        sleeps = []
        wrapper_err = (
            "Fetching distribution.\n"
            "Downloading https://services.gradle.org/distributions/gradle-9.5.0-bin.zip\n"
            "Exception in thread \"main\" java.net.SocketTimeoutException: Read timed out\n"
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

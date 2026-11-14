"""Routing, real Git snapshots and heavy fallback; no connection to winbox."""
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('winbox', HERE / 'winbox.py')
winbox = importlib.util.module_from_spec(spec)
spec.loader.exec_module(winbox)


class Transport(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='heavy-test-')
        self.root = Path(self.temp.name).resolve()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        java_home = self.root / 'jdk'
        (java_home / 'bin').mkdir(parents=True)
        fingerprints = {key: '0' * 64 for key in ('coach.tailHost', 'coach.course', 'coach.port')}
        self.executable(java_home / 'bin/java', "#!/bin/sh\nprintf '%s\\n' '" + json.dumps(fingerprints) + "'\n")
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'],
                        JAVA_HOME=str(java_home),
                        COACH_HEAVY_LOCK=str(self.root / 'locks/local'),
                        COACH_HEAVY_LANES_FILE=str(self.root / 'lanes'))
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.git('config', 'user.name', 'Fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        (self.root / '.gitignore').write_text('.coach-winbox/\n')
        self.executable(self.root / 'gradlew', '#!/bin/sh\necho LOCAL "$@"\nexit 7\n')
        (self.root / 'source').write_text('base\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'base')
        (self.root / 'tools/dev').mkdir(parents=True)
        for key in ('JAVA_OPTS', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            self.env.pop(key, None)
        for name in ('heavy', 'winbox.py', 'winbox.ps1', 'WinboxConfig.java'):
            shutil.copy2(HERE / name, self.root / 'tools/dev' / name)

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return winbox.git(self.root, *args)

    def executable(self, path, body):
        path.write_text(body)
        path.chmod(0o755)

    def run_heavy(self, *args):
        return subprocess.run([str(self.root / 'tools/dev/heavy'), *args], env=self.env,
                              cwd=self.root, capture_output=True, text=True, timeout=20)

    def run_winbox(self, *args):
        return subprocess.run(['python3', str(self.root / 'tools/dev/winbox.py'), *args],
                              env=self.env, cwd=self.root, capture_output=True, text=True, timeout=20)

    def test_desktop_transport_requires_explicit_session_selection(self):
        original = Path.cwd()
        os.chdir(self.root)
        try:
            command = ['./gradlew', ':desktopApp:test', '--rerun', '--tests', 'sample.*']
            self.assertIsNone(winbox.portable_args(command, self.root))
            self.assertEqual(winbox.portable_args(command, self.root, desktop=True), command[1:])
            for task in (':desktopApp:run', ':desktopApp:packageMsi', ':desktopApp:check', ':server:app:test'):
                self.assertIsNone(winbox.portable_args(['./gradlew', task], self.root, desktop=True))
            self.assertEqual(winbox.session_option(['--interactive', *command]), (command, True, True, None))
            self.assertEqual(winbox.session_option(['--interactive=12', *command]), (command, True, True, 12))
            self.assertEqual(winbox.session_option(['--ssh', *command]), (command, True, False, None))
            for option in ('--interactive=0', '--interactive=-1', '--interactive=bad', '--interactive=4294967295'):
                with self.assertRaises(ValueError):
                    winbox.session_option([option, *command])
        finally:
            os.chdir(original)

    def test_unavailable_console_returns_logs_and_never_falls_back(self):
        self.executable(self.bin / 'ssh', '#!/bin/sh\ncase "$*" in *-File*) exit 69;; esac\nexit 0\n')
        self.executable(self.bin / 'scp', '''#!/usr/bin/env python3
import json, pathlib, shutil, sys
source, dest = sys.argv[-2:]
if source.endswith('/result.json'):
    pathlib.Path(dest).write_text(json.dumps({'exit':69, 'seconds':0, 'artifacts':False, 'logs':True}))
elif source.endswith('/stdout.log'): pathlib.Path(dest).write_text('')
elif source.endswith('/gradle.log') or source.endswith('/stderr.log'):
    pathlib.Path(dest).write_text('INTERACTIVE_UNAVAILABLE: no active console')
elif source.endswith('request.json'): shutil.copyfile(source, 'captured-request.json')
''')
        result = self.run_heavy('--local', 'python3', 'tools/dev/winbox.py', '--interactive=12',
                                './gradlew', ':desktopApp:test', '--tests', 'sample.*')
        self.assertEqual(result.returncode, 69, result.stdout + result.stderr)
        self.assertNotIn('LOCAL :desktopApp:test', result.stdout)
        request = json.loads((self.root / 'captured-request.json').read_text())
        self.assertTrue(request['interactive'])
        self.assertEqual(request['consoleSessionId'], 12)
        output = next((self.root / '.coach-winbox').iterdir())
        self.assertIn('INTERACTIVE_UNAVAILABLE', (output / 'stderr.log').read_text())
        self.assertEqual((output / 'stdout.log').read_text(), '')

    def test_explicit_windows_offline_and_unsupported_commands_cannot_fall_back(self):
        self.executable(self.bin / 'ssh', '#!/bin/sh\nexit 255\n')
        for option in ('--interactive', '--ssh'):
            result = self.run_winbox(option, './gradlew', ':desktopApp:test')
            self.assertEqual(result.returncode, 70, result.stdout + result.stderr)
            self.assertNotIn('LOCAL', result.stdout)
        result = self.run_winbox('--interactive', './gradlew', ':desktopApp:packageMsi')
        self.assertEqual(result.returncode, 70, result.stdout + result.stderr)
        self.assertIn('unsupported explicit Windows command', result.stderr)

    def test_remote_routes_only_portable_explicit_tasks(self):
        original = Path.cwd()
        os.chdir(self.root)
        try:
            for command in (
                ['./gradlew', ':androidApp:assembleDebug', ':shared:domain:jvmTest'],
                ['bash', './gradlew', 'shared:client:jvmTest', '--tests', 'a test.*'],
                ['./gradlew', ':shared:ui:compileAndroidMain', '-PcoachApplicationId=com.coach.candidate'],
            ):
                self.assertIsNotNone(winbox.portable_args(command, self.root), command)
            for task in (':server:app:test', 'test', 'build', ':desktopApp:packageDmg',
                         ':androidApp:connectedDebugAndroidTest', ':shared:ui:macosTest',
                         ':shared:ui:allTests', ':shared:domain:check', ':shared:ui:jvmTest',
                         ':shared:ui:testDebugUnitTest', ':shared:ui:check', ':shared:ui:compileTestKotlinJvm'):
                self.assertIsNone(winbox.portable_args(['./gradlew', task], self.root), task)
            for tail in (['-p', '/another'], ['--init-script=x'], ['-Pvalue=a&b'],
                         ['-Dorg.gradle.java.home=C:\\jdk'], ['--tests'], ['-Duser.home=alternate'],
                         ['-Dorg.gradle.jvmargs=-Duser.home=alternate']):
                self.assertIsNone(winbox.portable_args(['./gradlew', ':shared:domain:jvmTest', *tail], self.root), tail)
        finally:
            os.chdir(original)

    def test_snapshot_contains_unstaged_and_new_files_without_changing_index(self):
        (self.root / 'source').write_text('staged\n')
        self.git('add', 'source')
        (self.root / 'source').write_text('unstaged\n')
        (self.root / 'new.kt').write_text('new\n')
        index = (self.root / '.git/index').read_bytes()
        head = self.git('rev-parse', 'HEAD')
        with tempfile.TemporaryDirectory() as temp:
            sha = winbox.snapshot(self.root, Path(temp), 'refs/test/snapshot')
            self.assertEqual(self.git('show', sha + ':source'), 'unstaged')
            self.assertEqual(self.git('show', sha + ':new.kt'), 'new')
            self.assertTrue((Path(temp) / 'source.bundle').exists())
        self.assertEqual(index, (self.root / '.git/index').read_bytes())
        self.assertEqual(head, self.git('rev-parse', 'HEAD'))

    def test_caller_limits_are_replaced_without_duplicate_gradle_options(self):
        self.assertEqual(winbox.runner_args([
            ':androidApp:assembleDebug', '--max-workers=8', '--console', 'rich',
            '--priority=normal', '--daemon', '--no-configuration-cache',
            '--tests', 'sample.*', '--no-build-cache',
        ]), [':androidApp:assembleDebug', '--tests', 'sample.*', '--no-build-cache'])

    def test_artifacts_restore_standard_paths_and_reject_escape(self):
        archive = self.root / 'artifacts.zip'
        with zipfile.ZipFile(archive, 'w') as bundle:
            bundle.writestr('androidApp/build/outputs/apk/debug/app.apk', b'apk')
            bundle.writestr('shared/domain/build/test-results/jvmTest/TEST.xml', '<testsuite/>')
            bundle.writestr('shared/ui/build/screens/records-attempt.png', b'new screenshot')
        winbox.restore(archive, self.root)
        self.assertEqual((self.root / 'androidApp/build/outputs/apk/debug/app.apk').read_bytes(), b'apk')
        self.assertEqual((self.root / 'shared/ui/build/screens/records-attempt.png').read_bytes(), b'new screenshot')
        for name in ('../build/escape', 'build/../../escape', 'C:/build/escape', 'build\\escape', 'source',
                     'shared/ui/build/src/source.kt'):
            with zipfile.ZipFile(archive, 'w') as bundle:
                bundle.writestr(name, 'unsafe')
            with self.assertRaises(ValueError):
                winbox.restore(archive, self.root)

    def test_unreachable_and_busy_fall_back_and_propagate_local_exit(self):
        for code in (255, 75):
            with self.subTest(code=code):
                self.executable(self.bin / 'ssh', f'#!/bin/sh\ncase "$*" in *-File*) exit {code};; esac\nexit {255 if code == 255 else 0}\n')
                self.executable(self.bin / 'scp', '#!/bin/sh\nexit 0\n')
                result = self.run_heavy('./gradlew', ':androidApp:assembleDebug')
                self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
                self.assertIn('LOCAL :androidApp:assembleDebug', result.stdout)
                self.assertIn(f'exit={code}', result.stderr)
                self.assertIn('host=Mac local lock', result.stderr)
                self.assertEqual(self.git('for-each-ref', 'refs/coach-heavy/'), '')
                self.assertFalse((self.root / 'locks/local.2').exists())

    def test_build_failure_is_not_retried_locally(self):
        self.executable(self.bin / 'ssh', '#!/bin/sh\ncase "$*" in *-File*) exit 42;; esac\nexit 0\n')
        self.executable(self.bin / 'scp', '''#!/usr/bin/env python3
import json, pathlib, sys
source, dest = sys.argv[-2:]
if source.endswith('/result.json'):
    pathlib.Path(dest).write_text(json.dumps({'exit':42, 'seconds':1, 'artifacts':False}))
elif source.endswith('/gradle.log'): pathlib.Path(dest).write_text('BUILD FAILED')
''')
        result = self.run_heavy('./gradlew', ':shared:domain:jvmTest')
        self.assertEqual(result.returncode, 42, result.stdout + result.stderr)
        self.assertNotIn('LOCAL', result.stdout)
        self.assertNotIn('host=Mac', result.stderr)
        self.assertFalse((self.root / 'locks').exists())

    def test_server_mixed_emulator_and_forced_local_never_contact_winbox(self):
        self.executable(self.bin / 'ssh', '#!/bin/sh\necho UNEXPECTED_SSH >&2\nexit 99\n')
        for args in (
            ['./gradlew', ':server:app:test'],
            ['./gradlew', ':androidApp:assembleDebug', ':server:app:test'],
            ['./gradlew', ':shared:ui:jvmTest'],
            ['./gradlew', ':androidApp:assembleDebug', ':shared:ui:check'],
            ['--emulator', './gradlew', ':androidApp:assembleDebug'],
            ['--local', './gradlew', ':shared:domain:jvmTest'],
        ):
            with self.subTest(args=args):
                result = self.run_heavy(*args)
                self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
                self.assertNotIn('UNEXPECTED_SSH', result.stderr)

    def test_jvm_environment_overrides_stay_local(self):
        self.executable(self.bin / 'ssh', '#!/bin/sh\necho UNEXPECTED_SSH >&2\nexit 99\n')
        for key in ('JAVA_OPTS', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            with self.subTest(key=key):
                self.env[key] = '-Duser.home=/private/fixture'
                result = self.run_heavy('./gradlew', ':androidApp:assembleDebug')
                self.env.pop(key)
                self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
                self.assertNotIn('UNEXPECTED_SSH', result.stderr)

    def test_android_configuration_uses_java_properties_and_build_defaults(self):
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
        config = self.root / 'config.properties'
        def fingerprint():
            result = subprocess.run([java, str(HERE / 'WinboxConfig.java'), str(config)],
                                    capture_output=True, text=True, check=True, timeout=30)
            return json.loads(result.stdout)
        def hashes(host='', course='', port='18791'):
            return {key: hashlib.sha256(value.encode()).hexdigest() for key, value in
                    zip(('coach.tailHost', 'coach.course', 'coach.port'), (host, course, port))}
        self.assertEqual(fingerprint(), hashes())
        config.write_bytes(b'coach.tailHost: 10.0.0.1\ncoach.course=synthetic\\u4e2d\\\n  continuation\n'
                           b'coach.port = 19876\nunrelated.key=ignored\n')
        self.assertEqual(fingerprint(), hashes('10.0.0.1', 'synthetic中continuation', '19876'))
        config.write_text('coach.port=\n')
        self.assertEqual(fingerprint(), hashes(port=''))


if __name__ == '__main__':
    unittest.main(verbosity=2)

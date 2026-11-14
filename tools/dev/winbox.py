#!/usr/bin/env python3
"""Winbox transport for heavy. 76 means local-only; 75/255 allow local fallback."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
SSH_OPTIONS = ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15',
               '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3']


def portable_args(command, root, desktop=False):
    """Only explicit portable projects; ambiguous selectors stay on the Mac."""
    args = list(command)
    if args and args[0] in ('bash', 'sh'):
        args.pop(0)
    if not args or Path(args[0]).resolve() != root / 'gradlew':
        return None
    args.pop(0)
    tasks = []
    value_next = False
    for arg in args:
        # Never pass shell syntax to Windows, even via native PS argument binding.
        if re.search(r'[\r\n"%&|<>^`$;]', arg):
            return None
        if value_next:
            value_next = False
            continue
        if arg in ('--tests', '--console', '--max-workers', '--warning-mode', '--priority'):
            value_next = True
        elif arg.startswith(('-P', '-D')):
            # Machine paths / explicit init scripts cannot be transported.
            if ('/' in arg or '\\' in arg
                    or re.search(r'user\.home|org\.gradle\.(?:java\.home|jvmargs)', arg)):
                return None
        elif arg in ('--offline', '--rerun', '--rerun-tasks', '--stacktrace', '--info', '--debug',
                     '--no-daemon', '--daemon', '--no-configuration-cache',
                     '--configuration-cache', '--no-build-cache', '--build-cache',
                     '--continue', '--dry-run', '-q', '--quiet'):
            pass
        elif arg.startswith(('--console=', '--max-workers=', '--warning-mode=', '--priority=')):
            pass
        elif arg.startswith('-'):
            return None
        else:
            task = arg if arg.startswith(':') else ':' + arg
            if desktop and task == ':desktopApp:test':
                tasks.append(task)
                continue
            if not re.fullmatch(r':(?:androidApp|shared:(?:domain|protocol|client|ui)):[A-Za-z0-9]+', task):
                return None
            if re.search(r'connected|device|managed|install|uninstall|run|ios|macos|native|xcode|publish', task, re.I):
                return None
            # UI tests read ~/Coach/corpus/render and produce local evidence.
            # Keep all test variants (including compileTest/testClasses) local.
            if task.startswith(':shared:ui:') and re.search(r'test|check', task, re.I):
                return None
            # KMP aggregate tests/check/build can include native targets later.
            if task.startswith(':shared:') and task.rsplit(':', 1)[1] in ('allTests', 'check', 'build'):
                return None
            tasks.append(task)
    return args if tasks and not value_next else None


def session_option(command):
    """Explicit desktop transport; --interactive=ID also pins the console session.

    tools/dev/heavy --local python3 tools/dev/winbox.py --interactive ./gradlew :desktopApp:test
    Use --ssh instead for the Session 0 diagnostic. Without either, routing is unchanged.
    """
    command = list(command)
    option = command[0] if command else ''
    if option == '--ssh':
        return command[1:], True, False, None
    if option == '--interactive' or option.startswith('--interactive='):
        expected = None
        if '=' in option:
            value = option.partition('=')[2]
            if not value.isdecimal() or not 1 <= int(value) < 0xFFFFFFFF:
                raise ValueError('--interactive=SESSION_ID requires a positive console session ID')
            expected = int(value)
        return command[1:], True, True, expected
    return command, False, False, None


def runner_args(args):
    """Replace runner-owned switches, avoiding Gradle's duplicate-option error."""
    result = []
    skip = False
    for arg in args:
        if skip:
            skip = False
            continue
        if arg in ('--max-workers', '--console', '--priority'):
            skip = True
        elif arg.startswith(('--max-workers=', '--console=', '--priority=')):
            continue
        elif arg in ('--daemon', '--no-daemon', '--configuration-cache', '--no-configuration-cache'):
            continue
        else:
            result.append(arg)
    return result


def android_config_fingerprint():
    """Use the same Properties.load(InputStream) semantics as the build script.

    Send only hashes of the three consumed values, never the configuration file.
    """
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    result = subprocess.run([java, str(HERE / 'WinboxConfig.java')], capture_output=True)
    if result.returncode:
        raise ValueError('cannot fingerprint local Android configuration (Java 17+ required)')
    hashes = json.loads(result.stdout)
    if (set(hashes) != {'coach.tailHost', 'coach.course', 'coach.port'}
            or any(not re.fullmatch(r'[a-f0-9]{64}', v) for v in hashes.values())):
        raise ValueError('invalid Android configuration fingerprint')
    return hashes


def git(root, *args, env=None):
    return subprocess.check_output(['git', '-C', str(root), *args], env=env, text=True).strip()


def snapshot(root, tmp, ref):
    """Ship current files using a private index; never touch the user's index/HEAD."""
    env = dict(os.environ, GIT_INDEX_FILE=str(tmp / 'index'))
    git(root, 'read-tree', 'HEAD', env=env)
    git(root, 'add', '-A', '--', '.', env=env)
    tree = git(root, 'write-tree', env=env)
    head = git(root, 'rev-parse', 'HEAD')
    sha = head
    if tree != git(root, 'rev-parse', 'HEAD^{tree}'):
        sha = git(root, 'commit-tree', tree, '-p', head, '-m', 'heavy: worktree snapshot')
    git(root, 'update-ref', ref, sha)
    git(root, 'bundle', 'create', str(tmp / 'source.bundle'), ref)
    return sha


def restore(archive, root):
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or '..' in path.parts or '\\' in entry.filename
                    or ':' in entry.filename
                    or not any(path.parts[n:n+2] in (('build', 'outputs'), ('build', 'reports'),
                                                   ('build', 'test-results'), ('build', 'screens'))
                               for n in range(len(path.parts)-1))):
                raise ValueError('invalid artifact path: ' + entry.filename)
            target = root.joinpath(*path.parts)
            if not target.resolve().is_relative_to(root.resolve()):
                raise ValueError('artifact escapes repository: ' + entry.filename)
        bundle.extractall(root)


def main(command):
    command, explicit, interactive, expected_session = session_option(command)
    executable = command[1] if command and command[0] in ('bash', 'sh') and len(command) > 1 else (command[0] if command else '')
    if Path(executable).name != 'gradlew':
        if explicit:
            raise ValueError('explicit Windows transport requires ./gradlew at the repository root')
        return 76
    root = Path(git(Path.cwd(), 'rev-parse', '--show-toplevel')).resolve()
    args = portable_args(command, root, desktop=explicit)
    if args is None or Path.cwd().resolve() != root:
        if explicit:
            raise ValueError('unsupported explicit Windows command; desktop transport supports :desktopApp:test')
        return 76
    # These can alter user.home/Gradle configuration on the caller only.
    if any(os.environ.get(key) for key in ('JAVA_OPTS', 'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS')):
        if explicit:
            raise ValueError('explicit Windows transport cannot carry local JVM environment overrides')
        return 76
    host = os.environ.get('COACH_WINBOX_HOST', 'winbox')
    if not re.fullmatch(r'[A-Za-z0-9_.@-]+', host) or host.startswith('-'):
        raise ValueError('COACH_WINBOX_HOST must be an SSH host alias')
    mode = os.environ.get('COACH_WINBOX_MODE', 'isolated')
    if mode not in ('isolated', 'reuse'):
        raise ValueError('COACH_WINBOX_MODE must be isolated or reuse')
    job = 'heavy-' + uuid.uuid4().hex
    out = root / '.coach-winbox' / job
    out.mkdir(parents=True)
    remote = 'D:/agent/logs/' + job
    started = time.monotonic()
    print(f'Coach heavy: host=winbox ({host}) job={job} output={out.relative_to(root)}', flush=True)

    def ssh(script, **kwargs):
        return subprocess.run(['ssh', *SSH_OPTIONS, host,
                               'powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass ' + script], **kwargs)

    def scp(source, dest):
        # scp uses SSH's 255 for connection failure, but may also return 1.
        result = subprocess.run(['scp', '-q', *SSH_OPTIONS, source, dest], capture_output=True)
        if result.returncode:
            message = result.stderr.decode(errors='replace')
            if result.returncode == 255 or re.search(r'connection|could not resolve|no route|network is unreachable', message, re.I):
                raise ConnectionError(f'scp exit={result.returncode}: {message}')
            raise ValueError(f'scp failed: {message}')

    ref = 'refs/coach-heavy/' + job
    try:
        # Probe first, so an offline winbox costs no bundle write on the Mac.
        probe = ssh(f'-Command "New-Item -ItemType Directory -Force {remote} | Out-Null"')
        if probe.returncode:
            if explicit:
                return 70  # An explicitly requested Windows session must never fall back to Mac.
            return 255 if probe.returncode == 255 else 75
        try:
            android_config = android_config_fingerprint() if any(
                arg.lstrip(':').startswith('androidApp:') for arg in args) else None
        except (OSError, ValueError) as error:
            print(f'Coach heavy: {error}', file=sys.stderr)
            return 70 if explicit else 75
        with tempfile.TemporaryDirectory(prefix='coach-winbox-') as scratch:
            tmp = Path(scratch)
            sha = snapshot(root, tmp, ref)
            config = dict(job=job, sha=sha, args=runner_args(args), originalArgs=args, mode=mode,
                          interactive=interactive, consoleSessionId=expected_session,
                          interactiveSeconds=int(os.environ.get('COACH_WINBOX_INTERACTIVE_SECONDS', '900')),
                          androidConfig=android_config,
                          queueSeconds=int(os.environ.get('COACH_WINBOX_QUEUE_SECONDS', '900')))
            (out / 'request.json').write_text(json.dumps(config, indent=2) + '\n')
            scp(str(tmp / 'source.bundle'), f'{host}:{remote}/source.bundle')
            scp(str(out / 'request.json'), f'{host}:{remote}/request.json')
            scp(str(HERE / 'winbox.ps1'), f'{host}:{remote}/runner.ps1')
            scp(str(HERE / 'WinboxConfig.java'), f'{host}:{remote}/WinboxConfig.java')
        with subprocess.Popen(['ssh', *SSH_OPTIONS, host,
                               f'powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File {remote}/runner.ps1 -Job {job}'],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            try:
                with (out / 'remote.log').open('wb') as log:
                    for line in process.stdout:
                        log.write(line)
                        sys.stdout.buffer.write(line)
                        sys.stdout.flush()
                remote_code = process.wait()
            except BaseException:
                process.terminate()
                process.wait()
                raise
        if remote_code in (75, 255) or (remote_code == 70 and not explicit):
            return 70 if explicit else remote_code
        scp(f'{host}:{remote}/result.json', str(out / 'result.json'))
        report = json.loads((out / 'result.json').read_text(encoding='utf-8-sig'))
        if remote_code != report['exit']:
            raise ValueError('remote process and result exit codes disagree')
        scp(f'{host}:{remote}/gradle.log', str(out / 'gradle.log'))
        if report.get('logs'):
            for name in ('stdout.log', 'stderr.log'):
                scp(f'{host}:{remote}/{name}', str(out / name))
        if report.get('artifacts'):
            scp(f'{host}:{remote}/artifacts.zip', str(out / 'artifacts.zip'))
            restore(out / 'artifacts.zip', root)
        print(f'Coach heavy: host=winbox sha={sha} exit={report["exit"]} '
              f'wall={time.monotonic()-started:.2f}s gradle={report["seconds"]}s', flush=True)
        return int(report['exit'])
    except ConnectionError as error:
        print(f'Coach heavy: transport failed: {error}', file=sys.stderr)
        return 70 if explicit else 255
    finally:
        git(root, 'update-ref', '-d', ref)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(130))
    try:
        sys.exit(main(sys.argv[1:]))
    except KeyboardInterrupt:
        sys.exit(130)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Coach heavy: winbox preparation failed: {error}', file=sys.stderr)
        sys.exit(70)

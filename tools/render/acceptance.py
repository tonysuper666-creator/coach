"""Acceptance gate: shape -> performance -> diagnostic, every child exit code kept.

The exit-code rules live in check-android.py. That file is a script whose body drives an
emulator, so importing it would start one; only its pure gate functions are compiled out of
its source here, which keeps the rules in a single place. Exit 2 keeps its meaning (the shape
check never observed the process reclaim) and is reported as neither 0 nor a plain 1.

Child commands default to the repository scripts behind them and can be pointed at the phone
deployment's own scripts (shapes-phone.py, perf-logged.py, trace-diagnostic.py):

  ACCEPT_SHAPE_CMD / ACCEPT_PERF_CMD / ACCEPT_DIAG_CMD   shell-style command line
  ACCEPT_TIMEOUT                                          seconds per child (default 1800)

  python3 tools/render/acceptance.py --dry-run --shape 0 --perf 0 --diag 0   # exits 0
  python3 tools/render/acceptance.py --dry-run --shape 1 --perf 0 --diag 0   # exits 1
  python3 tools/render/acceptance.py --dry-run --shape 2 --perf 0 --diag 0   # exits 2, not a pass
"""
import argparse, ast, json, os, shlex, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GATE_FUNCTIONS = ('combine_exits', 'aggregate_acceptance')
CHECKS = (('shape', 'ACCEPT_SHAPE_CMD', 'check-android.py'),
          ('performance', 'ACCEPT_PERF_CMD', 'perf-device.py'),
          ('diagnostic', 'ACCEPT_DIAG_CMD', 'trace-diagnostic.py'))


def gate_functions():
    path = HERE / 'check-android.py'
    tree = ast.parse(path.read_text())
    defs = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in GATE_FUNCTIONS]
    missing = set(GATE_FUNCTIONS) - {node.name for node in defs}
    if missing:
        raise SystemExit(f'{path.name} defines no {", ".join(sorted(missing))}')
    namespace = {'__name__': path.stem}
    exec(compile(ast.Module(body=defs, type_ignores=[]), str(path), 'exec'), namespace)
    return namespace


def child_command(env_name, script):
    override = os.environ.get(env_name, '').strip()
    if override:
        return shlex.split(override)
    path = HERE / script
    return [sys.executable, str(path)] if path.is_file() else None


def run_child(command, timeout):
    try:
        return subprocess.run(command, timeout=timeout).returncode
    except subprocess.TimeoutExpired:
        print(f'timed out after {timeout}s', flush=True)
        return 1
    except OSError as error:
        print(f'cannot run: {error}', flush=True)
        return 1


def parse(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--dry-run', action='store_true', help='inject the child exit codes instead of running the checks')
    parser.add_argument('--shape', type=int, default=0)
    parser.add_argument('--perf', type=int, default=0)
    parser.add_argument('--diag', type=int, default=0)
    parser.add_argument('--out', type=Path, help='also write the execution record to this path')
    parser.add_argument('--timeout', type=float, default=float(os.environ.get('ACCEPT_TIMEOUT', '1800')))
    return parser.parse_args(argv)


def main(argv):
    args = parse(argv)
    aggregate = gate_functions()['aggregate_acceptance']
    codes = {name: None for name, _, _ in CHECKS}
    commands = {name: None for name, _, _ in CHECKS}
    if args.dry_run:
        codes = {'shape': args.shape, 'performance': args.perf, 'diagnostic': args.diag}
        for name, code in codes.items():
            print(f'[{name}] dry-run exit={code}', flush=True)
    else:
        for name, env_name, script in CHECKS:
            command = child_command(env_name, script)
            commands[name] = ' '.join(command) if command else None
            print(f'[{name}] ' + (commands[name] or f'{script} missing, counted as a failure'), flush=True)
            codes[name] = run_child(command, args.timeout) if command else 1
            if codes['shape'] == 2:
                print('reclaim_not_observed restoration_not_executed', flush=True)
                break
    total = aggregate(codes['shape'], codes['performance'], codes['diagnostic'])
    record = {'shape_exit': codes['shape'], 'performance_exit': codes['performance'],
              'diagnostic_exit': codes['diagnostic'], 'aggregate_exit': total,
              'dry_run': args.dry_run, 'commands': commands}
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))
    return total


if __name__ == '__main__' and sys.argv[1:] == ['--self-test']:
    import tempfile, unittest

    script = str(Path(__file__).resolve())
    python = sys.executable

    class SelfTest(unittest.TestCase):
        def cli(self, *argv, env=None):
            return subprocess.run([python, script, *argv], capture_output=True, text=True,
                                  env=dict(os.environ, **(env or {})), timeout=120)

        def test_dry_run_all_green(self):
            self.assertEqual(self.cli('--dry-run', '--shape', '0', '--perf', '0', '--diag', '0').returncode, 0)

        def test_dry_run_failure_is_not_swallowed(self):
            for flag in ('--shape', '--perf', '--diag'):
                with self.subTest(flag=flag):
                    self.assertEqual(self.cli('--dry-run', flag, '1').returncode, 1)

        def test_dry_run_keeps_reclaim_code(self):
            self.assertEqual(self.cli('--dry-run', '--shape', '2').returncode, 2)

        def test_real_mode_failure_is_not_swallowed(self):
            result = self.cli(env={'ACCEPT_SHAPE_CMD': f'{python} -c "import sys; sys.exit(0)"',
                                   'ACCEPT_PERF_CMD': f'{python} -c "import sys; sys.exit(1)"',
                                   'ACCEPT_DIAG_CMD': f'{python} -c "import sys; sys.exit(0)"'})
            self.assertEqual(result.returncode, 1)

        def test_real_mode_stops_at_two_and_keeps_it(self):
            with tempfile.TemporaryDirectory() as directory:
                marker, out = Path(directory) / 'perf-ran', Path(directory) / 'execution.json'
                perf = Path(directory) / 'perf.py'
                perf.write_text(f'from pathlib import Path\nPath({str(marker)!r}).write_text("ran")\n')
                result = self.cli('--out', str(out), env={'ACCEPT_SHAPE_CMD': f'{python} -c "import sys; sys.exit(2)"',
                                                          'ACCEPT_PERF_CMD': f'{python} {perf}'})
                self.assertEqual(result.returncode, 2)
                self.assertFalse(marker.exists(), 'performance ran after a shape exit 2')
                record = json.loads(out.read_text())
                self.assertEqual((record['shape_exit'], record['performance_exit'], record['aggregate_exit']), (2, None, 2))

    unittest.main(argv=[sys.argv[0]])
elif __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

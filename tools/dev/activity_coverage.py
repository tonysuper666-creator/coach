"""Coach process-owned coverage protocol. Trusted local control socket, no learner data."""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
import time

try:
    from .ton1175_switch import GateError, get_time, load, require
except ImportError:
    from ton1175_switch import GateError, get_time, load, require


class Control:
    def __init__(self, path):
        path = Path(path)
        require(path.is_absolute() and not path.is_symlink(), 'absolute owned coverage socket required')
        parent, node = path.parent.stat(), path.stat()
        require(parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700
                and node.st_uid == os.getuid() and stat.S_ISSOCK(node.st_mode)
                and stat.S_IMODE(node.st_mode) == 0o600, 'private coverage socket required')
        self.socket = socket.socket(socket.AF_UNIX)
        self.socket.settimeout(5)
        try:
            self.socket.connect(str(path))
            self.stream = self.socket.makefile('rwb', buffering=0)
        except BaseException:
            self.socket.close()
            raise

    def ask(self, command, **kwargs):
        self.stream.write((json.dumps(dict(command=command, **kwargs))+'\n').encode())
        line = self.stream.readline(16385)
        require(len(line) <= 16384 and line.endswith(b'\n'), 'incomplete coverage reply')
        value = json.loads(line)
        require('error' not in value and value.get('protocol') == 1, 'coverage unavailable')
        return value

    def close(self):
        self.stream.close()
        self.socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def healthy(value):
    require(value['failures'] == 0 and value['active'] == 0 and value['workQuiet']
            and not value['closed'], 'activity, unfinished work or log loss')


def same_lifetime(a, b):
    require(all(a[k] == b[k] for k in ('lifetime', 'pid', 'jvmStart')),
            'coverage process lifetime changed')


def observed(proof):
    coverage = proof.get('coverage')
    require(isinstance(coverage, dict) and coverage.get('protocol') == 1,
            'unsupported access-log protocol: lossless coverage cannot be established')
    start, end = coverage['start'], coverage['end']
    healthy(start); healthy(end); same_lifetime(start, end)
    require(start['pid'] == int(proof['pid']), 'coverage PID differs from idle JVM')
    require(start['ingress'] == end['ingress'] and start['rejected'] == end['rejected'],
            'activity during observation')
    require(not start.get('seal') and not end.get('seal'), 'observation was fenced')
    window_start, window_end = get_time(proof['windowStart']), get_time(proof['windowEnd'])
    require(get_time(start['at']) <= window_start <= window_end <= get_time(end['at'])
            and window_end-window_start >= 900 and (end['monoNs']-start['monoNs']) >= 900_000_000_000,
            'coverage does not bracket 900-second observation')
    require(0 <= time.time()-get_time(end['at']) <= 120, 'coverage end stale or future')
    require(abs((get_time(end['at'])-get_time(start['at'])) -
                (end['monoNs']-start['monoNs'])/1e9) <= 2, 'coverage wall/monotonic clocks disagree')
    return coverage, end


def chain(paths, anchor, checkpoints=()):
    """Validate every durable row from this lifetime's open through the live/closed tail.

    Per-row hash links cross daily files. Missing/duplicated/reordered files,
    truncation, partial writes and deletion of the final row all fail against the
    service's independently obtained high-water mark.
    """
    require(paths and len(set(paths)) == len(paths), 'coverage log set missing or duplicated')
    rows = []
    for name in paths:
        path = Path(name)
        require(path.is_file() and not path.is_symlink(), 'coverage log missing')
        data = path.read_bytes()
        require(data and data.endswith(b'\n'), 'incomplete coverage log')
        for raw in data.splitlines():
            row = json.loads(raw)
            require(row.get('protocol') == 1, 'unsupported coverage row')
            same_lifetime(row, anchor)
            rows.append((row, hashlib.sha256(raw).hexdigest()))
    rows.sort(key=lambda pair: pair[0]['seq'])
    previous, active, ingress, rejected, last_mono = '0'*64, set(), 0, 0, -1
    for number, (row, sha) in enumerate(rows, 1):
        require(row['seq'] == number and row['prev'] == previous and row['failures'] == 0,
                'coverage sequence, rotation link or write loss')
        require(row['monoNs'] >= last_mono, 'coverage monotonic clock regressed')
        last_mono = row['monoNs']
        event = row['event']
        if event == 'open':
            require(number == 1, 'duplicate coverage open')
        elif event == 'begin':
            ingress += 1
            require(row['id'] == ingress and row['kind'] in ('http', 'ws'), 'invalid ingress')
            active.add(ingress)
        elif event == 'end':
            require(row['id'] in active, 'unmatched completion')
            active.remove(row['id'])
        elif event == 'rejected':
            rejected += 1
        else:
            require(event in ('seal', 'release', 'closed'), 'coverage failure or unknown event')
        require(row['active'] == len(active) and row['ingress'] == ingress and row['rejected'] == rejected,
                'coverage counters do not reconcile')
        previous = sha
        for point in checkpoints:
            if point['seq'] == number:
                same_lifetime(point, row)
                require(point['tail'] == sha and all(point[key] == row[key] for key in
                        ('ingress', 'active', 'failures', 'rejected')),
                        'observation checkpoint is not in durable chain')
                require(row['monoNs'] <= point['monoNs'] <= anchor['monoNs']
                        and get_time(row['at']) <= get_time(point['at']) <= get_time(anchor['at']),
                        'checkpoint clock outside durable/live bounds')
    require(all(1 <= point['seq'] <= len(rows) for point in checkpoints), 'checkpoint missing')
    require(rows[0][0]['event'] == 'open' and len(rows) == anchor['seq']
            and previous == anchor['tail'] and ingress == anchor['ingress']
            and len(active) == anchor['active'] and rejected == anchor['rejected'],
            'coverage tail missing or changed')
    return rows[-1][0]


def verify(evidence, paths, *, stopped=False, seal=None):
    proof = load(evidence)
    coverage, end = observed(proof)
    if stopped:
        # A closure alone is insufficient: it must carry this transaction's live seal nonce.
        require(seal is not None, 'held coverage seal required')
        candidates = [json.loads(line) for path in paths for line in Path(path).read_bytes().splitlines()]
        require(candidates, 'closed coverage missing')
        final = max(candidates, key=lambda row: row['seq'])
        same_lifetime(final, end)
        anchor = dict(final, tail=hashlib.sha256(
            next(line for path in paths for line in Path(path).read_bytes().splitlines()
                 if json.loads(line) == final)).hexdigest())
        require(final['event'] == 'closed' and final.get('seal') == seal, 'shutdown barrier incomplete')
        try:
            os.kill(int(end['pid']), 0)
        except ProcessLookupError:
            pass
        else:
            raise GateError('observed process still exists after stop')
    else:
        with Control(coverage['socket']) as client:
            anchor = client.ask('snapshot')
        healthy(anchor); same_lifetime(end, anchor)
    require(anchor['failures'] == 0 and anchor['active'] == 0
            and anchor['ingress'] == end['ingress'] and anchor['rejected'] == end['rejected'],
            'activity or log failure after observation')
    chain(paths, anchor, (coverage['start'], end))
    return anchor


@contextlib.contextmanager
def stop_guard(evidence, paths):
    coverage, end = observed(load(evidence))
    # The connection itself holds the admission fence. EOF/exception releases it.
    with Control(coverage['socket']) as client:
        sealed = client.ask('seal', expected=end)
        healthy(sealed); same_lifetime(end, sealed)
        require(sealed['ingress'] == end['ingress'] and sealed['rejected'] == end['rejected']
                and sealed.get('seal'), 'invalid stop seal')
        chain(paths, sealed, (coverage['start'], end))
        yield sealed['seal']


def main():
    import argparse
    import sys
    try:
        from .ton1175_switch import atomic
    except ImportError:
        from ton1175_switch import atomic
    parser = argparse.ArgumentParser(description='Collect private service-owned coverage checkpoints around JFR')
    sub = parser.add_subparsers(dest='action', required=True)
    start = sub.add_parser('start')
    start.add_argument('--socket', required=True)
    start.add_argument('--output', required=True)
    end = sub.add_parser('finish')
    for name in ('start', 'idle', 'output'):
        end.add_argument('--'+name, required=True)
    args = parser.parse_args()
    try:
        require(not Path(args.output).exists(), 'output must be new')
        if args.action == 'start':
            with Control(args.socket) as client:
                point = client.ask('snapshot')
            healthy(point)
            require(not point.get('seal'), 'observation already fenced')
            value = dict(protocol=1, socket=args.socket, start=point)
        else:
            coverage = load(args.start)
            require(coverage['protocol'] == 1, 'unsupported checkpoint')
            with Control(coverage['socket']) as client:
                point = client.ask('snapshot')
            value = dict(load(args.idle), coverage=dict(coverage, end=point))
            observed(value)
        atomic(args.output, (json.dumps(value, indent=2)+'\n').encode())
    except (GateError, OSError, ValueError, KeyError, TypeError):
        print('ERROR: coverage unavailable or evidence invalid', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

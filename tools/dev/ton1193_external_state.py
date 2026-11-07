"""Closed, no-follow inventory of Coach state outside transaction-owned data.

Never opens external SQLite databases. Logs and runtime payloads are not read;
production-key is compared only by stat. Reports contain relative paths only.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import stat

from .ton1175_switch import GateError
from .ton1193_trust import _open_path


def category(path):
    if path == 'data' or path.startswith(('data/', 'data.pre-', 'data.candidate-')):
        return 0
    if path in ('installation.json', 'service/installation.json', 'service/releases') or path.startswith('service/releases/'):
        return 0
    if path in ('logs', 'bridge/logs') or path.startswith(('logs/', 'bridge/logs/')):
        return 1
    if path in ('run', 'bridge/work') or path.startswith(('run/', 'bridge/work/')):
        return 2
    if path.startswith('state/activity-') and path.endswith('.sock') and '/' not in path[6:]:
        return 2
    if path in ('bridge/state/daily.json', 'bridge/state/daily.tmp'):
        return 3
    if path == 'bridge/state/production-key':
        return 4
    return 5


def reject(kind, path):
    raise GateError(f'EXTERNAL_STATE_CHANGED category={kind} path={path}')


def inventory(data):
    base = Path(data).parent
    result = {'other': {}, 'logs': {}, 'budget': None, 'key': None}
    root = _open_path(str(base), directory=True)

    def walk(fd, prefix=''):
        for name in sorted(os.listdir(fd)):
            rel = prefix + name
            kind = category(rel)
            if kind == 0:
                continue
            node = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if kind == 2:
                # This subtree is deliberately opaque; never follow its links.
                continue
            if stat.S_ISDIR(node.st_mode):
                if kind in (3, 4):
                    reject(kind, rel)
                child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                try:
                    if (os.fstat(child).st_dev, os.fstat(child).st_ino) != (node.st_dev, node.st_ino):
                        reject(kind, rel)
                    walk(child, rel + '/')
                finally:
                    os.close(child)
            elif not stat.S_ISREG(node.st_mode) or node.st_nlink != 1:
                reject(kind, rel)
            elif kind == 1:
                result['logs'][rel] = [node.st_dev, node.st_ino, node.st_size]
                continue
            elif kind == 4:
                result['key'] = [node.st_dev, node.st_ino, node.st_size, node.st_mtime_ns]
            else:
                if rel == 'bridge/state/daily.tmp':
                    reject(3, rel)
                opened = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=fd)
                try:
                    before = os.fstat(opened)
                    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (node.st_dev, node.st_ino, node.st_size, node.st_mtime_ns):
                        reject(kind, rel)
                    hasher, chunks = hashlib.sha256(), []
                    while chunk := os.read(opened, 65536):
                        hasher.update(chunk)
                        if kind == 3:
                            chunks.append(chunk)
                            if sum(map(len, chunks)) > 4096:
                                reject(3, rel)
                    after = os.fstat(opened)
                    if (after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (before.st_size, before.st_mtime_ns, before.st_ctime_ns):
                        reject(kind, rel)
                    if kind == 3:
                        try:
                            budget = json.loads(b''.join(chunks))
                            if (not isinstance(budget, dict) or 'date' not in budget
                                or set(budget) - {'date', 'claude_calls', 'test_claude_calls'}
                                or datetime.date.fromisoformat(budget['date']).isoformat() != budget['date']
                                or any(type(budget.get(k, 0)) is not int or budget.get(k, 0) < 0
                                       for k in ('claude_calls', 'test_claude_calls'))):
                                reject(3, rel)
                        except (ValueError, TypeError, KeyError):
                            reject(3, rel)
                        result['budget'] = budget
                    else:
                        result['other'][rel] = hasher.hexdigest()
                finally:
                    os.close(opened)
            current = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if (node.st_dev, node.st_ino) != (current.st_dev, current.st_ino):
                reject(kind, rel)
    try:
        walk(root)
    finally:
        os.close(root)
    return result


def compare(before, after):
    for path, old in before['logs'].items():
        new = after['logs'].get(path)
        if new and old[:2] == new[:2] and new[2] < old[2]:
            reject(1, path)
    for path in sorted(before['other'].keys() | after['other'].keys()):
        if before['other'].get(path) != after['other'].get(path):
            reject(5, path)
    if before['key'] != after['key']:
        reject(4, 'bridge/state/production-key')
    old, new = before['budget'], after['budget']
    fields = ('claude_calls', 'test_claude_calls')
    if new is None:
        if old is not None:
            reject(3, 'bridge/state/daily.json')
        return {k: 0 for k in fields}
    same = old is not None and old['date'] == new['date']
    if not same and new['date'] != datetime.datetime.now().astimezone().date().isoformat():
        reject(3, 'bridge/state/daily.json')
    delta = {k: new.get(k, 0) - (old.get(k, 0) if same else 0) for k in fields}
    if any(v < 0 for v in delta.values()):
        reject(3, 'bridge/state/daily.json')
    return delta

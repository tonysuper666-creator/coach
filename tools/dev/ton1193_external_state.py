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
import re

from .ton1175_switch import GateError
from .ton1193_trust import _open_path
from . import ton1193_usage_schema as usage_schema


def utc_today():
    return datetime.datetime.now(datetime.timezone.utc).date()


def usage_category(path):
    return (path == 'state/diagnostics' or path.startswith('state/diagnostics/')
            or re.fullmatch(r'state/usage-[^/]*\.(md|tmp)', path) is not None)


def usage_stat(path, node):
    if (not stat.S_ISREG(node.st_mode) or node.st_nlink != 1
            or node.st_uid != os.getuid() or node.st_mode & 0o022):
        reject(6, path)
    if path.startswith('state/usage-'):
        match = re.fullmatch(r'state/usage-([0-9]{8})\.md', path)
        try:
            if not match or datetime.datetime.strptime(match[1], '%Y%m%d').strftime('%Y%m%d') != match[1]:
                reject(6, path)
        except ValueError:
            reject(6, path)
        limit = 1024 * 1024
    else:
        if path not in ('state/diagnostics/usage.sqlite', 'state/diagnostics/usage.sqlite-wal',
                        'state/diagnostics/usage.sqlite-shm'):
            reject(6, path)
        limit = 64 * 1024 * 1024
    if node.st_size > limit:
        reject(6, path)
    return [node.st_dev, node.st_ino, node.st_size, node.st_mtime_ns]


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


def inventory(data, usage_proof=None):
    base = Path(data).parent
    result = {'other': {}, 'logs': {}, 'budget': None, 'key': None}
    if usage_schema.enabled(usage_proof):
        result['usage'] = dict(day=utc_today().isoformat(), files={}, diagnostics=False)
    root = _open_path(str(base), directory=True)

    def walk(fd, prefix=''):
        for name in sorted(os.listdir(fd)):
            rel = prefix + name
            kind = category(rel)
            if 'usage' in result and usage_category(rel):
                kind = 6
            if kind == 0:
                continue
            node = os.stat(name, dir_fd=fd, follow_symlinks=False)
            if kind == 2:
                # This subtree is deliberately opaque; never follow its links.
                continue
            if stat.S_ISDIR(node.st_mode):
                if kind in (3, 4) or kind == 6 and rel != 'state/diagnostics':
                    reject(kind, rel)
                if kind == 6:
                    if stat.S_IMODE(node.st_mode) != 0o700 or node.st_uid != os.getuid():
                        reject(6, rel)
                    result['usage']['diagnostics'] = True
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
            elif kind == 6:
                result['usage']['files'][rel] = usage_stat(rel, node)
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


def compare_usage(before, after):
    if ('usage' in before) != ('usage' in after):
        reject(6, 'state/diagnostics')
    if 'usage' not in before:
        return {'changedFiles': 0, 'sizeDelta': 0}
    old, new = before['usage'], after['usage']
    if new['day'] < old['day'] or old['diagnostics'] != new['diagnostics']:
        reject(6, 'state/diagnostics')
    for path in sorted(old['files'].keys() | new['files'].keys()):
        a, b = old['files'].get(path), new['files'].get(path)
        if path.startswith('state/usage-'):
            if b is None:
                reject(6, path)
            day = datetime.datetime.strptime(path[12:20], '%Y%m%d').date().isoformat()
            if not old['day'] <= day <= new['day'] and a != b:
                reject(6, path)
        elif path == 'state/diagnostics/usage.sqlite' and (a is None or b is None or a[:2] != b[:2]):
            reject(6, path)
    return dict(changedFiles=sum(old['files'].get(p) != new['files'].get(p)
                                for p in old['files'].keys() | new['files'].keys()),
                sizeDelta=sum(v[2] for v in new['files'].values()) - sum(v[2] for v in old['files'].values()))


def compare(before, after):
    compare_usage(before, after)
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

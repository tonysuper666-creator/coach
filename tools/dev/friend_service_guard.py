"""Durable legacy-start interlock, shared by every service-controller start.

The policy lives outside releases and stage directories. It is an operator
input approved with the cutover, not a request-controlled configuration file.
"""
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess

try:
    from . import friend_revoke_offline as offline
except ImportError:
    import friend_revoke_offline as offline


class GateError(Exception):
    pass


def require(value):
    if not value:
        raise GateError('friend start gate failed; service must remain stopped')


def private_file(path):
    path = Path(path)
    require(path.is_absolute() and path.resolve(strict=True) == path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
            and stat.S_IMODE(info.st_mode) == 0o600)
    return path


def policy_at(path):
    data = json.loads(private_file(path).read_text())
    require(set(data) == {'version', 'binding', 'capableHeads', 'tailscale', 'publicPort'})
    require(data['version'] == 1 and type(data['publicPort']) is int
            and 1 <= data['publicPort'] <= 65535
            and data['publicPort'] not in (18791, 8790, 8791, 3790, 19540))
    require(isinstance(data['capableHeads'], list) and data['capableHeads']
            and all(isinstance(head, str) and re.fullmatch('[0-9a-f]{40}', head)
                    for head in data['capableHeads']))
    binding = data['binding']
    require(isinstance(binding, dict) and set(binding) == {'database', 'device', 'inode', 'epoch'})
    require(isinstance(binding['epoch'], str) and binding['epoch']
            and all(type(binding[key]) is int for key in ('device', 'inode')))
    offline.check_file(binding)
    require(isinstance(data['tailscale'], str) and Path(data['tailscale']).is_absolute()
            and Path(data['tailscale']).is_file())
    return data


def captured(argv):
    result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, timeout=30)
    require(result.returncode == 0)
    return result.stdout


def close_entry(policy):
    """Remove only our root handler on HTTPS 443, never reset shared rules.

    An unexpected configuration is a refusal, including a handler aimed at the
    owner port. Callers must still stop the service when this check fails.
    """
    cli = policy['tailscale']
    before = json.loads(captured([cli, 'funnel', 'status', '--json']))
    web = before.get('Web') or {}
    owned = [key for key in web if key.endswith(':443')]
    require(len(owned) <= 1)
    if not owned:
        require(not any(key.endswith(':443') and enabled
                        for key, enabled in (before.get('AllowFunnel') or {}).items()))
        return
    key = owned[0]
    require(web[key] == {'Handlers': {'/': {'Proxy': f'http://127.0.0.1:{policy["publicPort"]}'}}})
    captured([cli, 'funnel', '--https=443', 'off'])
    after = json.loads(captured([cli, 'funnel', 'status', '--json']))
    require(key not in (after.get('Web') or {})
            and not (after.get('AllowFunnel') or {}).get(key))
    # Other ports/rules must be byte-value equivalent, modulo absent empty maps.
    for section in ('Web', 'AllowFunnel', 'TCP'):
        expected = dict(before.get(section) or {})
        expected.pop('443' if section == 'TCP' else key, None)
        require(expected == (after.get(section) or {}))
    require({k: v for k, v in before.items() if k not in ('Web', 'AllowFunnel', 'TCP')}
            == {k: v for k, v in after.items() if k not in ('Web', 'AllowFunnel', 'TCP')})


def durable_marker(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(b'{"blocked":1}\n')
        handle.flush()
        os.fsync(handle.fileno())
    sync_directory(path.parent)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


@contextlib.contextmanager
def operation(path):
    path = Path(path)
    # Do not unlink this inode. All stages and the desktop start shortcut share it.
    fd = os.open(path.parent / 'friend-operation.lock',
                 os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'rb') as handle:
        info = os.fstat(handle.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o600)
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def start(path, source_head, database, stop, launch):
    """Hold the operation lock across stop, offline revoke AND startup decision.

    stop() must independently wait for the owned JVM and managed children to
    disappear; neither this operation lock nor SQLite proves process shutdown.
    Any interrupted/failed legacy attempt latches closed. No automated retry
    clears the latch, even when revocation may already have committed.
    """
    path = Path(path)
    with operation(path):
        blocked = path.parent / 'friend-start-blocked.json'
        require(not blocked.exists() and not blocked.is_symlink())
        policy = policy_at(path)
        require(Path(policy['binding']['database']) == Path(database).resolve(strict=True))
        if source_head in policy['capableHeads']:
            launch()
            return
        durable_marker(blocked)
        try:
            close_entry(policy)
        finally:
            # Closing Funnel failing must never leave the candidate running.
            stop()
        counts = offline.revoke(policy['binding'], apply=True)
        require(counts['active_friends'] == 0 and counts['pending_friend_codes'] == 0)
        try:
            launch()
        except BaseException:
            stop()
            raise
        blocked.unlink()
        sync_directory(blocked.parent)

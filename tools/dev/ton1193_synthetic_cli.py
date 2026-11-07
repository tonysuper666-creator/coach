"""Install and verify a synthetic CLI only inside a private isolated fixture."""
import http.client
import json
import os
from pathlib import Path

from .ton1175_switch import atomic, file_hash, require
from .ton1193_trust import _open_path

SOURCE = Path(__file__).with_name('ton1193_synthetic_claude.py')
MODEL = 'claude-ton1193-synthetic'


def target(root):
    from . import ton1193_isolated_controller as isolated
    root = Path(root)
    isolated.fixture_nonce(root)
    home = root / 'h'
    isolated.private_directories(home / '.local/bin', root)
    path = home / '.local/bin/claude'
    require(path.is_relative_to(root) and not path.is_symlink(), 'synthetic CLI outside private root')
    return path


def install(root):
    path = target(root)
    require(not os.path.lexists(path), 'synthetic CLI target occupied')
    atomic(path, SOURCE.read_bytes())
    path.chmod(0o700)
    return {'path': str(path), 'sha256': file_hash(path), 'mode': 0o700, 'cli': 'SYNTHETIC'}


def verify(config):
    root = Path(config['stateDir']).parent
    manifest = Path(config['adapterManifest'])
    require(file_hash(manifest) == config['adapterManifestSha256'], 'isolated manifest changed')
    proof = json.loads(manifest.read_bytes())['isolatedSyntheticCli']
    path = target(root)
    fd = _open_path(str(path))
    try:
        node = os.fstat(fd)
        require(proof == {'path': str(path), 'sha256': file_hash(SOURCE), 'mode': 0o700, 'cli': 'SYNTHETIC'}
                and node.st_uid == os.getuid() and node.st_nlink == 1 and node.st_mode & 0o777 == 0o700
                and file_hash(path) == proof['sha256'], 'synthetic CLI changed')
    finally:
        os.close(fd)
    return proof


def health(config):
    verify(config)
    port = int(config['adapterEnvPins']['BRIDGE_PORT'])
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    try:
        conn.request('GET', '/health')
        response = conn.getresponse()
        require(response.status == 200, 'synthetic bridge health failed')
        value = json.loads(response.read())
    finally:
        conn.close()
    upstreams = value['upstreams']
    require(upstreams['claude']['logged_in'] is True and upstreams['codex']['logged_in'] is False
            and upstreams['agy']['enabled'] is False, 'synthetic fallback isolation failed')
    return {'cli': 'SYNTHETIC', 'codex': {'loggedIn': False}, 'agy': {'enabled': False}}


def log_offsets(config):
    # BRIDGE_LOG_STDOUT=1 is drained by ManagedServices to daily service logs.
    logs = Path(config['stateDir']).parent / 'h/Coach/logs'
    return {str(path): path.stat().st_size for path in logs.glob('bridge-*.log')}


def evidence(config, offsets):
    rows = []
    for path in log_offsets(config):
        with open(path, 'rb') as stream:
            stream.seek(offsets.get(path, 0))
            rows.extend(line.decode().split() for line in stream if b' rid=' in line)
    fields = [dict(item.split('=', 1) for item in row if '=' in item) for row in rows]
    require(fields and all(row.get('served') == MODEL and row.get('fallback') == '0'
                           and row.get('status') == 'complete' for row in fields),
            'synthetic CLI completion evidence missing')
    return dict(health(config), served_by=MODEL, fallback=False, completedRequests=len(fields))


def smoke(config):
    """Check the real bridge protocol before spending 900 seconds recording."""
    status = health(config)
    conn = http.client.HTTPConnection('127.0.0.1', int(config['adapterEnvPins']['BRIDGE_PORT']), timeout=20)
    try:
        conn.request('POST', '/v1/chat/completions', json.dumps({
            'model': 'claude-sonnet-5-5', 'stream': True,
            'messages': [{'role': 'user', 'content': 'Synthetic rehearsal smoke'}]}),
            {'Content-Type': 'application/json', 'X-Coach-Traffic': 'test', 'X-Coach-Fallback': 'off'})
        response = conn.getresponse()
        require(response.status == 200, 'synthetic bridge smoke failed')
        frames = [json.loads(line[6:]) for line in response.read().decode().splitlines()
                  if line.startswith('data: ') and line != 'data: [DONE]']
    finally:
        conn.close()
    ends = [frame['x_coach'] for frame in frames if 'x_coach' in frame]
    require(len(ends) == 1 and ends[0]['status'] == 'complete'
            and ends[0]['served_by'] == MODEL and ends[0]['fallback'] is False,
            'synthetic bridge terminal evidence failed')
    return dict(status, served_by=MODEL, fallback=False)

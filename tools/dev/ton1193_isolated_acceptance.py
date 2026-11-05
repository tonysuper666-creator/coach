"""Partial acceptance hooks for the disposable TON-1193 fixture only.

The verifier deliberately refuses to accept until lecture and exercise checks
are implemented. A successful hello is not a successful switch acceptance.
"""
import argparse
import http.client
import json
from pathlib import Path
import sys
import time

from . import ton1193_isolated_controller as isolated
from .ton1175_switch import GateError, atomic, digest, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot


def observe(config, roots):
    data = Path(roots['home']) / 'Coach/data'
    identity = directory_snapshot(str(data))
    token, _ = _read_file(data / 'device-token', identity, 0o600)
    credential = token.decode('ascii').strip()
    require(bool(credential) and '\n' not in credential and '\r' not in credential,
            'invalid isolated token')
    connection = http.client.HTTPConnection('127.0.0.1', int(config['adapterEnvPins']['COACH_PORT']), timeout=5)
    try:
        connection.request('GET', '/v1/hello', headers={
            'X-Coach-Device': 'desktop', 'Authorization': 'Bearer ' + credential})
        response = connection.getresponse()
        body = response.read(65537)
        require(response.status == 200 and len(body) <= 65536, 'isolated authenticated hello failed')
        hello = json.loads(body)
        require(isinstance(hello['epoch'], str) and bool(hello['epoch'])
                and type(hello['lastSeq']) is int and hello['lastSeq'] >= 0,
                'invalid isolated hello')
        return {'epoch': hello['epoch'], 'lastSeq': hello['lastSeq'], 'tokenSha256': digest(token)}
    finally:
        connection.close()


def run(config_path, action, baseline, started=None, deadline=None):
    config, roots, _ = isolated.fixture(config_path)
    require(baseline == str(Path(config['stateDir']) / 'acceptance-baseline.json'),
            'baseline outside isolated stage')
    state_id = directory_snapshot(config['stateDir'])
    if action == 'snapshot':
        atomic(Path(baseline), json.dumps(observe(config, roots), sort_keys=True).encode())
        return
    require(started is not None and deadline is not None and deadline - started == 300
            and time.time() < deadline, 'invalid acceptance deadline')
    raw, _ = _read_file(baseline, state_id, 0o600)
    before = json.loads(raw)
    current = observe(config, roots)
    require(current['epoch'] == before['epoch'] and current['tokenSha256'] == before['tokenSha256']
            and current['lastSeq'] >= before['lastSeq'], 'isolated identity or ledger changed')
    # Never turn authentication alone into a successful five-minute acceptance.
    raise GateError('isolated lecture and exercise acceptance not implemented')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('action', choices=('snapshot', 'accept'))
    parser.add_argument('baseline')
    parser.add_argument('started', nargs='?', type=float)
    parser.add_argument('deadline', nargs='?', type=float)
    args = parser.parse_args()
    try:
        run(args.config, args.action, args.baseline, args.started, args.deadline)
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, http.client.HTTPException):
        print('isolated acceptance refused', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())

"""Drive synthetic learning only against a validated TON-1193 isolated fixture.

This performs actual tutor requests; it is not proof without the separately
recorded ledger acceptance and real managed bridge/mathsvc evidence.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import struct
import sys
import time

from . import ton1193_isolated_acceptance as acceptance
from . import ton1193_isolated_controller as isolated
from . import ton1193_live as live
from . import ton1193_synthetic_cli as synthetic
from .ton1175_switch import GateError, atomic, digest, require
from .ton1193_transaction import _read_file
from .ton1193_trust import directory_snapshot


class WebSocket:
    def __init__(self, port, token, deadline):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        self.deadline = deadline
        self.pending = []
        try:
            key = base64.b64encode(os.urandom(16)).decode('ascii')
            request = (f'GET /v1/ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
                       'Upgrade: websocket\r\nConnection: Upgrade\r\n'
                       f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
                       f'Authorization: Bearer {token}\r\nX-Coach-Device: desktop\r\n\r\n')
            self.sock.sendall(request.encode('ascii'))
            response = b''
            while not response.endswith(b'\r\n\r\n'):
                response += self.read(1)
                require(len(response) <= 16384, 'oversized websocket handshake')
            lines = response.split(b'\r\n')
            require(lines[0].startswith(b'HTTP/1.1 101 '), 'websocket upgrade refused')
            expected = base64.b64encode(hashlib.sha1(
                (key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
            require(any(line.split(b':', 1)[0].lower() == b'sec-websocket-accept'
                        and line.split(b':', 1)[1].strip() == expected
                        for line in lines[1:] if b':' in line), 'invalid websocket handshake')
        except BaseException:
            self.sock.close()
            raise

    def read(self, size):
        result = b''
        while len(result) < size:
            remaining = self.deadline - time.monotonic()
            require(remaining > 0, 'isolated learning deadline expired')
            self.sock.settimeout(min(remaining, 15))
            part = self.sock.recv(size - len(result))
            require(bool(part), 'websocket closed')
            result += part
        return result

    def send(self, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        require(len(data) < 65536, 'oversized websocket request')
        mask = os.urandom(4)
        size = len(data)
        header = bytes((0x81, 0x80 | size)) if size < 126 else bytes((0x81, 0xfe)) + struct.pack('!H', size)
        self.sock.sendall(header + mask + bytes(x ^ mask[i % 4] for i, x in enumerate(data)))

    def await_type(self, *types):
        for i, frame in enumerate(self.pending):
            if frame.get('t') in types:
                return self.pending.pop(i)
        while True:
            head = self.read(2)
            require(head[0] & 0x80 and not head[1] & 0x80, 'unsupported websocket frame')
            opcode, size = head[0] & 15, head[1] & 127
            if size == 126:
                size = struct.unpack('!H', self.read(2))[0]
            elif size == 127:
                size = struct.unpack('!Q', self.read(8))[0]
            require(size <= 1024 * 1024, 'oversized websocket frame')
            payload = self.read(size)
            require(opcode != 8, 'websocket closed before expected event')
            if opcode != 1:
                continue
            frame = json.loads(payload)
            require(isinstance(frame, dict), 'invalid websocket event')
            if frame.get('t') in types:
                return frame
            self.pending.append(frame)
            require(len(self.pending) <= 1024, 'too many websocket events')

    def close(self):
        self.sock.close()


def run(config_path, baseline, started, deadline, *, recovery=False):
    config, roots, _ = isolated.fixture(config_path)
    def check_phase():
        if recovery:
            from . import ton1193_data_transaction as data
            _, journal = data.load(config)
            require(journal['dataState'] == 'restored', 'isolated data not restored')
            require(live._live_transaction(config['stateDir'], ('rolled_back',)) == 'rolled_back',
                    'isolated recovery lock not held')
        else:
            require(live.live_acceptance(config['stateDir']) == 'validating', 'isolated switch is not validating')
    check_phase()
    require(baseline == str(Path(config['stateDir']) / 'acceptance-baseline.json'), 'baseline outside isolated stage')
    raw, _ = _read_file(baseline, directory_snapshot(config['stateDir']), 0o600)
    before = json.loads(raw)
    now = time.time()
    require(deadline - started == 300 and before['createdAt'] <= started <= now < deadline
            and started - before['createdAt'] <= 120, 'invalid acceptance deadline or baseline')
    current = acceptance.observe(config, roots)
    require(all(current[k] == before[k] for k in ('epoch', 'tokenSha256', 'credentialSha256'))
            and current['lastSeq'] >= before['lastSeq'], 'isolated identity or ledger changed')
    data = Path(roots['home']) / 'Coach/data'
    token, _ = _read_file(data / 'device-token', directory_snapshot(str(data)), 0o600)
    credential = token.decode('ascii').strip()
    require(digest(token) == before['tokenSha256'] and digest(credential.encode()) == before['credentialSha256']
            and credential and '\n' not in credential and '\r' not in credential, 'isolated token changed')
    check_phase()
    synthetic.health(config)
    log = Path(roots['home']) / 'Coach/bridge/logs/bridge.log'
    offset = log.stat().st_size if log.exists() else 0
    ws = WebSocket(int(config['adapterEnvPins']['COACH_PORT']), credential,
                   time.monotonic() + deadline - time.time())
    try:
        common = {'threadId': 'thread-1', 'courseId': 'course-1'}
        ws.send(dict(common, t='tutor.open', kpId='kp-1', conceptIds=['cost'], title='Synthetic objective'))
        ws.await_type('tutor.state')
        ws.send(dict(common, t='msg.ask', text='继续'))
        ws.await_type('msg.start')
        delta = ws.await_type('msg.delta')
        require(bool(delta.get('text') or delta.get('delta')), 'empty lecture')
        require(ws.await_type('msg.end').get('status') == 'complete', 'lecture incomplete')
        while ws.await_type('tutor.state').get('state') != 'AWAIT':
            pass
        ws.send(dict(common, t='msg.ask', text='继续'))
        group = ws.await_type('tutor.cards')
        cards = group['cards']
        require(len(cards) == 3, 'expected three isolated questions')
        answers = {card['attemptId']: card['letterMap'][card['questionId'] + '-0'] for card in cards}
        ws.send(dict(common, t='tutor.answer', groupId=group['groupId'],
                     submissionId='synthetic-submit', answers=answers))
        ws.await_type('tutor.feedback')
    finally:
        ws.close()
    # Validate ledger evidence, original token, and deadline after the requests.
    acceptance.run(config_path, 'accept', baseline, started, deadline)
    check_phase()
    evidence = dict(synthetic.evidence(config, offset), lecture=True, threeAnswers=True,
                    ledgerVerified=True, elapsedSeconds=time.time() - started)
    atomic(Path(config['stateDir']).parent / ('synthetic-learning-restored.json' if recovery else
                                            'synthetic-learning-candidate.json'),
           json.dumps(evidence).encode())
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('baseline')
    parser.add_argument('started', type=float)
    parser.add_argument('deadline', type=float)
    args = parser.parse_args()
    try:
        run(args.config, args.baseline, args.started, args.deadline)
        return 0
    except (GateError, OSError, ValueError, KeyError, TypeError, EOFError):
        print('isolated learning refused', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())

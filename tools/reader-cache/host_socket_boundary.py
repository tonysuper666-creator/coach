"""Single synthetic relay boundary, NOT a netsimd syscall or kernel-send probe."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import threading
import time
from urllib.parse import parse_qs, urlsplit

from fixture_server import FORBIDDEN, PREFIX, TOKEN
from host_clock import sample as clock_sample

MAX_HEADER = 8192
MAX_RESPONSE = 16 * 1024 * 1024
MAX_EVENTS = 512


def digest(data):
    return hashlib.sha256(data).hexdigest()


def clock_identity():
    info = time.get_clock_info('monotonic')
    recording = time.get_clock_info('perf_counter')
    return dict(pid=os.getpid(), implementation=info.implementation, resolutionSeconds=info.resolution,
                recordingImplementation=recording.implementation, recordingResolutionSeconds=recording.resolution)


def headers(data):
    head, separator, body = data.partition(b'\r\n\r\n')
    if not separator or len(head) > MAX_HEADER:
        raise ValueError('incomplete_or_oversized_headers')
    lines = head.decode('ascii').split('\r\n')
    fields = {}
    for line in lines[1:]:
        key, value = line.split(':', 1)
        key = key.lower()
        if key in fields:
            raise ValueError('duplicate_header')
        fields[key] = value.strip()
    return lines[0], fields, body


class SocketBoundaryRelay:
    """Own two sockets; only forward health and one exact, authenticated nonce.

    This changes the diagnostic path. The observed return points belong to this
    Python relay, not netsimd. It does not patch or attach to the SDK process.
    """
    def __init__(self, host, backend_port, probe_id, recording_budget_ns=1_000_000):
        host = str(ipaddress.IPv4Address(host))
        if host == '0.0.0.0' or not 1024 <= backend_port <= 65535 or backend_port in FORBIDDEN:
            raise ValueError('exact_host_and_owned_fixture_port_required')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', probe_id):
            raise ValueError('exact_synthetic_nonce_required')
        if type(recording_budget_ns) is not int or recording_budget_ns <= 0:
            raise ValueError('positive_recording_budget_required')
        self.host, self.backend_port, self.probe_id = host, backend_port, probe_id
        self.budget = recording_budget_ns
        self.listener = socket.socket()
        try:
            self.listener.bind((host, 0))
            self.listener.listen(2)
            self.listener.settimeout(.1)
            self.port = self.listener.getsockname()[1]
            if self.port in FORBIDDEN:
                raise ValueError('production_port_selected')
        except BaseException:
            self.listener.close()
            raise
        self.stop = threading.Event()
        self.done = threading.Event()
        self.lock = threading.Lock()
        self.active = []
        self.records = []
        self.errors = []
        self.forced_close = False
        self.joined = False
        self.used = False
        self.thread = threading.Thread(target=self._serve, name='reader-single-boundary')

    def __enter__(self):
        try:
            self.thread.start()
        except BaseException:
            self.listener.close()
            raise
        return self

    def _io(self, row, operation, side, offset, action):
        called = time.monotonic_ns()
        try:
            result = action()
        except OSError as error:
            recording_start = time.perf_counter_ns()
            returned = time.monotonic_ns()
            event = dict(operation=operation, side=side, offset=offset, callNs=called,
                         returnNs=returned, error=type(error).__name__, bytes=None)
            self._record(row, event, recording_start)
            raise
        recording_start = time.perf_counter_ns()
        returned = time.monotonic_ns()
        event = dict(operation=operation, side=side, offset=offset, callNs=called,
                     returnNs=returned, bytes=len(result) if isinstance(result, bytes) else result,
                     error=None)
        self._record(row, event, recording_start)
        return result

    def _record(self, row, event, recording_start):
        if len(row['events']) >= MAX_EVENTS:
            row['droppedEvents'] += 1
            return
        event.update(sequence=len(row['events']) + 1, tid=threading.get_native_id(),
                     returnClock=clock_sample())
        row['events'].append(event)
        # Observed local recording interval only, not a full probe overhead bound.
        event['recordEndNs'] = time.monotonic_ns()
        event['recordingStartPerfNs'] = recording_start
        event['recordingEndPerfNs'] = time.perf_counter_ns()
        event['observedRecordingNs'] = event['recordingEndPerfNs'] - recording_start

    def _send(self, sock, data, row, operation, side, offset=0):
        sent = 0
        while sent < len(data):
            count = self._io(row, operation, side, offset + sent,
                             lambda: sock.send(memoryview(data)[sent:]))
            if not count:
                raise ValueError('zero_byte_send')
            sent += count

    def _serve(self):
        while not self.stop.is_set():
            try:
                incoming, peer = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                if not self.stop.is_set():
                    self.errors.append('accept_failed')
                return
            row = dict(connection=len(self.records) + 1, events=[], droppedEvents=0, errors=[],
                       peerPort=peer[1], listenerPort=self.port, incomingFd=incoming.fileno(),
                       acceptedNs=time.monotonic_ns(), closed=False, probeId=None)
            self.records.append(row)
            incoming.settimeout(15)
            with incoming:
                with self.lock:
                    self.active.append(incoming)
                try:
                    self._exchange(incoming, row)
                except Exception as error:
                    row['errors'].append(type(error).__name__ + ':' + str(error))
                finally:
                    with self.lock:
                        self.active.remove(incoming)
            row['closed'] = True
            row['closedNs'] = time.monotonic_ns()
            row['recordedEvents'] = len(row['events'])
            if row.get('probeId') == self.probe_id:
                self.done.set()

    def _exchange(self, incoming, row):
        request = b''
        while b'\r\n\r\n' not in request:
            block = self._io(row, 'request_read', 'netsimd-facing', len(request),
                             lambda: incoming.recv(4096))
            if not block:
                raise ValueError('incomplete_request')
            request += block
            if len(request) > MAX_HEADER:
                raise ValueError('request_too_large')
        line, fields, extra = headers(request)
        method, target, protocol = line.split(' ')
        if method != 'GET' or protocol not in ('HTTP/1.0', 'HTTP/1.1') or extra or \
                'transfer-encoding' in fields or fields.get('content-length', '0') != '0':
            raise ValueError('only_bodyless_synthetic_get_allowed')
        parsed = urlsplit(target)
        if target == '/health':
            row['purpose'] = 'health'
        else:
            expected_path = PREFIX + '/synthetic-baseline/pages/1/image'
            query = parse_qs(parsed.query, keep_blank_values=True)
            if parsed.path != expected_path or parsed.scheme or parsed.netloc or parsed.fragment or \
                    query != {'version': ['v1'], 'readerProbe': [self.probe_id]} or \
                    fields.get('authorization') != 'Bearer ' + TOKEN or \
                    fields.get('host') != f'{self.host}:{self.port}' or self.used:
                raise ValueError('wrong_or_repeated_synthetic_nonce')
            self.used = True
            row.update(purpose='probe', probeId=self.probe_id)
        row['requestBytes'] = len(request)
        row['requestSha256'] = digest(request)
        with socket.socket() as outgoing:
            outgoing.settimeout(15)
            with self.lock:
                self.active.append(outgoing)
            try:
                outgoing.connect(('127.0.0.1', self.backend_port))
                row.update(outgoingFd=outgoing.fileno(), outgoingLocalPort=outgoing.getsockname()[1],
                           backendHost='127.0.0.1', backendPort=self.backend_port,
                           connectedNs=time.monotonic_ns(),
                           incomingNoDelay=bool(incoming.getsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY)),
                           outgoingNoDelay=bool(outgoing.getsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY)))
                self._send(outgoing, request, row, 'request_send', 'fixture-facing')
                response = bytearray()
                while True:
                    block = self._io(row, 'response_read', 'fixture-facing', len(response),
                                     lambda: outgoing.recv(65536))
                    if not block:
                        break
                    if len(response) + len(block) > MAX_RESPONSE:
                        raise ValueError('response_too_large')
                    self._send(incoming, block, row, 'response_send', 'netsimd-facing', len(response))
                    response.extend(block)
                row.update(responseBytes=len(response), responseSha256=digest(response))
                status, response_fields, body = headers(bytes(response))
                if not status.startswith('HTTP/1.0 200 ') or \
                        response_fields.get('content-length') != str(len(body)):
                    raise ValueError('incomplete_response')
                row['bodySha256'] = digest(body)
                if row['purpose'] == 'probe':
                    if response_fields.get('x-coach-reader-probe') != self.probe_id or \
                            response_fields.get('x-coach-reader-ordinal') != '1':
                        raise ValueError('wrong_response_echo')
                    row['serverConnectionId'] = int(response_fields['x-coach-reader-connection'])
                    row['bodyBytes'] = len(body)
            finally:
                with self.lock:
                    self.active.remove(outgoing)

    def close(self):
        if self.joined:
            return
        self.stop.set()
        self.listener.close()
        with self.lock:
            for sock in self.active:
                self.forced_close = True
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        self.thread.join(timeout=17)
        self.joined = not self.thread.is_alive()
        if not self.joined:
            raise RuntimeError('owned_boundary_thread_not_joined')

    def __exit__(self, *_):
        self.close()

    def report(self):
        return dict(schema=1, mode='single-synthetic-relay', probeId=self.probe_id,
                    hostClock=clock_identity(), listenerHost=self.host, listenerPort=self.port,
                    backendHost='127.0.0.1', backendPort=self.backend_port,
                    records=self.records, errors=self.errors, joined=self.joined,
                    forcedClose=self.forced_close, recordingBudgetNs=self.budget,
                    netsimdSocketReturnObserved=False, kernelSendObserved=False,
                    captureClockSourceVerified=False, installedSourceMappingVerified=False,
                    clockErrorBoundValidated=False)


def analyze_boundary(association, report, run_id, configured_host, port, backend_port):
    """Fail closed for identity, missing events, partial sends, timing and overhead.

    Fixture and relay are in the SAME Python process and monotonic clock. No
    capture/guest timestamp enters these calculations, and no flag is promoted.
    """
    errors = list(association.get('errors', []))
    expected = run_id + '-boundary-1'
    samples = association.get('samples', [])
    records = report.get('records', [])
    probes = [row for row in records if row.get('probeId') == expected]
    if not association.get('complete') or len(samples) != 1 or len(probes) != 1 or \
            report.get('probeId') != expected or report.get('mode') != 'single-synthetic-relay':
        errors.append('need_one_exact_nonce')
    if report.get('joined') is not True or report.get('forcedClose') is not False or report.get('errors'):
        errors.append('relay_cleanup_or_service_error')
    if any(report.get(key) is not False for key in ('captureClockSourceVerified',
            'installedSourceMappingVerified','clockErrorBoundValidated')):
        errors.append('capture_clock_flags_must_remain_unverified')
    if any(row.get('purpose') not in ('health', 'probe') or row.get('errors') or not row.get('closed')
           for row in records) or sum(row.get('purpose') == 'health' for row in records) != 1 or \
            sum(row.get('purpose') == 'probe' for row in records) != 1:
        errors.append('unexpected_connection_or_incomplete_health')
    if (report.get('listenerHost'), report.get('listenerPort'), report.get('backendHost'), report.get('backendPort')) != \
            (configured_host, port, '127.0.0.1', backend_port):
        errors.append('relay_endpoint_mismatch')
    recording = []
    deltas = {}
    if len(samples) == len(probes) == 1:
        sample, relay = samples[0], probes[0]
        client, server = sample['client'], sample['server']
        if sample.get('probeId') != expected or client.get('probeId') != expected or \
                client.get('route') != 'configured' or client.get('destinationHost') != configured_host or \
                client.get('destinationPort') != port or client.get('requestHost') != configured_host or \
                client.get('localPort') != relay.get('peerPort') or \
                server.get('clientPort') != relay.get('outgoingLocalPort') or \
                server.get('serverBindAddress') != '127.0.0.1' or server.get('serverPort') != backend_port or \
                server.get('requestHost') != f'{configured_host}:{port}':
            errors.append('nonce_or_socket_tuple_mismatch')
        if client.get('serverConnectionId') != relay.get('serverConnectionId') or \
                server.get('connectionId') != relay.get('serverConnectionId') or \
                client.get('serverRequestOrdinal') != 1 or server.get('requestOnConnection') != 1 or \
                client.get('freshConnection') is not True or client.get('page') != 1 or server.get('page') != 1 or \
                client.get('clientTcpNoDelayEnabled') is not False or server.get('tcpNoDelayEnabled') is not False or \
                relay.get('incomingNoDelay') is not False or relay.get('outgoingNoDelay') is not False or \
                server.get('protocol') != 'HTTP/1.0':
            errors.append('connection_or_transport_identity_mismatch')
        if relay.get('bodySha256') != client.get('bodySha256') or \
                relay.get('bodySha256') != server.get('bodySha256') or \
                relay.get('bodyBytes') != client.get('bytes'):
            errors.append('response_payload_mismatch')
        events = relay.get('events', [])
        if not events or relay.get('droppedEvents') != 0 or relay.get('recordedEvents') != len(events) or \
                [e.get('sequence') for e in events] != list(range(1, len(events) + 1)):
            errors.append('missing_or_duplicate_events')
        offsets = dict(request_read=0, request_send=0, response_read=0, response_send=0)
        groups = {key: [] for key in offsets}
        last = relay.get('acceptedNs', 0)
        for event in events:
            op = event.get('operation')
            points = [event.get(k) for k in ('callNs', 'returnNs', 'recordEndNs')]
            count = event.get('bytes')
            expected_side = 'netsimd-facing' if op in ('request_read','response_send') else 'fixture-facing'
            if op not in offsets or event.get('side') != expected_side or type(event.get('tid')) is not int or \
                    any(type(n) is not int for n in points) or \
                    points != sorted(points) or points[0] < last or event.get('error') or \
                    type(count) is not int or count < 0 or event.get('offset') != offsets.get(op):
                errors.append('invalid_io_event'); continue
            witness = event.get('returnClock', {})
            bounds = [witness.get(k) for k in ('hostMonotonicBeforeNs', 'hostMonotonicAfterNs')]
            if any(type(n) is not int for n in bounds) or type(witness.get('hostEpochNs')) is not int or \
                    not points[1] <= bounds[0] <= bounds[1] <= points[2]:
                errors.append('invalid_io_clock_witness')
            cost = [event.get(k) for k in ('recordingStartPerfNs','recordingEndPerfNs')]
            duration = cost[1] - cost[0] if all(type(n) is int for n in cost) else -1
            if duration < 0 or event.get('observedRecordingNs') != duration:
                errors.append('invalid_recording_duration')
            recording.append(duration)
            last = points[2]
            offsets[op] += count
            groups[op].append(event)
        if any(not group for group in groups.values()) or \
                offsets['request_read'] != relay.get('requestBytes') or \
                offsets['request_send'] != relay.get('requestBytes') or \
                offsets['response_read'] != relay.get('responseBytes') or \
                offsets['response_send'] != relay.get('responseBytes') or \
                not groups['response_read'] or groups['response_read'][-1].get('bytes') != 0:
            errors.append('incomplete_io_byte_coverage')
        if any(e.get('bytes',0)<=0 for op in ('request_read','request_send','response_send') for e in groups[op]) or \
                sum(e.get('bytes')==0 for e in groups['response_read'])!=1:
            errors.append('unexpected_zero_byte_io')
        meta = server.get('hostClock', {}).get('metadata', {})
        same_clock = report.get('hostClock', {})
        if type(same_clock.get('pid')) is not int or same_clock['pid']<=0 or \
                not isinstance(same_clock.get('implementation'),str) or not same_clock['implementation'] or \
                type(same_clock.get('resolutionSeconds')) not in (int,float) or same_clock['resolutionSeconds']<=0 or \
                (meta.get('hostPid'), meta.get('monotonicImplementation'), meta.get('monotonicResolutionSeconds')) != \
                (same_clock.get('pid'), same_clock.get('implementation'), same_clock.get('resolutionSeconds')):
            errors.append('fixture_relay_monotonic_identity_mismatch')
        elif groups['request_read'] and any(e.get('bytes',0)>0 for e in groups['response_read']):
            # Signed observations can overlap: a send return is not kernel send.
            deltas = dict(relayRequestReadToFixtureParsedNs=server['requestHeadersParsedNs'] - groups['request_read'][-1]['returnNs'],
                          fixtureHeaderReturnToRelayFirstReadNs=next(
                              e['returnNs'] for e in groups['response_read'] if e['bytes'] > 0) - server['headersQueuedNs'])
    budget = report.get('recordingBudgetNs')
    cost_resolution = report.get('hostClock',{}).get('recordingResolutionSeconds')
    overhead_ok = (type(budget) is int and budget > 0 and bool(recording) and min(recording)>=0 and max(recording) <= budget
                   and type(cost_resolution) in (int,float) and 0 < cost_resolution*1e9 <= budget)
    if not overhead_ok:
        errors.append('observed_recording_budget_exceeded_or_missing')
    return dict(complete=not errors, errors=errors, probeId=expected, matchedNonces=len(probes),
                fixtureRelayMonotonicDeltasNs=deltas,
                fixtureRelayClock=report.get('hostClock'),
                recording=dict(samples=len(recording), maximumObservedNs=max(recording, default=None),
                               budgetNs=budget, resolutionSeconds=cost_resolution,
                               durationClock='perf_counter spans only; no counter/monotonic timestamp subtraction',
                               observedWithinBudget=overhead_ok, fullOverheadBoundVerified=False),
                boundary='Python relay socket returns; changed path, not netsimd returns or kernel transmission',
                netsimdSocketReturnObserved=False, packetHostAligned=False, guestHostAligned=False,
                captureClockSourceVerified=False, installedSourceMappingVerified=False, clockErrorBoundValidated=False,
                networkCauseVerified=False, uiPerformanceVerified=False, physicalMemoryBudgetVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True)
    args = parser.parse_args()
    def read(name):
        return json.loads((args.run_dir / name).read_text(encoding='utf-8'))
    identity = read('run.json')
    if identity.get('singleSocketBoundaryRequested') is not True:
        parser.error('not a single-boundary run; old route evidence is never reinterpreted')
    relay = read('host-socket-events.json')
    result = analyze_boundary(read('transport-association.json'), relay, identity['runId'],
                              identity['routeEndpoints']['configured'], identity['routeEndpoints']['port'],
                              identity['routeEndpoints']['backendPort'])
    (args.run_dir / 'host-socket-boundary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    return 0 if result['complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())

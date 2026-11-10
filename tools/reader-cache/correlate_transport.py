"""Join synthetic probe identities, retaining intervals in each clock's own domain."""
from collections import defaultdict


def correlate(probes, transfers):
    servers=defaultdict(list)
    for row in transfers:
        if row.get('probeId'): servers[row['probeId']].append(row)
    result=[];errors=[];seen=set()
    for kind,key in (('cio','samples'),('url','urlConnectionControl'),('socket','socketControl')):
        for client in probes.get(key,[]):
            token=client.get('probeId')
            if not token or token in seen:
                errors.append(dict(kind=kind,reason='missing_or_duplicate_client_token'));continue
            seen.add(token)
            matches=servers.get(token,[])
            if len(matches)!=1 or client.get('responseEchoVerified') is not True:
                errors.append(dict(probeId=token,kind=kind,reason='server_match_or_echo_not_unique'));continue
            server=matches[0]
            accepted=server.get('socketAcceptedNs');started=server.get('handlerStartedNs')
            points=[server.get(k) for k in ('acceptInitializedNs','requestWaitStartedNs','requestLineReadNs',
                'requestHeadersParsedNs','handlerNs','responseHeaderWriteStartedNs','headersQueuedNs','bodyQueuedNs')]
            if accepted is None or started is None or any(value is None for value in points) or \
                    [accepted,started]+points!=sorted([accepted,started]+points) or server.get('writeError'):
                errors.append(dict(probeId=token,kind=kind,reason='incomplete_server_boundaries'));continue
            if server.get('status')!=200 or client.get('bytes')!=server.get('bytes'):
                errors.append(dict(probeId=token,kind=kind,reason='status_or_length_mismatch'));continue
            if 'bodySha256' in client and client['bodySha256']!=server.get('bodySha256'):
                errors.append(dict(probeId=token,kind=kind,reason='payload_digest_mismatch'));continue
            if 'serverConnectionId' in client and (client['serverConnectionId']!=server['connectionId'] or
                    client.get('serverRequestOrdinal')!=server['requestOnConnection']):
                errors.append(dict(probeId=token,kind=kind,reason='connection_echo_mismatch'));continue
            intervals=dict(serverAcceptToHandlerMs=(started-accepted)/1e6,
                serverReadWaitToLineMs=(points[2]-points[1])/1e6,
                serverHeaderParseMs=(points[3]-points[2])/1e6,
                serverHandlingToHeaderWriteMs=(points[5]-points[3])/1e6,
                serverHeaderWriteMs=(points[6]-points[5])/1e6,
                serverHeadersToBodyQueuedMs=(points[7]-points[6])/1e6)
            intervals['serverAcceptToHeaderWriteMs']=(points[6]-accepted)/1e6
            if 'acceptCalledNs' in server:
                if server['acceptCalledNs']>accepted:
                    errors.append(dict(probeId=token,kind=kind,reason='invalid_accept_call_order'));continue
                intervals['serverAcceptCallMs']=(accepted-server['acceptCalledNs'])/1e6
            if kind=='socket':
                times=[client.get(k) for k in ('startNs','connectedNs','requestWrittenNs','readRequestedNs','firstByteNs','headersNs','endNs')]
                if any(t is None for t in times) or times!=sorted(times):
                    errors.append(dict(probeId=token,kind=kind,reason='incomplete_client_boundaries'));continue
                intervals.update(clientConnectMs=(times[1]-times[0])/1e6,
                    clientWriteMs=(times[2]-times[1])/1e6,clientWriteToFirstReadMs=(times[4]-times[2])/1e6,
                    clientReadInvocationToReturnMs=(times[4]-times[3])/1e6)
            result.append(dict(probeId=token,kind=kind,connectionId=server['connectionId'],
                requestOnConnection=server['requestOnConnection'],client=client,server=server,intervals=intervals))
    extras=set(servers)-seen
    if extras: errors.append(dict(reason='unmatched_server_tokens',probeIds=sorted(extras)))
    return dict(complete=bool(result) and not errors,samples=result,errors=errors,
        clocks='Client and host intervals are separate; no clock offset or one-way latency inferred.',
        kernelReceiveObserved=False,networkCauseVerified=False,performanceTargetVerified=False)

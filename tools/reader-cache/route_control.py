"""Validate counterbalanced socket routes; no cross-clock or UI performance inference."""
import math
import statistics


def summarize_routes(association, configured_host, port):
    errors=list(association['errors'])
    rows=association['samples']
    orders=[['configured','host-loopback','host-loopback','configured'],
            ['host-loopback','configured','configured','host-loopback']]*2
    expected=[route for block in orders for route in block]
    if not association['complete'] or len(rows)!=16:
        errors.append('need_16_uniquely_correlated_requests')
    groups={'configured':[], 'host-loopback':[]}
    digests={}
    connections=set()
    for index,row in enumerate(rows):
        client,server=row['client'],row['server']
        route=client.get('route');block=index//4+1
        if index>=16 or route!=expected[index] or client.get('sequence')!=index+1 or client.get('block')!=block:
            errors.append('route_order_or_block_mismatch');continue
        destination=configured_host if route=='configured' else '10.0.2.2'
        bind=configured_host if route=='configured' else '127.0.0.1'
        if client.get('destinationHost')!=destination or client.get('destinationPort')!=port or \
                client.get('requestHost')!=configured_host or server.get('serverBindAddress')!=bind or server.get('serverPort')!=port:
            errors.append('route_endpoint_mismatch');continue
        digest=client.get('bodySha256')
        if not isinstance(digest,str) or len(digest)!=64 or digest!=server.get('bodySha256') or \
                client.get('page')!=block or server.get('page')!=block or \
                (block in digests and digests[block]!=digest):
            errors.append('route_payload_mismatch');continue
        digests[block]=digest
        if client.get('freshConnection') is not True or client.get('clientTcpNoDelayEnabled') is not False or \
                server.get('protocol')!='HTTP/1.0' or server.get('tcpNoDelayEnabled') is not False or \
                server.get('requestOnConnection')!=1 or server.get('connectionId') in connections:
            errors.append('route_transport_policy_mismatch');continue
        connections.add(server['connectionId'])
        groups[route].append(row['intervals'])
    result={}
    for route,intervals in groups.items():
        if len(intervals)!=8: errors.append('need_8_requests_per_route')
        values={}
        if intervals:
            for key in intervals[0]:
                samples=sorted(item[key] for item in intervals)
                values[key]=dict(p50=statistics.median(samples),p95=samples[math.ceil(len(samples)*.95)-1],maximum=max(samples))
        result[route]=dict(samples=len(intervals),milliseconds=values)
    return dict(suite='route',complete=not errors,errors=errors,routes=result,
        comparison='Destination/listener control in one emulator run; host and guest durations remain separate.',
        networkCauseVerified=False,kernelReceiveObserved=False,uiPerformanceVerified=False,physicalMemoryBudgetVerified=False)

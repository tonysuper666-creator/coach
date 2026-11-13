"""Same-process available() observations, never kernel packet timestamps."""


def analyze_queue_watch(association):
    result=[];errors=list(association['errors'])
    if not association['complete']: errors.append('incomplete_request_association')
    for sample in association['samples']:
        client=sample['client'];watch=client.get('queueWatch')
        token=client.get('probeId')
        if not isinstance(watch,dict) or watch.get('mode')!='available' or watch.get('joined') is not True or \
                watch.get('forcedSocketClose') is not False or watch.get('error') is not None:
            errors.append(dict(probeId=token,reason='missing_or_failed_queue_observer'));continue
        times=[watch.get('startedNs'),watch.get('readyNs'),client['requestWrittenNs'],watch.get('joinedNs')]
        if any(type(t) is not int for t in times) or times!=sorted(times) or times[0]<client['connectedNs'] or times[-1]<client['endNs'] or \
                type(watch.get('observerTid')) is not int or watch['observerTid']<=0 or \
                type(client.get('readTid')) is not int or client['readTid']<=0 or watch['observerTid']==client['readTid']:
            errors.append(dict(probeId=token,reason='invalid_observer_identity_or_clock'));continue
        queries=watch.get('queries')
        if not isinstance(queries,list) or len(queries)>512 or watch.get('limitReached')!=(len(queries)==512) or \
                not isinstance(watch.get('inputClass'),str) or not watch['inputClass']:
            errors.append(dict(probeId=token,reason='invalid_query_records'));continue
        previous=client['requestWrittenNs'];early=[];valid=True
        for query in queries:
            if not isinstance(query,dict): valid=False;break
            invoked=query.get('invokedNs');returned=query.get('returnedNs');count=query.get('availableBytes')
            if any(type(value) is not int for value in (invoked,returned,count)) or count<0 or \
                    not previous<=invoked<=returned<=watch['joinedNs']:
                valid=False;break
            previous=returned
            if count>0 and returned<client['firstByteNs']: early.append(query)
        if not valid:
            errors.append(dict(probeId=token,reason='invalid_query_boundaries'));continue
        first=early[0] if early else None
        classification=('positive_before_read_call' if first['returnedNs']<client['readRequestedNs'] else 'positive_during_read') if first else 'no_early_positive_observation'
        result.append(dict(probeId=token,route=client.get('route'),queries=len(queries),classification=classification,
            positiveObservationToReadMarkerMs=(client['firstByteNs']-first['returnedNs'])/1e6 if first else None,
            firstEarlyPositive=first,limitReached=watch.get('limitReached'),inputClass=watch.get('inputClass')))
    return dict(complete=bool(result) and not errors,samples=result,errors=errors,
        interpretation='Positive available() return bounds application-visible readability only. No early witness is inconclusive.',
        kernelReceiveObserved=False,applicationWakeCauseVerified=False,networkCauseVerified=False,performanceTargetVerified=False)

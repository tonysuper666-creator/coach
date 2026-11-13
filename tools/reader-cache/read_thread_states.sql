SELECT s.id AS slice_id, st.id AS state_id, st.ts, st.dur, st.state,
       st.io_wait, st.blocked_function, st.waker_utid, t.utid, t.tid, p.pid
FROM slice s
JOIN thread_track tr ON tr.id=s.track_id
JOIN thread t ON t.utid=tr.utid
JOIN process p ON p.upid=t.upid
LEFT JOIN thread_state st ON st.utid=t.utid AND st.ts<s.ts+s.dur
    AND (st.dur=-1 OR st.ts+st.dur>s.ts)
WHERE p.pid={{PID}} AND s.name GLOB 'ReaderSocket/first-read/*' AND s.dur>0
ORDER BY s.id, st.ts, st.id;

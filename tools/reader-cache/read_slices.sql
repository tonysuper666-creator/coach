SELECT s.id AS slice_id, s.name AS label, s.ts, s.dur, t.utid, t.tid, p.upid, p.pid
FROM slice s
JOIN thread_track tr ON tr.id=s.track_id
JOIN thread t ON t.utid=tr.utid
JOIN process p ON p.upid=t.upid
WHERE p.pid={{PID}} AND s.name GLOB 'ReaderSocket/first-read/*'
ORDER BY s.ts, s.id;

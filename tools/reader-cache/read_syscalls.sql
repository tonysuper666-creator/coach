SELECT m.id AS read_slice_id, m.name AS read_label, c.id AS syscall_slice_id, c.name, c.ts, c.dur,
       c.arg_set_id, t.utid, t.tid, p.pid,
       (SELECT a.int_value FROM args a WHERE a.arg_set_id=c.arg_set_id AND a.key='ret') AS ret,
       (SELECT a.int_value FROM args a WHERE a.arg_set_id=c.arg_set_id AND a.key='args[0]') AS arg0
FROM slice m
JOIN thread_track tr ON tr.id=m.track_id
JOIN thread t ON t.utid=tr.utid
JOIN process p ON p.upid=t.upid
JOIN slice c ON c.track_id=m.track_id AND c.name GLOB 'sys_*'
    AND c.ts<m.ts+m.dur AND (c.dur=-1 OR c.ts+c.dur>m.ts)
WHERE p.pid={{PID}} AND m.name GLOB 'ReaderSocket/first-read/*' AND m.dur>0
ORDER BY m.id, c.ts, c.id;

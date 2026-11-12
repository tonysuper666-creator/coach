SELECT DISTINCT c.id AS syscall_slice_id, a.key, a.flat_key, a.value_type,
       a.int_value, a.string_value, a.real_value, a.display_value
FROM slice m
JOIN thread_track tr ON tr.id=m.track_id
JOIN thread t ON t.utid=tr.utid
JOIN process p ON p.upid=t.upid
JOIN slice c ON c.track_id=m.track_id AND c.name GLOB 'sys_*'
    AND c.ts<m.ts+m.dur AND (c.dur=-1 OR c.ts+c.dur>m.ts)
LEFT JOIN args a ON a.arg_set_id=c.arg_set_id
WHERE p.pid={{PID}} AND m.name GLOB 'ReaderSocket/first-read/*' AND m.dur>0
ORDER BY c.id, a.key;

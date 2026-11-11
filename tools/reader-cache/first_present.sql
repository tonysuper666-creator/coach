-- Replace {{PID}} with the measured integer PID. Run on the original raw trace.
-- Export LEFT JOIN failures and later draws too. The checker chooses the first
-- JSON hardware draw and rejects its failed/ambiguous join, never substitutes a redraw.
SELECT m.id AS marker_id,m.name AS marker,m.ts AS marker_ns,t.pid AS marker_pid,
       k.id AS clock_id,k.name AS clock_marker,k.ts AS clock_ns,
       c.id AS choreographer_id,
       CAST(SUBSTR(c.name,LENGTH('Choreographer#doFrame ')+1) AS INT) AS choreographer_token,
       a.id AS app_id,ap.pid AS app_pid,a.ts AS app_ns,a.dur AS app_dur,
       a.surface_frame_token AS surface_token,a.display_frame_token AS app_display_token,
       a.layer_name,a.present_type AS app_present_type,
       sf.id AS display_id,sf.ts AS display_ns,sf.dur AS display_dur,
       sf.surface_frame_token AS display_surface_token,sf.display_frame_token AS display_token,
       sf.present_type AS display_present_type,p.pid AS display_pid,p.name AS display_process
FROM slice m
JOIN thread_track tr ON tr.id=m.track_id
JOIN thread th ON th.utid=tr.utid
JOIN process t ON t.upid=th.upid
LEFT JOIN slice k ON k.parent_id=m.id AND k.name GLOB 'ReaderClock/*'
LEFT JOIN ancestor_slice(m.id) c ON c.name GLOB 'Choreographer#doFrame [0-9]*'
LEFT JOIN actual_frame_timeline_slice a
  ON a.surface_frame_token=CAST(SUBSTR(c.name,LENGTH('Choreographer#doFrame ')+1) AS INT)
 AND a.upid=t.upid
LEFT JOIN process ap ON ap.upid=a.upid
LEFT JOIN actual_frame_timeline_slice sf
  ON sf.display_frame_token=a.display_frame_token AND sf.surface_frame_token IS NULL
LEFT JOIN process p ON p.upid=sf.upid
WHERE t.pid={{PID}} AND (m.name GLOB 'ReaderPage/target_window_draw_commands/*'
                       OR m.name GLOB 'ReaderPage/input_*')
ORDER BY m.ts,m.id,k.id,a.id,sf.id;

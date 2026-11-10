-- Replace {{PID}} with run.json measurementPid (an integer). Capture all candidate rows.
-- This is a witness query, not an automatic acceptance decision. Reject ambiguous app/layer
-- or display matches, unfinished durations, dropped frames, and frames outside the action.
-- A later unique presented redraw bounds display latency; it is not necessarily first present.
SELECT m.id AS marker_id,m.name AS marker,m.ts AS marker_ns,
       c.id AS choreographer_id,c.name AS choreographer,c.ts AS choreographer_ns,
       a.id AS app_id,a.ts AS app_ns,a.dur AS app_dur,a.surface_frame_token,
       a.display_frame_token,a.layer_name,a.present_type AS app_present_type,
       sf.id AS display_id,sf.ts AS display_ns,sf.dur AS display_dur,
       sf.present_type AS display_present_type,p.pid AS display_pid,p.name AS display_process
FROM slice m JOIN ancestor_slice(m.id) c
LEFT JOIN actual_frame_timeline_slice a
  ON a.surface_frame_token=CAST(SUBSTR(c.name,LENGTH('Choreographer#doFrame ')+1) AS INT)
 AND a.upid=(SELECT upid FROM process WHERE pid={{PID}})
LEFT JOIN actual_frame_timeline_slice sf
  ON sf.display_frame_token=a.display_frame_token AND sf.surface_frame_token IS NULL
LEFT JOIN process p ON p.upid=sf.upid
WHERE m.name GLOB 'ReaderPage/target_window_draw_commands/*'
  AND c.name GLOB 'Choreographer#doFrame [0-9]*'
ORDER BY m.ts;

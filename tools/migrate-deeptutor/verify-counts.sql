-- Run only against a copied mastery.sqlite3, with sqlite3 -readonly.
-- Set @path_id to mapping.json.path_id before reading this file.
SELECT 'knowledge_points', sum(json_array_length(json_extract(m.value, '$.knowledge_points')))
FROM mastery_paths p, json_each(p.state_json, '$.modules') m
WHERE p.path_id = @path_id;
SELECT 'passed', count(*)
FROM mastery_paths p, json_each(p.state_json, '$.mastery_levels') l
WHERE p.path_id = @path_id AND l.value >= 1.0;
SELECT 'schedules', count(*)
FROM mastery_paths p, json_each(p.state_json, '$.repetition_states') r
WHERE p.path_id = @path_id;
SELECT 'retained_evidence', count(*) FROM mastery_learning_evidence WHERE path_id = @path_id;
SELECT 'attempts', count(*) FROM mastery_interactions WHERE path_id = @path_id AND length(trim(user_answer)) > 0;
SELECT 'events', count(*) FROM mastery_events WHERE path_id = @path_id;
SELECT 'graded_events', count(*) FROM mastery_events WHERE path_id = @path_id AND event_type = 'interaction.graded';
SELECT 'schedules_not_passed', r.key
FROM mastery_paths p, json_each(p.state_json, '$.repetition_states') r
WHERE p.path_id = @path_id
AND coalesce(json_extract(p.state_json, '$.mastery_levels.' || r.key), 0) < 1.0;

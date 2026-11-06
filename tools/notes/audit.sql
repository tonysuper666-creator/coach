-- Independent SQL count of historical wrong attempts, for a readonly COPY.
-- Bind :course. Output includes all eleven weeks; no learner text is returned.
-- Like the exporter, revised legacy sources are unsupported and must stop.
WITH RECURSIVE
weeks(week) AS (SELECT 1 UNION ALL SELECT week+1 FROM weeks WHERE week<11),
es AS (SELECT seq,type,payload FROM events WHERE course_id=:course),
raw_points AS (
  SELECT 0 AS seq, 0 AS sub, kp_id AS kp, title,
         NULL AS explicit_week, retired FROM outline_kp WHERE course_id=:course
  UNION ALL
  SELECT e.seq, CAST(j.key AS INTEGER), json_extract(j.value,'$.id'),
         COALESCE(json_extract(j.value,'$.title'),json_extract(j.value,'$.name')),
         json_extract(j.value,'$.week'), COALESCE(json_extract(j.value,'$.retired'),0)
    FROM es e, json_each(e.payload,'$.knowledgePoints') j WHERE e.type='outline_revised'
  UNION ALL
  SELECT e.seq, 100000+CAST(j.key AS INTEGER),
         COALESCE(json_extract(j.value,'$.kpId'),json_extract(j.value,'$.id')),
         COALESCE(json_extract(j.value,'$.title'),json_extract(j.value,'$.name')),
         json_extract(j.value,'$.week'), json_extract(j.value,'$.op')='retire'
    FROM es e, json_each(e.payload,'$.ops') j
   WHERE e.type='outline_revised' AND (
     json_extract(j.value,'$.title') IS NOT NULL OR
     json_extract(j.value,'$.name') IS NOT NULL OR
     json_extract(j.value,'$.week') IS NOT NULL OR
     json_extract(j.value,'$.op')='retire')
),
point_rank AS (
  SELECT *,row_number() OVER(PARTITION BY kp ORDER BY seq DESC,sub DESC) AS rank
    FROM raw_points WHERE kp IS NOT NULL
),
points AS (
  SELECT p.kp,p.explicit_week,
    (SELECT r.title FROM raw_points r WHERE r.kp=p.kp AND r.title IS NOT NULL
       ORDER BY r.seq DESC,r.sub DESC LIMIT 1) AS title
    FROM point_rank p WHERE p.rank=1 AND NOT p.retired
),
grades AS (
  SELECT seq,json_extract(payload,'$.attemptId') AS aid,
    COALESCE(json_extract(payload,'$.revised.score'),json_extract(payload,'$.score')) AS score,
    COALESCE(json_extract(payload,'$.revised.max'),json_extract(payload,'$.max')) AS maximum
    FROM es WHERE type IN ('attempt_graded','grade_reviewed')
),
valid_grades AS (
  SELECT *,row_number() OVER(PARTITION BY aid ORDER BY seq DESC) AS rank
    FROM grades WHERE score IS NOT NULL AND maximum IS NOT NULL
),
poses AS (
  SELECT json_extract(payload,'$.attemptId') AS aid,type,payload
    FROM es WHERE type IN ('question_posed','legacy_attempt')
),
scored AS (
  SELECT p.*,
    COALESCE(g.score,CASE WHEN p.type='legacy_attempt' AND json_extract(p.payload,'$.result.final')=1
      AND COALESCE(json_extract(p.payload,'$.result.needs_clarification'),0)=0
      AND json_type(p.payload,'$.result.is_correct') IN ('true','false')
      THEN json_extract(p.payload,'$.result.is_correct') END) AS score,
    COALESCE(g.maximum,CASE WHEN p.type='legacy_attempt' THEN 1 END) AS maximum
    FROM poses p LEFT JOIN valid_grades g ON g.aid=p.aid AND g.rank=1
   WHERE COALESCE(json_extract(p.payload,'$.reset'),0)=0
     AND COALESCE(json_extract(p.payload,'$.answer_unresolved'),0)=0
     AND NOT EXISTS(SELECT 1 FROM es v WHERE v.type='attempt_voided'
       AND json_extract(v.payload,'$.attemptId')=p.aid)
     AND ((p.type='legacy_attempt' AND json_type(p.payload,'$.answer') IS NOT NULL)
       OR EXISTS(SELECT 1 FROM es a WHERE a.type='answer_submitted'
         AND json_extract(a.payload,'$.attemptId')=p.aid))
),
wrong AS (SELECT * FROM scored WHERE score>=0 AND score<maximum),
bindings AS (
  SELECT w.aid,j.value AS kp FROM wrong w,json_each(w.payload,'$.kpIds') j
  UNION
  SELECT aid,json_extract(payload,'$.kp_id') FROM wrong
    WHERE type='legacy_attempt' AND COALESCE(json_array_length(payload,'$.kpIds'),0)=0
),
counts AS (
  SELECT w.week,count(DISTINCT b.aid) AS mistakes FROM weeks w
    LEFT JOIN points p ON CASE WHEN p.explicit_week>0 THEN p.explicit_week=w.week ELSE
      instr(p.title,'【W'||w.week||'·')>0 OR instr(p.title,'【W'||w.week||'・')>0 OR
      instr(p.title,'【W'||w.week||' ')>0 OR instr(p.title,'【W'||w.week||'】')>0 END
    LEFT JOIN bindings b ON b.kp=p.kp GROUP BY w.week
)
SELECT week,mistakes FROM counts ORDER BY week;

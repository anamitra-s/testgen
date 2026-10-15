-- ============================================================================
-- TestGen Observability Grafana SQL Queries (§9.3, ADR-015)
-- Direct PostgreSQL queries without custom metrics infrastructure.
-- ============================================================================

-- 1. North-star: MRs merged with zero human edits
-- Joined against mr_outcomes or computed by a nightly job querying GitLab MR API
SELECT date_trunc('week', j.completed_at) AS wk,
       count(*) FILTER (WHERE m.human_commits = 0)::float / nullif(count(*), 0) AS clean_merge_rate
FROM jobs j
JOIN mr_outcomes m ON m.mr_iid = j.mr_iid
WHERE j.state = 'DONE' AND m.merged_at IS NOT NULL
GROUP BY 1
ORDER BY 1;

-- 2. Time to MR (p50 and p95 over last 7 days)
SELECT percentile_cont(0.5)  WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - created_at)) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - created_at)) AS p95
FROM jobs
WHERE completed_at > now() - interval '7 days';

-- 3. Cache effectiveness — validates the §2.4 cost model
SELECT sum(cache_read_tokens)::float
       / nullif(sum(cache_read_tokens + input_tokens), 0) AS cache_read_ratio
FROM cost_events
WHERE created_at > now() - interval '7 days';

-- 4. L2 fail-open rate (alert when rate > 0.20)
SELECT count(*) FILTER (WHERE outcome = 'SKIPPED')::float / nullif(count(*), 0) AS l2_skip_rate
FROM verification_runs
WHERE created_at > now() - interval '7 days';

-- 5. Where quality is moving: blocking reason codes over time
SELECT date_trunc('day', a.started_at) AS d,
       v.reason_code,
       count(*) AS failure_count
FROM validation_results v
JOIN retry_attempts a ON a.id = v.attempt_id
WHERE v.severity = 'BLOCKING'
  AND a.started_at > now() - interval '30 days'
GROUP BY 1, 2
ORDER BY 1, 3 DESC;

-- 6. Cost per merged test
SELECT sum(c.estimated_cost_usd) / nullif(count(DISTINCT j.id), 0) AS cost_per_merged_test_usd
FROM jobs j
JOIN cost_events c ON c.job_id = j.id
WHERE j.state = 'DONE'
  AND j.completed_at > now() - interval '30 days';

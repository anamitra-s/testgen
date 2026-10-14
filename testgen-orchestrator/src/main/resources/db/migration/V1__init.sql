-- ════════════════════════════════ JOBS ════════════════════════════════
CREATE TABLE jobs (
    id                    UUID        PRIMARY KEY,
    idempotency_key       TEXT        NOT NULL UNIQUE,      -- §7.2
    target_area           TEXT        NOT NULL,
    test_description      TEXT        NOT NULL,
    requester             TEXT        NOT NULL,
    recording_branch      TEXT        NOT NULL,

    state                 TEXT        NOT NULL,
    confidence_tier       TEXT,                             -- VERIFIED|STATIC_ONLY|STATIC_ONLY_DEGRADED
    failure_reason        TEXT,

    -- Provenance: makes quality changes attributable (R-04)
    prompt_bundle_hash    TEXT        NOT NULL,
    model_id              TEXT        NOT NULL,
    orchestrator_version  TEXT        NOT NULL,
    recorder_version      TEXT,
    base_commit_sha       TEXT,

    -- Lease / fencing (§7.4)
    lease_owner           TEXT,
    lease_epoch           BIGINT      NOT NULL DEFAULT 0,
    lease_expires_at      TIMESTAMPTZ,

    redaction_report      JSONB,
    mr_iid                INT,
    mr_url                TEXT,

    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at          TIMESTAMPTZ
);
CREATE INDEX idx_jobs_open_review ON jobs (state, created_at DESC)
       WHERE state IN ('NEEDS_HUMAN_REVIEW','FAILED');
CREATE INDEX idx_jobs_area_time   ON jobs (target_area, created_at DESC);
CREATE INDEX idx_jobs_lease       ON jobs (lease_expires_at)
       WHERE state NOT IN ('DONE','FAILED','NEEDS_HUMAN_REVIEW');

-- ════════════════════════════════ ATTEMPTS ═══════════════════════════════
CREATE TABLE retry_attempts (
    id             BIGSERIAL PRIMARY KEY,
    job_id         UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_no     INT  NOT NULL,
    phase          TEXT NOT NULL,
    passed         BOOLEAN,
    feedback_sent  TEXT,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    UNIQUE (job_id, attempt_no, phase)
);

CREATE TABLE validation_results (
    id                 BIGSERIAL PRIMARY KEY,
    attempt_id         BIGINT NOT NULL REFERENCES retry_attempts(id) ON DELETE CASCADE,
    layer              TEXT   NOT NULL,      -- L0 | L1 | L2 | LINT
    element_ref_id     TEXT,
    locator_expression TEXT,
    reason_code        TEXT   NOT NULL,
    match_count        INT,
    severity           TEXT   NOT NULL,      -- BLOCKING | ADVISORY
    detail             TEXT
);
CREATE INDEX idx_vr_attempt ON validation_results (attempt_id);
CREATE INDEX idx_vr_reason  ON validation_results (reason_code);

-- ════════════════════════════════ COST ═════════════════════════════════
CREATE TABLE cost_events (
    id                    BIGSERIAL PRIMARY KEY,
    job_id                UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_id            BIGINT REFERENCES retry_attempts(id) ON DELETE CASCADE,
    phase                 TEXT NOT NULL,
    model_id              TEXT NOT NULL,
    input_tokens          INT  NOT NULL,
    output_tokens         INT  NOT NULL,
    cache_creation_tokens INT  NOT NULL DEFAULT 0,
    cache_read_tokens     INT  NOT NULL DEFAULT 0,   -- makes cache effectiveness MEASURABLE
    estimated_cost_usd    NUMERIC(12,6),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cost_job ON cost_events (job_id);

-- ════════════════════════════════ VERIFICATION ══════════════════════════════
CREATE TABLE verification_runs (
    id           BIGSERIAL PRIMARY KEY,
    job_id       UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    outcome      TEXT NOT NULL,   -- PASSED|PASSED_AFTER_FLAKE|FAILED|SKIPPED
    skip_reason  TEXT,            -- NO_SESSION|MUTATING_TEST|ENV_UNAVAILABLE|TIMEOUT
    duration_ms  INT,
    failure_tail TEXT,
    blocking     BOOLEAN NOT NULL DEFAULT FALSE,   -- advisory during rollout
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ════════════════════════════════ EFFECT LEDGER (§7) ══════════════════════════
CREATE TABLE external_effects (
    id            BIGSERIAL PRIMARY KEY,
    job_id        UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    effect_type   TEXT NOT NULL,   -- BRANCH|COMMIT|MERGE_REQUEST|SLACK
    effect_key    TEXT NOT NULL,   -- deterministic natural key
    status        TEXT NOT NULL,   -- PENDING|CONFIRMED|ABANDONED
    external_id   TEXT,
    lease_epoch   BIGINT NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at  TIMESTAMPTZ,
    UNIQUE (job_id, effect_type, effect_key)
);
CREATE INDEX idx_effects_pending ON external_effects (status, created_at)
       WHERE status = 'PENDING';

-- ════════════════════════════════ EVAL HARNESS (§8) ══════════════════════════
CREATE TABLE eval_runs (
    id                 UUID PRIMARY KEY,
    suite              TEXT NOT NULL,
    trigger            TEXT NOT NULL,   -- NIGHTLY|PR|MANUAL|CANARY
    model_id           TEXT NOT NULL,
    prompt_bundle_hash TEXT NOT NULL,
    orchestrator_version TEXT NOT NULL,
    git_ref            TEXT,
    repeats            INT  NOT NULL,
    case_count         INT  NOT NULL,
    is_baseline        BOOLEAN NOT NULL DEFAULT FALSE,
    metrics            JSONB NOT NULL,   -- aggregate metrics + CIs
    gate_outcome       TEXT,             -- PASS|FAIL|NO_BASELINE
    gate_detail        JSONB,
    started_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ
);
CREATE INDEX idx_eval_baseline ON eval_runs (suite, model_id, prompt_bundle_hash)
       WHERE is_baseline;

CREATE TABLE eval_case_results (
    id                       BIGSERIAL PRIMARY KEY,
    run_id                   UUID NOT NULL REFERENCES eval_runs(id) ON DELETE CASCADE,
    case_id                  TEXT NOT NULL,
    repeat_no                INT  NOT NULL,
    first_attempt_pass       BOOLEAN NOT NULL,
    attempts_used            INT  NOT NULL,
    escalated                BOOLEAN NOT NULL,
    locator_exact_matches    INT  NOT NULL,
    locator_strategy_matches INT  NOT NULL,
    reference_locator_count  INT  NOT NULL,
    input_tokens             INT  NOT NULL,
    output_tokens            INT  NOT NULL,
    cache_read_tokens        INT  NOT NULL,
    duration_ms              INT  NOT NULL,
    failure_codes            TEXT[],
    UNIQUE (run_id, case_id, repeat_no)
);
CREATE INDEX idx_ecr_run_case ON eval_case_results (run_id, case_id);

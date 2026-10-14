# Automated AI Test-Generation Pipeline
## Final Engineering Design Document

| | |
|---|---|
| **Document** | TESTGEN-DD-001 |
| **Version** | 3.0 (Final — supersedes v1 feasibility/HLD/LLD and v2 architecture review) |
| **Status** | Approved for implementation |
| **Audience** | Implementing engineer(s), QA leadership, platform/CI owners, security reviewer |
| **System** | `testgen-orchestrator` — automated Playwright/TestNG test generation from browser recordings |
| **Language / Runtime** | Java 21 (LTS), JVM on Jenkins agents |

### Changelog from v2 review

| Change | Section |
|---|---|
| Full requirements traceability (FR/NFR → features → components) added | §4 |
| **Idempotency subsystem fully specified** — key derivation, lease + fencing token, effect ledger, reconciler | §7 |
| **Evaluation harness fully specified** — corpus, scoring, statistical gating, CI integration, drift monitor | §8 |
| Configuration reference, exit-code contract, runbook, ADR log added | §9, §11, App. A–D |
| Cost model with explicit arithmetic added | §2.4 |

---

# Table of Contents

1. [Scope](#1-scope)
2. [Feasibility Analysis](#2-feasibility-analysis)
3. [Features & Functionality](#3-features--functionality)
4. [Requirements](#4-requirements)
5. [High-Level Design](#5-high-level-design)
6. [Low-Level Design](#6-low-level-design)
7. [Idempotency & Exactly-Once Subsystem (Gap A)](#7-idempotency--exactly-once-subsystem-gap-a)
8. [Evaluation Harness & Quality Regression Detection (Gap B)](#8-evaluation-harness--quality-regression-detection-gap-b)
9. [Cross-Cutting Concerns](#9-cross-cutting-concerns)
10. [Implementation Plan](#10-implementation-plan)
11. [Operations Runbook](#11-operations-runbook)
12. [Appendices](#12-appendices)

---

# 1. Scope

## 1.1 Problem Statement

QA engineers currently record a browser session, then run an interactive AI-assisted session in Cursor to convert that recording into Java/TestNG/Playwright test code. The interactive session takes 15–30 minutes per test and requires the engineer to sit through a locator-approval gate before code generation.

The goal is to move that work into CI: the engineer records, pushes, walks away, and receives a Merge Request minutes later. The human decision point moves from *mid-generation* to *merge review*.

## 1.2 In Scope

| # | Item |
|---|---|
| S-01 | A CLI (`testgen`) that validates and uploads a recording and triggers the pipeline |
| S-02 | A Java orchestrator that parses recordings, redacts sensitive data, resolves locators via Claude, validates them deterministically, and generates Java/TestNG/Playwright test + Page Object code |
| S-03 | A three-tier validation ladder (grammar → static DOM → executed test) replacing the human locator-approval gate |
| S-04 | Automated GitLab branch/commit/Merge-Request creation with an auto-generated review report |
| S-05 | Reuse detection against existing Page Objects via JavaParser static analysis |
| S-06 | Jenkins integration: parameterized pipeline job, session-minting job, nightly evaluation job |
| S-07 | Postgres persistence for job state, retries, validation results, cost, and evaluation results |
| S-08 | Per-job LLM cost budgeting, tracking, and circuit-breaking |
| S-09 | DOM redaction before any data leaves the network |
| S-10 | An evaluation harness that gates model and prompt changes on a golden corpus |
| S-11 | Idempotent, re-runnable execution with exactly-once external effects |
| S-12 | Observability: structured logs, Grafana dashboards over Postgres, Slack notifications |

## 1.3 Out of Scope (Non-Goals)

| # | Item | Rationale |
|---|---|---|
| N-01 | **Auto-merging generated code** | Permanent non-goal. A human MR approver is the final backstop and is not a temporary pilot measure. |
| N-02 | Building or fine-tuning a model | No training loop exists in this system. Claude is consumed as an API. |
| N-03 | Replacing the browser recorder | The recorder is an existing owned asset. This design *extends its output contract* (§6.3) but does not rebuild it. |
| N-04 | A web UI or job-status REST API | Grafana over Postgres serves the same need for zero Java. Revisit only if a *machine* needs to consume job state. |
| N-05 | Multi-repo / multi-project support | v3 targets one test repository. The `target_area` field leaves room, but no cross-project routing is built. |
| N-06 | Self-healing of *existing* tests when the app changes | Different problem, different inputs. Deliberately deferred. |
| N-07 | A message broker (Kafka/RabbitMQ) | Volume does not justify it. Trigger path stays REST + process invocation. §5.6 defines the threshold at which this is revisited. |
| N-08 | Generating non-UI tests (API, unit, contract) | Recordings are the only input format. |

## 1.4 Glossary

| Term | Definition |
|---|---|
| **Recording** | Output of the browser recorder: `recorded-test.js` (action sequence) + `recorded-elements.json` (DOM metadata) |
| **Target area** | A logical section of the app under test (`dashboard`, `composer`, `statsimport`). Determines Page Object package and locking granularity. |
| **Locator** | A Playwright element selector expression, e.g. `page.getByTestId("submit-btn")` |
| **L0 / L1 / L2** | The three validation tiers: grammar, static DOM, executed test |
| **Confidence tier** | Per-job label recording which validation tiers actually ran: `VERIFIED`, `STATIC_ONLY`, `STATIC_ONLY_DEGRADED` |
| **Fingerprint** | A structural + semantic identity signature for a DOM element, used to prove the resolved locator matches the element the engineer actually interacted with |
| **Prompt bundle** | The versioned set of system prompts, conventions, and schemas used for a job; identified by `prompt_bundle_hash` |
| **Golden corpus** | Curated historical recordings with human-approved reference outputs, used for regression evaluation |
| **Effect** | An externally visible, non-transactional side effect (branch, commit, MR, Slack message) |
| **Fencing token** | A monotonically increasing epoch number that prevents a zombie process from writing after its lease was reclaimed |

## 1.5 Stakeholders

| Role | Interest | Sign-off required on |
|---|---|---|
| QA Engineers | Primary users; record and review MRs | CLI UX, MR report format |
| QA Lead | Throughput, test quality | Rollout plan, SLOs |
| Platform/CI team | Jenkins agents, secrets, Postgres | Infrastructure, on-call ownership |
| Security / Compliance | Data leaving the network to a third-party LLM | §9.4 redaction design, data-classification review |
| Application engineers | Consumers of the test repo | Code conventions, MR volume, CODEOWNERS |

---

# 2. Feasibility Analysis

## 2.1 Technical Feasibility — **HIGH**

No component requires novel research. Every capability exists as a proven point solution; the work is integration, state management, and validation design.

| Capability | Mechanism | Maturity | Risk |
|---|---|---|---|
| LLM parses recording → Java/TestNG code | Claude via LangChain4j, native structured outputs | Proven locally in Cursor workflow | Low |
| Schema-guaranteed LLM output | Anthropic Structured Outputs (`output_config.format`), grammar-constrained sampling, GA | Vendor feature, generally available | Low |
| Deterministic locator uniqueness | Jsoup query over captured page HTML | Mature library | Low — **conditional on grammar constraint (§6.4)** |
| Element identity verification | Fingerprint comparison | Custom, ~200 LOC | Medium — depends on recorder contract v2 |
| Executed-test verification | `mvn test -Dtest=X` with injected `storageState` | Reuses existing suite infrastructure | Medium — test-env availability |
| Existing Page Object analysis | JavaParser AST walk | Mature library | Low |
| GitLab branch/commit/MR automation | GitLab4J-API | Mature library | Low |
| Jenkins parameterized/triggered builds | Jenkins REST API | Standard | Low |
| Job state, cost, audit | Postgres + Flyway + JDBI | Standard | Low |
| Exactly-once external effects | Intent-log + reconciliation (§7) | Standard distributed-systems pattern | Low |
| Quality regression detection | Golden-corpus eval harness (§8) | Standard ML-eval practice | Medium — corpus curation effort |

**Concentration of risk:** replacing the human "does this locator look right" judgment. Sections 6.4–6.7 and 8 are where the design budget is spent.

## 2.2 Organizational Feasibility — **MEDIUM-HIGH**

**Favourable:**
- Engineers already produce the exact input artifacts as part of their existing workflow. Nothing changes before "stop recording."
- The team already reads and writes Java/TestNG/Playwright, so MR review requires no new skill.
- The output lands in a test repository, not production code — lower blast radius for cultural acceptance.

**Prerequisites that do not exist yet:**

| Prerequisite | Owner | Blocking? |
|---|---|---|
| Data-classification sign-off for sending redacted DOM metadata to a third-party LLM | Security/Compliance | **Yes — blocks first real recording** |
| Postgres instance (managed preferred) + backup policy | Platform | Yes — blocks Week 1 |
| Jenkins agent label `testgen-worker` with JVM + Maven + Playwright browsers | Platform | Yes — blocks Week 4 |
| Automation service account per target area, with credentials in Vault/Jenkins credentials | Platform + App team | Blocks L2 only (Week 5) |
| On-call ownership for "pipeline stuck / silently failing" | Platform or QA Lead | Before wider rollout |
| CODEOWNERS on the test repo requiring human MR approval | QA Lead | Before first MR |
| Agreement that auto-generated code may land in shared repos | Engineering leadership | Early conversation, not technical |

## 2.3 Compliance Feasibility

The pipeline sends DOM metadata derived from a live application to an external API. This is a data-flow change requiring review regardless of how benign the content looks.

**Design response (detailed in §9.4):**
1. **Allow-list redaction** — only an explicitly enumerated set of attributes leaves the network. Input `value` attributes, arbitrary `data-*`, and `src` are always dropped.
2. **URL normalization** — query strings stripped, identifier path segments replaced with `{id}`.
3. **PII sweep** — typed-token replacement for email, phone, card, JWT/bearer, and locale-specific national ID patterns.
4. **Fail-closed** — a redaction failure aborts the job. This is the one component that does *not* fail open.
5. **Auditability** — a `redaction_report` (rule → count) and the SHA-256 of the redacted payload are persisted per job, so "what left our network on job X" is answerable without re-deriving anything.
6. **Zero prompt bodies in logs** — logs carry `prompt_bundle_hash` and payload SHA, never the payload.

Confirm the vendor's data-handling terms (retention, training use, ZDR eligibility) as part of sign-off.

## 2.4 Economic Feasibility

**Cost drivers per job:** input tokens × attempts, output tokens, and the effect of prompt caching.

Representative job (order-of-magnitude, to be replaced by measured values in Week 2):

| Component | Tokens |
|---|---|
| System prompt + coding conventions | ~4,000 |
| Existing-code context (JavaParser digest) | ~6,000 |
| Redacted DOM snapshot (pruned, capped) | ~20,000 |
| **Stable prefix subtotal (cacheable)** | **~30,000** |
| Task instruction + correction feedback (volatile) | ~500 |
| Output (Resolve) | ~2,000 |
| Output (Generate) | ~4,000 |

**Effect of the cache-first prompt layout (§6.8).** Anthropic prices cache writes at ~1.25× base input and cache reads at ~0.1×. The stable prefix is re-sent on every retry attempt within seconds, well inside the 5-minute TTL.

| Attempts | Uncached cost multiple on prefix | Cached (1 write + N−1 reads) | Saving |
|---|---|---|---|
| 1 | 1.00× | 1.25× | −25% (caching loses) |
| 2 | 2.00× | 1.35× | 33% |
| 3 | 3.00× | 1.45× | 52% |
| 4 | 4.00× | 1.55× | 61% |

Caching is a net loss on single-attempt jobs and a large win on retries. Since Resolve and Generate both consume the same prefix, **even a first-attempt-success job makes two calls** and therefore lands at 2 uses → caching wins from the first job. Enable it unconditionally.

**Controls (§6.9):**
- `testgen.budget.max-tokens-per-job` — pre-flight refusal, not post-hoc discovery
- `testgen.budget.max-usd-per-job` — hard circuit-break
- `testgen.retry.max-attempts` — bounded loop, fails toward escalation
- `testgen.dom.max-snapshot-tokens` — bounds the dominant input term
- Weekly spend alert derived from `cost_events`, not the vendor's monthly bill

**Economic verdict:** the dominant cost is engineer time, not tokens. A pipeline that converts a 20-minute interactive session into a 2-minute MR review pays for its own token spend many times over at any plausible price point. The purpose of budgeting is not savings — it is preventing an uncapped retry loop on a pathological recording from producing a surprise invoice.

## 2.5 Operational Feasibility

Moving from "fails in my IDE, I notice" to "CI service" is the real organizational change. Requirements this imposes:

- **Failures must be loud.** Every terminal state notifies Slack, including escalations. Silent failure is the enemy.
- **Failures must be diagnosable without re-running.** Hence Postgres persistence and `job-summary.json` as a build artifact.
- **Re-running must be safe.** Hence §7. Without idempotency, the natural operator response to a confusing failure — "just rebuild it" — creates duplicate MRs and erodes trust in the system faster than any technical defect.
- **Degradation must be visible, not silent.** Fail-open behaviour in L2 is correct, but a system that fails open 90% of the time while reporting success is worse than one that fails loudly. Hence `confidence_tier` in the MR title and a fail-open-rate SLI.

## 2.6 Risk Register

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-01 | LLM proposes a **unique but wrong** locator | Medium | High | Fingerprint identity assertion (§6.6); L2 executed test; human MR approval | Eng |
| R-02 | Static validator diverges from Playwright's selector semantics → silent false pass | **High if unconstrained** | High | Constrained locator grammar (§6.4) with 1:1 Jsoup mapping and no default branch | Eng |
| R-03 | Duplicate MRs from Jenkins retry | High | Medium | Idempotency subsystem (§7) | Eng |
| R-04 | Quality regression after model/prompt change goes unnoticed | High | High | Eval harness with CI gate + nightly run + production drift monitor (§8) | Eng |
| R-05 | Runaway retry loop / cost blowout | Medium | Medium | Pre-flight token check, hard USD cap, bounded retries, escalation | Eng |
| R-06 | Sensitive data sent to third-party LLM | Low-Medium | High | Allow-list redaction, fail-closed, audit trail, compliance sign-off | Security |
| R-07 | Duplicate/near-duplicate Page Object methods accumulate | High if reuse skipped | Medium | Mandatory `ExistingPageObjectAnalyzer` phase, not optional | Eng |
| R-08 | Concurrent jobs conflict on the same Page Object file | Medium | Medium | Advisory lock on `target_area` around Generate+commit; HEAD SHA re-check | Eng |
| R-09 | Session credentials leak via preserved Jenkins workspace | Medium | High | Separate minting job → Vault; orchestrator read-only; `0600`; `cleanWs()` in `post{always}` | Platform |
| R-10 | L2 silently degrades to never running | Medium | Medium | Fail-open-rate SLI, alert at >20% | Eng |
| R-11 | Recorder contract v2 not delivered → L1 degraded | Medium | High | Contract versioning; CLI rejects unversioned recordings; degraded mode explicitly labelled | Eng |
| R-12 | Stale recording (app changed since capture) burns full retry budget | Medium | Low | 24-hour freshness gate at ingestion | Eng |
| R-13 | Reviewers rubber-stamp MRs (automation bias) | Medium | High | Confidence tier in MR title; reviewer rotation; per-reviewer edit-rate metric | QA Lead |
| R-14 | Jenkins agent contention | Low | Low | Dedicated label; ephemeral containerized agents if needed | Platform |
| R-15 | Anthropic rate limiting under burst | Low | Low | Backoff with jitter; Jenkins concurrent-build cap | Eng |
| R-16 | Golden corpus goes stale → false regression alerts | Medium | Medium | Quarterly refresh; per-case staleness quarantine (§8.7) | Eng |

## 2.7 Verdict

**Build it.** All identified risks are known and mitigable; none are open research problems. Required shape:

1. Phased rollout (§10), pilot before general availability.
2. Human-approved MRs as a **permanent** control, never auto-merge.
3. Deterministic validation replaces the human locator gate — not a longer prompt asking the model to self-check.
4. Compliance sign-off before the first real recording leaves the network.
5. Idempotency and the eval harness are **Week 1–2 items**, not hardening. Both are cheap to build early and expensive to retrofit.

---

# 3. Features & Functionality

## 3.1 Feature Catalog

Priority: **P0** = pilot blocker, **P1** = required for general availability, **P2** = post-GA.

| ID | Feature | Priority | Description |
|---|---|---|---|
| F-01 | Recording ingestion CLI | P0 | `testgen push <folder>` validates contract, checks freshness, computes idempotency key, pushes scratch branch, triggers Jenkins, prints build URL |
| F-02 | Contract validation | P0 | Schema + version check on recording files, client-side, sub-second |
| F-03 | Freshness gate | P0 | Reject recordings older than 24h |
| F-04 | Recording parse | P0 | Action sequence extraction, login-flow detection, target-element identification |
| F-05 | DOM redaction | P0 | Allow-list attribute filtering, URL normalization, PII sweep, audit report |
| F-06 | DOM pruning | P0 | Bound snapshot to a configured token ceiling; drop farthest ancestors first |
| F-07 | Existing-code analysis | P1 | JavaParser AST walk producing reusable method/locator digest |
| F-08 | Locator resolution (LLM) | P0 | Claude proposes locators under a schema-constrained grammar |
| F-09 | L0 grammar validation | P0 | Locator conforms to allowed grammar; no banned APIs |
| F-10 | L1 static DOM validation | P0 | Jsoup uniqueness + fingerprint identity assertion |
| F-11 | Self-healing retry loop | P0 | Scoped corrective feedback; only failed locators are re-proposed |
| F-12 | Code generation (LLM) | P0 | Java/TestNG test + Page Object files under project conventions |
| F-13 | Post-generation lint | P1 | Generated code parses (JavaParser) and passes Checkstyle |
| F-14 | L2 executed-test verification | P1 | Run the generated test against the test env with injected session |
| F-15 | Session minting | P1 | Scheduled job producing `storageState` per target area into Vault |
| F-16 | GitLab MR automation | P0 | Deterministic branch, commit, MR with auto-generated report |
| F-17 | Escalation path | P0 | Draft MR flagged `NEEDS REVIEW` with exact failing locators |
| F-18 | Slack notification | P1 | Terminal-state notification to requester with MR link + confidence tier |
| F-19 | Job persistence | P0 | Postgres record of state, attempts, validation results |
| F-20 | Cost tracking & budgeting | P0 | Per-call token accounting, pre-flight refusal, hard USD circuit-breaker |
| F-21 | **Idempotent execution** | P0 | Content-derived key, lease + fencing, effect ledger, reconciliation (§7) |
| F-22 | **Evaluation harness** | P0 | Golden corpus, deterministic scoring, statistical gate, CI integration (§8) |
| F-23 | Production drift monitor | P1 | EWMA on first-attempt pass rate vs. baseline; alert on regression |
| F-24 | Structured logging | P0 | JSON logs with MDC correlation fields |
| F-25 | Grafana dashboards | P1 | Job status, cost, quality trend, SLI panels over Postgres |
| F-26 | Concurrency control | P1 | Advisory lock per target area; HEAD SHA re-check before commit |
| F-27 | Confidence tiering | P0 | Per-job label surfaced in MR title reflecting which tiers actually ran |
| F-28 | Reconciler job | P1 | Scheduled sweep for orphaned leases and unconfirmed effects (§7.7) |
| F-29 | Slack-triggered ingestion | P2 | Alternative to CLI; build only if adoption data justifies it |
| F-30 | Multi-repo routing | P2 | Out of scope for v3 |

## 3.2 Primary User Journey — Happy Path

```
1.  Engineer records a browser flow.                          [existing tooling, unchanged]
2.  $ testgen push ./dashboard-filter-test \
        --area=dashboard \
        --desc="Dashboard date filter persists across reload"

    ✓ Contract v2 valid (14 actions, 9 interacted elements, page HTML captured)
    ✓ Recording age 3m (limit 24h)
    ✓ Idempotency key  a3f9c2...  (new)
    ✓ Pushed testgen-scratch/8f2a-...
    ✓ Triggered  https://jenkins/job/testgen-pipeline/1841/

3.  Pipeline runs (typical 3–6 min):
      Parse → Redact → Assemble context → Resolve → L0/L1 → Generate → Lint → L2 Verify → MR

4.  Slack DM:
      ✅ [VERIFIED] Test-gen MR ready — Dashboard date filter persists across reload
         !2291 · 2 files changed (1 new) · 1 attempt · 32k tokens · $0.14 · 4m12s
         Reused: DashboardPage.openDateFilter(), DashboardPage.applyFilter()

5.  Engineer reviews the MR (~2 min), approves, merges.
```

## 3.3 Journey — Retry & Self-Healing

```
Attempt 0:  Resolve → 9 locators proposed
            L1: dateFilterButton -> NOT_UNIQUE (3 matches)
                applyButton      -> WRONG_ELEMENT (role=button name="Apply" found,
                                    but recorded fingerprint had accessibleName="Apply filters")
            → corrective feedback constructed, scoped to those 2 elements only

Attempt 1:  Resolve (7 passing locators explicitly frozen)
            L1: all 9 pass
            → proceed to Generate
```

The retry loop **narrows scope monotonically**: each attempt is strictly easier than the last, and passing locators are never re-litigated. This is what keeps token cost bounded and convergence fast.

## 3.4 Journey — Escalation

```
Attempts 0,1,2 all fail L1 on `rowActionMenu` (NOT_UNIQUE: 12 matches — a table row menu
with no distinguishing test id).

→ state = NEEDS_HUMAN_REVIEW
→ Draft MR opened: "[Draft][NEEDS REVIEW][STATIC_ONLY] Dashboard row actions"
   Description contains:
     - The 8 locators that passed, with strategies
     - The 1 that failed, with all 3 attempts, match counts, and candidate DOM
     - A concrete suggestion: "no stable identifier available — consider adding
       data-testid to the row action trigger"
→ Slack: ⚠️ manual review required
```

Escalation is a **first-class successful outcome**, not a failure. The pipeline produced a partially-complete, clearly-annotated artifact and told a human exactly what to look at. Jenkins marks the build `UNSTABLE`, not `FAILURE`.

## 3.5 Journey — Re-run / Idempotency

```
Jenkins agent dies mid-Generate. Operator clicks "Rebuild".

Bootstrap:
  idempotency key a3f9c2... already present, state=GENERATE, heartbeat 14m stale
  → lease reclaimed, lease_epoch 1 → 2
  → effect ledger consulted:
       BRANCH  testgen/dashboard/8f2a  CONFIRMED   (exists, no-op)
       COMMIT  <deterministic tree>    PENDING     → reconcile: query GitLab, found, mark CONFIRMED
       MR      <source branch>         ABSENT      → create
  → resumes from GENERATE, opens exactly one MR
```

If the original process wakes up and attempts a write, its `lease_epoch=1` no longer matches the row's `lease_epoch=2`, and every write is rejected. See §7.4.

## 3.6 Functional Description of Each Phase

| Phase | Input | Output | Failure behaviour |
|---|---|---|---|
| **Bootstrap** | CLI parameters | Claimed job with lease | Fail closed (exit 40) if DB unavailable |
| **Parse** | `recorded-test.js`, `recorded-elements.json` | `ParsedTestPlan`, `PageSnapshot` | Fail (exit 30) — invalid contract |
| **Redact** | `PageSnapshot` | Redacted `PageSnapshot` + `RedactionReport` | **Fail closed (exit 60)** |
| **Context Assembly** | Target area, git tree SHA | `ExistingCodeContext`, pruned snapshot | Degrade: empty context, log warning |
| **Resolve** | Plan + snapshot + context + feedback | `ResolvedLocatorSet` | Retry on transport error; fail on budget breach (exit 50) |
| **L0 Grammar** | `ResolvedLocatorSet` | Violations | Feeds retry loop |
| **L1 Static DOM** | Locators + snapshot | Violations | Feeds retry loop; degrades if no page HTML |
| **Generate** | Plan + locators + context | `List<GeneratedFile>` | Retry once on lint failure, then escalate |
| **Lint** | Generated files | Pass/violations | Escalate on failure |
| **L2 Verify** | Generated files + session | `VerificationResult` | **Fail open** — skip and label, never fail the job |
| **Publish** | Files + job | Branch, commit, MR, Slack | Retry with backoff; effects are exactly-once |

---

# 4. Requirements

## 4.1 Functional Requirements

| ID | Requirement | Priority | Verifies | Component |
|---|---|---|---|---|
| FR-01 | The CLI SHALL validate the recording against contract v2 and reject invalid or unversioned recordings before any network call | P0 | F-02 | `testgen-cli` |
| FR-02 | The CLI SHALL reject recordings whose `capturedAt` is older than `testgen.ingestion.max-age` (default 24h) | P0 | F-03 | `testgen-cli` |
| FR-03 | The CLI SHALL compute the idempotency key from canonicalized recording content and pass it to Jenkins as a build parameter | P0 | F-21 | `IdempotencyKeyDeriver` |
| FR-04 | The orchestrator SHALL claim a job by idempotency key before performing any LLM call, external write, or spend | P0 | F-21 | `IdempotencyGate` |
| FR-05 | The orchestrator SHALL short-circuit with exit code 20 and print the existing MR URL when the key maps to a terminal job | P0 | F-21 | `IdempotencyGate` |
| FR-06 | The orchestrator SHALL redact the DOM snapshot before it is included in any prompt, and SHALL abort the job if redaction fails | P0 | F-05 | `RedactionPhase` |
| FR-07 | The orchestrator SHALL prune the DOM snapshot to at most `testgen.dom.max-snapshot-tokens` | P0 | F-06 | `DomPruner` |
| FR-08 | The LLM SHALL be constrained by JSON schema such that only grammar-legal locator strategies can be emitted | P0 | F-08, F-09 | `SchemaRegistry` |
| FR-09 | L0 SHALL reject any locator using XPath, `:nth-child`, chained `>>` selectors, regex name matching, or generated-id patterns | P0 | F-09 | `LocatorGrammarValidator` |
| FR-10 | L1 SHALL assert each locator matches exactly one element in the captured page HTML | P0 | F-10 | `StaticDomValidator` |
| FR-11 | L1 SHALL assert the matched element's identity fingerprint equals the recorded fingerprint (tag, role, accessible name) | P0 | F-10 | `StaticDomValidator` |
| FR-12 | L1 SHALL emit `STRUCTURAL_DRIFT` as an advisory (non-blocking) violation when `domPathHash` differs but identity matches | P1 | F-10 | `StaticDomValidator` |
| FR-13 | The retry loop SHALL send corrective feedback naming each failed locator, its reason code, and its match count | P0 | F-11 | `PipelineOrchestrator` |
| FR-14 | The retry loop SHALL instruct the model not to alter locators that already passed, and SHALL discard any change to them | P0 | F-11 | `PipelineOrchestrator` |
| FR-15 | The retry loop SHALL terminate after `testgen.retry.max-attempts` in state `NEEDS_HUMAN_REVIEW`, never in `DONE` | P0 | F-11 | `PipelineOrchestrator` |
| FR-16 | The orchestrator SHALL analyze existing Page Objects and include reusable methods/locators in the Generate prompt | P1 | F-07 | `ExistingPageObjectAnalyzer` |
| FR-17 | Generated code SHALL be parsed and Checkstyle-linted before commit; failures escalate | P1 | F-13 | `GeneratePhase` |
| FR-18 | L2 SHALL execute the generated test with an injected service-account `storageState` and record the outcome | P1 | F-14 | `VerifyPhase` |
| FR-19 | L2 SHALL fail open (`SKIPPED` with reason) on missing session, env unavailability, timeout, or mutating test — never failing the job | P1 | F-14 | `VerifyPhase` |
| FR-20 | An L2 failure SHALL escalate to `NEEDS_HUMAN_REVIEW` and SHALL NOT re-enter the Resolve retry loop | P1 | F-14 | `PipelineOrchestrator` |
| FR-21 | Every external effect (branch, commit, MR, Slack) SHALL be recorded in the effect ledger and executed at most once | P0 | F-21 | `EffectExecutor` |
| FR-22 | The MR title SHALL contain the confidence tier; the description SHALL contain the validation report | P0 | F-16, F-27 | `MergeRequestTemplate` |
| FR-23 | Escalated jobs SHALL open a Draft MR listing every failing locator with all attempt history | P0 | F-17 | `MergeRequestTemplate` |
| FR-24 | The orchestrator SHALL record token usage (input, output, cache-create, cache-read) for every LLM call | P0 | F-20 | `TokenBudgetGuard` |
| FR-25 | The orchestrator SHALL refuse a call whose pre-flight token estimate would breach the job budget | P0 | F-20 | `TokenBudgetGuard` |
| FR-26 | The orchestrator SHALL hold an advisory lock on `target_area` across Generate, commit, and MR creation | P1 | F-26 | `TargetAreaLock` |
| FR-27 | The orchestrator SHALL re-read `main` HEAD SHA before commit and re-run reuse analysis if it changed | P1 | F-26 | `GeneratePhase` |
| FR-28 | The eval harness SHALL score a suite of golden cases and produce a machine-readable report | P0 | F-22 | `EvalRunner` |
| FR-29 | A prompt or model change SHALL be gated on a non-inferiority test against the recorded baseline | P0 | F-22 | `EvalGate` |
| FR-30 | The eval harness SHALL run each case `k` times and report per-case variance | P0 | F-22 | `EvalRunner` |
| FR-31 | Every job SHALL persist `prompt_bundle_hash`, `model_id`, and `orchestrator_version` | P0 | F-19, F-22 | `JobRepository` |
| FR-32 | The system SHALL alert when the rolling first-attempt pass rate regresses beyond threshold vs. baseline | P1 | F-23 | `DriftMonitor` |
| FR-33 | All logs SHALL be JSON with MDC fields `jobId`, `idempotencyKey`, `targetArea`, `phase`, `attempt`, `leaseEpoch`, `promptBundleHash`, `modelId` | P0 | F-24 | `LoggingBootstrap` |
| FR-34 | Prompt bodies SHALL NOT appear in logs; only hashes | P0 | F-24 | `LoggingBootstrap` |
| FR-35 | The orchestrator SHALL emit `job-summary.json` as a Jenkins build artifact on every terminal state | P1 | F-24 | `JobSummaryWriter` |
| FR-36 | A reconciler SHALL periodically resolve orphaned leases and unconfirmed effects | P1 | F-28 | `ReconcilerJob` |
| FR-37 | Session `storageState` SHALL be minted by a dedicated scheduled job into Vault, never by the orchestrator | P1 | F-15 | `session-mint` job |

## 4.2 Non-Functional Requirements

### Performance & Capacity

| ID | Requirement | Target | Measurement |
|---|---|---|---|
| NFR-01 | End-to-end time to MR, p50 | ≤ 4 min | `jobs.created_at → completed_at` |
| NFR-02 | End-to-end time to MR, p95 | ≤ 8 min | Same |
| NFR-03 | CLI validation latency | ≤ 500 ms | Local timing |
| NFR-04 | L1 validation latency per attempt | ≤ 200 ms | In-process timing |
| NFR-05 | L2 verification timeout | 5 min hard cap | `VerifyPhase` |
| NFR-06 | Sustained throughput | 20 jobs/day, bursts to 8 concurrent | Jenkins queue metrics |
| NFR-07 | Postgres working set | < 1 GB at 5 years of retention | Row-count projection |

### Reliability & Availability

| ID | Requirement |
|---|---|
| NFR-08 | The pipeline SHALL be safe to re-run at any point without producing duplicate external effects |
| NFR-09 | A crashed job SHALL be resumable after lease expiry without operator intervention beyond a rebuild |
| NFR-10 | Postgres unavailability SHALL cause the job to fail **before** any LLM spend or external write (fail closed) |
| NFR-11 | Anthropic or GitLab transient errors SHALL be retried with exponential backoff and jitter, distinct from the semantic retry loop |
| NFR-12 | No silent model fallback: a degraded model SHALL NOT be substituted on primary-model failure |
| NFR-13 | Target availability of the pipeline as perceived by users: 99% of submissions reach a terminal state within NFR-02 |

### Security & Privacy

| ID | Requirement |
|---|---|
| NFR-14 | Only allow-listed DOM attributes SHALL leave the network |
| NFR-15 | Input `value` attributes SHALL never be transmitted |
| NFR-16 | Secrets SHALL be sourced from Jenkins credentials/Vault and never written to logs, MR descriptions, or the database |
| NFR-17 | `storageState` files SHALL be mode `0600`, deleted in `finally`, and the workspace wiped in `post { always { cleanWs() } }` |
| NFR-18 | Sessions SHALL belong to a dedicated automation service account, never an individual's account |
| NFR-19 | The GitLab bot token SHALL be scoped to the test repository with the minimum required scopes |
| NFR-20 | A per-job `redaction_report` and redacted-payload SHA SHALL be retained for audit |

### Observability

| ID | Requirement |
|---|---|
| NFR-21 | Every terminal state SHALL notify the requester via Slack |
| NFR-22 | Every job SHALL be diagnosable post-hoc from Postgres + build artifacts without re-running |
| NFR-23 | All SLIs in §9.6 SHALL be computable via SQL against Postgres |
| NFR-24 | For jobs ending in `NEEDS_HUMAN_REVIEW`, the full redacted request/response pair SHALL be retained for `testgen.retention.debug-days` (default 30) |

### Maintainability & Portability

| ID | Requirement |
|---|---|
| NFR-25 | All LLM interaction SHALL sit behind the `LlmClient` interface; no framework types leak into `phases/` or `orchestrator/` |
| NFR-26 | Prompts SHALL be versioned files in the repository, never inline string literals in phase classes |
| NFR-27 | Database schema changes SHALL go through Flyway migrations; no manual DDL |
| NFR-28 | Third-party library versions SHALL be pinned exactly (no version ranges, no `LATEST`) |
| NFR-29 | The orchestrator SHALL run on any JVM 21 host with Maven and Playwright browsers; no OS-specific assumptions |

## 4.3 Data Requirements

| ID | Requirement |
|---|---|
| DR-01 | The recorder SHALL emit `contractVersion: 2` and be rejected otherwise |
| DR-02 | Contract v2 SHALL include, per interacted element, an `ElementFingerprint` |
| DR-03 | Contract v2 SHALL include a pruned `pageHtml` per distinct DOM state, capped at `maxNodes` (default 2000) |
| DR-04 | Retention: `jobs`, `retry_attempts`, `validation_results`, `cost_events` retained 24 months; `verification_runs` 12 months; debug payloads 30 days |
| DR-05 | Eval corpus cases SHALL be stored in the orchestrator repository under `evals/cases/` and versioned with the code |
| DR-06 | Baselines for the eval gate SHALL be stored in Postgres, keyed by `(suite, model_id, prompt_bundle_hash)` |

## 4.4 Constraints

| ID | Constraint |
|---|---|
| C-01 | Java 21 — the team's language; also enables JavaParser co-tooling with the target codebase |
| C-02 | The orchestrator is a **one-shot CLI process**, not a persistent service. All caching, state, and coordination must survive process death. |
| C-03 | Jenkins is the scheduler. No new orchestration platform is introduced. |
| C-04 | Target test repository is GitLab; MR is the delivery mechanism |
| C-05 | Generated artifacts remain Java/TestNG/Playwright — the existing suite's stack is unchanged |
| C-06 | No auto-merge, ever |

## 4.5 Assumptions

| ID | Assumption | If false |
|---|---|---|
| A-01 | The recorder can be extended to contract v2 | L1 runs in degraded mode; identity assertion unavailable; escalation rate rises materially |
| A-02 | A test environment reachable from Jenkins agents exists | L2 permanently `SKIPPED`; system operates at `STATIC_ONLY` |
| A-03 | A service account can be provisioned per target area | Same as A-02 |
| A-04 | 30–50 historical recordings with approved outputs are recoverable | Eval harness starts from a smaller corpus; gate is weaker until corpus grows |
| A-05 | Compliance approves redacted DOM egress | Project blocked; consider self-hosted model as a fallback path |
| A-06 | Target areas map 1:1 to Page Object packages | Locking granularity must be revisited |

## 4.6 Acceptance Criteria (Pilot Exit)

| ID | Criterion |
|---|---|
| AC-01 | ≥ 70% of opened MRs merged with zero human edits over a 20-job window |
| AC-02 | p95 time-to-MR ≤ 8 minutes |
| AC-03 | Zero duplicate MRs across ≥ 10 deliberately induced re-runs |
| AC-04 | Eval harness gate demonstrably blocks a deliberately degraded prompt |
| AC-05 | L2 skip rate < 20% |
| AC-06 | Escalation rate < 15% |
| AC-07 | Zero secrets or PII found in a manual audit of 20 job records, logs, and MR descriptions |
| AC-08 | Every failure mode in the runbook (§11) reproduced at least once and resolved by following the documented steps |

---

# 5. High-Level Design

## 5.1 Design Principles

These are the rules that resolve arguments during implementation.

1. **The LLM proposes; deterministic code decides.** No validation gate is ever a prompt instruction. If a property can be checked mechanically, it is checked mechanically.
2. **Validators fail open and label; the redactor fails closed.** A degraded validation costs a lower-confidence MR. A degraded redaction costs a data-exposure incident. The asymmetry is deliberate.
3. **Degradation is always visible.** Every fail-open writes a reason code that reaches a human and a metric.
4. **Every operation is safe to repeat.** The system is a batch job invoked by a CI system that retries. Idempotency is a correctness requirement, not a nicety.
5. **Don't reimplement what you already own.** The generated test is run by the existing test runner, not by a bespoke replay engine.
6. **Constrain the output space rather than validating a large one.** A small locator grammar with an exact 1:1 mapping to the validator beats a permissive grammar with an approximate validator.
7. **Measure the claims the design rests on.** Cache effectiveness, fail-open rate, and quality trend are all persisted columns, not assumptions.
8. **Build the smallest thing that satisfies the requirement.** Grafana over Postgres instead of a status API; Postgres advisory locks instead of a distributed lock service; no broker.

## 5.2 System Context (C4 Level 1)

```
                            ┌───────────────────────┐
                            │     QA Engineer       │
                            │  records, reviews MR  │
                            └───┬───────────────▲───┘
                  testgen push  │               │  Slack: MR link + tier
                                ▼               │
       ┌────────────────────────────────────────┴──────────────────────┐
       │                  TEST-GENERATION PIPELINE                       │
       │   CLI · Jenkins jobs · Java orchestrator · Postgres             │
       └──┬────────────┬─────────────┬──────────────┬──────────────┬────┘
          │            │             │              │              │
          ▼            ▼             ▼              ▼              ▼
   ┌────────────┐ ┌─────────┐ ┌────────────┐ ┌───────────┐ ┌────────────┐
   │ Claude API │ │ GitLab  │ │  Test Env  │ │   Vault   │ │  Grafana   │
   │ structured │ │ repo +  │ │ (app under │ │ (service  │ │ (read-only │
   │ outputs +  │ │  MRs    │ │  test)     │ │  account) │ │  over PG)  │
   │ caching    │ │         │ │            │ │           │ │            │
   └────────────┘ └─────────┘ └────────────┘ └───────────┘ └────────────┘
```

## 5.3 Container Diagram (C4 Level 2)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ DEVELOPER WORKSTATION                                                         │
│  ┌──────────────────────────────────────────────────────────┐                │
│  │ testgen-cli  (picocli, fat jar / native)                  │                │
│  │  · contract v2 validation      · freshness gate           │                │
│  │  · idempotency key derivation  · scratch-branch push      │                │
│  │  · Jenkins trigger (REST)                                 │                │
│  └──────────────────────────────────────────────────────────┘                │
└──────────────────────────────────────────────────────────────────────────────┘
                                     │ POST /buildWithParameters
                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ JENKINS                                                                       │
│  ┌────────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ testgen-pipeline   │  │ testgen-session- │  │ testgen-eval-nightly     │  │
│  │ (parameterized)    │  │ mint (cron)      │  │ (cron) + testgen-eval-pr │  │
│  └─────────┬──────────┘  └────────┬─────────┘  └────────────┬─────────────┘  │
│            │                      │ storageState            │                │
│            │                      ▼                         │                │
│            │              ┌──────────────┐                  │                │
│            │              │    Vault     │                  │                │
│            │              └──────────────┘                  │                │
│  ┌─────────▼──────────────────────────────────────────────────────────────┐  │
│  │              testgen-orchestrator.jar  (one-shot JVM, Java 21)          │  │
│  │                                                                         │  │
│  │  orchestrator/   phases/        validation/    llm/       persistence/  │  │
│  │  idempotency/    redaction/     gitlab/        eval/      observability/│  │
│  └─────────┬───────────────┬──────────────┬─────────────────┬─────────────┘  │
└────────────┼───────────────┼──────────────┼─────────────────┼────────────────┘
             │               │              │                 │
             ▼               ▼              ▼                 ▼
      ┌────────────┐  ┌────────────┐  ┌──────────┐   ┌─────────────────┐
      │ Claude API │  │  GitLab    │  │ Test Env │   │   PostgreSQL    │
      │            │  │  (4J API)  │  │ (mvn)    │   │  jobs           │
      │ Resolve    │  │            │  │          │   │  retry_attempts │
      │ Generate   │  │ branch     │  │ L2 verify│   │  validation_res │
      │            │  │ commit     │  │          │   │  cost_events    │
      │            │  │ MR         │  │          │   │  verification   │
      │            │  │            │  │          │   │  effects        │
      │            │  │            │  │          │   │  eval_*         │
      └────────────┘  └────────────┘  └──────────┘   └────────┬────────┘
                                                              │ SQL
                                                              ▼
                                                       ┌─────────────┐
                                                       │   Grafana   │
                                                       └─────────────┘
```

## 5.4 Component Responsibilities

| Component | Responsibility | Explicitly NOT responsible for |
|---|---|---|
| `testgen-cli` | Client-side validation, key derivation, upload, trigger | Any LLM interaction or business logic |
| `IdempotencyGate` | Claim/reclaim, lease, fencing token | Executing effects |
| `EffectExecutor` | At-most-once external effects with reconciliation | Deciding *what* effects to perform |
| `ParsePhase` | Action extraction, login detection, snapshot construction | Any network I/O |
| `RedactionPhase` | Allow-list filter, URL normalization, PII sweep, audit report | Deciding what is "sensitive" at runtime (rules are static config) |
| `ContextAssemblyPhase` | DOM pruning, JavaParser digest, cache lookup | Prompt construction |
| `LlmClient` | Structured-output calls, cache layout, usage capture | Retry policy, validation, budget decisions |
| `TokenBudgetGuard` | Pre-flight estimate, post-hoc accounting, circuit break | Making the call |
| `LocatorGrammarValidator` (L0) | Grammar conformance, banned patterns | DOM knowledge |
| `StaticDomValidator` (L1) | Uniqueness + fingerprint identity via Jsoup | Browser semantics beyond the grammar |
| `VerifyPhase` (L2) | Execute generated test, classify outcome | Feeding results back into Resolve |
| `PipelineOrchestrator` | Phase sequencing, retry loop, state transitions | Any I/O detail |
| `GitLabIntegrationService` | Branch/commit/MR via GitLab4J | Idempotency (delegated to `EffectExecutor`) |
| `EvalRunner` / `EvalGate` | Corpus execution, scoring, statistical gating | Production job execution |
| `ReconcilerJob` | Orphan lease recovery, unconfirmed-effect resolution | Business logic |

## 5.5 Data Flow

```
recorded-test.js ──┐
                   ├─▶ ParsePhase ──▶ RawSnapshot ──▶ RedactionPhase ──▶ SafeSnapshot
recorded-elements.json ─┘                                    │                  │
                                                             ▼                  │
                                                    RedactionReport ──▶ Postgres│
                                                                                │
git tree SHA ──▶ ContextAssembly ──▶ ExistingCodeContext ──┐                    │
                    │                                       ▼                    ▼
                    └── (cache: content-addressed) ──▶ [ SYSTEM MESSAGE: prompts +
                                                          conventions + context +
                                                          SafeSnapshot ]   ◀── cacheable prefix
                                                                    +
                                                         [ USER MESSAGE: task +
                                                           correctionFeedback ]  ◀── volatile
                                                                    │
                                                                    ▼
                                                            Claude (schema-constrained)
                                                                    │
                                                       ResolvedLocatorSet + TokenUsage
                                                            │              │
                                                            ▼              ▼
                                                     L0 → L1 → violations  cost_events
                                                            │
                                                  pass      │      fail → corrective feedback
                                                            ▼               (loop, scoped)
                                                       GeneratePhase
                                                            │
                                                    GeneratedFile[] ──▶ Lint ──▶ L2 Verify
                                                            │
                                                            ▼
                                              EffectExecutor: branch → commit → MR → Slack
                                                            │
                                                            ▼
                                                  effects ledger + jobs (terminal)
```

## 5.6 Integration Patterns

| Boundary | Pattern | Justification | Revisit trigger |
|---|---|---|---|
| CLI → GitLab | git push over HTTPS | Content transfer; git is the transport | — |
| CLI → Jenkins | REST `buildWithParameters`, fire-and-forget | Single call; a broker adds an operational dependency for no benefit at this volume | Sustained > 50 jobs/day |
| Jenkins → Orchestrator | Process invocation (`java -jar`) | One-shot lifecycle is the point; no long-lived state to manage | Queue latency dominates NFR-02 |
| Orchestrator → Claude | Synchronous REST via LangChain4j | The retry loop is inherently sequential — attempt N+1 depends on attempt N's violations | Batch API becomes attractive only for the eval harness (§8.5) |
| Orchestrator → Postgres | JDBC + HikariCP + JDBI 3, transactional | Advisory locks, unique constraints, and the effect ledger are the coordination primitives | — |
| Orchestrator → GitLab | REST (GitLab4J), wrapped in `EffectExecutor` | Deterministic names + ledger make retries safe | — |
| Orchestrator → Test Env | Process invocation (`mvn test`) | Reuses the existing runner rather than reimplementing it | — |
| Orchestrator → Slack | Jenkins `post{}` + `slackSend` | Notifier already exists; don't build a second one | Requires per-user DM routing beyond Jenkins' capability |
| Read path (dashboards) | SQL via Grafana | Replaces a job-status API entirely | A machine (not a human) needs to consume job state |

**When event-driven becomes correct:** if throughput exceeds ~50 jobs/day *or* Jenkins queue wait becomes the dominant term in time-to-MR, replace the Jenkins trigger with a Postgres-backed work queue using `SELECT ... FOR UPDATE SKIP LOCKED` and a small worker pool. Still not Kafka — the queue depth will never justify a broker, and Postgres is already a hard dependency.

## 5.7 Job State Machine

```
                       ┌──────────────┐
                       │   CLAIMED    │◀── IdempotencyGate (lease acquired, epoch N)
                       └──────┬───────┘
                              ▼
       ┌────────┐      ┌────────────┐      ┌──────────────────┐
       │ PARSE  │─────▶│  REDACT    │─────▶│ CONTEXT_ASSEMBLY │
       └────────┘      └─────┬──────┘      └────────┬─────────┘
                             │ fail-closed          │
                             ▼                      ▼
                         ┌───────┐            ┌──────────┐
                         │FAILED │            │ RESOLVE  │◀────────┐
                         └───────┘            └────┬─────┘         │
                                                   ▼               │ corrective
                                             ┌──────────┐          │ feedback
                                             │ VALIDATE │──fail────┘ (attempt < max)
                                             │  L0 + L1 │
                                             └────┬─────┘
                                        pass      │      fail & attempt == max
                                                  ▼                  │
                                            ┌──────────┐             │
                                            │ GENERATE │             │
                                            └────┬─────┘             │
                                                 ▼                   │
                                            ┌──────────┐             │
                                            │   LINT   │──fail───────┤
                                            └────┬─────┘             │
                                                 ▼                   │
                                            ┌──────────┐             │
                                            │  VERIFY  │──fail───────┤
                                            │   (L2)   │             │
                                            └────┬─────┘             │
                                    pass/skipped │                   │
                                                 ▼                   ▼
                                            ┌──────────┐   ┌───────────────────┐
                                            │ PUBLISH  │   │ NEEDS_HUMAN_REVIEW│
                                            └────┬─────┘   └─────────┬─────────┘
                                                 ▼                   ▼
                                            ┌──────────┐      (draft MR + Slack,
                                            │   DONE   │       Jenkins UNSTABLE)
                                            └──────────┘
```

Terminal states: `DONE`, `NEEDS_HUMAN_REVIEW`, `FAILED`. Only `FAILED` is an actual error; `NEEDS_HUMAN_REVIEW` is a designed outcome.

## 5.8 Deployment View

| Artifact | Where it runs | Trigger | Lifetime |
|---|---|---|---|
| `testgen-cli.jar` | Engineer workstation | Manual | Seconds |
| `testgen-orchestrator.jar` (mode `run`) | Jenkins agent `testgen-worker` | Parameterized build | 3–8 min |
| `testgen-orchestrator.jar` (mode `eval`) | Jenkins agent | Nightly cron + PR webhook | 10–40 min |
| `testgen-orchestrator.jar` (mode `reconcile`) | Jenkins agent | Cron `*/15 * * * *` | Seconds |
| `session-mint` (Playwright script) | Jenkins agent | Cron, at ½ session TTL | ~30 s per area |
| PostgreSQL | Managed instance | Always on | — |
| Grafana | Existing instance | Always on | — |

## 5.9 What Changed From the Original Design

| # | v1 design | v3 design | Why |
|---|---|---|---|
| 1 | `PlaywrightReplayValidator` + `ActionReplayer` | **Deleted.** `VerifyPhase` executes the generated test | `ActionReplayer` is a bespoke reimplementation of a test runner already owned. Running the real test checks locators *plus* assertions, page-object wiring, and imports, for one Maven invocation. |
| 2 | Hand-rolled `CssMatcher` over a flat element list | **Constrained locator grammar + Jsoup over real HTML** | A hand-rolled matcher cannot match Playwright's selector engine. Every divergence is a silent false pass that lands in `main`. Shrinking the grammar makes the validator provably equivalent. |
| 3 | Validation contract = `count == 1` | **Uniqueness + fingerprint identity** | Uniqueness does not prove correctness. "Unique but wrong" was in v1's own risk register and unaddressed by v1's design. |
| 4 | Caffeine for existing-code context | **Content-addressed cache keyed on git tree SHA** (and only if measured necessary) | The orchestrator is a one-shot process; an in-process cache has ~0% cross-job hit rate. Caffeine remains for intra-job memoization only. |
| 5 | No persistence — state in a heap object | **Postgres + Flyway, 8 tables** | Required for idempotency, cost governance, forensics, and eval baselines. |
| 6 | No idempotency | **§7: content-derived key, lease + fencing token, effect ledger, reconciler** | A Jenkins rebuild currently opens a second MR. This will happen. |
| 7 | No quality measurement | **§8: golden corpus, deterministic scoring, non-inferiority gate, drift monitor** | Without it, a model or prompt change can silently degrade output for weeks. |
| 8 | Forced tool-use for structured output | **Native Structured Outputs (`output_config.format`)** | Neither call is really a tool call; grammar-constrained sampling is the direct fit. Sending zero tools also sidesteps LangChain4j's `cacheTools` block-limit and cache-invalidation bugs. |
| 9 | Prompt layout unspecified | **Cache-first layout mandated** (stable content in system message) | `langchain4j-anthropic` places `cache_control` on system messages and tools only. Putting the DOM snapshot in the user message silently disables caching — a ~50% cost difference on multi-attempt jobs. |
| 10 | `AiServices` declarative proxy | **Low-level `ChatModel` API behind `LlmClient`** | Cost governance requires `AnthropicTokenUsage` on every call and explicit cache placement. Both are things a declarative proxy tends to hide. ~15 lines of boilerplate for the entire cost-control story. |
| 11 | `AuthSessionProvider` logs in inline, caches `Path`s forever | **Separate scheduled minting job → Vault; orchestrator reads only** | v1 wrote live session tokens to a Jenkins workspace with no cleanup. Also decouples MFA/rotation failures from job failures. |
| 12 | Implied job-status REST API | **Grafana over Postgres** | Same outcome, zero Java. |
| 13 | Redaction "considered" but not built | **Mandatory fail-closed `RedactionPhase`** | v1 identified the compliance gap and left it open. |
| 14 | No freshness or contract gating | **Contract v2 + 24h freshness gate at the CLI** | Stale recordings burn the entire retry budget on unfixable failures. |

---

# 6. Low-Level Design

## 6.1 Repository & Module Layout

```
testgen/                                   (single Maven multi-module repo)
├── pom.xml                                (parent, dependencyManagement, versions pinned)
├── testgen-cli/                           (picocli, no orchestrator deps)
├── testgen-core/                          (domain model, contracts — shared by cli & orchestrator)
├── testgen-orchestrator/                  (the pipeline)
├── evals/
│   ├── cases/<case-id>/                   (golden corpus, versioned with code)
│   └── suites.yaml
├── prompts/
│   ├── v7/resolve.system.md
│   ├── v7/generate.system.md
│   ├── v7/conventions.md
│   └── v7/bundle.yaml                     (manifest → hashed)
├── docker-compose.yml                     (local Postgres + Flyway)
└── docs/adr/                              (architecture decision records)
```

### Package structure — `testgen-orchestrator`

```
com.company.testgen
├── Main.java                              (picocli: run | eval | reconcile | migrate)
├── orchestrator/
│   ├── PipelineOrchestrator.java
│   ├── PipelineContext.java
│   ├── PipelineState.java                 (enum)
│   ├── PipelineOutcome.java
│   └── CorrectiveFeedbackBuilder.java
├── idempotency/                           ★ §7
│   ├── IdempotencyKeyDeriver.java
│   ├── IdempotencyGate.java
│   ├── Lease.java
│   ├── FencedJobWriter.java
│   ├── EffectExecutor.java
│   ├── EffectType.java                    (enum)
│   └── ReconcilerJob.java
├── phases/
│   ├── ParsePhase.java
│   ├── RedactionPhase.java
│   ├── ContextAssemblyPhase.java
│   ├── ResolvePhase.java
│   ├── GeneratePhase.java
│   ├── LintPhase.java
│   └── VerifyPhase.java
├── model/
│   ├── RecordedAction.java     RecordedInteraction.java   ElementFingerprint.java
│   ├── PageSnapshot.java       DomElementSnapshot.java    ParsedTestPlan.java
│   ├── LocatorStrategy.java    ResolvedLocator.java       ResolvedLocatorSet.java
│   ├── ValidationViolation.java  ValidationResult.java    Severity.java
│   ├── GeneratedFile.java      ExistingCodeContext.java   ConfidenceTier.java
│   ├── VerificationResult.java RedactionReport.java       TokenUsage.java
│   └── TestGenerationJob.java
├── llm/
│   ├── LlmClient.java                     (interface — the anti-corruption layer)
│   ├── LangChain4jLlmClient.java
│   ├── PromptBundle.java                  (loads prompts/vN, computes bundle hash)
│   ├── SchemaRegistry.java                (JSON schemas for structured outputs)
│   ├── TokenBudgetGuard.java
│   └── TransportRetryPolicy.java
├── validation/
│   ├── LocatorGrammarValidator.java       (L0)
│   ├── StaticDomValidator.java            (L1)
│   ├── JsoupLocatorTranslator.java
│   ├── FingerprintExtractor.java
│   └── ExistingPageObjectAnalyzer.java
├── redaction/
│   ├── DomRedactor.java   UrlNormalizer.java   PiiScrubber.java   RedactionRules.java
├── gitlab/
│   ├── GitLabIntegrationService.java   MergeRequestTemplate.java   BranchNamer.java
├── persistence/
│   ├── Db.java  (Hikari + JDBI bootstrap)
│   ├── JobRepository.java          RetryAttemptRepository.java
│   ├── ValidationResultRepository.java   CostEventRepository.java
│   ├── VerificationRepository.java  EffectRepository.java  EvalRepository.java
│   ├── TargetAreaLock.java
│   └── migration/V1__init.sql …
├── eval/                                  ★ §8
│   ├── EvalRunner.java  EvalCase.java  EvalScorer.java  EvalMetrics.java
│   ├── EvalGate.java    NonInferiorityTest.java  Bootstrap.java
│   └── DriftMonitor.java
├── observability/
│   ├── LoggingBootstrap.java  JobSummaryWriter.java  Mdc.java
└── config/
    └── PipelineProperties.java
```

## 6.2 Domain Model

```java
// ─── Locator grammar ────────────────────────────────────────────────────────
public enum LocatorStrategy {
    TEST_ID,        // page.getByTestId("...")
    ROLE_NAME,      // page.getByRole(ROLE, new GetByRoleOptions().setName("...").setExact(true))
    LABEL,          // page.getByLabel("...", new GetByLabelOptions().setExact(true))
    PLACEHOLDER,    // page.getByPlaceholder("...", ...setExact(true))
    TEXT            // page.getByText("...", ...setExact(true))   — last resort, low confidence
}

// ─── DOM capture (contract v2) ──────────────────────────────────────────────
public record ElementFingerprint(
        String tagName,
        String role,                  // ARIA role, computed
        String accessibleName,        // computed accessible name
        String normalizedText,        // trimmed, whitespace-collapsed, <= 120 chars
        int    ordinalAmongRolePeers, // document-order index among same-role elements
        String domPathHash            // sha256 of ancestor tag+ordinal chain (structure only)
) {
    /** Identity: semantic fields only. Structural drift is advisory, not blocking. */
    public boolean matchesIdentity(ElementFingerprint other) {
        return tagName.equalsIgnoreCase(other.tagName)
            && Objects.equals(role, other.role)
            && Objects.equals(accessibleName, other.accessibleName);
    }
    public String describe() { return tagName + "[role=" + role + ", name=" + accessibleName + "]"; }
}

public record DomElementSnapshot(
        String elementRefId,
        String tagName,
        Map<String,String> attributes,   // post-redaction, allow-listed only
        String textContent,
        String role,
        String accessibleName,
        ElementFingerprint fingerprint
) {}

public record PageSnapshot(
        String  stateId,                 // distinct DOM state this snapshot represents
        String  normalizedUrl,           // query stripped, ids replaced with {id}
        String  prunedHtml,              // may be null -> degraded mode
        List<DomElementSnapshot> interactedElements,
        Instant capturedAt
) {
    public boolean hasHtml() { return prunedHtml != null && !prunedHtml.isBlank(); }
    public ElementFingerprint fingerprintFor(String refId) { /* ... */ }
}

// ─── Plan & results ─────────────────────────────────────────────────────────
public record RecordedAction(int index, String type, String elementRefId,
                             String value, String stateId) {}

public record ParsedTestPlan(
        String targetArea,
        String testDescription,
        List<RecordedAction> actions,
        List<PageSnapshot> snapshots,
        boolean containsLoginFlow,
        boolean mutatesData
) {}

public record ResolvedLocator(
        String elementRefId,
        LocatorStrategy strategy,
        String value,
        String role,                 // populated only for ROLE_NAME
        String expression            // rendered Playwright-Java call, derived not free-text
) {}

public record ResolvedLocatorSet(List<ResolvedLocator> locators) {}

public enum Severity { BLOCKING, ADVISORY }

public record ValidationViolation(
        String locatorExpression,
        String elementRefId,
        String reasonCode,           // see table §6.7
        int    matchCount,           // -1 when N/A
        Severity severity,
        String detail
) {}

public record ValidationResult(List<ValidationViolation> violations) {
    public boolean passed() { return violations.stream().noneMatch(v -> v.severity() == Severity.BLOCKING); }
    public List<ValidationViolation> blocking() { /* ... */ }
}

public enum ConfidenceTier { VERIFIED, STATIC_ONLY, STATIC_ONLY_DEGRADED }

public record GeneratedFile(String path, String content, boolean isNew) {}

public record TokenUsage(int inputTokens, int outputTokens,
                         int cacheCreationTokens, int cacheReadTokens) {}

public record VerificationResult(String outcome, String skipReason,
                                 int durationMs, String failureTail) { /* factories */ }
```

## 6.3 Ingestion Contract v2

**This is the single most load-bearing change to an owned system outside the orchestrator.** L1's soundness depends on it.

### `recorded-elements.json`

```jsonc
{
  "contractVersion": 2,
  "recorderVersion": "3.1.0",
  "capturedAt": "2026-08-05T09:14:22Z",
  "targetArea": "dashboard",
  "baseUrl": "https://app.example.com",

  "snapshots": [
    {
      "stateId": "s0",
      "url": "https://app.example.com/dashboard?tab=overview",
      "prunedHtml": "<main id=\"root\">…</main>",   // see pruning rules below
      "prunedNodeCount": 1_842,
      "truncated": false
    }
  ],

  "interactions": [
    {
      "elementRefId": "e3",
      "stateId": "s0",
      "actionIndex": 4,
      "tagName": "button",
      "attributes": {
        "data-testid": "date-filter-trigger",
        "class": "btn btn-secondary",
        "type": "button",
        "aria-haspopup": "dialog"
      },
      "textContent": "Last 30 days",
      "role": "button",
      "accessibleName": "Last 30 days",
      "fingerprint": {
        "tagName": "button",
        "role": "button",
        "accessibleName": "Last 30 days",
        "normalizedText": "Last 30 days",
        "ordinalAmongRolePeers": 7,
        "domPathHash": "sha256:9f2c…"
      }
    }
  ]
}
```

### `prunedHtml` capture rules (recorder-side)

Include, for each distinct DOM state:
1. The interacted element and its full subtree.
2. Its complete ancestor chain to `<body>`, with each ancestor's tag + `id`/`class`/`role`/`data-testid` attributes retained.
3. **All siblings sharing the interacted element's role**, anywhere in the document — this is what makes uniqueness checking meaningful rather than locally-scoped.
4. All elements bearing a `data-testid`, `aria-label`, or `role` attribute (cheap, and these are the candidate locator targets).
5. Cap at `maxNodes` (default 2000), dropping farthest ancestors first, then non-candidate leaves. Set `truncated: true` when the cap binds.

**Explicitly excluded at capture time:** `<script>`, `<style>`, `<svg>` internals, comments, and all `value` attributes.

### Degraded mode

If `prunedHtml` is absent or `truncated: true`, L1 runs against `interactions[]` only:
- Uniqueness is checkable only among captured elements → weaker.
- Fingerprint identity is **not** checkable (there is no document to match against).
- Job is labelled `STATIC_ONLY_DEGRADED` and this appears in the MR title.

### Validation at the CLI (FR-01)

```java
public final class ContractValidator {
    private static final int SUPPORTED_VERSION = 2;

    public void validate(Path folder) {
        RecordedElements re = Json.read(folder.resolve("recorded-elements.json"), RecordedElements.class);

        require(re.contractVersion() == SUPPORTED_VERSION,
                "Recorder contract v%d required, found v%s. Upgrade the recorder."
                        .formatted(SUPPORTED_VERSION, re.contractVersion()));
        require(!re.interactions().isEmpty(), "No interacted elements captured.");
        require(re.interactions().stream().allMatch(i -> i.fingerprint() != null),
                "Contract v2 requires a fingerprint on every interaction.");
        require(re.snapshots().stream().anyMatch(s -> s.prunedHtml() != null),
                "No page HTML captured — L1 would run degraded. Re-record with recorder >= 3.1.");

        Duration age = Duration.between(re.capturedAt(), Instant.now());
        require(age.compareTo(maxAge) <= 0,
                "Recording is %s old (limit %s). The app may have changed; please re-record."
                        .formatted(humanize(age), humanize(maxAge)));

        // Cross-file: every action's elementRefId must resolve
        Set<String> refs = re.interactions().stream().map(Interaction::elementRefId).collect(toSet());
        parseActions(folder.resolve("recorded-test.js")).forEach(a ->
            require(a.elementRefId() == null || refs.contains(a.elementRefId()),
                    "Action %d references unknown element %s".formatted(a.index(), a.elementRefId())));
    }
}
```

Failing here costs 200 ms on the engineer's laptop instead of four minutes into a Jenkins build.

## 6.4 The Locator Grammar (the keystone of deterministic validation)

**Problem being solved.** Playwright's selector engine supports `:has-text()`, `>>` chaining, layout selectors, and shadow-DOM piercing. Jsoup supports none of those. Any hand-rolled matcher supports fewer still. If the validator's semantics differ from the runtime's, the validator will pass locators that fail at runtime — **silently, and into `main`.**

**Solution.** Do not build a better matcher. Shrink the output space until the matcher is provably exact.

### Allowed productions

| Strategy | Playwright rendering | Jsoup query | Confidence |
|---|---|---|---|
| `TEST_ID` | `page.getByTestId("v")` | `[data-testid="v"]` | High |
| `ROLE_NAME` | `page.getByRole(AriaRole.R, new Page.GetByRoleOptions().setName("v").setExact(true))` | role-aware traversal + accessible-name computation (§6.6) | High |
| `LABEL` | `page.getByLabel("v", new Page.GetByLabelOptions().setExact(true))` | `label[for]`/wrapping-label/`aria-label`/`aria-labelledby` resolution | Medium-High |
| `PLACEHOLDER` | `page.getByPlaceholder("v", …setExact(true))` | `[placeholder="v"]` | Medium |
| `TEXT` | `page.getByText("v", …setExact(true))` | exact normalized-text match on leaf-ish elements | Low — always advisory-flagged |

### Banned, enforced at two layers

| Banned | Enforced by |
|---|---|
| XPath (`xpath=`, `//`) | Schema — `LocatorStrategy` enum has no XPath member |
| Raw CSS beyond the above | Schema |
| `:nth-child`, `:nth-of-type` | L0 regex |
| Chained `>>` selectors | L0 regex |
| `.nth(i)` | L0 regex — unless `i` is derived from the recorded `ordinalAmongRolePeers`, in which case it is emitted by the renderer, never by the model |
| Regex name matching (`setName(Pattern…)`) | Schema — `value` is `string`, not pattern |
| Generated-id patterns (`generated-id-\d+`, `:r[0-9a-z]+:`, `mui-\d+`, `ember\d+`) | L0 regex |

### Crucially: the model never emits an expression string

The schema constrains the model to `{strategy, value, role, elementRefId}`. The Playwright expression is **rendered by our code** from those fields:

```java
public final class LocatorRenderer {
    public static String render(ResolvedLocator l) {
        return switch (l.strategy()) {
            case TEST_ID     -> "page.getByTestId(%s)".formatted(q(l.value()));
            case ROLE_NAME   -> "page.getByRole(AriaRole.%s, new Page.GetByRoleOptions().setName(%s).setExact(true))"
                                    .formatted(l.role().toUpperCase(Locale.ROOT), q(l.value()));
            case LABEL       -> "page.getByLabel(%s, new Page.GetByLabelOptions().setExact(true))".formatted(q(l.value()));
            case PLACEHOLDER -> "page.getByPlaceholder(%s, new Page.GetByPlaceholderOptions().setExact(true))".formatted(q(l.value()));
            case TEXT        -> "page.getByText(%s, new Page.GetByTextOptions().setExact(true))".formatted(q(l.value()));
        };
        // No default branch. A new strategy is a COMPILE ERROR, not a silent fallthrough.
    }
    private static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
}
```

This eliminates an entire class of failure: the model cannot emit a syntactically valid but semantically unmapped expression, because it never emits expressions at all. It emits *intent*; we render *syntax*.

## 6.5 L0 — Grammar Validator

```java
public class LocatorGrammarValidator {

    private static final List<Pattern> BANNED = List.of(
            Pattern.compile(":nth-(child|of-type)"),
            Pattern.compile(">>"),
            Pattern.compile("\\.nth\\("),
            Pattern.compile("xpath=|//"),
            Pattern.compile("generated-id-\\d+"),
            Pattern.compile(":r[0-9a-z]+:"),          // React useId
            Pattern.compile("\\b(mui|ember|ng)-?\\d{2,}\\b")
    );

    public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan plan) {
        List<ValidationViolation> v = new ArrayList<>();
        Set<String> expectedRefs = plan.interactedRefIds();

        for (ResolvedLocator l : set.locators()) {
            if (!expectedRefs.contains(l.elementRefId()))
                v.add(blocking(l, "UNKNOWN_ELEMENT_REF", "not present in the recording"));

            if (l.value() == null || l.value().isBlank())
                v.add(blocking(l, "EMPTY_VALUE", null));

            if (l.strategy() == LocatorStrategy.ROLE_NAME && !AriaRoles.isValid(l.role()))
                v.add(blocking(l, "INVALID_ROLE", l.role()));

            String expr = LocatorRenderer.render(l);
            BANNED.stream().filter(p -> p.matcher(expr).find()).findFirst()
                  .ifPresent(p -> v.add(blocking(l, "BANNED_PATTERN", p.pattern())));

            if (l.strategy() == LocatorStrategy.TEXT)
                v.add(advisory(l, "LOW_CONFIDENCE_STRATEGY",
                        "TEXT locators break on copy changes and i18n"));
        }

        // Completeness: every interacted element must have exactly one locator
        Map<String, Long> perRef = set.locators().stream()
                .collect(groupingBy(ResolvedLocator::elementRefId, counting()));
        expectedRefs.forEach(ref -> {
            long n = perRef.getOrDefault(ref, 0L);
            if (n == 0) v.add(blockingRef(ref, "MISSING_LOCATOR", null));
            if (n > 1)  v.add(blockingRef(ref, "DUPLICATE_LOCATOR", "count=" + n));
        });

        return new ValidationResult(v);
    }
}
```

L0 costs microseconds, has no I/O, and catches the majority of malformed output before any DOM work.

## 6.6 L1 — Static DOM Validator

```java
public class StaticDomValidator {

    public ValidationResult validate(ResolvedLocatorSet set, ParsedTestPlan plan) {
        List<ValidationViolation> violations = new ArrayList<>();

        for (ResolvedLocator loc : set.locators()) {
            PageSnapshot snapshot = plan.snapshotFor(loc.elementRefId());

            if (!snapshot.hasHtml()) {
                violations.addAll(degradedCheck(loc, snapshot));   // element-list only
                continue;
            }

            Document doc = JsoupCache.parse(snapshot);             // parsed once per snapshot
            Elements matched = JsoupLocatorTranslator.query(doc, loc);

            if (matched.size() != 1) {
                violations.add(blocking(loc, "NOT_UNIQUE", matched.size(),
                        matched.isEmpty()
                            ? "no element matched — the value may be wrong or the element may be dynamic"
                            : "matched %d elements: %s".formatted(matched.size(), describe(matched, 3))));
                continue;
            }

            ElementFingerprint actual   = FingerprintExtractor.from(matched.first(), doc);
            ElementFingerprint expected = snapshot.fingerprintFor(loc.elementRefId());

            if (!actual.matchesIdentity(expected)) {
                violations.add(blocking(loc, "WRONG_ELEMENT", 1,
                        "expected %s but locator resolves to %s"
                                .formatted(expected.describe(), actual.describe())));
            } else if (!actual.domPathHash().equals(expected.domPathHash())) {
                violations.add(advisory(loc, "STRUCTURAL_DRIFT", 1,
                        "identity matches but DOM position differs — likely a benign refactor"));
            }
        }
        return new ValidationResult(violations);
    }
}
```

### `JsoupLocatorTranslator` — the exact mapping

```java
public final class JsoupLocatorTranslator {

    public static Elements query(Document doc, ResolvedLocator l) {
        return switch (l.strategy()) {
            case TEST_ID     -> doc.select("[data-testid=%s]".formatted(cssQuote(l.value())));
            case PLACEHOLDER -> doc.select("[placeholder=%s]".formatted(cssQuote(l.value())));
            case ROLE_NAME   -> byRoleAndName(doc, l.role(), l.value());
            case LABEL       -> byLabel(doc, l.value());
            case TEXT        -> byExactText(doc, l.value());
        };
        // No default. Adding a strategy without a mapping does not compile.
    }

    /** Mirrors Playwright's getByRole: implicit-role mapping + accessible-name computation. */
    private static Elements byRoleAndName(Document doc, String role, String name) {
        Elements out = new Elements();
        for (Element el : doc.getAllElements()) {
            if (!role.equalsIgnoreCase(AriaRoles.effectiveRole(el))) continue;
            if (!name.equals(AccessibleName.compute(el))) continue;   // exact=true
            out.add(el);
        }
        return out;
    }
}
```

`AriaRoles.effectiveRole` and `AccessibleName.compute` are the two places where semantic drift from the browser is possible. They are:
- **Deliberately conservative** — when the accessible-name computation is ambiguous (e.g. `aria-labelledby` pointing at a missing id), the element is treated as *not* matching, biasing toward `NOT_UNIQUE` (a false rejection, which costs a retry) rather than a false accept (which costs a bad test in `main`).
- **Unit-tested against a fixture corpus** of real DOM fragments with Playwright-verified expected results (§9.5). This is the single highest-value test suite in the project.

### Corrective feedback construction

```java
public String build(List<ValidationViolation> blocking, ResolvedLocatorSet current) {
    StringBuilder sb = new StringBuilder("""
        The following locators FAILED deterministic validation and MUST be revised.
        Propose new locators for ONLY these elements. Do not modify any other locator;
        changes to passing locators will be discarded.

        """);

    for (ValidationViolation v : blocking) {
        sb.append("- element %s (%s): %s".formatted(
                v.elementRefId(), v.locatorExpression(), v.reasonCode()));
        if (v.matchCount() >= 0) sb.append(" — matched %d elements; must match exactly 1".formatted(v.matchCount()));
        if (v.detail() != null)  sb.append("\n    ").append(v.detail());
        sb.append('\n');
    }

    sb.append("\nAlready-passing locators (frozen, for context only):\n");
    current.locators().stream()
           .filter(l -> blocking.stream().noneMatch(v -> v.elementRefId().equals(l.elementRefId())))
           .forEach(l -> sb.append("  %s -> %s\n".formatted(l.elementRefId(), l.expression())));

    return sb.toString();
}
```

The orchestrator then **enforces** the freeze rather than trusting it: any returned locator for a previously-passing `elementRefId` is discarded and the prior value retained. Prompt instructions are a hint; the merge is code.

## 6.7 Reason Code Reference

| Code | Tier | Severity | Meaning | Typical fix |
|---|---|---|---|---|
| `UNKNOWN_ELEMENT_REF` | L0 | Blocking | Locator references an element not in the recording | Model error; retry |
| `EMPTY_VALUE` | L0 | Blocking | Null/blank locator value | Model error; retry |
| `INVALID_ROLE` | L0 | Blocking | Not a valid ARIA role | Model error; retry |
| `BANNED_PATTERN` | L0 | Blocking | Grammar violation | Model error; retry |
| `MISSING_LOCATOR` | L0 | Blocking | An interacted element has no locator | Retry |
| `DUPLICATE_LOCATOR` | L0 | Blocking | Two locators for one element | Retry |
| `LOW_CONFIDENCE_STRATEGY` | L0 | Advisory | `TEXT` strategy used | Note in MR |
| `NOT_UNIQUE` | L1 | Blocking | Matched ≠ 1 element | Retry with a different strategy |
| `WRONG_ELEMENT` | L1 | Blocking | Unique, but not the recorded element | Retry |
| `STRUCTURAL_DRIFT` | L1 | Advisory | Identity matches, DOM position differs | Note in MR |
| `DEGRADED_NO_HTML` | L1 | Advisory | No page HTML; identity unverifiable | Label job `STATIC_ONLY_DEGRADED` |
| `LINT_FAILURE` | Lint | Blocking | Generated code fails Checkstyle/parse | One regeneration, then escalate |
| `VERIFY_FAILED` | L2 | Blocking | Generated test failed twice | Escalate — **never retried** |
| `VERIFY_SKIPPED` | L2 | Advisory | Fail-open with reason | Label `STATIC_ONLY` |

## 6.8 L2 — Verify Phase

```java
public class VerifyPhase {

    private final AuthSessionProvider sessions;   // READ-ONLY: never performs a login
    private final Duration timeout;

    public VerificationResult execute(TestGenerationJob job, List<GeneratedFile> files, Path workspace) {

        if (job.plan().mutatesData())
            return VerificationResult.skipped("MUTATING_TEST");

        Optional<Path> storageState = sessions.read(job.targetArea());
        if (storageState.isEmpty())
            return VerificationResult.skipped("NO_SESSION");

        try {
            ProcessResult first = runTest(job, workspace, storageState.get());
            if (first.exitCode() == 0)  return VerificationResult.passed(first.durationMs());
            if (first.timedOut())       return VerificationResult.skipped("TIMEOUT");
            if (first.isInfraFailure()) return VerificationResult.skipped("ENV_UNAVAILABLE");

            // One retry distinguishes genuine breakage from environmental flake.
            ProcessResult second = runTest(job, workspace, storageState.get());
            return second.exitCode() == 0
                    ? VerificationResult.passedAfterFlake(second.durationMs())
                    : VerificationResult.failed(first.tail(200));
        } finally {
            SecureDelete.of(storageState.get());   // token material never outlives the phase
        }
    }

    private ProcessResult runTest(TestGenerationJob job, Path ws, Path state) {
        return Processes.exec("mvn", "-q", "test",
                        "-Dtest=" + job.generatedTestClassName(),
                        "-DfailIfNoTests=true")
                .workingDir(ws)
                .env("PW_STORAGE_STATE", state.toString())
                .env("TEST_ENV", job.targetEnvironment())
                .timeout(timeout)
                .run();
    }
}
```

**Design decision: L2 failures do not re-enter the Resolve retry loop (FR-20).**

Rationale, stated explicitly because this will be questioned:

| Consideration | Effect of feeding L2 failures back |
|---|---|
| Error attribution | A test failure can be a bad locator, a bad assertion, bad page-object logic, or a genuinely broken app. The loop cannot tell which. |
| Scope | The retry loop's key property is monotonic narrowing. A stack trace regenerates *whole files* — the opposite. |
| Cost | Each L2 cycle is a full generation plus a 1–5 minute test run. Three cycles could exceed the entire job budget. |
| Signal | A human reading the stack trace resolves this in a minute. The pipeline has already done 95% of the work. |

Revisit only after collecting a failure taxonomy from ≥ 50 real L2 failures. If one category (say, missing `waitFor`) dominates, a *targeted* second loop for that category may be justified. A general one is not.

**Rollout:** L2 runs in **advisory mode** for the first four weeks — it executes, records to `verification_runs`, and annotates the MR, but never blocks. Flip to blocking only when `skip_rate < 20%` and `false_positive_rate < 5%` measured against human MR outcomes. Turning on a blocking gate you haven't measured is how you teach people to distrust the pipeline.

## 6.9 Redaction Engine

**The only component in the system that fails closed.**

### Rules

```java
public final class RedactionRules {

    /** ALLOW-LIST. A deny-list loses by default: one `data-customer-email` defeats it. */
    static final Set<String> ALLOWED_ATTRS = Set.of(
            "id", "class", "name", "type", "role",
            "data-testid", "data-test", "data-test-id",
            "alt", "title", "placeholder", "href", "for", "disabled", "checked", "selected");

    static final Predicate<String> ALLOWED_PREFIX = a -> a.startsWith("aria-");

    /** ALWAYS DROPPED, even if they would match above. */
    static final Set<String> NEVER = Set.of("value", "src", "srcset", "style", "content", "data-value");

    static final int MAX_TEXT_LEN = 120;
}
```

`value` is dropped unconditionally. It is exactly where a recording that accidentally captured a real form puts customer data, and it has zero value for locator resolution.

### Implementation

```java
public class DomRedactor {

    private final PiiScrubber pii;
    private final UrlNormalizer urls;

    public Redacted<PageSnapshot> redact(PageSnapshot raw) {
        RedactionReport.Builder report = RedactionReport.builder();
        try {
            String html = raw.hasHtml() ? redactHtml(raw.prunedHtml(), report) : null;

            List<DomElementSnapshot> els = raw.interactedElements().stream()
                    .map(e -> new DomElementSnapshot(
                            e.elementRefId(), e.tagName(),
                            filterAttrs(e.attributes(), report),
                            scrubText(e.textContent(), report),
                            e.role(),
                            scrubText(e.accessibleName(), report),
                            e.fingerprint()))              // fingerprint is structural — no PII
                    .toList();

            PageSnapshot safe = new PageSnapshot(
                    raw.stateId(), urls.normalize(raw.normalizedUrl(), report),
                    html, els, raw.capturedAt());

            report.payloadSha256(Hashing.sha256(Json.write(safe)));
            return new Redacted<>(safe, report.build());

        } catch (RuntimeException e) {
            // FAIL CLOSED. A validator that fails open costs a lower-confidence MR.
            // A redactor that fails open costs a data-exposure incident.
            throw new RedactionFailedException(
                    "Redaction failed; job aborted before any external call", e);
        }
    }

    private String redactHtml(String html, RedactionReport.Builder r) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, svg, iframe, template").remove();
        doc.filter(new NodeFilter() { /* strip comments */ });

        for (Element el : doc.getAllElements()) {
            List<String> toRemove = el.attributes().asList().stream()
                    .map(Attribute::getKey)
                    .filter(k -> !isAllowed(k))
                    .toList();
            toRemove.forEach(k -> { el.removeAttr(k); r.dropped(k); });

            if (el.hasAttr("href")) el.attr("href", urls.normalize(el.attr("href"), r));
        }
        doc.textNodes().forEach(tn -> tn.text(scrubText(tn.text(), r)));
        return doc.body().html();
    }

    private static boolean isAllowed(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return !RedactionRules.NEVER.contains(k)
                && (RedactionRules.ALLOWED_ATTRS.contains(k) || RedactionRules.ALLOWED_PREFIX.test(k));
    }
}
```

### PII scrubbing — typed tokens, not deletion

```java
public class PiiScrubber {
    private static final List<Rule> RULES = List.of(
        rule("EMAIL",   "[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}"),
        rule("PHONE",   "\\+?\\d[\\d\\s().-]{7,}\\d"),
        rule("JWT",     "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
        rule("BEARER",  "(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}"),
        rule("PAN",     "[A-Z]{5}\\d{4}[A-Z]"),          // India PAN
        rule("AADHAAR", "\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b"),
        rule("CARD",    "\\b(?:\\d[ -]*?){13,19}\\b", PiiScrubber::luhnValid)
    );

    public String scrub(String in, RedactionReport.Builder r) {
        if (in == null) return null;
        String out = in;
        for (Rule rule : RULES) out = rule.apply(out, r);   // -> "[REDACTED:EMAIL]"
        return out.length() > RedactionRules.MAX_TEXT_LEN
                ? out.substring(0, RedactionRules.MAX_TEXT_LEN) + "…" : out;
    }
}
```

Typed tokens preserve the *shape* of the content so the model still understands the element's purpose ("an input containing `[REDACTED:EMAIL]`" is more useful than an empty string) without transmitting the value.

### URL normalization

```
https://app.example.com/customers/98213/invoices/f7a1-4c9e-…?token=abc&tab=2#top
                    ↓
https://app.example.com/customers/{id}/invoices/{id}
```
Query and fragment removed entirely; numeric and UUID path segments replaced.

### Audit trail

`redaction_report` (JSONB on `jobs`):
```json
{
  "rulesApplied": {"EMAIL": 3, "PHONE": 1, "CARD": 0, "JWT": 0},
  "attributesDropped": {"value": 12, "src": 4, "data-user-id": 2, "style": 31},
  "urlsNormalized": 7,
  "textsTruncated": 5,
  "payloadSha256": "sha256:1c4f…",
  "rulesVersion": "redaction-v3"
}
```

This answers "what left our network on job X" without re-deriving anything — the question a compliance reviewer will actually ask.

## 6.10 LLM Gateway

### Interface (anti-corruption layer — NFR-25)

```java
public interface LlmClient {
    ResolvedLocatorSet resolveLocators(ResolveRequest request);
    GeneratedFileSet   generateCode(GenerateRequest request);
}
```

No LangChain4j type appears in `phases/` or `orchestrator/`. If the library must be replaced, one file changes.

### Implementation

```java
public class LangChain4jLlmClient implements LlmClient {

    // Pin a DATED snapshot. A floating alias is silent model drift (R-04).
    private final String modelId;              // e.g. "claude-sonnet-5" — verify exact id in vendor docs
    private final ChatModel model;
    private final TokenBudgetGuard budget;
    private final PromptBundle prompts;
    private final TransportRetryPolicy transport;

    public LangChain4jLlmClient(PipelineProperties props, TokenBudgetGuard budget, PromptBundle prompts) {
        this.modelId = props.llm().modelId();
        this.model = AnthropicChatModel.builder()
                .apiKey(props.llm().apiKey())
                .modelName(modelId)
                .cacheSystemMessages(true)   // cache_control on the last system block
                .cacheTools(false)           // we send ZERO tools — see note below
                .temperature(0.0)            // minimize variance; does not guarantee determinism
                .maxTokens(props.llm().maxOutputTokens())
                .maxRetries(0)               // transport retries owned by TransportRetryPolicy
                .timeout(props.llm().timeout())
                .build();
        this.budget = budget; this.prompts = prompts;
        this.transport = new TransportRetryPolicy(props.llm().transportRetries());
    }

    @Override
    public ResolvedLocatorSet resolveLocators(ResolveRequest req) {

        // ══ CACHE-FIRST LAYOUT — the highest-leverage decision in this class ══
        // langchain4j-anthropic places cache_control on the last SYSTEM message and
        // on tools. It does NOT give an ergonomic breakpoint on a user-message prefix.
        // Therefore everything stable for this job lives in the SYSTEM message.
        // Putting the DOM snapshot in the user message silently disables caching
        // and roughly doubles the cost of a multi-attempt job. (§2.4)
        SystemMessage system = SystemMessage.from(String.join("\n\n---\n\n",
                prompts.resolveSystem(),            // stable across ALL jobs
                prompts.codingConventions(),        // stable across ALL jobs
                req.existingCodeContextJson(),      // stable within THIS job
                req.safeSnapshotJson()));           // stable within THIS job  ← the big one

        // Volatile: the only thing that differs between retry attempts.
        UserMessage user = UserMessage.from(req.attempt() == 0
                ? prompts.resolveTask(req.planJson())
                : prompts.resolveTask(req.planJson()) + "\n\n" + req.correctionFeedback());

        budget.assertHeadroom(req.jobId(), estimate(system, user));   // refuse, don't overrun

        ChatResponse response = transport.execute(() -> model.chat(ChatRequest.builder()
                .messages(system, user)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(SchemaRegistry.RESOLVED_LOCATOR_SET)   // grammar-constrained sampling
                        .build())
                .build()));

        recordUsage(req, Phase.RESOLVE, response);
        return Json.parse(response.aiMessage().text(), ResolvedLocatorSet.class);
    }

    private void recordUsage(LlmRequest req, Phase phase, ChatResponse response) {
        AnthropicTokenUsage u = (AnthropicTokenUsage)
                ((AnthropicChatResponseMetadata) response.metadata()).tokenUsage();
        budget.record(req.jobId(), req.attemptId(), phase, modelId, new TokenUsage(
                u.inputTokenCount(), u.outputTokenCount(),
                u.cacheCreationInputTokens(), u.cacheReadInputTokens()));
    }
}
```

### Why the low-level `ChatModel` API rather than `AiServices`

| Need | `AiServices` | Low-level `ChatModel` |
|---|---|---|
| `AnthropicTokenUsage` on every call (FR-24) | Often abstracted away by the proxy | Direct access via response metadata |
| Explicit control of the cache boundary | Implicit, dictated by annotation layout | Explicit message construction |
| Boilerplate | Lower | ~15 lines higher |

Fifteen lines is a small price for the two things the entire cost-governance story depends on. If a pinned version demonstrably exposes usage cleanly through `AiServices`, switch — the `LlmClient` interface makes that a one-file change.

### Why native Structured Outputs rather than forced tool use

Neither Resolve nor Generate is really "calling a tool"; both produce structured data. `output_config.format` with grammar-constrained sampling is the direct fit. It also has a concrete secondary benefit: **we send zero tool definitions**, which sidesteps two known LangChain4j behaviours — `cacheTools` emitting one `cache_control` block per tool (blowing past Anthropic's 4-block limit) and tool-definition changes invalidating the system-message cache.

### Pre-commitment gates (run these in Week 1, before building on the library)

| Gate | Test | If it fails |
|---|---|---|
| G-1 | Native JSON-schema structured output round-trips correctly against a fixture schema (LangChain4j has had a since-fixed bug in exactly this Anthropic mapping — assume the class recurs) | Drop to raw HTTP for that call path |
| G-2 | `cacheSystemMessages(true)` produces non-zero `cacheCreationInputTokens` then `cacheReadInputTokens` on the pinned model | Implement cache_control manually or drop to raw HTTP |
| G-3 | `AnthropicTokenUsage` reachable on every response | Use low-level API (already the plan) |
| G-4 | Schema rejection behaviour on an intentionally over-complex schema is a clean error, not a hang | Simplify schema; add timeout |

Each gate is an integration test in `LlmClientContractIT`, run against the live API, tagged so it does not run in normal CI.

### Token budget guard

```java
public class TokenBudgetGuard {
    private final int    maxTokensPerJob;
    private final BigDecimal maxUsdPerJob;
    private final CostEventRepository repo;
    private final PriceBook prices;                 // per-model $/Mtok, config-driven

    public void assertHeadroom(UUID jobId, int estimatedTokens) {
        JobSpend spend = repo.spendFor(jobId);
        if (spend.totalTokens() + estimatedTokens > maxTokensPerJob)
            throw new BudgetExceededException("token", spend.totalTokens(), estimatedTokens, maxTokensPerJob);
        if (spend.usd().compareTo(maxUsdPerJob) >= 0)
            throw new BudgetExceededException("usd", spend.usd(), maxUsdPerJob);
    }

    public void record(UUID jobId, Long attemptId, Phase phase, String modelId, TokenUsage u) {
        repo.insert(new CostEvent(jobId, attemptId, phase, modelId, u, prices.cost(modelId, u)));
    }
}
```

Estimation is `chars/3.6` on the assembled messages — deliberately pessimistic, since the cost of over-estimating is a false refusal (visible, recoverable) and the cost of under-estimating is an overrun (invisible until the invoice).

## 6.11 Existing Code Reuse Analysis

```java
public class ExistingPageObjectAnalyzer {

    public ExistingCodeContext analyze(Path pageObjectDir, String treeSha) {
        return cache.get(cacheKey(treeSha), () -> doAnalyze(pageObjectDir));
    }

    private ExistingCodeContext doAnalyze(Path dir) {
        List<PageObjectDigest> digests = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith("Page.java")).forEach(path -> {
                CompilationUnit cu = StaticJavaParser.parse(path.toFile());
                String className = cu.getPrimaryTypeName().orElse(path.getFileName().toString());

                List<MethodDigest> methods = cu.findAll(MethodDeclaration.class).stream()
                        .filter(m -> m.isPublic())
                        .map(m -> new MethodDigest(
                                m.getNameAsString(),
                                m.getSignature().asString(),
                                m.getJavadocComment().map(c -> c.getContent().strip()).orElse(null),
                                m.getBody().map(b -> extractLocatorLiterals(b)).orElse(List.of())))
                        .toList();

                List<LocatorDigest> locators = cu.findAll(FieldDeclaration.class).stream()
                        .flatMap(f -> f.getVariables().stream())
                        .flatMap(v -> v.getInitializer().stream()
                                .map(init -> new LocatorDigest(v.getNameAsString(), init.toString())))
                        .toList();

                digests.add(new PageObjectDigest(className, relativePath(path), methods, locators));
            });
        } catch (IOException e) {
            throw new PipelineException("Failed to analyze Page Objects in " + dir, e);
        }
        return new ExistingCodeContext(digests);
    }
}
```

The digest — not raw source — goes into the Generate prompt as *"these methods and locators already exist; reuse or extend, do not duplicate."* Sending signatures and Javadoc rather than full bodies keeps the context an order of magnitude smaller while preserving everything the model needs to decide reuse.

### Caching — corrected from v1

The orchestrator is a **one-shot process (C-02)**. An in-process cache cannot survive it.

| Layer | Technology | Scope | Build when |
|---|---|---|---|
| Intra-job memoization | Caffeine | Within one JVM run (Generate + Lint reading the same ASTs) | Always — 3 lines |
| Cross-job cache | Postgres `code_context_cache`, key = `sha256(gitTreeSha + analyzerVersion)` | Across runs | **Only if the JavaParser pass is measured > 5s** |

Content-addressing on the git tree SHA makes invalidation automatic and correct: the key changes exactly when the code changes. `analyzerVersion` in the key means shipping a new analyzer invalidates cleanly. Redis is the wrong answer at every scale this system will reach.

## 6.12 Generate & Lint

```java
public class GeneratePhase {

    public List<GeneratedFile> execute(ParsedTestPlan plan, ResolvedLocatorSet locators,
                                       ExistingCodeContext context, String baseSha) {
        GeneratedFileSet raw = llm.generateCode(new GenerateRequest(plan, locators, context, baseSha));

        LintResult lint = lintPhase.check(raw);
        if (!lint.passed()) {
            // ONE regeneration with the lint output as feedback, then escalate.
            raw = llm.generateCode(new GenerateRequest(plan, locators, context, baseSha)
                                        .withLintFeedback(lint.report()));
            lint = lintPhase.check(raw);
            if (!lint.passed()) throw new EscalationRequired("LINT_FAILURE", lint.report());
        }
        return raw.files();
    }
}
```

`LintPhase` runs, in order:
1. **JavaParser parse** — catches truncated or malformed output immediately, no subprocess needed.
2. **Locator substitution check** — every `ResolvedLocator.expression()` appears verbatim in the generated source, and no locator expression appears that isn't in the validated set. *This closes the gap where a model produces validated locators and then writes different ones into the file.*
3. **Checkstyle** against the target repo's config.
4. **Import/package sanity** — generated files land in the expected package for `target_area`.

Step 2 is not obvious and is essential: without it, validation applies to a JSON payload rather than to the code that actually ships.

## 6.13 Orchestrator

```java
public class PipelineOrchestrator {

    public PipelineOutcome run(TestGenerationJob job) {
        try (var mdc = Mdc.forJob(job)) {

            ParsedTestPlan plan = parsePhase.execute(job);
            Redacted<ParsedTestPlan> safe = redactionPhase.execute(plan);      // FAIL CLOSED
            jobs.recordRedaction(job.id(), safe.report());

            ExistingCodeContext context = contextPhase.execute(job.targetArea());

            ResolvedLocatorSet accepted = ResolvedLocatorSet.empty();
            ValidationResult validation = null;
            String feedback = null;

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Long attemptId = attempts.begin(job.id(), attempt, Phase.RESOLVE);

                ResolvedLocatorSet proposed = llm.resolveLocators(
                        new ResolveRequest(job, safe.value(), context, feedback, attempt, attemptId));

                // Enforce the freeze in CODE — the prompt instruction is only a hint.
                ResolvedLocatorSet merged = accepted.mergeKeepingAccepted(proposed);

                validation = ValidationResult.merge(
                        grammarValidator.validate(merged, safe.value()),       // L0
                        staticValidator.validate(merged, safe.value()));       // L1

                validations.saveAll(attemptId, validation);
                attempts.finish(attemptId, validation.passed());

                accepted = merged.retainingPassing(validation);

                if (validation.passed()) break;
                feedback = feedbackBuilder.build(validation.blocking(), accepted);
            }

            if (validation == null || !validation.passed())
                return escalate(job, plan, accepted, validation, "VALIDATION_EXHAUSTED");

            String baseSha = gitLab.headSha("main");
            List<GeneratedFile> files;
            try (var lock = areaLock.acquire(job.targetArea())) {              // FR-26
                if (!baseSha.equals(gitLab.headSha("main"))) {                 // FR-27
                    context = contextPhase.execute(job.targetArea());          // re-analyze
                    baseSha = gitLab.headSha("main");
                }
                files = generatePhase.execute(plan, accepted, context, baseSha);

                VerificationResult verify = verifyPhase.execute(job, files, workspace);
                verifications.save(job.id(), verify);

                if (verify.isFailure() && verifyBlocking)
                    return escalate(job, plan, accepted, validation, "VERIFY_FAILED");

                ConfidenceTier tier = ConfidenceTier.from(verify, safe.value());
                return publish(job, files, tier, verify);                      // exactly-once (§7)
            }
        }
    }
}
```

Note the two things enforced in code rather than by prompt: the **locator freeze** (`mergeKeepingAccepted`) and the **HEAD SHA re-check**. Both are cases where trusting an instruction would produce a rare, hard-to-reproduce defect.

## 6.14 Database Schema

Flyway migration `V1__init.sql`:

```sql
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

-- ═════════════════════════════ ATTEMPTS ═══════════════════════════════
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

-- ═══════════════════════════════ COST ═════════════════════════════════
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

-- ══════════════════════════ VERIFICATION ══════════════════════════════
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

-- ═══════════════════════ EFFECT LEDGER (§7) ═══════════════════════════
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

-- ════════════════════════ EVAL HARNESS (§8) ═══════════════════════════
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
```

Eight tables. Nothing speculative — no `users`, no `projects`, no soft deletes. `cache_read_tokens` and `confidence_tier` exist specifically so the two claims this design rests on ("caching controls cost", "hybrid validation actually runs") are measurable rather than asserted.

## 6.15 GitLab Integration

```java
public class GitLabIntegrationService {

    private final GitLabApi api;
    private final String projectId;
    private final EffectExecutor effects;      // §7.5 — all writes go through here

    public MergeRequestResult publish(TestGenerationJob job, List<GeneratedFile> files,
                                      ConfidenceTier tier, VerificationResult verify) {

        String branch = BranchNamer.forJob(job);   // deterministic: testgen/<area>/<jobId>

        effects.exactlyOnce(job, EffectType.BRANCH, branch,
                () -> { api.getRepositoryApi().createBranch(projectId, branch, "main"); return branch; },
                () -> branchExists(branch) ? Optional.of(branch) : Optional.empty());

        String contentKey = Hashing.sha256(files);   // deterministic natural key for the commit
        effects.exactlyOnce(job, EffectType.COMMIT, contentKey,
                () -> commit(branch, job, files),
                () -> findCommitByTree(branch, contentKey));

        return effects.exactlyOnce(job, EffectType.MERGE_REQUEST, branch,
                () -> createMr(job, branch, tier, verify),
                () -> findOpenMrBySourceBranch(branch));
    }

    private MergeRequestResult createMr(TestGenerationJob job, String branch,
                                        ConfidenceTier tier, VerificationResult verify) {
        MergeRequestParams p = new MergeRequestParams()
                .withSourceBranch(branch)
                .withTargetBranch("main")
                .withTitle("[auto][%s] %s".formatted(tier, job.testDescription()))  // FR-22
                .withDescription(MergeRequestTemplate.render(job, tier, verify))
                .withRemoveSourceBranch(true)
                .withSquash(true)
                .withAssigneeId(job.requesterGitLabUserId())
                .withLabels(List.of("auto-generated", "testgen", tier.name().toLowerCase()));
        MergeRequest mr = api.getMergeRequestApi().createMergeRequest(projectId, p);
        return new MergeRequestResult(mr.getIid(), mr.getWebUrl());
    }
}
```

### MR description template

This is v1's "Report phase" — unchanged in content, moved from *synchronous approval before generation* to *the review artifact itself*.

```markdown
## Auto-generated test — `dashboard`
> Dashboard date filter persists across reload

**Confidence: VERIFIED** · 1 attempt · 4m12s · 32.4k tokens (18.1k cached) · $0.14

### Validation summary
| Element | Strategy | Locator | L0 | L1 | Notes |
|---|---|---|---|---|---|
| `e3` date filter trigger | TEST_ID | `getByTestId("date-filter-trigger")` | ✅ | ✅ unique, identity match | |
| `e7` apply button | ROLE_NAME | `getByRole(BUTTON, name="Apply filters")` | ✅ | ✅ unique, identity match | |
| `e9` results header | TEXT | `getByText("Showing 30 days")` | ⚠️ | ✅ unique, identity match | Low-confidence strategy — breaks on copy changes. Consider adding a test id. |

### Verification (L2)
✅ `DashboardDateFilterTest` passed against `staging` in 41s.

### Code reuse
Reused 2 existing methods: `DashboardPage.openDateFilter()`, `DashboardPage.applyFilter()`
New: `DashboardPage.assertDateRangeLabel(String)`

### Provenance
`model=claude-sonnet-5` · `prompts=v7 (a91f…)` · `orchestrator=1.4.2` · `base=main@7c2e91a`
<details><summary>Redaction report</summary>3 EMAIL, 1 PHONE, 12 `value` attrs dropped, 7 URLs normalized</details>

---
🤖 Generated by testgen. **A human approval is required — this MR will not auto-merge.**
```

### Escalation template (Draft MR)

```markdown
## ⚠️ NEEDS HUMAN REVIEW — validation could not be completed

**Confidence: STATIC_ONLY** · 3 attempts exhausted · reason: `VALIDATION_EXHAUSTED`

### ❌ Unresolved
**`e12` — row action menu**
| Attempt | Strategy | Value | Result |
|---|---|---|---|
| 0 | ROLE_NAME | button / "Actions" | NOT_UNIQUE — matched 12 |
| 1 | TEXT | "Actions" | NOT_UNIQUE — matched 12 |
| 2 | LABEL | "Row actions" | NOT_UNIQUE — matched 0 |

**Diagnosis:** every table row renders an identical action trigger with no
distinguishing attribute. No locator in the allowed grammar can disambiguate.

**Suggested fix:** add `data-testid="row-actions-{rowId}"` to the trigger, then re-record.

### ✅ Resolved (8 of 9) — committed for reference
… table as above …
```

The escalation MR is deliberately *actionable*: it names the root cause and the fix, rather than reporting "the AI failed."

---

# 7. Idempotency & Exactly-Once Subsystem (Gap A)

## 7.1 The Problem, Precisely Stated

The orchestrator is a batch process invoked by a CI system that retries. Retries happen for reasons entirely outside the pipeline's control:

| Trigger | Frequency |
|---|---|
| Jenkins agent lost mid-build (spot instance reclaimed, OOM, node drain) | Weekly at any real scale |
| Operator clicks **Rebuild** on a confusing failure | The single most common human response to a red build |
| Network partition between orchestrator and GitLab *after* the MR was created but *before* the response was read | Rare but inevitable |
| Duplicate Jenkins trigger from a double-click or a retried webhook | Occasional |

In v1's design each of these produces a second branch, a second commit, and **a second Merge Request** for the same recording. Beyond the obvious noise, this destroys trust faster than any technical defect: an engineer who receives two MRs for one recording concludes the pipeline is unreliable, and no amount of validation sophistication recovers that.

**Requirement:** the pipeline must be *safe to re-run at any point* (NFR-08), producing at most one of each external effect.

## 7.2 Layer 1 — Content-Derived Idempotency Key

Following the standard API idempotency-key pattern (Stripe, AWS, and every payment API), but with the key **derived from content rather than client-generated**, because the natural retry path here is a Jenkins rebuild that has no memory of a previously generated UUID.

```java
public final class IdempotencyKeyDeriver {

    private static final String KEY_VERSION = "v2";   // bump to force re-processing on semantic changes

    public String derive(Path recordingFolder, String targetArea, String testDescription) {
        // Canonicalization is essential: the same logical recording must hash identically
        // across OSes, JSON serializers, and git checkouts. Skip this and the key is useless.
        String canonicalElements = JsonCanonicalizer.canonicalize(   // RFC 8785 JCS
                Files.readString(recordingFolder.resolve("recorded-elements.json"), UTF_8));
        String canonicalScript = Files.readString(recordingFolder.resolve("recorded-test.js"), UTF_8)
                .replace("\r\n", "\n")              // normalize line endings
                .strip();

        return KEY_VERSION + ":" + Hashing.sha256Hex(String.join("\u0000",
                KEY_VERSION,
                canonicalElements,
                canonicalScript,
                targetArea.strip().toLowerCase(Locale.ROOT),
                testDescription.strip()));
    }
}
```

**Canonicalization rules (all of them matter):**

| Rule | Failure mode if skipped |
|---|---|
| RFC 8785 JSON Canonicalization (sorted keys, normalized numbers, no insignificant whitespace) | A pretty-printer or a `HashMap` iteration-order change produces a different key for identical content |
| Line-ending normalization | A Windows recorder and a Linux CI produce different keys for the same file |
| Trim + lowercase on `targetArea` | `"Dashboard "` and `"dashboard"` are treated as different jobs |
| Explicit `KEY_VERSION` prefix | No way to deliberately force reprocessing after a semantic change to the pipeline |
| `\u0000` field separator | Concatenation ambiguity: `("ab","c")` and `("a","bc")` would otherwise hash identically |

`capturedAt` is deliberately **excluded** — re-pushing the same recording should be recognised as the same job, and including a timestamp would defeat the entire mechanism.

**Propagation:** the CLI computes the key and passes it as an explicit Jenkins build parameter `IDEMPOTENCY_KEY`. A Jenkins *rebuild* reuses the same parameters by construction, so the retry path is covered for free. The orchestrator re-derives the key from the checked-out recording and **asserts it matches the parameter** — a mismatch means the scratch branch was mutated, which is a hard failure.

## 7.3 Layer 2 — Claim Protocol

```java
public class IdempotencyGate {

    public Claim claim(String idempotencyKey, JobSpec spec) {
        return db.inTransaction(h -> {
            Optional<JobRecord> existing = jobs.findByKey(h, idempotencyKey);

            if (existing.isEmpty()) {
                UUID id = UUID.randomUUID();
                jobs.insertClaimed(h, id, idempotencyKey, spec, leaseOwner(), /*epoch*/ 1L, leaseTtl());
                return Claim.proceed(id, 1L);
            }

            JobRecord job = existing.get();
            return switch (job.state()) {

                // ── Terminal & successful: this exact recording already produced an MR.
                case DONE, NEEDS_HUMAN_REVIEW ->
                        Claim.alreadyComplete(job.id(), job.mrUrl(), job.state());

                // ── Terminal & failed: a rebuild is a legitimate request to try again.
                case FAILED -> {
                    long epoch = jobs.reclaim(h, job.id(), leaseOwner(), leaseTtl());
                    yield Claim.proceed(job.id(), epoch);
                }

                // ── In flight.
                default -> {
                    if (job.leaseExpiresAt().isAfter(Instant.now()))
                        yield Claim.alreadyRunning(job.id(), job.leaseOwner(), job.leaseExpiresAt());
                    long epoch = jobs.reclaim(h, job.id(), leaseOwner(), leaseTtl());  // epoch++
                    log.warn("Reclaimed stale lease from {} (expired {}), epoch now {}",
                             job.leaseOwner(), job.leaseExpiresAt(), epoch);
                    yield Claim.resume(job.id(), epoch, job.state());
                }
            };
        });
    }
}
```

The `UNIQUE` constraint on `idempotency_key` is what makes the insert path race-free: two orchestrators starting simultaneously produce one winner and one `DuplicateKeyException`, which falls through to the `existing` branch on retry.

```sql
-- reclaim(): atomic epoch bump. The WHERE clause prevents two reclaimers both winning.
UPDATE jobs
   SET lease_owner      = :owner,
       lease_epoch      = lease_epoch + 1,
       lease_expires_at = now() + :ttl,
       updated_at       = now()
 WHERE id = :id
   AND (lease_expires_at IS NULL OR lease_expires_at < now() OR state = 'FAILED')
RETURNING lease_epoch;
```

**Claim outcomes and process behaviour:**

| Outcome | Exit code | Jenkins result | Message |
|---|---|---|---|
| `PROCEED` | — | continues | — |
| `RESUME` | — | continues from persisted state | logs the reclaim |
| `ALREADY_COMPLETE` | 20 | SUCCESS | "This recording was already processed. MR: <url>" |
| `ALREADY_RUNNING` | 20 | SUCCESS (no-op) | "Job <id> is in flight on <owner> until <time>." |

`ALREADY_COMPLETE` exits **0-equivalent (SUCCESS)** deliberately. A rebuild that finds the work already done is not an error; treating it as one trains operators to ignore red builds.

## 7.4 Layer 3 — Leases and Fencing Tokens

Lease expiry alone is not sufficient. Consider:

```
t=0    Process A claims job, epoch=1, lease expires t=30m
t=5m   A's host is network-partitioned (A is still running, unaware)
t=32m  B reclaims the stale lease, epoch=2, regenerates, opens MR
t=35m  Partition heals. A resumes exactly where it was and opens a SECOND MR.
```

A time-based lease cannot prevent this, because A has no way to know it lost the lease. The standard solution is a **fencing token**: a monotonically increasing number handed out with the lease, presented on every write, and checked by the resource being written.

```java
/**
 * All job mutations go through here. Every UPDATE carries the lease epoch.
 * A zombie process holding epoch=1 finds every write rejected once epoch=2 exists.
 */
public class FencedJobWriter {

    private final UUID  jobId;
    private final long  leaseEpoch;

    public void setState(PipelineState state) {
        int rows = db.update("""
            UPDATE jobs
               SET state = :state, updated_at = now(), lease_expires_at = now() + :ttl
             WHERE id = :id AND lease_epoch = :epoch
            """, state, jobId, leaseEpoch, ttl);

        if (rows == 0) throw new LeaseLostException(
                "Lease epoch %d is no longer current for job %s — another process has taken over. "
              + "Aborting to avoid duplicate effects.".formatted(leaseEpoch, jobId));
    }
}
```

`LeaseLostException` is fatal and uncatchable by phase code: the process logs, writes `job-summary.json`, and exits 41. It does **not** attempt cleanup, because cleanup would itself be a write it is no longer entitled to make.

**Heartbeat.** A background thread extends `lease_expires_at` every `ttl/3` while the job runs. This lets the TTL be short (30 minutes) without penalising legitimately slow jobs. The heartbeat write is also fenced — if it fails, the job knows it lost the lease within one heartbeat interval rather than at the next state transition.

```java
public class LeaseHeartbeat implements AutoCloseable {
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("lease-heartbeat").factory());

    public LeaseHeartbeat(FencedJobWriter writer, Duration ttl, Runnable onLost) {
        exec.scheduleAtFixedRate(() -> {
            try { writer.touch(); }
            catch (LeaseLostException e) { onLost.run(); }     // interrupt the main thread
        }, ttl.toMillis()/3, ttl.toMillis()/3, MILLISECONDS);
    }
}
```

## 7.5 Layer 4 — Effect Ledger (the part that actually prevents duplicate MRs)

Job-level idempotency is not enough on its own, because the external systems are not transactional with Postgres. There is no two-phase commit with GitLab. The industry-standard answer is an **intent log plus reconciliation**: record the *intent* to perform an effect before performing it, then confirm it afterwards; on retry, resolve any unconfirmed intent by querying the external system with a deterministic natural key.

```java
public class EffectExecutor {

    /**
     * Perform `action` at most once across all runs of this job.
     *
     *  1. Reserve  — INSERT (job, type, key) PENDING. Unique constraint = only one reserver.
     *  2. Perform  — the actual external call.
     *  3. Confirm  — UPDATE to CONFIRMED with the external id.
     *
     * A crash between 1 and 3 leaves a PENDING row. The next run does NOT blindly retry:
     * it calls `reconcile` to ask the external system whether the effect already landed.
     * This is why every effect needs a DETERMINISTIC natural key.
     */
    public <T> T exactlyOnce(TestGenerationJob job, EffectType type, String effectKey,
                             Supplier<T> action, Supplier<Optional<T>> reconcile) {

        Optional<EffectRecord> prior = effects.find(job.id(), type, effectKey);

        if (prior.isPresent() && prior.get().status() == CONFIRMED) {
            log.info("Effect {}/{} already confirmed as {} — skipping",
                     type, effectKey, prior.get().externalId());
            return rehydrate(prior.get());                      // no external call at all
        }

        if (prior.isPresent() && prior.get().status() == PENDING) {
            Optional<T> found = reconcile.get();                // did it actually land?
            if (found.isPresent()) {
                effects.confirm(job.id(), type, effectKey, idOf(found.get()));
                log.info("Reconciled orphaned PENDING effect {}/{}", type, effectKey);
                return found.get();
            }
            effects.incrementAttempts(job.id(), type, effectKey);
            if (prior.get().attempts() >= MAX_EFFECT_ATTEMPTS)
                throw new EffectExhaustedException(type, effectKey);
            // Fall through and perform: reconciliation proved it did not land.
        }

        if (prior.isEmpty()) effects.reserve(job.id(), type, effectKey, job.leaseEpoch());

        T result = action.get();
        effects.confirm(job.id(), type, effectKey, idOf(result));
        return result;
    }
}
```

### Deterministic natural keys — the enabling requirement

Reconciliation is only possible if each effect has a name that can be recomputed and looked up. This is why branch names are derived, not random:

| Effect | Natural key | Reconciliation query |
|---|---|---|
| `BRANCH` | `testgen/<area>/<jobId>` | `GET /projects/:id/repository/branches/:branch` → 404 means absent |
| `COMMIT` | `sha256(sorted file paths + contents)` | List commits on the branch; compare resulting tree hash |
| `MERGE_REQUEST` | source branch name | `GET /merge_requests?source_branch=X&state=all` → existing MR is the answer |
| `SLACK` | `<jobId>:<terminalState>` | Not queryable → at-most-once by ledger check alone; a lost notification is acceptable, a duplicate is merely annoying |

Because `jobId` is stable across reruns (it comes from the idempotency key lookup, not `UUID.randomUUID()` on each run), the branch name is stable, and therefore the MR lookup is stable. **This is the mechanism that makes duplicate MRs structurally impossible**, not merely unlikely.

### Effect state machine

```
              reserve()
     (none) ──────────────▶ PENDING ──── confirm() ────▶ CONFIRMED
                              │                              │
                              │ reconcile() finds it ────────┘
                              │
                              │ attempts >= MAX
                              ▼
                          ABANDONED  ──▶ surfaced by reconciler, requires human
```

## 7.6 Idempotency of Non-External Operations

| Operation | Repeat-safety mechanism |
|---|---|
| LLM calls | **Not** idempotent and deliberately so — a retry that returned identical output would be a wasted attempt. Bounded instead by `retry_attempts` (unique on `job_id, attempt_no, phase`) and `TokenBudgetGuard`. Every attempt is *accounted for* even though it is not *repeatable*. |
| Redaction | Pure function of input → deterministic |
| L0 / L1 validation | Pure functions → deterministic |
| L2 verification | Not deterministic (real environment). Each run appends a `verification_runs` row; the latest is authoritative. Append-only avoids the "which result is current" ambiguity. |
| Context assembly | Deterministic given a tree SHA |
| Postgres writes | Fenced by lease epoch; `UNIQUE` constraints on natural keys |

## 7.7 Reconciler Job

A scheduled sweep (`*/15 * * * *`) that repairs states no single job run can fix:

```java
public class ReconcilerJob {
    public ReconcileReport run() {
        // 1. Jobs whose lease expired but which never reached a terminal state.
        //    Do NOT resume them here — just mark them RECLAIMABLE and alert.
        //    Resuming from an unowned process is exactly what fencing exists to prevent.
        List<JobRecord> orphans = jobs.findExpiredNonTerminal(Duration.ofMinutes(45));
        orphans.forEach(j -> {
            jobs.markFailed(j.id(), "ORPHANED_LEASE");
            slack.alertOps("Job %s orphaned in state %s — rebuild %s to resume"
                           .formatted(j.id(), j.state(), j.jenkinsBuildUrl()));
        });

        // 2. PENDING effects older than the grace window: reconcile against GitLab.
        effects.findPendingOlderThan(Duration.ofMinutes(30)).forEach(e -> {
            Optional<String> external = gitLab.lookup(e.effectType(), e.effectKey());
            if (external.isPresent()) effects.confirm(e, external.get());
            else                      effects.abandon(e, "NOT_FOUND_AFTER_GRACE");
        });

        // 3. Consistency audit: jobs marked DONE with no CONFIRMED MERGE_REQUEST effect.
        //    This should be empty. If it isn't, there is a bug — page someone.
        List<UUID> inconsistent = jobs.doneWithoutConfirmedMr();
        if (!inconsistent.isEmpty())
            slack.alertOps("⛔ INVARIANT VIOLATION: %d jobs DONE without a confirmed MR: %s"
                           .formatted(inconsistent.size(), inconsistent));

        return new ReconcileReport(orphans.size(), /*…*/);
    }
}
```

Item 3 is the safety net that catches design errors rather than operational ones. It should always return zero rows; alerting on non-zero turns a silent correctness bug into a page.

## 7.8 Test Matrix

These are the tests that prove FR-04, FR-05, FR-21 and NFR-08. All run against Testcontainers Postgres with WireMock standing in for GitLab.

| ID | Scenario | Expected |
|---|---|---|
| IT-01 | Same recording pushed twice, first run completed | Second exits 20, prints original MR URL, **no second MR** |
| IT-02 | Two orchestrators claim the same key concurrently | One proceeds, one gets `ALREADY_RUNNING`; exactly one MR |
| IT-03 | Kill process after branch creation, before commit | Rerun reconciles branch as CONFIRMED, creates commit, one MR |
| IT-04 | Kill process after MR created, before confirm | Rerun finds MR by source branch, confirms ledger, **no second MR** |
| IT-05 | Zombie process writes after lease reclaimed | `LeaseLostException`, exit 41, no writes applied |
| IT-06 | Stale lease reclaimed after TTL | New process resumes from persisted state, epoch incremented |
| IT-07 | Byte-identical recording with different JSON key order | Same idempotency key (canonicalization works) |
| IT-08 | CRLF vs LF line endings in `recorded-test.js` | Same idempotency key |
| IT-09 | Recording differing by one attribute | Different key → new job |
| IT-10 | GitLab returns 500 after actually creating the MR | Retry reconciles, one MR |
| IT-11 | `FAILED` job rebuilt | Proceeds with epoch+1, reuses same job id |
| IT-12 | Reconciler on a `DONE` job with no MR effect | Ops alert fired |
| IT-13 | Effect exceeds `MAX_EFFECT_ATTEMPTS` | `ABANDONED`, job escalates, ops alerted |

IT-03 through IT-05 require deliberate mid-process kills. Implement with a test-only `FaultInjector` that throws at named checkpoints, driven by a system property. This is worth the small amount of production-code intrusion — these are precisely the paths that never get exercised otherwise, and precisely the ones that produce duplicate MRs in production.

---

# 8. Evaluation Harness & Quality Regression Detection (Gap B)

## 8.1 The Problem, Precisely Stated

This system has **no training loop** — nothing is fine-tuned, there are no model artifacts, no feedback dataset. Treating it as a training pipeline would be a category error. But the failure mode that drift management exists to prevent applies exactly:

> Someone changes a prompt, or the model is upgraded, or the vendor updates behaviour behind a floating alias — and output quality degrades. Nobody notices, because every individual MR still *looks* plausible. Three weeks later the escalation rate has doubled and nobody can say when or why.

Four distinct drift vectors, all real:

| Vector | Mechanism | Detected by |
|---|---|---|
| **Model drift** | Vendor updates behaviour; or you upgrade | Offline eval gate + canary (§8.6) |
| **Prompt regression** | Prompts are this system's "training config" | Offline eval gate on every prompt PR |
| **Target-app drift** | The app changed; corpus cases reference a dead DOM | Corpus staleness quarantine (§8.7) |
| **Codebase drift** | Existing-code context stale relative to `main` | Content-addressed cache key; not an eval concern |

**Design stance: the primary gate uses deterministic scoring functions, not LLM-as-judge.** A model judging a model shares failure modes with it, adds cost, adds variance, and is unfalsifiable. Every metric below is computed by code. LLM-as-judge may later be added as a *secondary, non-gating* signal for subjective qualities like naming quality — never as the gate.

## 8.2 The Golden Corpus

### Structure

```
evals/
├── suites.yaml
└── cases/
    └── dashboard-date-filter-001/
        ├── meta.yaml
        ├── recorded-test.js              # frozen input
        ├── recorded-elements.json        # frozen input (contract v2)
        ├── expected-locators.json        # human-approved reference output
        └── existing-code-context.json    # frozen snapshot — see note below
```

```yaml
# meta.yaml
caseId: dashboard-date-filter-001
targetArea: dashboard
description: "Dashboard date filter persists across reload"
sourcedFrom: "MR !1183, approved by @asha 2026-03-14"
capturedAt: "2026-03-14T10:22:00Z"
difficulty: medium          # easy | medium | hard  — for stratified reporting
tags: [modal, table, aria-heavy]
knownLimitations: "row action menu has no test id; TEXT strategy expected"
status: active              # active | quarantined | retired
```

```yaml
# suites.yaml
suites:
  smoke:    { cases: 10, selection: stratified, repeats: 1 }   # PR gate, ~4 min
  golden:   { cases: all, selection: all,        repeats: 3 }   # nightly, ~35 min
  hard:     { filter: "difficulty == hard",      repeats: 5 }   # model-upgrade gate
```

### The frozen-context decision

`existing-code-context.json` is **frozen per case**, not recomputed from current `main`. This is deliberate and important: if the context changed under the corpus, an eval regression could be caused by the test repo evolving rather than by the prompt or model change under test. Freezing the context isolates the variable being measured. The cost is that the corpus drifts from reality over time — which is what the quarterly refresh (§8.7) addresses.

### Curation policy

| Rule | Rationale |
|---|---|
| Minimum 30 cases to enable the gate; target 50 | Below ~30, the confidence interval on a proportion is too wide to detect a 5-point regression |
| Stratified across `difficulty` and `targetArea` | An all-easy corpus certifies nothing |
| Sourced from **merged** MRs with human-approved locators | The reference output must be something a human actually accepted |
| Include known-hard cases with documented limitations | The gate must not require perfection on cases where perfection is impossible |
| Include at least 3 cases that *should* escalate | Verifies the escalation path itself doesn't regress into false confidence |
| Never edit a case to make a new prompt pass | This is the cardinal sin of eval maintenance. If a case is genuinely wrong, retire it with a recorded reason. |

That last rule needs enforcement, not just documentation: corpus changes require a separate MR from prompt changes, and `evals/` has its own CODEOWNERS entry.

## 8.3 Metrics

All deterministic, all computed by `EvalScorer`.

### Primary (gating)

| Metric | Definition | Direction |
|---|---|---|
| `first_attempt_pass_rate` | Fraction of cases where L0+L1 pass with zero retries | ↑ |
| `locator_exact_match_rate` | Σ locators where `(strategy, value, role)` equals reference ÷ Σ reference locators | ↑ |
| `escalation_rate` | Fraction of cases exhausting retries | ↓ |

### Secondary (reported, alerting only)

| Metric | Definition |
|---|---|
| `locator_strategy_match_rate` | Matches on `strategy` alone — a lower bar that isolates "right approach, wrong value" |
| `mean_attempts` | Mean retry attempts across cases |
| `tokens_per_case` / `cost_per_case` | Mean, from `cost_events` |
| `cache_read_ratio` | `cache_read ÷ (cache_read + input)` — validates the §2.4 cost model |
| `p95_duration_ms` | Latency |
| `failure_code_histogram` | Distribution of blocking reason codes — *where* quality moved, not just *that* it moved |
| `per_case_variance` | Std. dev. of `first_attempt_pass` across `k` repeats |

`failure_code_histogram` is the most useful diagnostic in practice. A drop in pass rate accompanied by a spike in `NOT_UNIQUE` means the model got worse at disambiguation; a spike in `BANNED_PATTERN` means it stopped respecting the grammar. Different causes, different fixes.

### Scoring

```java
public class EvalScorer {

    public CaseScore score(EvalCase c, PipelineOutcome outcome) {
        Map<String, ResolvedLocator> expected = c.expectedLocators();   // by elementRefId
        Map<String, ResolvedLocator> actual   = outcome.finalLocators();

        int exact = 0, strategyOnly = 0;
        for (var e : expected.entrySet()) {
            ResolvedLocator a = actual.get(e.getKey());
            if (a == null) continue;
            if (isExact(a, e.getValue()))            exact++;
            if (a.strategy() == e.getValue().strategy()) strategyOnly++;
        }

        return new CaseScore(
                c.caseId(),
                outcome.attemptsUsed() == 1 && !outcome.escalated(),   // first_attempt_pass
                outcome.attemptsUsed(),
                outcome.escalated(),
                exact, strategyOnly, expected.size(),
                outcome.tokenUsage(), outcome.durationMs(),
                outcome.blockingReasonCodes());
    }

    /** Exact match compares SEMANTICS, not rendered strings — whitespace and
     *  formatting differences in the renderer must not register as regressions. */
    private boolean isExact(ResolvedLocator a, ResolvedLocator b) {
        return a.strategy() == b.strategy()
            && Objects.equals(norm(a.value()), norm(b.value()))
            && Objects.equals(lower(a.role()),  lower(b.role()));
    }
}
```

## 8.4 Statistical Gating — Why a Simple Threshold Fails

Naïve approach: "block if `first_attempt_pass_rate < 0.60`." This breaks immediately, because LLM output is stochastic. On 40 cases, a true rate of 0.65 produces observed values roughly in the 0.50–0.79 range across runs by chance alone. A fixed threshold therefore produces both false blocks (blocking a fine change) and false passes (shipping a real regression) at unacceptable rates — and false blocks are the more damaging of the two, because they train people to bypass the gate.

**The gate is a non-inferiority test against a recorded baseline**, which is the standard framing when you want to answer "is the new version *not meaningfully worse*" rather than "is it better."

```java
public class EvalGate {

    private static final double MARGIN = 0.05;   // 5 percentage points of tolerated regression
    private static final double ALPHA  = 0.05;

    public GateOutcome evaluate(EvalRun candidate, Optional<EvalRun> baseline) {
        if (baseline.isEmpty())
            return GateOutcome.noBaseline("No baseline for this suite — recording candidate as baseline.");

        List<String> failures = new ArrayList<>();

        // Bootstrap the difference in proportions over per-case results, resampling CASES
        // (not case-repeats) so the interval accounts for case-level heterogeneity.
        for (Metric m : List.of(FIRST_ATTEMPT_PASS_RATE, LOCATOR_EXACT_MATCH_RATE)) {
            Interval ci = Bootstrap.differenceCi(
                    candidate.perCase(m), baseline.get().perCase(m), 10_000, 1 - ALPHA);

            // Non-inferiority: the LOWER bound of (candidate − baseline) must exceed −MARGIN.
            if (ci.lower() < -MARGIN)
                failures.add("%s regressed: Δ=%.3f (95%% CI %.3f..%.3f), margin −%.2f"
                        .formatted(m, ci.point(), ci.lower(), ci.upper(), MARGIN));
        }

        // Escalation rate is inverted.
        Interval esc = Bootstrap.differenceCi(
                candidate.perCase(ESCALATION_RATE), baseline.get().perCase(ESCALATION_RATE), 10_000, 0.95);
        if (esc.upper() > MARGIN)
            failures.add("escalation_rate regressed: Δ=%.3f (upper %.3f)".formatted(esc.point(), esc.upper()));

        // Cost is a guardrail, not a quality metric: allow 20% drift before blocking.
        if (candidate.meanCostUsd() > baseline.get().meanCostUsd().multiply(new BigDecimal("1.20")))
            failures.add("cost/case up %.0f%% vs baseline".formatted(costDeltaPct(candidate, baseline.get())));

        // High-variance cases indicate an unstable corpus, not necessarily a bad change.
        List<String> unstable = candidate.casesWithVarianceAbove(0.25);
        if (unstable.size() > candidate.caseCount() * 0.1)
            failures.add("%d/%d cases unstable across repeats — corpus needs attention: %s"
                    .formatted(unstable.size(), candidate.caseCount(), unstable));

        return failures.isEmpty() ? GateOutcome.pass(candidate) : GateOutcome.fail(failures);
    }
}
```

**Why bootstrap rather than a closed-form test:** the per-case outcomes are not independent Bernoulli trials — hard cases fail together, and repeats within a case are correlated. Resampling *cases* with replacement respects that structure without requiring a distributional assumption. Ten thousand resamples on 50 cases is milliseconds.

**Why `k` repeats.** `temperature=0` reduces variance but does not guarantee determinism. Running each case `k` times and treating the mean as the case's score removes most single-run noise. `k=1` for the fast PR smoke suite (where the gate is advisory), `k=3` nightly, `k=5` for model upgrades.

**Margin selection.** 5 percentage points is a starting value. Calibrate it after four weeks: run the *unchanged* pipeline against itself nightly and observe the natural run-to-run spread. The margin should sit just above that noise floor. Setting it below the noise floor produces a gate that fails randomly — which is worse than no gate, because it gets disabled.

## 8.5 Runner & CI Integration

```java
public class EvalRunner {

    public EvalRun run(SuiteSpec suite, RunConfig config) {
        UUID runId = UUID.randomUUID();
        evalRepo.startRun(runId, suite, config);

        List<EvalCase> cases = corpus.load(suite);

        // Bounded parallelism: respects vendor rate limits without serializing a 40-min suite.
        try (var pool = Executors.newFixedThreadPool(config.concurrency())) {
            List<Future<CaseScore>> futures = new ArrayList<>();
            for (EvalCase c : cases)
                for (int r = 0; r < suite.repeats(); r++) {
                    final int repeat = r;
                    futures.add(pool.submit(() -> runOne(runId, c, repeat, config)));
                }
            futures.forEach(f -> evalRepo.saveCaseResult(runId, get(f)));
        }

        EvalMetrics metrics = EvalMetrics.aggregate(evalRepo.caseResults(runId));
        evalRepo.finishRun(runId, metrics);
        return evalRepo.load(runId);
    }

    /**
     * Runs Parse → Redact → Resolve → L0 → L1 with the retry loop, using the case's
     * FROZEN existing-code context. Generate, Lint, Verify, and all GitLab effects are
     * DISABLED — the harness measures locator resolution, and must never touch a repo.
     */
    private CaseScore runOne(UUID runId, EvalCase c, int repeat, RunConfig config) { … }
}
```

The harness sharing the production `PipelineOrchestrator` code path (with publishing disabled) is essential — an eval that exercises a parallel implementation measures the parallel implementation.

### Batch API consideration

The nightly `golden` suite makes ~150 calls (50 cases × 3 repeats) with no latency requirement. This is the one place in the system where Anthropic's Batch API is a good fit: substantially cheaper, and a multi-hour turnaround is irrelevant for a nightly job. **Not** applicable to the production pipeline, where the retry loop is inherently sequential and latency is a user-facing SLO. Implement synchronously first; move the nightly suite to batch if eval cost becomes noticeable.

### CI wiring

| Job | Trigger | Suite | Repeats | Gate |
|---|---|---|---|---|
| `testgen-eval-pr` | MR touching `prompts/**`, `SchemaRegistry`, `LlmClient*`, or `PipelineProperties` model config | `smoke` | 1 | **Advisory** — comments on the MR, does not block |
| `testgen-eval-nightly` | Cron 02:00 on `main` | `golden` | 3 | **Blocking** — failure alerts `#qa-automation` and blocks promotion of the prompt bundle |
| `testgen-eval-model-upgrade` | Manual, on model change | `golden` + `hard` | 5 | **Blocking** — required for the model-id change to merge |

The PR gate is advisory because a 10-case suite at `k=1` cannot support a confident block; its job is to surface an obvious catastrophe within four minutes. The nightly run on the full corpus is the real gate.

```groovy
// Jenkinsfile — testgen-eval-nightly
pipeline {
  agent { label 'testgen-worker' }
  triggers { cron('H 2 * * *') }
  stages {
    stage('Eval') {
      steps {
        withCredentials([string(credentialsId: 'anthropic-api-key', variable: 'ANTHROPIC_API_KEY')]) {
          sh '''java -jar target/testgen-orchestrator.jar eval \
                  --suite=golden --repeats=3 --concurrency=4 \
                  --gate=blocking --report=eval-report.json'''
        }
      }
    }
  }
  post {
    always  { archiveArtifacts 'eval-report.json' }
    failure { slackSend channel: '#qa-automation',
                        color: 'danger',
                        message: "🔴 Nightly eval GATE FAILED — quality regression detected. ${env.BUILD_URL}" }
  }
}
```

### Baseline management

- A baseline is an `eval_runs` row with `is_baseline = true`, keyed by `(suite, model_id, prompt_bundle_hash)`.
- **Promotion is explicit**, never automatic: a passing candidate becomes the baseline only via `eval --promote-baseline=<runId>`, run by a human after review.
- Automatic promotion would allow slow drift — each run passing non-inferiority against the last while the absolute level walks steadily downhill. Ten consecutive 4-point "non-regressions" is a 40-point collapse that never trips the gate.
- **Guard against exactly that:** the gate also compares against the *oldest baseline in the last 90 days*, with a wider margin (10 points). This catches ratchet drift that pairwise comparison cannot.

## 8.6 Model & Prompt Change Procedure

```
1. Open MR changing model_id or prompts/vN
2. testgen-eval-pr runs smoke suite  → advisory comment on the MR
3. Run testgen-eval-model-upgrade    → golden + hard, k=5, blocking gate
4. If gate PASSES:
     a. Merge with the model/prompt change behind config, defaulted OFF
     b. CANARY: enable for 10% of production jobs (hash of job id) for 3 days
     c. Compare canary vs control on production metrics:
        first_attempt_pass_rate, escalation_rate, MR-merged-without-edits
     d. If canary is non-inferior → flip default ON, promote the eval baseline
5. If gate FAILS: the failure_code_histogram delta tells you which capability moved.
```

Canary requires only a config flag and a deterministic hash bucket:

```java
boolean useCandidate = Math.floorMod(job.id().hashCode(), 100) < props.canaryPercent();
String modelId = useCandidate ? props.llm().candidateModelId() : props.llm().modelId();
```

The `model_id` and `prompt_bundle_hash` columns on `jobs` (FR-31) make canary comparison a `GROUP BY` — this is precisely why those columns exist.

## 8.7 Corpus Hygiene

The corpus is an asset that decays. Untended, it produces false alerts, which get ignored, which makes the gate worthless.

| Policy | Cadence | Mechanism |
|---|---|---|
| **Staleness quarantine** | Automatic | A case failing on ≥ 3 consecutive nightly runs *with an unchanged prompt bundle and model* is auto-quarantined (`status: quarantined`), excluded from the gate, and reported. The app has almost certainly changed. |
| **Refresh** | Quarterly | Replace quarantined cases with fresh recordings from recently merged MRs. Target a stable count of 50. |
| **Retirement** | As needed | A case whose feature was removed is retired with a recorded reason. Retired cases stay in git for provenance. |
| **Growth** | Continuous | Any production job that escalates *and is then fixed by a human in a way that reveals a systematic gap* becomes a corpus candidate. This is how the corpus stays representative of real difficulty rather than of whatever was easy to collect. |
| **Difficulty rebalance** | Quarterly | If pass rate exceeds 90%, the corpus has become too easy to detect regressions; add hard cases. |

## 8.8 Production Drift Monitor

Offline eval catches regressions from *changes you made*. The drift monitor catches regressions from changes you *didn't* make — vendor-side behaviour shifts, or the application under test evolving in ways that make locator resolution systematically harder.

```java
public class DriftMonitor {

    private static final double ALPHA = 0.2;         // EWMA smoothing
    private static final double ALERT_DELTA = 0.10;  // 10 percentage points

    public void check() {
        // Compare only within a fixed (model, prompt) configuration — otherwise
        // this would just re-detect deployments you already know about.
        for (Config cfg : jobs.activeConfigurations()) {
            double baseline = jobs.firstAttemptPassRate(cfg, Window.days(30).endingDaysAgo(7));
            double current  = jobs.ewmaFirstAttemptPassRate(cfg, Window.days(7), ALPHA);
            int    n        = jobs.countIn(cfg, Window.days(7));

            if (n < 20) continue;                     // insufficient signal; stay silent

            if (baseline - current > ALERT_DELTA) {
                Map<String,Double> codeDelta = jobs.failureCodeDelta(cfg, Window.days(7), Window.days(30));
                slack.alert("""
                    ⚠️ Production quality drift — %s / prompts %s
                    first_attempt_pass_rate: %.1f%% → %.1f%% (n=%d, 7d)
                    Shift by reason code: %s
                    No deployment in window. Suspect vendor-side change or app drift.
                    """.formatted(cfg.modelId(), cfg.promptHash().substring(0,8),
                                  baseline*100, current*100, n, top3(codeDelta)));
            }
        }
    }
}
```

Runs daily from the reconciler job. The `n < 20` guard prevents alert fatigue during low-volume periods — a monitor that cries wolf gets muted, and a muted monitor is worse than none.

## 8.9 What the Eval Harness Deliberately Does Not Do

| Not done | Why |
|---|---|
| LLM-as-judge as the gate | Shares failure modes with the system under test; unfalsifiable; adds variance and cost |
| Score generated code quality (readability, naming) | Not deterministically measurable. Checkstyle covers the mechanical part; humans cover the rest at MR review. |
| Run L2 verification in the harness | Requires a live environment matching each case's recorded state; would make the eval flaky and slow, contaminating the signal it exists to produce |
| Gate on absolute thresholds | Guarantees false blocks or false passes given stochastic output (§8.4) |
| Auto-promote baselines | Permits ratchet drift (§8.5) |
| Auto-edit failing cases | The cardinal sin of eval maintenance — the corpus would silently converge on whatever the current prompt happens to do |

---

# 9. Cross-Cutting Concerns

## 9.1 Configuration Reference

Resolved in order: CLI flags → environment variables → `application.yaml` → defaults. Secrets **only** from environment (injected by Jenkins `withCredentials`), never from files.

```yaml
testgen:
  ingestion:
    max-age: 24h
    required-contract-version: 2

  llm:
    provider: anthropic
    model-id: "claude-sonnet-5"          # PIN A DATED SNAPSHOT — floating aliases are silent drift
    candidate-model-id: ""               # canary target (§8.6); empty disables
    canary-percent: 0
    api-key: ${ANTHROPIC_API_KEY}
    max-output-tokens: 8192
    temperature: 0.0
    timeout: 120s
    transport-retries: 3
    transport-backoff-base: 2s
    cache-system-messages: true
    cache-tools: false

  budget:
    max-tokens-per-job: 250000
    max-usd-per-job: 2.50
    price-book:                          # $ per Mtok; update when vendor pricing changes
      claude-sonnet-5: { input: 3.00, output: 15.00, cache-write: 3.75, cache-read: 0.30 }

  retry:
    max-attempts: 3

  dom:
    max-snapshot-tokens: 25000
    max-nodes: 2000

  redaction:
    rules-version: "redaction-v3"
    fail-closed: true                    # DO NOT change; here to be auditable, not tunable

  validation:
    l2-enabled: true
    l2-blocking: false                   # advisory during rollout (§6.8)
    l2-timeout: 5m
    allow-text-strategy: true            # emits advisory violation when used

  gitlab:
    url: https://gitlab.example.com
    project-id: "1842"
    token: ${GITLAB_TOKEN}
    target-branch: main
    branch-prefix: "testgen"

  db:
    url: ${TESTGEN_DB_URL}
    user: ${TESTGEN_DB_USER}
    password: ${TESTGEN_DB_PASSWORD}
    pool-size: 4
    migrate-on-start: true

  idempotency:
    lease-ttl: 30m
    heartbeat-interval: 10m
    max-effect-attempts: 3
    reconcile-grace: 30m

  eval:
    corpus-dir: "evals/cases"
    default-repeats: 3
    concurrency: 4
    non-inferiority-margin: 0.05
    long-horizon-margin: 0.10
    bootstrap-resamples: 10000

  retention:
    debug-days: 30

  session:
    vault-path: "secret/testgen/sessions"
    max-age: 4h
```

## 9.2 Exit Code Contract

Jenkins maps exit codes to build results. This mapping is deliberate: **the only RED builds are ones a human must act on.**

| Code | Meaning | Jenkins result | Action |
|---|---|---|---|
| 0 | Success — MR opened | SUCCESS | None |
| 10 | Escalated — draft MR opened, needs human review | **UNSTABLE** | Engineer reviews the draft MR |
| 20 | No-op — already processed or already running | SUCCESS | None |
| 30 | Precondition failure — invalid contract, stale recording | FAILURE | Engineer re-records |
| 40 | Infrastructure failure — DB/GitLab/API unreachable | FAILURE | Ops; safe to rebuild |
| 41 | Lease lost — another process took over | SUCCESS (no-op) | None; the other process owns it |
| 50 | Budget exceeded | **UNSTABLE** | Engineer reviews; likely a pathological recording |
| 60 | **Redaction failure** | FAILURE | **Security review before rebuild** |
| 70 | Configuration error | FAILURE | Ops |

Code 10 as UNSTABLE rather than FAILURE is important: escalation is a designed outcome, and marking it red teaches people that red builds are normal.

## 9.3 Observability

### MDC fields (FR-33)

```java
public final class Mdc implements AutoCloseable {
    public static Mdc forJob(TestGenerationJob job) {
        MDC.put("jobId",            job.id().toString());
        MDC.put("idempotencyKey",   job.idempotencyKey());
        MDC.put("targetArea",       job.targetArea());
        MDC.put("requester",        job.requester());
        MDC.put("leaseEpoch",       String.valueOf(job.leaseEpoch()));
        MDC.put("promptBundleHash", job.promptBundleHash());
        MDC.put("modelId",          job.modelId());
        MDC.put("buildUrl",         System.getenv("BUILD_URL"));
        return new Mdc();
    }
    public static void phase(String phase, int attempt) {
        MDC.put("phase", phase); MDC.put("attempt", String.valueOf(attempt));
    }
}
```

JSON via `logstash-logback-encoder` to stdout; Jenkins captures it. No log shipper needed at this volume.

**Never logged (NFR-34, NFR-16):** prompt bodies, DOM content, API keys, GitLab tokens, `storageState` contents, MR descriptions. Log `promptBundleHash` and `payloadSha256` for correlation instead.

### `job-summary.json` (build artifact)

```json
{
  "jobId": "8f2a…", "idempotencyKey": "v2:a3f9…", "state": "DONE",
  "confidenceTier": "VERIFIED", "targetArea": "dashboard",
  "attempts": 1, "escalated": false,
  "locators": [{"elementRefId":"e3","strategy":"TEST_ID","value":"date-filter-trigger",
                "l0":"PASS","l1":"PASS"}],
  "verification": {"outcome":"PASSED","durationMs":41200},
  "cost": {"inputTokens":32400,"cacheReadTokens":18100,"outputTokens":3900,"usd":0.14},
  "provenance": {"modelId":"claude-sonnet-5","promptBundleHash":"a91f…",
                 "orchestratorVersion":"1.4.2","baseCommitSha":"7c2e91a"},
  "mrUrl": "https://gitlab…/merge_requests/2291",
  "durationMs": 252000
}
```

This is what gets pasted into a bug report. Designing it deliberately, rather than expecting people to read raw logs, is the difference between a supportable service and a demo.

### Grafana panels (SQL over Postgres — no metrics infrastructure)

```sql
-- North-star: MRs merged with zero human edits (joined against a GitLab sync table
-- or computed by a nightly job that queries the MR API for commit counts)
SELECT date_trunc('week', j.completed_at) AS wk,
       count(*) FILTER (WHERE m.human_commits = 0)::float / nullif(count(*),0) AS clean_merge_rate
FROM jobs j JOIN mr_outcomes m ON m.mr_iid = j.mr_iid
WHERE j.state = 'DONE' AND m.merged_at IS NOT NULL
GROUP BY 1 ORDER BY 1;

-- Time to MR
SELECT percentile_cont(0.5)  WITHIN GROUP (ORDER BY extract(epoch FROM completed_at-created_at)) AS p50,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at-created_at)) AS p95
FROM jobs WHERE completed_at > now() - interval '7 days';

-- Cache effectiveness — validates the §2.4 cost model
SELECT sum(cache_read_tokens)::float
       / nullif(sum(cache_read_tokens + input_tokens),0) AS cache_read_ratio
FROM cost_events WHERE created_at > now() - interval '7 days';

-- L2 fail-open rate (alert > 0.20)
SELECT count(*) FILTER (WHERE outcome='SKIPPED')::float / nullif(count(*),0)
FROM verification_runs WHERE created_at > now() - interval '7 days';

-- Where quality is moving: blocking reason codes over time
SELECT date_trunc('day', a.started_at) AS d, v.reason_code, count(*)
FROM validation_results v JOIN retry_attempts a ON a.id = v.attempt_id
WHERE v.severity='BLOCKING' AND a.started_at > now() - interval '30 days'
GROUP BY 1,2 ORDER BY 1,3 DESC;

-- Cost per merged test
SELECT sum(c.estimated_cost_usd) / nullif(count(DISTINCT j.id),0)
FROM jobs j JOIN cost_events c ON c.job_id = j.id
WHERE j.state='DONE' AND j.completed_at > now() - interval '30 days';
```

## 9.4 Security

| Concern | Control |
|---|---|
| Data egress to third-party LLM | Allow-list redaction, fail-closed, per-job audit report, compliance sign-off before first real recording |
| API keys | Jenkins credentials → env vars; never in files, logs, DB, or MR descriptions |
| GitLab bot token | Scoped to the single test project; `api` scope minimum needed for branch/commit/MR |
| Session tokens (`storageState`) | Minted by a separate scheduled job into Vault; orchestrator reads only; file mode `0600`; deleted in `finally`; `cleanWs()` in `post{always}` |
| Service account identity | Dedicated automation account per target area — never an engineer's personal login. Scoped to the test environment only. |
| Prompt injection via DOM content | The DOM is untrusted input. Mitigations: (a) structured outputs constrain the model to a schema it cannot escape into free text; (b) L0/L1 validate every field mechanically; (c) generated code is Checkstyle-linted and locator-substitution-checked (§6.12) before commit; (d) a human approves the MR. An injected instruction cannot produce a locator outside the grammar, and cannot produce code that ships without human approval. |
| Generated code executing in CI | L2 runs generated code. Constrain it: dedicated agent, test environment only, no production credentials on the agent, network egress restricted to the test env. |
| Database | Least-privilege application role: `SELECT/INSERT/UPDATE` on the application tables, no `DROP`; Flyway runs under a separate migration role |
| Audit | `jobs.redaction_report` retained 24 months; every effect recorded with timestamps |

## 9.5 Testing Strategy

| Layer | Tooling | Scope | Runs |
|---|---|---|---|
| Unit | JUnit 5, AssertJ | Pure logic: grammar validator, renderer, redaction rules, key derivation, scorer, bootstrap | Every commit |
| **DOM fixture corpus** | JUnit 5 + Jsoup | `AriaRoles.effectiveRole` and `AccessibleName.compute` against ~200 real DOM fragments with **Playwright-verified** expected results | Every commit |
| Integration | Testcontainers (Postgres) + WireMock (Anthropic, GitLab) | Retry loop, idempotency, effect ledger, lease/fencing, budget guard | Every commit |
| Contract | Live API, tagged, excluded from normal CI | LLM gateway gates G-1..G-4 (§6.10) | Manual + weekly |
| Eval | `EvalRunner` | Prompt/model quality | PR + nightly |
| E2E | Full pipeline against a sandbox GitLab project | Happy path, escalation, idempotent re-run | Pre-release |

### The DOM fixture corpus deserves emphasis

`AriaRoles.effectiveRole` and `AccessibleName.compute` are the only places where L1 can silently diverge from browser behaviour. Everything else in the validation ladder is either exact string comparison or delegated to a real browser. **This is the highest-value test suite in the project.** Build it by:

1. Collecting real DOM fragments from the app under test.
2. For each, running Playwright in a real browser to record `getByRole`/`getByLabel` match results.
3. Asserting the Jsoup translator produces identical results.
4. Adding a fixture every time a production `NOT_UNIQUE`/`WRONG_ELEMENT` turns out to be a translator bug.

### WireMock scenarios that must exist

| Scenario | Asserts |
|---|---|
| Attempt 0 non-unique → attempt 1 unique | Retry loop converges; feedback contains failed expressions only |
| Model changes a passing locator on retry | Change is **discarded**; prior value retained (`mergeKeepingAccepted`) |
| Model returns malformed JSON | Clean failure, no NPE, budget still recorded |
| Model returns a locator for an unknown `elementRefId` | `UNKNOWN_ELEMENT_REF`, retry |
| All attempts fail | Terminates in `NEEDS_HUMAN_REVIEW`, never `DONE`; draft MR opened |
| Budget exhausted mid-loop | Circuit-breaks **before** the next call; exit 50 |
| Anthropic returns 429 then 200 | Transport retry succeeds; only one `retry_attempts` row (transport ≠ semantic retry) |
| GitLab 500 after MR creation | Reconciles; exactly one MR |

## 9.6 SLIs and Starting SLOs

| SLI | Source | Starting SLO | Alert |
|---|---|---|---|
| MRs merged with zero human edits | GitLab + `mr_outcomes` | ≥ 70% | < 60% over 20 jobs |
| Time to MR, p95 | `jobs` | ≤ 8 min | > 12 min |
| L1 first-attempt pass rate | `retry_attempts` | ≥ 60% | 10% relative drop (drift monitor) |
| Mean attempts per successful job | `retry_attempts` | < 1.5 | > 2.0 |
| Escalation rate | `jobs.state` | < 15% | > 25% |
| L2 fail-open rate | `verification_runs` | < 20% | > 20% |
| Cost per merged test | `cost_events` | Baseline in Week 4 | 2× baseline |
| Cache read ratio | `cost_events` | > 40% | < 20% (caching broken) |
| Duplicate MRs | `external_effects` audit | **0** | Any occurrence — page |
| Nightly eval gate | `eval_runs` | PASS | Any FAIL |

## 9.7 Build & Dependencies

Java 21, Maven. **All versions pinned exactly (NFR-28)** — no ranges, no `LATEST`.

```xml
<properties>
  <java.version>21</java.version>
  <langchain4j.version>1.11.0</langchain4j.version>   <!-- verify + re-pin at implementation time -->
  <jsoup.version>1.18.3</jsoup.version>
  <javaparser.version>3.26.4</javaparser.version>
  <gitlab4j.version>6.0.0</gitlab4j.version>
  <jdbi.version>3.45.4</jdbi.version>
  <flyway.version>11.1.0</flyway.version>
  <postgresql.version>42.7.5</postgresql.version>
  <hikari.version>6.2.1</hikari.version>
  <jackson.version>2.18.2</jackson.version>
  <picocli.version>4.7.6</picocli.version>
  <logback.version>1.5.15</logback.version>
  <logstash.encoder.version>8.0</logstash.encoder.version>
  <caffeine.version>3.1.8</caffeine.version>
  <junit.version>5.11.4</junit.version>
  <testcontainers.version>1.20.4</testcontainers.version>
  <wiremock.version>3.10.0</wiremock.version>
  <assertj.version>3.27.0</assertj.version>
</properties>
```

Key choices:
- **JDBI 3, not JPA/Hibernate.** The data access is a handful of hand-written queries plus advisory locks and `RETURNING` clauses. An ORM adds indirection and fights the concurrency primitives this design depends on.
- **picocli** for both CLI and orchestrator subcommands (`run`, `eval`, `reconcile`, `migrate`).
- **Caffeine** for intra-job memoization only (§6.11).
- Verify every version against its registry at implementation time; the versions above are indicative.

## 9.8 Main Pipeline Jenkinsfile

```groovy
pipeline {
  agent { label 'testgen-worker' }
  options {
    timeout(time: 20, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: false)   // idempotency handles it, but reduces noise
    buildDiscarder(logRotator(numToKeepStr: '200'))
  }
  parameters {
    string(name: 'RECORDING_BRANCH',       description: 'Scratch branch holding the recording')
    string(name: 'TARGET_AREA',            description: 'dashboard | composer | statsimport')
    string(name: 'TEST_DESCRIPTION',       description: 'What the test verifies')
    string(name: 'REQUESTER_GITLAB_USER',  description: 'GitLab username to assign the MR to')
    string(name: 'IDEMPOTENCY_KEY',        description: 'Computed by the CLI — do not edit')
  }
  stages {
    stage('Checkout') {
      steps {
        git branch: 'main', url: env.ORCHESTRATOR_REPO_URL
        sh "git clone --depth 1 --branch ${params.RECORDING_BRANCH} ${env.TEST_REPO_URL} recording"
      }
    }
    stage('Run pipeline') {
      steps {
        withCredentials([
          string(credentialsId: 'anthropic-api-key', variable: 'ANTHROPIC_API_KEY'),
          string(credentialsId: 'gitlab-bot-token',  variable: 'GITLAB_TOKEN'),
          usernamePassword(credentialsId: 'testgen-db', usernameVariable: 'TESTGEN_DB_USER',
                                                        passwordVariable: 'TESTGEN_DB_PASSWORD')
        ]) {
          script {
            def rc = sh(returnStatus: true, script: """
              java -jar target/testgen-orchestrator.jar run \\
                --recording-dir=recording \\
                --recording-branch=${params.RECORDING_BRANCH} \\
                --target-area=${params.TARGET_AREA} \\
                --description='${params.TEST_DESCRIPTION}' \\
                --requester=${params.REQUESTER_GITLAB_USER} \\
                --idempotency-key=${params.IDEMPOTENCY_KEY}
            """)
            // Exit-code contract (§9.2)
            if (rc in [10, 50])       currentBuild.result = 'UNSTABLE'
            else if (rc in [0,20,41]) currentBuild.result = 'SUCCESS'
            else                      error("testgen exited ${rc}")
          }
        }
      }
    }
  }
  post {
    always  {
      archiveArtifacts artifacts: 'job-summary.json', allowEmptyArchive: true
      cleanWs()                                   // NFR-17: wipe session material
    }
    success   { slackSend channel: '#qa-automation', color: 'good',
                          message: "✅ Test-gen MR ready: ${readMrUrl()}" }
    unstable  { slackSend channel: '#qa-automation', color: 'warning',
                          message: "⚠️ Test-gen needs review: ${readMrUrl()}" }
    failure   { slackSend channel: '#qa-automation', color: 'danger',
                          message: "🔴 Test-gen failed (${params.TARGET_AREA}): ${env.BUILD_URL}" }
  }
}
```

## 9.9 Local Development

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:16.6                 # same major as production
    environment:
      POSTGRES_DB: testgen
      POSTGRES_USER: testgen
      POSTGRES_PASSWORD: testgen
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U testgen"]
      interval: 5s
  flyway:
    image: flyway/flyway:11.1.0
    command: -url=jdbc:postgresql://postgres:5432/testgen -user=testgen -password=testgen migrate
    volumes: ["./testgen-orchestrator/src/main/resources/db/migration:/flyway/sql"]
    depends_on: { postgres: { condition: service_healthy } }
volumes: { pgdata: }
```

```bash
make dev-up        # compose up + migrate
make test          # unit + integration (Testcontainers spins its own PG)
make eval-smoke    # 10-case suite against the live API
make run-local     # full pipeline against a fixture recording, GitLab effects mocked
```

---

# 10. Implementation Plan

| Week | Deliverable | Exit criteria | Notes |
|---|---|---|---|
| **1** | **Foundations.** Maven skeleton, Postgres + Flyway + docker-compose, `LlmClient` gates G-1..G-4, cache-first prompt layout, `cost_events` wired, MDC logging, Parse + Redact phases. | Gates pass; a fixture recording produces a redacted snapshot with an audit report; a live Resolve call records token usage including cache tokens. | **Redaction and persistence are Week 1, not hardening.** Redaction is a compliance gate — the first real recording cannot leave the network without it. Cost tracking is cheapest before there is anything to retrofit. |
| **2** | **Validation + retry loop.** Contract v2 in the recorder. L0 grammar validator, `LocatorRenderer`, `JsoupLocatorTranslator`, `FingerprintExtractor`, L1. DOM fixture corpus. WireMock harness. Retry loop with scoped feedback and code-enforced freeze. | Retry loop provably converges under WireMock. DOM fixture corpus ≥ 100 cases, all passing against Playwright-verified expectations. | The highest-risk week. If the translator can't match Playwright on real fragments, that is discovered here, cheaply. |
| **3** | **Idempotency + GitLab.** Key derivation, claim protocol, lease + fencing, effect ledger, reconciler. `GitLabIntegrationService` through `EffectExecutor`. MR templates. Still CLI-triggered. | Integration tests IT-01..IT-13 all green, including fault-injected mid-process kills. | Building idempotency *with* the GitLab integration rather than after it means the ledger shapes the integration, not the reverse. |
| **4** | **Eval harness + Jenkins.** Golden corpus ≥ 30 cases, `EvalRunner`, `EvalScorer`, bootstrap, `EvalGate`. Nightly + PR eval jobs. Main Jenkinsfile, ingestion CLI, Slack, Grafana dashboards. | Gate demonstrably blocks a deliberately degraded prompt (AC-04). End-to-end: `testgen push` → MR. | Eval before Generate is deliberate: it measures the Resolve phase, which is where quality actually lives, and it must exist before anyone starts tuning prompts. |
| **5** | **Generate + reuse + L2.** `ExistingPageObjectAnalyzer`, Generate phase, `LintPhase` (incl. locator-substitution check), `VerifyPhase` in **advisory mode**. Session-minting job + Vault. | Generated code compiles and passes Checkstyle; L2 records outcomes; reuse detection demonstrably avoids duplicating an existing method. | L2 advisory-only. Blocking comes after measurement. |
| **6** | **Hardening + pilot.** Budget alerting, drift monitor, `NEEDS_HUMAN_REVIEW` path fully wired, runbook, documentation. Pilot with 2–3 engineers, ≥ 20 real recordings. Flip L2 to blocking if skip rate < 20% and false-positive rate < 5%. | All acceptance criteria in §4.6 met. | Don't skip this because it isn't glamorous — it is what makes this a supportable service instead of a demo. |

**Sequencing rationale.** Weeks 1–3 cover the unfamiliar ground for an SDET moving into platform work: structured outputs, JSON schema design, Postgres concurrency primitives, GitLab's commit/MR model, distributed-systems idempotency. Weeks 4–6 lean on CI and test-infrastructure instincts already present. Front-loading unfamiliar work while scope is still small is the right order for building confidence — and the two items most likely to be deferred indefinitely if they aren't scheduled early (redaction and eval) are scheduled early on purpose.

---

# 11. Operations Runbook

| Symptom | Likely cause | Diagnosis | Action |
|---|---|---|---|
| Build exits 20 repeatedly | Recording already processed | `SELECT state, mr_url FROM jobs WHERE idempotency_key = '<key>'` | Working as designed. Point the engineer at the existing MR. To force reprocessing, bump `KEY_VERSION` or change the description. |
| Build exits 41 | Lease lost to another process | Check `lease_owner`, `lease_epoch` on the job | No action — the other process owns it. Confirm exactly one MR exists. |
| Escalation rate spiked | Prompt/model regression, or app change | Compare nightly `eval_runs.metrics`; check `failure_code_histogram` delta | If eval also regressed → roll back prompt/model. If eval is fine → app drift; check for a recent frontend deploy. |
| L2 skip rate > 20% | Session minting failing, or test env down | `SELECT skip_reason, count(*) FROM verification_runs WHERE created_at > now()-interval '7 days' GROUP BY 1` | `NO_SESSION` → check the minting job and Vault. `ENV_UNAVAILABLE` → escalate to the env owner. |
| Cache read ratio < 20% | Prompt layout regression | Inspect the assembled system message — did something volatile get appended to it? | Any per-job-varying content in the system message after the snapshot invalidates the cache. Move it to the user message. |
| Cost per job doubled | Retries up, or caching broken, or a huge DOM | `SELECT avg(attempts), avg(input_tokens) FROM …` grouped by week | Check retries first, then cache ratio, then `max-snapshot-tokens` breaches. |
| Duplicate MRs observed | **Invariant violation** | `SELECT job_id, count(*) FROM external_effects WHERE effect_type='MERGE_REQUEST' GROUP BY 1 HAVING count(*)>1` | Page. This should be impossible. Capture the ledger state before touching anything. |
| Reconciler reports `DONE` without confirmed MR | Bug in publish path | Inspect `external_effects` for that job | Page. Do not mass-fix; find the code path first. |
| Redaction failure (exit 60) | New attribute pattern, or malformed DOM | Check the exception and `redaction_report` | **Security review before rebuild.** Do not disable redaction to unblock. |
| Nightly eval gate failing but no change deployed | Corpus staleness or vendor-side drift | Check quarantine candidates; check drift monitor | If ≥ 3 cases fail consistently → quarantine and refresh. If broad → suspect vendor change; escalate. |
| Jobs stuck in a non-terminal state | Agent died, reconciler not running | `SELECT * FROM jobs WHERE lease_expires_at < now() AND state NOT IN ('DONE','FAILED','NEEDS_HUMAN_REVIEW')` | Verify reconciler cron; rebuild the Jenkins job to resume. |
| Anthropic 429s | Burst concurrency | Jenkins queue + transport retry logs | Lower `disableConcurrentBuilds` ceiling or Jenkins job concurrency. |

---

# 12. Appendices

## Appendix A — Architecture Decision Records

| ADR | Decision | Status | Rationale summary |
|---|---|---|---|
| ADR-001 | Java 21 orchestrator, not Python | Accepted | JavaParser gives a real AST of the Page Objects being modified; shared Playwright bindings; team fluency; JVM already on the agents |
| ADR-002 | One-shot CLI, not a service | Accepted | Simplest lifecycle; Jenkins already provides scheduling. Consequence: all state must be external (C-02) |
| ADR-003 | Hybrid validation, static-primary | Accepted | Static is cheap and always available; dynamic is authoritative but environment-dependent |
| ADR-004 | **Delete `ActionReplayer`; execute the generated test instead** | Accepted | A replay engine is a reimplementation of an owned test runner; running the real test checks strictly more |
| ADR-005 | **Constrained locator grammar** | Accepted | Makes the static validator provably equivalent to Playwright for the allowed subset; eliminates silent false passes |
| ADR-006 | **Fingerprint identity assertion** | Accepted | `count == 1` proves uniqueness, not correctness |
| ADR-007 | Native Structured Outputs over forced tool use | Accepted | Direct fit; sending zero tools avoids two known LangChain4j cache bugs |
| ADR-008 | Low-level `ChatModel` API, not `AiServices` | Accepted | Token usage and cache placement are load-bearing; a declarative proxy tends to hide both |
| ADR-009 | **Cache-first prompt layout** (stable content in system message) | Accepted | `langchain4j-anthropic` only places `cache_control` on system messages and tools; ~50% cost difference |
| ADR-010 | Postgres + JDBI, not JPA | Accepted | Advisory locks, `RETURNING`, and fenced updates are the primitives; an ORM fights them |
| ADR-011 | **Content-derived idempotency key + effect ledger** | Accepted | Jenkins rebuilds have no memory of a client-generated key; deterministic natural keys enable reconciliation |
| ADR-012 | **Fencing tokens on all job writes** | Accepted | Lease expiry alone cannot stop a partitioned zombie process |
| ADR-013 | **Deterministic scoring, not LLM-as-judge** | Accepted | A judge shares failure modes with the system under test and is unfalsifiable |
| ADR-014 | **Non-inferiority gate, not absolute threshold** | Accepted | Stochastic output makes fixed thresholds produce false blocks, which get the gate disabled |
| ADR-015 | Grafana over Postgres instead of a status API | Accepted | Same outcome, zero Java |
| ADR-016 | No message broker | Accepted | Volume does not justify it; revisit at 50 jobs/day (§5.6) |
| ADR-017 | Redaction fails closed; validators fail open | Accepted | Asymmetric consequences justify asymmetric policy |
| ADR-018 | No auto-merge, permanently | Accepted | Everything else reduces how often the human catches something; nothing removes the need for them to be there |
| ADR-019 | L2 failures do not re-enter the retry loop | Accepted | Ambiguous error attribution; unbounded scope; a human resolves it in a minute |
| ADR-020 | Caffeine intra-job only; cross-job cache deferred | Accepted | A one-shot process gives an in-process cache ~0% cross-job hit rate |

## Appendix B — Residual Risks Accepted

| Risk | Why accepted |
|---|---|
| A unique, identity-matched, L2-passing locator can still be semantically wrong (right element, test passes for the wrong reason) | Only human review catches this. It is why ADR-018 is permanent rather than provisional. |
| The recorder becomes load-bearing for validation soundness | Mitigated by contract versioning and explicit degraded-mode labelling, not eliminated |
| Golden corpus decays and produces false alerts | Mitigated by auto-quarantine and quarterly refresh; requires ongoing ownership |
| Postgres outage stops the pipeline entirely | Deliberate. Unavailable beats duplicate-MRs-with-untracked-spend. |
| Eval harness measures Resolve, not generated-code quality | Code quality is not deterministically scoreable; Checkstyle plus human review cover it |
| Prompt injection via DOM content | Layered mitigation (§9.4) reduces but does not eliminate; the human approver is the final control |

## Appendix C — Cursor Artifact Migration Map

| Cursor artifact | Becomes | Note |
|---|---|---|
| `/generate-test` phase list (Parse → Resolve → Report → Generate) | Four narrowly-scoped system prompts, one per phase | Smaller prompts are more reliable than one covering all phases |
| "Gate 2: every locator verified unique" | `LocatorGrammarValidator` + `StaticDomValidator` + `VerifyPhase` | **The single most important translation.** Stops being a request that the model behave; becomes code. |
| 4 × `.cursor/rules/*.mdc` convention files | `prompts/vN/conventions.md` appended to the Generate system prompt, **plus** `LintPhase` | Never trust prompted conventions for anything mechanically verifiable |
| Phase 3 "Report — wait for user review" | The MR description (§6.15) | Review still exists; it moved from synchronous-before-generation to the review artifact itself |
| Recording folder structure | Contract v2 (§6.3) | Extended with fingerprints and pruned HTML — the one change validation soundness depends on |
| Ad-hoc "does this look right" judgment | Golden corpus + eval gate (§8) | The judgment is now measured over time instead of exercised per-invocation |

## Appendix D — Summary of the Eight Decisions That Matter Most

1. **Constrain the locator grammar.** Every other validation guarantee rests on the validator and the runtime agreeing, and they only agree if the output space is small enough to map exactly.
2. **Assert identity, not just uniqueness.** `count == 1` is necessary and insufficient.
3. **Run the generated test; don't build a replay engine.** You already own a test runner.
4. **Put stable content in the system message.** Prompt layout is a cost decision, dictated by the framework's cache hooks.
5. **Derive the idempotency key from content, and reconcile effects against deterministic natural keys.** This is what makes duplicate MRs structurally impossible rather than merely unlikely.
6. **Fence every write with a lease epoch.** Time-based leases alone cannot stop a partitioned process.
7. **Gate quality changes on a non-inferiority test against a recorded baseline, and never auto-promote the baseline.** Absolute thresholds produce false blocks; auto-promotion permits ratchet drift.
8. **Never auto-merge.** Everything above reduces how often the human catches something. Nothing above removes the need for them to be there.

package com.company.testgen.idempotency;

import com.company.testgen.gitlab.MergeRequestResult;
import com.company.testgen.model.TestGenerationJob;
import com.company.testgen.persistence.EffectRecord;
import com.company.testgen.persistence.EffectRepository;
import com.company.testgen.persistence.EffectStatus;
import com.company.testgen.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Exactly-once side-effect executor backed by external_effects intent ledger (§7.5).
 * Enforces MAX_EFFECT_ATTEMPTS (3).
 *
 * 1. Reserve — INSERT (job, type, key) PENDING with leaseEpoch. Unique constraint guarantees single reserver.
 * 2. Perform — external operation.
 * 3. Confirm — UPDATE to CONFIRMED with external ID, fenced by leaseEpoch.
 *
 * If prior run left a PENDING row, reconciles against external system before retrying.
 * If prior run CONFIRMED, rehydrates the full typed result across restarts (§7.5).
 */
public class EffectExecutor {

    private static final Logger log = LoggerFactory.getLogger(EffectExecutor.class);
    public static final int MAX_EFFECT_ATTEMPTS = 3;

    private final EffectRepository effects;

    public EffectExecutor(EffectRepository effects) {
        this.effects = Objects.requireNonNull(effects, "effects cannot be null");
    }

    /**
     * Executes {@code action} with exactly-once guarantees across process restarts (§7.5).
     */
    public <T> T exactlyOnce(
            TestGenerationJob job,
            EffectType type,
            String effectKey,
            Supplier<T> action,
            Supplier<Optional<T>> reconcile
    ) {
        Objects.requireNonNull(job, "job cannot be null");
        return exactlyOnce(job.id(), job.leaseEpoch(), type, effectKey, action, reconcile);
    }

    /**
     * Executes {@code action} with exactly-once guarantees across process restarts (§7.5).
     */
    public <T> T exactlyOnce(
            UUID jobId,
            long leaseEpoch,
            EffectType type,
            String effectKey,
            Supplier<T> action,
            Supplier<Optional<T>> reconcile
    ) {
        Objects.requireNonNull(jobId, "jobId cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(effectKey, "effectKey cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(reconcile, "reconcile cannot be null");

        Optional<EffectRecord> prior = effects.find(jobId, type, effectKey);

        if (prior.isPresent() && prior.get().status() == EffectStatus.CONFIRMED) {
            log.info("Effect {}/{} for job {} already confirmed as {} — skipping execution",
                    type, effectKey, jobId, prior.get().externalId());
            return rehydrate(prior.get(), reconcile);
        }

        if (prior.isPresent() && prior.get().status() == EffectStatus.PENDING) {
            Optional<T> found = reconcile.get();
            if (found.isPresent()) {
                String externalId = idOf(found.get());
                effects.confirm(jobId, leaseEpoch, type, effectKey, externalId);
                log.info("Reconciled orphaned PENDING effect {}/{} for job {} (externalId={})",
                        type, effectKey, jobId, externalId);
                return found.get();
            }

            effects.incrementAttempts(jobId, leaseEpoch, type, effectKey);
            int newAttempts = prior.get().attempts() + 1;
            if (newAttempts >= MAX_EFFECT_ATTEMPTS) {
                effects.abandon(jobId, leaseEpoch, type, effectKey, "EXHAUSTED");
                log.error("Effect {}/{} for job {} exceeded MAX_EFFECT_ATTEMPTS ({})",
                        type, effectKey, jobId, MAX_EFFECT_ATTEMPTS);
                throw new EffectExhaustedException(type, effectKey);
            }
            // Fall through to perform action: reconciliation proved it has not landed externally
        }

        if (prior.isEmpty()) {
            effects.reserve(jobId, type, effectKey, leaseEpoch);
            log.debug("Reserved PENDING effect {}/{} for job {} (epoch {})", type, effectKey, jobId, leaseEpoch);
        }

        T result = action.get();

        // Fault injection checkpoint for IT-04 (§7.8)
        if (type == EffectType.MERGE_REQUEST) {
            FaultInjector.checkpoint("after-mr-created-before-confirm");
        }
        FaultInjector.checkpoint("after-action-before-confirm");

        String externalId = idOf(result);
        effects.confirm(jobId, leaseEpoch, type, effectKey, externalId);
        log.info("Confirmed effect {}/{} for job {} with externalId {}", type, effectKey, jobId, externalId);
        return result;
    }

    public EffectRepository effects() {
        return effects;
    }

    protected String idOf(Object result) {
        if (result == null) return null;
        if (result instanceof String s) return s;
        if (result instanceof MergeRequestResult mr) {
            return Json.stringify(mr);
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    protected <T> T rehydrate(EffectRecord record, Supplier<Optional<T>> reconcile) {
        if (reconcile != null) {
            try {
                Optional<T> found = reconcile.get();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (Exception ignored) {}
        }

        String extId = record.externalId();
        if (extId == null) {
            return null;
        }

        // Try JSON deserialization
        if (extId.startsWith("{") && extId.endsWith("}")) {
            try {
                MergeRequestResult mr = Json.parse(extId, MergeRequestResult.class);
                if (mr != null && mr.webUrl() != null) {
                    return (T) mr;
                }
            } catch (Exception ignored) {}
        }

        // Try Record.toString() format: MergeRequestResult[iid=101, webUrl=https://...]
        if (extId.startsWith("MergeRequestResult[") && extId.endsWith("]")) {
            try {
                String inner = extId.substring("MergeRequestResult[".length(), extId.length() - 1);
                String[] parts = inner.split(",\\s*");
                Integer iid = null;
                String webUrl = null;
                for (String part : parts) {
                    if (part.startsWith("iid=")) {
                        String v = part.substring(4);
                        if (!"null".equals(v)) iid = Integer.parseInt(v);
                    } else if (part.startsWith("webUrl=")) {
                        webUrl = part.substring(7);
                    }
                }
                if (webUrl != null) {
                    return (T) new MergeRequestResult(iid, webUrl);
                }
            } catch (Exception ignored) {}
        }

        return (T) extId;
    }
}

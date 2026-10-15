package com.company.testgen.idempotency;

import java.util.Optional;

/**
 * Strategy interface to look up whether an effect landed in GitLab (§7.7).
 */
@FunctionalInterface
public interface GitLabLookup {
    Optional<String> lookup(EffectType effectType, String effectKey);
}

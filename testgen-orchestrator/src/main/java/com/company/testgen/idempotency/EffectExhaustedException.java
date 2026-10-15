package com.company.testgen.idempotency;

/**
 * Thrown when an external side-effect exceeds MAX_EFFECT_ATTEMPTS (§7.5).
 */
public class EffectExhaustedException extends RuntimeException {

    private final EffectType effectType;
    private final String effectKey;

    public EffectExhaustedException(EffectType effectType, String effectKey) {
        super(String.format("Effect %s with key '%s' exceeded maximum attempts (MAX_EFFECT_ATTEMPTS)", effectType, effectKey));
        this.effectType = effectType;
        this.effectKey = effectKey;
    }

    public EffectType getEffectType() {
        return effectType;
    }

    public String getEffectKey() {
        return effectKey;
    }
}

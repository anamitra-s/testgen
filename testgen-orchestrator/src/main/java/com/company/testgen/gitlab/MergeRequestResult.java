package com.company.testgen.gitlab;

/**
 * Result of publishing or locating a GitLab Merge Request (§6.15).
 */
public record MergeRequestResult(
        Integer iid,
        String webUrl
) {}

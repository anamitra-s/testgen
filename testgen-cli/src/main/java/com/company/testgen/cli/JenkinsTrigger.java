package com.company.testgen.cli;

import java.util.Map;

/**
 * Strategy interface to trigger Jenkins parameterized pipeline builds (§3.2, §4.1).
 */
@FunctionalInterface
public interface JenkinsTrigger {
    String trigger(String jenkinsUrl, String jobName, String user, String token, Map<String, String> parameters) throws Exception;
}

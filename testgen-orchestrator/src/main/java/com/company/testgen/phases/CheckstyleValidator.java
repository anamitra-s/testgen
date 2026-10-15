package com.company.testgen.phases;

import com.company.testgen.model.GeneratedFile;

import java.util.List;

/**
 * Interface for Checkstyle validation against repository rules (§6.12).
 */
@FunctionalInterface
public interface CheckstyleValidator {
    List<String> validate(List<GeneratedFile> files);
}

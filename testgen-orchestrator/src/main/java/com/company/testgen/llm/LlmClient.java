package com.company.testgen.llm;

import com.company.testgen.model.GeneratedFileSet;
import com.company.testgen.model.ResolvedLocatorSet;

/**
 * Anti-corruption layer interface for LLM gateway (§6.10, NFR-25).
 * No LangChain4j types appear in this interface or outside the llm package.
 */
public interface LlmClient {

    ResolvedLocatorSet resolveLocators(ResolveRequest request);

    GeneratedFileSet generateCode(GenerateRequest request);
}

package com.codepilot.core.domain.context;

import com.codepilot.core.domain.DomainRuleViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ImpactSet(
        Set<String> impactedFiles,
        Set<String> impactedSymbols,
        List<List<String>> callChains
) {

    public ImpactSet {
        impactedFiles = impactedFiles == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(impactedFiles));
        impactedSymbols = impactedSymbols == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(impactedSymbols));
        callChains = normalizeChains(callChains);
    }

    public boolean isEmpty() {
        return impactedFiles.isEmpty() && impactedSymbols.isEmpty() && callChains.isEmpty();
    }

    private static List<List<String>> normalizeChains(List<List<String>> rawCallChains) {
        if (rawCallChains == null) {
            return List.of();
        }

        List<List<String>> normalized = new ArrayList<>();
        for (List<String> callChain : rawCallChains) {
            if (callChain == null || callChain.isEmpty()) {
                throw new DomainRuleViolationException("ImpactSet call chain must not be empty");
            }
            normalized.add(List.copyOf(callChain));
        }
        return Collections.unmodifiableList(normalized);
    }
}

package com.company.testgen.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parsed test plan domain model (§6.2, §6.5, §6.6).
 */
public record ParsedTestPlan(
        String targetArea,
        String testDescription,
        List<RecordedAction> actions,
        List<PageSnapshot> snapshots,
        boolean containsLoginFlow,
        boolean mutatesData
) {
    public ParsedTestPlan {
        if (actions == null) {
            actions = List.of();
        } else {
            actions = List.copyOf(actions);
        }
        if (snapshots == null) {
            snapshots = List.of();
        } else {
            snapshots = List.copyOf(snapshots);
        }
    }

    /**
     * Set of all unique element reference IDs interacted with across snapshots and actions.
     */
    public Set<String> interactedRefIds() {
        Set<String> refs = new LinkedHashSet<>();
        if (snapshots != null) {
            for (PageSnapshot snap : snapshots) {
                if (snap.interactedElements() != null) {
                    for (DomElementSnapshot elem : snap.interactedElements()) {
                        if (elem.elementRefId() != null && !elem.elementRefId().isBlank()) {
                            refs.add(elem.elementRefId());
                        }
                    }
                }
            }
        }
        if (actions != null) {
            for (RecordedAction action : actions) {
                if (action.elementRefId() != null && !action.elementRefId().isBlank()) {
                    refs.add(action.elementRefId());
                }
            }
        }
        return refs;
    }

    /**
     * Resolves the PageSnapshot corresponding to the given elementRefId.
     */
    public PageSnapshot snapshotFor(String elementRefId) {
        if (elementRefId == null || snapshots == null || snapshots.isEmpty()) {
            return null;
        }

        // 1. Direct match in snapshot interacted elements
        for (PageSnapshot snap : snapshots) {
            if (snap.interactedElements() != null) {
                boolean contains = snap.interactedElements().stream()
                        .anyMatch(el -> elementRefId.equals(el.elementRefId()));
                if (contains) {
                    return snap;
                }
            }
        }

        // 2. Correlation via recorded actions stateId
        if (actions != null) {
            for (RecordedAction action : actions) {
                if (elementRefId.equals(action.elementRefId()) && action.stateId() != null) {
                    for (PageSnapshot snap : snapshots) {
                        if (action.stateId().equals(snap.stateId())) {
                            return snap;
                        }
                    }
                }
            }
        }

        // 3. Fallback to single snapshot if only one exists
        if (snapshots.size() == 1) {
            return snapshots.get(0);
        }

        return null;
    }
}

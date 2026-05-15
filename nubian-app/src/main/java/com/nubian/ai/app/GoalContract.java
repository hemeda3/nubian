package com.nubian.ai.app;

import java.util.List;

/**
 * Structured parse of the user's free-form goal text. Produced once at run start
 * by {@link GoalContractExtractor} and threaded into the planner and verifier so
 * downstream components do not re-interpret the raw prose. Keeps quantifiers
 * (cardinality), spatial relations, and entities explicit so a phrase like
 * "any funny fact" doesn't get over-decomposed into a per-instance enumeration.
 *
 * <p>Empty {@link #items} means extraction failed (or returned nothing usable);
 * callers should fall back to the previous "derive checklist from raw goal text"
 * behavior in that case.
 */
public record GoalContract(
        List<ContractItem> items,
        String raw) {

    public boolean isEmpty() { return items == null || items.isEmpty(); }

    public static GoalContract empty(String raw) {
        return new GoalContract(List.of(), raw == null ? "" : raw);
    }

    public enum Cardinality {
        /** "any X", "a X", "one X", or no quantifier — one satisfying instance is enough. */
        ANY_ONE,
        /** "each X", "every X", "all X" — every instance must be satisfied. */
        ALL_INSTANCES,
        /** "N X" with an explicit numeric quantifier — exactly N satisfying instances. */
        EXACT_N
    }

    public record ContractItem(
            String id,
            String description,
            Cardinality cardinality,
            int exactN,
            SpatialRelation spatial) {

        public ContractItem {
            if (id == null || id.isBlank()) id = "1";
            if (description == null) description = "";
            if (cardinality == null) cardinality = Cardinality.ANY_ONE;
            if (exactN < 0) exactN = 0;
        }
    }

    /** Optional spatial / containment constraint. Null when the goal does not
     *  impose a geometric relation. */
    public record SpatialRelation(String relation, String targetRef) {
        public SpatialRelation {
            if (relation == null) relation = "";
            if (targetRef == null) targetRef = "";
        }
    }
}

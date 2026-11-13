# Synthetic proposal: candidate identity and prerequisites

This is a proposal for TON-1447, not a runtime schema or integration. The adjacent example is entirely synthetic; it contains neither course content nor real identifiers. A private, independently frozen author mapping holds the actual candidate-to-capability claims.

Each immutable candidate version has a stable `candidate_id`, an explicit version, a stable capability identifier and a relation (`direct`, `partial`, or `supporting`). The relation is an author claim, not proof of complete coverage, a student’s mastery, or release eligibility. Unknown star ratings are null. `key_state` and separate independent checks retain their actual status.

Keep both the prerequisite identifiers recorded in the original frozen candidate and the proposed resolved identifiers. An alias is scoped to proposed prerequisite resolution, and must identify its source and target versions. The example substitutes v2 for v1 in a dependent candidate’s proposed prerequisites. This does not overwrite the original bytes or historical attempts, migrate user records, or authorize use of the newer candidate. Downstream integration and review remain separate work.

A consumer should bind a mapping to its immutable source snapshots and verify their real hashes; the all-zero value in the example is only a placeholder. Reject duplicate IDs, missing targets, ambiguous aliases, alias cycles and prerequisite cycles. Preserve unresolved references as errors or explicit pending items rather than silently discarding edges. Independent reviewers determine whether the claimed capability and prerequisite relationships are valid. No production writes or state upgrades follow from this document.

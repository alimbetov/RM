# IE-00B/A2 technical review and contract baseline

Source baseline fa16b7e53ddfbd665184c3cd9a89ae6e0ddbf37c; design 852b993271617d30721d24b3d3b759d17038ca5f. Existing W2-W5 workflow 45588358130 completed successfully on baseline. Global qualification was still running at observation. A1 source oracle tests isolated tenant lifetime totals, not historical coverage or period boundaries. No claim of completed A1/A3.

## Findings
- A1 independent SQL assertions cover both QR producers, generic fulfillment, cancellation and return outcomes. Test values differ from D02 normative 5000 KZT fixture.
- TenantAuthorization.requireForCommand uses transaction-bound account/workspace locks; VerifiedActor rechecks expiry on userId(). Reuse these fences; never mint synthetic runtime actors.
- Existing AuthenticatedInventoryQueries only delegates tenant reads; new warehouse analytics must explicitly requireAssigned after tenant permission. Manager-wide scope deferred, no implicit owner bypass.
- DomainModule and DomainPermission are existing registries; proposed int_/evt_ registration and grants require dedicated compatibility review before DDL. No existing grants modified.
- Source inventory test copies current blobs into fixture; this tests integrity logic rather than proving source baseline. Run standalone verifier against checkout separately. Source manifest remains an admission snapshot, not a permanently immutable source tree.

## A2 baseline
The JSON is the implementation contract candidate for B1, selected engineering defaults under user's instruction to implement A2, not independent domain-owner approval. Six BASELINE_SELECTED decisions are conservative selections (01–05 and 15). Remaining proposed/open/deferred decisions retain explicit status. No migration or runtime Java before A3.

## Remaining admission blockers
Actual clean/populated PostgreSQL schema and complete producer inventory; coverage policy for legacy history; current-link and warehouse revoke/read tests for both commit orders; calendar persistence ownership and grants; bounded feed discovery/retention; explicit A3 record. Production HOLD. Radar deferred.

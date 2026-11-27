# RM-DEV-GOV-R1 — Development Governance, Architecture Reuse & Change Control

2026-11-27 | OWNER AGREED IN CONVERSATION | normative development procedure; not D08 global or production admission.
Applies to RM development across domains. Registered here in the existing design branch; no design merge into runtime. Existing MIC, domain contracts, security, DAG and exact-SHA gates remain normative.

## Mandatory development card before implementation
Write in plain Russian, with technical names as identifiers, not substitutes for explanation.
1. Business objective: what user or operational problem is solved; what is explicitly outside scope.
2. Current implementation: pinned base_dev/design SHAs, actual existing classes/methods/tables/read routes, tests and evidence. Inspect actual sources, not only previous summaries.
3. Verified gap: what cannot already be achieved; evidence of missing behavior; smallest alternative including no change.
4. Reuse: existing owner service, canonical facts, aggregation, permissions, lifecycle, revision or scheduling contracts; why each is sufficient or insufficient.
5. Concrete delta: file/class/method/schema/CI inventory, flow of data and transaction/lock order, resource impact, migration/compatibility risks.
6. Responsibility boundary: what this component owns and must not do; reject duplicate engines, ledgers, schedulers, calculation paths or unapproved infrastructure.
7. Acceptance: functional and mathematical oracle, security, negative/rollback/late-event tests, workload only if justified, required evidence and stop conditions.
8. Decision: owner-approved scope, explicitly open assumptions, proposed changes requiring approval, next step and reason.

## Admission and execution
- Architecture, new domain/engine, schema/dependency, authority boundary, cross-domain lifecycle or behavior changes outside agreed scope require explicit owner review before implementation; local fixes within approved scope may proceed with recorded rationale.
- Compare source and approved design first. No functional expansion under labels such as qualification, hardening or performance. No test weakening, hidden architecture change, synthetic READY, or metric alias without semantic proof.
- base_dev only for implementation; design/intelligent-commerce-r2 for design; main only after separate admitted merge. Preserve history; no force push. Applied migrations are append-only; any source baseline restoration is NOT a DB rollback and must not deploy to a DB with removed changesets without separate approved forward compatibility/repair.
- CI exact SHA, source audit, runtime tests, architectural acceptance and production admission are distinct. Record PASS/FAIL/NOT RUN accurately, no claims based on queued CI. Review necessity before performance tuning.
- After implementation: actual files and behavior, reuse proven, decisions/assumptions/deviations, exact SHA and verified results, unresolved risks, next small slice in plain language. Architectural acceptance must compare implemented diff to approved card, not test count alone.
- Stop on unexplained scope drift, failed mandatory gate, or missing source/permission evidence. Do not add more stages merely to optimize an unapproved design.
- Existing roadmap/DAG stays authoritative; correction COR-01 is a review gate, not a parallel roadmap or new product feature.

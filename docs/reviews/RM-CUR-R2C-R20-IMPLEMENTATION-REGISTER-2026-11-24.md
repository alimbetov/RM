# RM-CUR-R2C — R2C.0 Implementation Register (2026-11-24)

**Specification:** [R1.1](../spec/RM-CUR-R2C-SMART-FX-SHIELD-CONTROLLED-AUTO-PRICING-R1.md) at commit `4ce8a42add5ecd6cb8904504bfdbb3e8b58ff6e5`.

## Scope-change record
The project owner requested implementation on 2026-11-24 after publication of R1.1. This authorizes an **offline, non-publication foundation pass only** in `base_dev`; it does **not** attest completion of MVP G3/G4, R2C-G0, independent security review, live pilot or production gates. The specification's deployment and admission holds remain binding. Existing MANUAL and FX_SUGGESTED behavior must be unchanged.

## Implemented offline foundation
- `FxAutoTargetRef`: tenant-qualified typed target and owner validation; SERVICE remains unsupported for AUTO.
- `FxAutoRollingLimitEvaluator`: mathematical reference checking step caps, candidate price bounds, baseline/net rolling caps, absolute variation including MANUAL revisions and successful AUTO count. Requires externally verified immutable owner history; cannot itself validate source or security.
- `FxAutoRollingLimitEvaluatorTest`: eight JUnit scenarios committed; execution not yet evidenced.

No database changes, REST endpoints, feature flags, scheduler, new source adapters, authorizations, price owner port or public price mutation. No AUTO path is reachable from current Spring services.

## G0 outstanding admission prerequisites
1. Inventory canonical price history, PRODUCT/SERVICE owner CAS and composite tenant links. Document actual owner price version and timestamp provenance.
2. Produce explicit IAM/tenant/OWNER + STOREFRONT_OFFER_MANAGE authorization matrix and JWT/JWKS service tests for execution paths.
3. Verify PRICING_ANCHOR additive migration and R2A legacy SALES_INTERNAL compatibility.
4. Establish source correction, consent activation/stop, budget-guard and owner lock DAG using actual PostgreSQL tables; run both race orders.
5. Freeze rolling-window boundary selection from canonical history, shortened-history policy, multiple active policy-window obligations, DB-clock trust and stable tenant budget locks.
6. Produce 24-requirement × 80-acceptance-test traceability and evidence of executed checks before claiming any gate PASS.
7. Record approved commercial limits, incident delivery and source operational admission prior to pilot. DRY_RUN and AUTO remain blocked pending their own gates.

## Qualification status
- R2C.0 offline code: **COMMITTED / UNVERIFIED BUILD**.
- R2C-G0 contract review: **OPEN**.
- R2C-G1/G2/G3/G4/G5/G6/G7-PILOT/G7-RELEASE: **NOT PASSED**.
- Production: **HOLD**; main: **UNCHANGED**.

Future implementers must not treat this standalone evaluator as transaction-safe publication authorization. The final admission must re-read current canonical state under shared owner/budget/stop locks and record the immutable receipt in the same transaction as the owner price revision and budget consumption.

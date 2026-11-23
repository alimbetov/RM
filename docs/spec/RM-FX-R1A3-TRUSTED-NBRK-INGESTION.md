# RM FX-R1A.3 — Trusted NBRK Ingestion

**Status:** implementation contract / qualification pending (not evidence of working code)  
**Date:** 2026-11-23  
**Branch:** `base_dev` (single active development branch)  
**Architecture:** RM-PLATFORM-STANDARD-R2; preserves DEC-FUL-01.  
**Boundary:** service + persistence + tests only. No controllers, order fulfillment changes, or SKU repricing in this milestone.

## 1. Purpose and source
Ingest the National Bank of Kazakhstan's **official KZT quotation rates** through a strictly constrained HTTPS adapter. Official NBRK documented interfaces:
- `https://nationalbank.kz/rss/rates_all.xml` (current feed)
- `https://nationalbank.kz/rss/get_rates.cfm?fdate=dd.MM.yyyy` (date-specific service)
- Documentation: `https://nationalbank.kz/ru/page/rss`.

The source is authoritative **for NBRK published rates**, not for sell/buy spreads or merchant execution prices. No assumptions that all dates have new quotations or that future dates can be requested successfully.

## 2. Integration rules
1. Reuse existing FX-R1A source, publication, rate, observation, and revision model. **Before editing code inspect FX-R1A.1/R1A.2 implementations and migrations.** No parallel table or service names where existing contracts suffice.
2. `source_code=NBRK`, `base_currency=KZT`, and one quote per published foreign currency + nominal. Preserve the NBRK denomination, e.g., quote units are KZT per `nominal` units of foreign currency.
3. Keep MOCK independently selectable and non-production. No silent fallback to MOCK or kurs24 for a failed NBRK import.
4. If a matching observation is already stored for the same effective date/currency/source/nominal and normalized numeric rate, retry is a no-op. If a rate differs, append a new immutable revision and atomically advance the active selector. No destructive overwrite.
5. Imported rates never mutate an already accepted offer, order line price, payment, stock valuation, or immutable historical snapshot. DEC-FUL-01 remains authoritative; SKU currency strategies are a separate CUR milestone.

## 3. Trusted HTTP client
- Fixed scheme `https`, authority `nationalbank.kz`, port `443`, and two allowlisted absolute paths above. Construct URLs from constants and a strictly formatted `LocalDate`; do not accept complete URLs/hosts/paths from runtime requests, DB data, or tenant configs.
- No userinfo, fragments, wildcard hosts, custom Host header, HTTP downgrade, proxies configured from untrusted input, cookies, or automatic redirects. Disable redirects completely, including 30x to another URL.
- TLS certificate and hostname validation enabled through a maintained JVM trust store; no trust-all certs or hostname-verifier bypass. DNS resolution + TLS transport is not independently signed NBRK data. Record this provenance limitation.
- Bound connection/response deadlines, XML size (suggest 1 MiB configurable downward), and total decompressed bytes; bounded retries with jitter only on transient transport failures/429/5xx. Never retry invalid signatures (if later added), invalid XML, invalid field mapping, or unexpected status/redirect.
- SSRF controls: fixed authority plus egress restrictions/DNS policy at deployment. A host allowlist alone is not a protection against compromised DNS, proxies, or network paths.
- Request metadata for audit: source, exact endpoint identifier (without secrets), request timestamp UTC, HTTP status, TLS peer identity metadata if available, response digest, content length, parser version and import outcome.
- No arbitrary untrusted XML external resources. Disable DTD/DOCTYPE, external general/parameter entities, XInclude, external schema resolution. Reject oversized, malformed or ambiguous records; never log entire untrusted response.

## 4. Parser/admission
- Treat payload as untrusted until XML validation and domain checks pass. Require expected schema/root and the feed's publication/effective date rules. Use strict decimal parsing with explicit locale treatment (comma vs dot based on observed official feed format), `BigDecimal`, no binary floating point.
- ISO 4217 alphabetic code allowlist driven by the domain currency registry; unknown currencies go to explicit reject/quarantine depending on policy, never silently map to KZT.
- `nominal > 0`, `rate > 0`, bounded precision/scale as per existing DB schema; reject duplicate conflicting rows for the same currency/date/source/nominal in one payload.
- Check actual effective date from the feed against requested date. For historic date queries, a mismatch is an error unless an explicitly documented NBRK non-working-day rule has been implemented and tested; do not guess date semantics.
- The entire payload must pass admission before any new active rate becomes visible. All currencies in an accepted ingestion batch share a published source/effective date and batch identifier.

## 5. Persistence, revisions and locking
- `raw_payload_sha256` is SHA-256 of exactly the downloaded response bytes; `semantic_rate_hash` is computed from canonical field serialization (source/currency/effective date/nominal/normalized decimal value). These hashes serve different purposes.
- Preserve immutable received batch/observation/revision metadata. A source response can change ordering or whitespace without causing semantic rate revisions.
- Lock or apply database uniqueness and compare-and-swap on `(source, currency, effective_date)`; concurrent imports must not publish two active versions. Recommended: PostgreSQL unique constraints plus serialized upsert/select-for-update; verify against real PostgreSQL, not only H2.
- Batch admission and active-selector update are atomic. On any DB failure roll back the entire batch. Historical revision and provenance must remain queryable.
- Audit correction as `supersedes_revision_id` + import batch ID (do not update history). Keep selection deterministic and monotonic. Derive effective selector from revision chain if possible instead of redundant mutable flags.
- Define a separate operational policy for partial/unavailable source days, missing currencies, and rollback of a published correction; never delete old revisions.

## 6. Internal service contracts (illustrative, adapt to existing naming)
```java
record NbrkImportRequest(LocalDate date, String requestedBy) {}
record NbrkImportResult(UUID batchId, LocalDate effectiveDate,
                        int unchanged, int inserted, int corrected,
                        String payloadSha256) {}
interface NbrkRateImportService {
    NbrkImportResult importForDate(NbrkImportRequest request);
}
```
Only privileged system scheduler/operator service may invoke import; never a public tenant command. External network fetch occurs outside the DB transaction; validation completes before a short persistence transaction. Avoid performing remote I/O while holding tenant/global database locks.

## 7. Required acceptance tests
| ID | Case | Expected |
|---|---|---|
| FX-NB-01 | Valid signed-by-TLS transport feed fixture and multiple nominal values | Correct KZT per nominal parsing |
| FX-NB-02 | Repeat exact response | No new semantic revisions |
| FX-NB-03 | Same content reordered / whitespace modified | Raw SHA differs; semantic revisions unchanged |
| FX-NB-04 | Retroactive rate correction | Exactly one appended active revision, old revision queryable |
| FX-NB-05 | Two competing imports with same correction | One active revision, no duplicates (PostgreSQL) |
| FX-NB-06 | Bad TLS/hostname/redirect/HTTP downgrade | Fail closed |
| FX-NB-07 | Doctype/XXE/oversize/decompression bomb | Reject, no DB writes |
| FX-NB-08 | Date, currency, scale, nominal and duplicate conflicts | Reject, no partial publication |
| FX-NB-09 | Timeout/503 then recovery | Bounded retry and exactly-once semantic publication |
| FX-NB-10 | Crash between download, parse and persist | Recover with no partial batch |
| FX-NB-11 | DB failure mid-batch | Atomic rollback |
| FX-NB-12 | MOCK remains active while NBRK fails | No silent source switch |
| FX-NB-13 | Historical accepted SALES offer/order and DEC-FUL-01 flow | No repricing or fulfillment effects |
| FX-NB-14 | Malicious host/URL injected through configuration or request | Cannot reach arbitrary destination |
| FX-NB-15 | Source rates for weekend/holiday/query mismatch | Explicit policy, never invented date |

Testing must include local deterministic HTTP fixtures, TLS verification scenarios, parser mutation/property tests, and real PostgreSQL integration. Run mandatory H2 + PostgreSQL main CI suites and capture exact head SHA, migration tests, test counts, and artifacts. **No claim of PASS without CI evidence.**

## 8. Release gate and next steps
1. **Discovery:** compare this contract to actual FX-R1A schema/services/migrations and annotate reused extension points.
2. **Implementation:** trusted client, parser, import application service, revision persistence adapter, deterministic tests; no controllers.
3. **Qualification:** FX-NB-01..15 with real PostgreSQL, full exact-SHA CI, adversarial networking, migration rehearsal.
4. **Then CUR-R1 completion:** a currency registry and validated conversion semantics; only after that define SKU pricing modes (fixed currency, display conversion, merchant repricing) and explicit order-price snapshot freeze.
5. **HOLD:** no production scheduler, no actual order repricing, no public endpoint, no main merge without review and independent qualification.

## 9. Open policy decisions
- Which currency codes are admitted/required and which are optional?
- How should NBRK feed publication dates interact with weekends/public holidays?
- What is the approved correction/rollback process and operational notification threshold?
- Which retention period applies to raw response bytes (digest and parsed immutable revisions must remain)?
- Who owns the trusted scheduler identity and network egress restrictions?

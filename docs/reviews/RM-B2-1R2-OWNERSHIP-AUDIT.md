# B2.1R.2 RC-01 ownership audit and fixed-vector correction
Baseline dae766c2900a96811e27c16ff502c355ee5a4519.
The admitted manifest retains nine literal INSERT sites in eight owner classes; SALES preparation transitions remain explicitly excluded from canonical facts. Existing strict PostgreSQL and A1 runs on the earlier baseline 1f55253f were successful.
Strengthened alternate producer detection for quoted schema/table identifiers, escaped Java SQL quotes, adjacent literal concatenation, ORM mappings, MERGE/COPY and resource writes. Added four scanner regression tests, mandatory before the production source inventory scan in A1. This remains a conservative source audit, not a proof against arbitrary dynamically generated SQL or out-of-repository database writers.
Corrected the independently calculated inventory V2 vector: Java new UUID(0,10) renders its final nibble as hexadecimal a, not decimal 10. Recomputed with Python uuid.UUID(int=10) and hashlib. No production digest change in this commit.
No producer wiring or schema changes. Exact-SHA qualification still required. A3-QUAL/Production/B2.2A HOLD.

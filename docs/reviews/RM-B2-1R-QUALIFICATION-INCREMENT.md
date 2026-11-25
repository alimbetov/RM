# B2.1R qualification and producer inventory increment
Baseline bd3e6a7fce5459e6ac88c0ffb795ccc6dc9e99e4. Hotfix CI observed 18 successful, 3 running of 21 registered workflows; not final admission. No production producer wiring or schema change.
- .04: extend negative source UPDATE/DELETE evidence for booked lines, SALES history and inventory movement; late line INSERT/sealing and populated upgrade remain open. Do not assert full source integrity.
- .05: real PostgreSQL READ COMMITTED worker race, two independent connections synchronized inside transactions; 2/8/16 submitted SALES append calls in two-worker waves, plus 2 INVENTORY fulfillment calls. Exactly one inserted per source. Not 8/16 simultaneous DB connections. No competing rollback race yet.
- .06: actual checkout within explicit outer transaction, append PLACED source, rollback; assert order, lines, event, receipt and fact absent. Does not prove all producers until B2.2/B2.3.
- .07: second onboarded workspace, negative foreign tenant and wrong kind; no positive shared source UUID across two tenants.
- .08: literal SQL insert-site inventory, newly identified stocktake adjustment and excluded preparation state history; return restock is a delegate. Fail-closed scanner in A1 workflow. Not proof of generated/ORM SQL or database procedure coverage.
- Existing encoding tests explicitly included in A1 evidence. Strict service PostgreSQL workflow triggers on changes to helper/appender/changelog. CI of this new SHA pending; A3-QUAL/Production HOLD.

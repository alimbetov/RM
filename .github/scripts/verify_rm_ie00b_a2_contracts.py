#!/usr/bin/env python3
"""Validate A2 contract shape; not runtime admission."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = "docs/reviews/RM-IE-00B-R2-A2-CONTRACTS.json"
SALES = {"PLACED_ORDERS", "BOOKED_ORDER_AMOUNT", "CONFIRMED_ORDERS",
         "FULFILLED_ORDERS", "CANCELLED_ORDERS"}
STOCK = {"STOCK_IN_QTY", "STOCK_OUT_QTY", "CURRENT_AVAILABLE"}

def verify(m):
    errors = []
    def need(ok, message):
        if not ok:
            errors.append(message)
    need(m["admission"] == m["productionAdmission"] == "HOLD"
         and m["runtimeQualification"] == "NOT_EXECUTED", "A2 cannot grant runtime admission")
    rows = m["metrics"]
    need(len(rows) == 8 and {r["code"] for r in rows} == SALES | STOCK, "metric whitelist")
    for r in rows:
        sales = r["code"] in SALES
        live = r["code"] == "CURRENT_AVAILABLE"
        need(r["owner"] == ("SALES" if sales else "INVENTORY")
             and r["permission"] == ("SALES_ORDER_READ" if sales else "INVENTORY_STOCK_READ")
             and r["warehouseAssignment"] is (not sales), "metric scope: " + r["code"])
        need(r["timeBasis"] == ("STATUS_OCCURRED" if sales else "LIVE_POSITION" if live else "STOCK_RECORDED")
             and r["selection"] == ("ORDER_PERIOD" if sales else "LIVE_POSITION" if live else "MOVEMENT_PERIOD")
             and r["methodVersion"] == 1, "metric selection: " + r["code"])
        need(r["unit"] == ("CURRENCY_AMOUNT" if r["code"] == "BOOKED_ORDER_AMOUNT" else "COUNT" if sales else "SKU_QUANTITY"), "metric unit: " + r["code"])
    d = m["decisions"]
    need(len(d) == 15 and {r["id"] for r in d} == {f"DEC-R2-{i:02}" for i in range(1,16)}, "decision inventory")
    need(all(r["state"] in {"OWNER_APPROVED"} and r["contract"].strip() for r in d), "decision state")
    need(m.get("ownerApproval", {}).get("runtimeAdmission") == "HOLD", "approval is not runtime admission")
    c = m["calendar"]
    need(c["periods"] == ["D","W","DEC","M","Q","H","Y"] and c["zone"] == "EXPLICIT_IANA"
         and c["versioned"] is True and c["defaultZoneAllowed"] is False and c["interval"] == "HALF_OPEN", "calendar contract")
    q = m["quality"]
    need(set(q["values"]) == {"READY","PARTIAL","MISSING","STALE","NOT_MEASURABLE"}
         and q["unknownAsZero"] is False and q["completenessAndFreshnessIndependent"] is True, "quality contract")
    s = m["security"]
    need(s["identity"] == "VerifiedActor" and s["authorization"] == "TenantAuthorization.requireForCommand"
         and s["warehouse"] == "WarehouseStaffScopeService.requireAssigned"
         and s["recheckActorBeforeReturn"] is True and s["tenantWideWarehouseRead"] is False
         and s["customerLink"] == "CURRENT_CONFIRMED" and s["workerMayMintActor"] is False, "security fence")
    return errors

if __name__ == "__main__":
    errors = verify(json.loads((ROOT / PATH).read_text(encoding="utf-8")))
    print(json.dumps({"contractGate":"FAIL" if errors else "PASS","admission":"HOLD","errors":errors}))
    raise SystemExit(int(bool(errors)))

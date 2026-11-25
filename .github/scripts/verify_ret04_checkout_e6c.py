#!/usr/bin/env python3
"""Verify committed registration/QR/checkout lineage against every RET04 SQL snapshot."""
import argparse
from decimal import Decimal
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
import verify_ret04_evidence as d
import verify_ret04_identity_e3a as a
import verify_ret04_revocation_e3b as b

SCHEMA="RET04-E6C/v4"
SUITE=d.SUITE
GROUPS=(("sql",d.METHODS),("identity-e3",a.METHODS),("revocation-e3b",b.METHODS))
require=d.require

def canonical(value):
    try:
        return isinstance(value,str) and str(uuid.UUID(value))==value
    except (ValueError,TypeError,AttributeError):
        return False

def check_rows(rows,meta,returns,log):
    require(len(rows)==len(returns)>0,"checkout return count mismatch")
    require({x["return_id"] for x in rows}=={x["id"] for x in returns},"checkout return binding mismatch")
    # request_sha256 fingerprints intent, not the receipt primary key.
    # Two identical baskets with distinct idempotency keys are valid orders.
    # All rows belong to meta["tenant"]; the hashed key represents the
    # second component of PK (tenant_id,idempotency_key).
    require(len({x["order_id"] for x in rows})==len(rows),"duplicate checkout order")
    require(len({x["receipt_key_sha"] for x in rows})==len(rows),"duplicate checkout receipt key")
    require(len({x["line_id"] for x in rows})==len(rows) and
            len({x["handover_id"] for x in rows})==len(rows),"duplicate checkout line/handover")
    for x in rows:
        for field in ("return_id","order_id","identity_id","challenge_id","registration_event","line_id",
                      "offer_id","sku","storefront_id","qr_id","handover_id","warehouse_id"):
            require(canonical(x[field]),"invalid checkout identifier: "+field)
        require(all(x[k]==meta["buyer"] for k in ("return_buyer","buyer","identity_buyer","identity_owner","registration_buyer")),
                "checkout buyer mismatch")
        require(x["qr_issuer"]==x["handover_staff"]==meta["staff"],"checkout staff mismatch")
        require(x["identity_state"]=="CURRENT" and x["verified"] is True and
                x["challenge_purpose"]==x["registration_purpose"]=="REGISTRATION" and
                x["consumed"] is True and x["registered_before_order"] is True and
                x["delivery_rows"]==1 and x["checkout_challenges"]==0,"registration proof mismatch")
        require(x["storefront_state"]==x["offer_state"]==x["item_state"]=="PUBLISHED" and
                x["item_kind"]=="PRODUCT" and x["qr_state"]=="ACTIVE" and x["price_rows"]==1,
                "publication proof mismatch")
        require(x["quantity"]==5 and x["unit_price"]==x["offer_price"]==Decimal("100") and
                x["total_amount"]==Decimal("500") and x["currency"]==x["order_currency"]==x["offer_currency"]=="KZT" and
                x["fulfillment_mode"]=="PICKUP_PROOF_REQUIRED","checkout economics mismatch")
        require(re.fullmatch(r"[0-9a-f]{64}",x["receipt_sha"]),"invalid request fingerprint")
        require(re.fullmatch(r"[0-9a-f]{64}",x["receipt_key_sha"]),"invalid receipt key digest")
        registration=f"RET04E6A registration proof actor={meta['buyer']} identity={x['identity_id']} challenge={x['challenge_id']}"
        checkout=f"RET04E6B verified checkout tenant={meta['tenant']} buyer={meta['buyer']} order={x['order_id']} offer={x['offer_id']} sku={x['sku']}"
        require(registration in log.splitlines() and checkout in log.splitlines(),"missing service lineage marker")

def check_physical(rows,meta,tables,projections):
    # BSR §14.3 / SQL-BSR-07 and 09: validate the original source
    # and the terminal effect independently from the service result.
    reqs={x["id"]:x for x in projections["sales_return_request"]}
    resolutions={x["return_id"]:x for x in projections["sales_return_quarantine_resolution"]}
    movements={x["id"]:x for x in projections["inv_stock_movement"]}
    events=[x for x in projections["sales_return_event"] if x["from_state"]=="QUARANTINED"]
    require(len(reqs)==len(rows) and len(resolutions)==len(projections["sales_return_quarantine_resolution"]) and
            len(movements)==len(projections["inv_stock_movement"]),"duplicate physical projection")
    require(set(resolutions)<=set(reqs),"foreign resolution")
    require(len(events)==len(resolutions) and {x["return_id"] for x in events}==set(resolutions),
            "terminal event binding")
    require(all(tables[k][0]==len(rows) for k in ("sales_return_posting","inv_return_quarantine_movement",
                "sales_pickup_handover","sales_pickup_handover_line","sales_order","sales_order_line")) and
            tables["fin_invoice"][0]==0,"original ledger cardinality or invoice side effect")
    require(len({x["posting_id"] for x in rows})==len(rows) and len({x["intake_id"] for x in rows})==len(rows),
            "duplicate original posting/intake")
    for x in rows:
        r=reqs[x["return_id"]]
        require(r["order_id"]==x["order_id"] and r["source_handover_id"]==x["handover_id"] and
                r["buyer_user_id"]==x["buyer"],"physical source binding")
        require(x["order_status"]=="COMPLETED" and x["reservation_state"]=="FULFILLED" and
                x["quantity"]==x["fulfilled_quantity"]==x["reservation_quantity"]==5 and
                x["reservation_warehouse"]==x["warehouse_id"] and x["reservation_item"]==x["sku"],
                "original fulfillment invariant")
        q=Decimal(x["return_quantity"])
        require(q in (Decimal(2),Decimal(3)) and q==Decimal(x["posted_quantity"])==Decimal(x["intake_quantity"]) and
                q==Decimal(x["allocated_quantity"])<=Decimal(x["fulfilled_quantity"]),"return capacity/quantity invariant")
        require(canonical(x["posting_id"]) and canonical(x["intake_id"]) and
                x["disposition"]=="QUARANTINE" and x["posting_sellable_movement"] is None and
                x["posting_quarantine_movement"]==x["intake_id"] and x["intake_return"]==x["return_id"] and
                x["posting_actor"]==x["intake_actor"]==meta["staff"] and
                x["posting_warehouse"]==x["intake_warehouse"]==x["warehouse_id"] and
                x["posting_item"]==x["intake_item"]==x["sku"] and
                Decimal(x["intake_before"])>=0 and Decimal(x["intake_after"])-Decimal(x["intake_before"])==q and
                x["invoice_rows"]==0,"original quarantine posting invariant")
        resolved=resolutions.get(x["return_id"])
        if r["state"]=="QUARANTINED":
            require(r["row_version"]==6 and resolved is None,"premature resolution")
            continue
        require(r["state"] in ("RESTOCKED","DISPOSED") and r["row_version"]==7 and resolved is not None,
                "terminal resolution missing")
        action="RESTOCK" if r["state"]=="RESTOCKED" else "DISPOSE"
        require(resolved["action_code"]==action and resolved["warehouse_id"]==x["warehouse_id"] and
                resolved["item_id"]==x["sku"] and resolved["actor_user_id"]==meta["staff"] and
                Decimal(resolved["quantity"])==q and
                Decimal(resolved["before_quantity"])-Decimal(resolved["after_quantity"])==q and
                Decimal(resolved["after_quantity"])>=0,"resolution source/quantity invariant")
        event=next(e for e in events if e["return_id"]==x["return_id"])
        require(event["to_state"]==r["state"] and event["actor_user_id"]==meta["staff"] and
                event["expected_row_version"]==6,"terminal event invariant")
        if action=="DISPOSE":
            require(resolved["stock_movement_id"] is None,"dispose linked sellable movement")
        else:
            movement=movements.get(resolved["stock_movement_id"])
            require(movement is not None and movement["location_id"]==x["warehouse_id"] and
                    movement["item_id"]==x["sku"] and Decimal(movement["delta_quantity"])==q and
                    Decimal(movement["balance_after"])-Decimal(movement["balance_before"])==q,
                    "restock movement binding/quantity")

PHASES=("BEFORE","PENDING","WRONG_PURPOSE_DENIED","CURRENT","REPLAY_DENIED")
COUNTS=("identities","challenges","events","deliveries")
IDENTITY_FIELDS=("identity_id","identity_buyer","identity_state","pending_reason","pending_owner",
                 "current_owner","identity_created_at","verified_at")
CHALLENGE_FIELDS=("challenge_id","challenge_identity","challenge_purpose","challenge_created_at","expires_at","consumed_at")
EVENT_FIELDS=("registration_event","registration_buyer","event_identity","event_challenge","registration_purpose","event_at")
DELIVERY_FIELDS=("delivery_id","delivery_identity","delivery_challenge")
FIELDS=set(("buyer","account_status")+COUNTS+IDENTITY_FIELDS+CHALLENGE_FIELDS+EVENT_FIELDS+DELIVERY_FIELDS)

def stamp(value):
    require(isinstance(value,str),"missing registration timestamp")
    result=datetime.fromisoformat(value.replace("Z","+00:00"))
    require(result.tzinfo is not None,"registration timestamp lacks offset")
    return result

def check_registration(states,meta,lineage):
    require(tuple(states)==PHASES,"registration phase order mismatch")
    before,pending,wrong,current,replay=(states[k] for k in PHASES)
    buyer=meta["buyer"]
    for row in states.values():
        require(set(row)==FIELDS and row["buyer"]==buyer and row["account_status"]=="ACTIVE",
                "registration actor/state/fields mismatch")
        require(all(type(row[k]) is int for k in COUNTS),"invalid registration counts")
    require(all(before[k]==0 for k in COUNTS) and
            all(before[k] is None for k in IDENTITY_FIELDS+CHALLENGE_FIELDS+EVENT_FIELDS+DELIVERY_FIELDS),
            "registration preexisting identity or side effect")
    require([pending[k] for k in COUNTS]==[1,1,0,1] and
            pending["identity_state"]=="PENDING" and pending["pending_reason"]=="REGISTRATION" and
            pending["pending_owner"]==pending["identity_buyer"]==buyer and
            pending["current_owner"] is None and pending["verified_at"] is None and
            pending["challenge_purpose"]=="REGISTRATION" and pending["consumed_at"] is None and
            all(pending[k] is None for k in EVENT_FIELDS),"registration pending invariant")
    require(all(canonical(pending[k]) for k in ("identity_id","challenge_id","delivery_id")) and
            pending["challenge_identity"]==pending["delivery_identity"]==pending["identity_id"] and
            pending["delivery_challenge"]==pending["challenge_id"],"registration challenge/delivery binding")
    require(stamp(pending["identity_created_at"])<=stamp(pending["challenge_created_at"])<
            stamp(pending["expires_at"]),"registration challenge chronology")
    require(wrong==pending,"wrong purpose changed registration state")
    require([current[k] for k in COUNTS]==[1,1,1,1] and
            current["identity_state"]=="CURRENT" and current["pending_reason"] is None and
            current["pending_owner"] is None and current["current_owner"]==buyer and
            current["registration_buyer"]==buyer and current["registration_purpose"]=="REGISTRATION" and
            canonical(current["registration_event"]),"registration current invariant")
    unchanged=("identity_id","identity_buyer","identity_created_at","challenge_id","challenge_identity",
               "challenge_purpose","challenge_created_at","expires_at")+DELIVERY_FIELDS
    require(all(current[k]==pending[k] for k in unchanged),"registration replaced identity/challenge/outbox")
    require(current["event_identity"]==current["identity_id"] and
            current["event_challenge"]==current["challenge_id"],"registration event binding")
    require(stamp(current["challenge_created_at"])<=stamp(current["consumed_at"])<
            stamp(current["expires_at"]) and stamp(current["verified_at"])==stamp(current["consumed_at"])==
            stamp(current["event_at"]),"registration commit chronology")
    require(replay==current,"registration replay changed persisted proof")
    require(bool(lineage) and all(all(x[k]==current[k] for k in ("identity_id","challenge_id","registration_event"))
            and x["buyer"]==buyer for x in lineage),"registration checkout binding mismatch")

def load_registration(path,sha,method,meta,lineage):
    lines=path.read_text(encoding="utf-8").splitlines()
    require(lines[:4]==["sha\t"+sha,"method\t"+method,"tenant\t"+meta["tenant"],"buyer\t"+meta["buyer"]],
            "registration snapshot provenance mismatch")
    phases={}
    for line in lines[4:]:
        phase,raw=line.split("\t",1)
        require(phase not in phases,"duplicate registration phase")
        phases[phase]=json.loads(raw)
    check_registration(phases,meta,lineage)
    return {"tenant":meta["tenant"],"buyer":meta["buyer"],"phases":list(PHASES),
            "sha256":hashlib.sha256(path.read_bytes()).hexdigest()}

def load(path,sha,method,tenant):
    lines=path.read_text(encoding="utf-8").splitlines()
    require(lines[:3]==["sha\t"+sha,"method\t"+method,"tenant\t"+tenant],"checkout snapshot provenance mismatch")
    require(all(l.startswith("row\t") for l in lines[3:]),"unexpected checkout snapshot row")
    return [json.loads(l[4:],parse_float=Decimal) for l in lines[3:]]

def verify(xml,evidence,sha):
    require((evidence/"exact-sha.txt").read_text().strip()==sha,"checkout evidence SHA mismatch")
    raw=xml.read_bytes()
    root=ET.fromstring(raw)
    require(root.tag=="testsuite" and root.get("name")==SUITE and root.get("tests")=="81" and
            all(root.get(k)=="0" for k in ("failures","errors","skipped")),"checkout suite not clean")
    cases={x.get("name"):x for x in root.findall("testcase")}
    require(len(cases)==81,"checkout suite count mismatch")
    expected={m for _,ms in GROUPS for m in ms}
    base=evidence/"checkout-e6c"
    require(base.is_dir() and {p.name for p in base.iterdir()}==expected,"checkout method set mismatch")
    registrations=evidence/"registration-e6c"
    require(registrations.is_dir() and {p.name for p in registrations.iterdir()}==expected,
            "registration method set mismatch")
    result=[]
    for folder,methods in GROUPS:
        for method in methods:
            case=cases.get(method)
            require(case is not None and case.get("classname")==SUITE and
                    not any(x.tag in ("failure","error","skipped","flakyFailure","flakyError","rerunFailure","rerunError") for x in case),
                    "checkout testcase missing/nonclean")
            log=case.findtext("system-out","")
            snapshots=sorted((evidence/folder/method).glob("snapshot-*.tsv"))
            proofs=base/method
            require(bool(snapshots) and {p.name for p in proofs.iterdir()}=={p.name for p in snapshots},
                    "checkout snapshot set mismatch")
            by_tenant={}
            registration_meta={}
            hashes=[]
            for snap in snapshots:
                meta,tables,projections=d.snapshot(snap,sha,method)
                path=proofs/snap.name
                rows=load(path,sha,method,meta["tenant"])
                try:
                    check_rows(rows,meta,projections["sales_return_request"],log)
                    check_physical(rows,meta,tables,projections)
                except (ValueError,KeyError,TypeError) as exc:
                    raise ValueError(f"{method}/{snap.name}: {exc}") from exc
                # Exact stable lineage across resolution, denied attempts, crash and replay.
                key=meta["tenant"]
                if key in by_tenant:
                    require(rows==by_tenant[key],"checkout lineage changed")
                else:
                    by_tenant[key]=rows
                    registration_meta[key]=meta
                hashes.append({"snapshot":snap.name,"tenant":key,
                               "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                               "returnIds":sorted(x["return_id"] for x in rows)})
            registration_dir=registrations/method
            require({p.name for p in registration_dir.iterdir()}=={k+".tsv" for k in by_tenant},
                    "registration tenant set mismatch: "+method)
            transitions=[]
            for tenant in sorted(by_tenant):
                path=registration_dir/(tenant+".tsv")
                try:
                    transitions.append(load_registration(path,sha,method,registration_meta[tenant],by_tenant[tenant]))
                except (ValueError,KeyError,TypeError) as exc:
                    raise ValueError(f"{method}/{tenant} registration: {exc}") from exc
            result.append({"method":method,"tenants":len(by_tenant),"snapshots":hashes,
                           "registrationTransitions":transitions})
    require(len(result)==56,"checkout coverage mismatch")
    return {"schema":SCHEMA,"sha":sha,"status":"PASS","cases":56,
            "surefireSha256":hashlib.sha256(raw).hexdigest(),"evidence":result,
            "mappingVerdict":"SUPPORTING_PARTIAL","productionAdmission":"HOLD"}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--xml",type=Path,required=True)
    parser.add_argument("--evidence",type=Path,required=True)
    args=parser.parse_args()
    sha=subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip()
    report=verify(args.xml,args.evidence,sha)
    (args.evidence/"ret04-e6c-manifest.json").write_text(json.dumps(report,indent=2)+"\n")
    print("RET04-E6C exact-SHA checkout lineage PASS: 56")
if __name__=="__main__":
    main()

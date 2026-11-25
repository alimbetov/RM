#!/usr/bin/env python3
"""Verify committed registration/QR/checkout lineage against every RET04 SQL snapshot."""
import argparse
from decimal import Decimal
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

SCHEMA="RET04-E6C/v1"
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
    require(len({x["order_id"] for x in rows})==len(rows) and
            len({x["receipt_sha"] for x in rows})==len(rows),"duplicate checkout receipt/order")
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
        require(re.fullmatch(r"[0-9a-f]{64}",x["receipt_sha"]),"invalid receipt digest")
        registration=f"RET04E6A registration proof actor={meta['buyer']} identity={x['identity_id']} challenge={x['challenge_id']}"
        checkout=f"RET04E6B verified checkout tenant={meta['tenant']} buyer={meta['buyer']} order={x['order_id']} offer={x['offer_id']} sku={x['sku']}"
        require(registration in log.splitlines() and checkout in log.splitlines(),"missing service lineage marker")

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
            hashes=[]
            for snap in snapshots:
                meta,_,projections=d.snapshot(snap,sha,method)
                path=proofs/snap.name
                rows=load(path,sha,method,meta["tenant"])
                check_rows(rows,meta,projections["sales_return_request"],log)
                # Exact stable lineage across resolution, denied attempts, crash and replay.
                key=meta["tenant"]
                if key in by_tenant:
                    require(rows==by_tenant[key],"checkout lineage changed")
                else:
                    by_tenant[key]=rows
                hashes.append({"snapshot":snap.name,"tenant":key,
                               "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
                               "returnIds":sorted(x["return_id"] for x in rows)})
            result.append({"method":method,"tenants":len(by_tenant),"snapshots":hashes})
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

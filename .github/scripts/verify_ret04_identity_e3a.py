#!/usr/bin/env python3
"""E.3A named strict-identity methods and tenant-scoped SQL evidence gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from decimal import Decimal
from verify_ret04_evidence import snapshot, verify_identity, verify_actor_bindings, require, TABLES, MUTABLE, SUITE

METHODS = (
    "qr07R25StrictRet04E01TrustedRestock",
    "qr07R25StrictRet04E02TrustedDispose",
    "qr07R25StrictRet04E03UntrustedSignature",
    "qr07R25StrictRet04E04WrongIssuer",
    "qr07R25StrictRet04E05WrongAudience",
    "qr07R25StrictRet04E06IdToken",
    "qr07R25StrictRet04E07RefreshToken",
    "qr07R25StrictRet04E08ExpiredBearer",
    "qr07R25StrictRet04E09SpoofedClaims",
    "qr07R25StrictRet04E10UnknownSubject",
    "qr07R25StrictRet04E11ForeignTenant",
    "qr07R25StrictRet04E12ForeignReturn",
    "qr07R25StrictRet04E13WrongWarehouse",
    "qr07R25StrictRet04E14InactiveWarehouse",
    "qr07R25StrictRet04E15MissingPostRestock",
    "qr07R25StrictRet04E16MissingPostDispose",
    "qr07R25StrictRet04E17MissingReceive",
    "qr07R25StrictRet04E18MissingAdjust",
    "qr07R25StrictRet04E19ReceivePostOnly",
    "qr07R25StrictRet04E20AdjustPostOnly",
    "qr07R25StrictRet04E21RoleUnassigned",
    "qr07R25StrictRet04E22RoleDeactivated",
    "qr07R25StrictRet04E23AccountSuspended",
    "qr07R25StrictRet04E24MembershipSuspended",
)
ALLOW = {1:"RESTOCK",2:"DISPOSE",9:"RESTOCK",19:"RESTOCK",20:"DISPOSE"}
REASONS = {
    3:"OIDC token verification failed",4:"OIDC token verification failed",5:"OIDC token verification failed",
    6:"OIDC token verification failed",7:"OIDC token verification failed",8:"OIDC token verification failed",
    9:"Active organization membership required",10:"OIDC principal not linked to RM",
    11:"Active organization membership required",12:"QUARANTINE_SOURCE_NOT_ACCESSIBLE",
    13:"WAREHOUSE_SCOPE_REQUIRED",14:"WAREHOUSE_INACTIVE",
    **{i:"Tenant RBAC permission denied" for i in range(15,23)},
    23:"Actor inactive or unknown",24:"Active organization membership required",
}

def check_tenant(history, action):
    require(len(history) >= 2, "missing tenant baseline/final")
    first, last = history[0], history[-1]
    require(all(d[0] == first[0] for d in history), "identity metadata changed")
    require(first[1]["sales_return_quarantine_resolution"][0] == 0, "initial resolution exists")
    require(len(first[2]["sales_return_request"]) == 1 and
            first[2]["sales_return_request"][0]["state"] == "QUARANTINED" and
            first[2]["sales_return_request"][0]["row_version"] == 6, "invalid baseline")
    if action is None:
        require(all(d[1:] == first[1:] for d in history), "denied business mutation")
        return
    require(all(first[1][t] == last[1][t] for t in TABLES-MUTABLE), "immutable history changed")
    resolved = last[2]["sales_return_quarantine_resolution"]
    verify_actor_bindings(first[0], resolved, last[2]["sales_return_event"], 1)
    require(resolved[0]["action_code"] == action and Decimal(resolved[0]["quantity"]) == 2
            and Decimal(resolved[0]["before_quantity"]) == 2 and Decimal(resolved[0]["after_quantity"]) == 0,
            "incorrect resolution")
    req = last[2]["sales_return_request"]
    require(len(req)==1 and req[0]["state"]==("RESTOCKED" if action=="RESTOCK" else "DISPOSED")
            and req[0]["row_version"]==7 and req[0]["id"]==resolved[0]["return_id"], "wrong terminal return")
    q0, q1 = first[2]["inv_return_quarantine_balance"], last[2]["inv_return_quarantine_balance"]
    require(len(q0)==len(q1)==1 and Decimal(q0[0]["quantity"])==2 and Decimal(q1[0]["quantity"])==0,
            "quarantine balance mismatch")
    def stock(d):
        rows=[x for x in d[2]["inv_stock_balance"] if x["location_id"]==q0[0]["warehouse_id"] and x["item_id"]==q0[0]["item_id"]]
        require(len(rows)==1,"stock position missing")
        return rows[0]
    before, after=stock(first),stock(last)
    require(Decimal(after["on_hand"])-Decimal(before["on_hand"])==(2 if action=="RESTOCK" else 0)
            and after["reserved"]==before["reserved"],"stock mismatch")
    require(last[1]["inv_stock_movement"][0]-first[1]["inv_stock_movement"][0]==int(action=="RESTOCK")
            and last[1]["sales_return_event"][0]-first[1]["sales_return_event"][0]==1,"movement/event delta mismatch")
    require(history[-2][1:]==last[1:],"replay side effect")

def verify(xml,evidence,sha):
    require((evidence/"exact-sha.txt").read_text().strip()==sha,"E3A SHA mismatch")
    root=ET.parse(xml).getroot()
    require(root.tag=="testsuite" and root.get("name")==SUITE and
            all(root.get(k)=="0" for k in ("failures","errors","skipped")),"E3A suite not green")
    cases=[x for x in root.findall("testcase") if x.get("name","").startswith("qr07R25StrictRet04E")]
    require(len(cases)==24 and {x.get("name") for x in cases}==set(METHODS),"E3A method set mismatch")
    folder=evidence/"identity-e3"
    require(folder.is_dir() and {p.name for p in folder.iterdir()}==set(METHODS),"E3A evidence set mismatch")
    entries=[]
    for i,name in enumerate(METHODS,1):
        case=next(x for x in cases if x.get("name")==name)
        require(case.get("classname")==SUITE and not any(x.tag in ("failure","error","skipped","flakyFailure","flakyError","rerunFailure","rerunError") for x in case),"E3A testcase not clean")
        log=case.findtext("system-out","")
        if i in REASONS:
            require("RET04E3 denied reason="+REASONS[i] in log,"missing denial marker "+name)
        if i in ALLOW:
            require("RET04E3 allowed action="+ALLOW[i] in log,"missing allow marker "+name)
        files=sorted((folder/name).glob("snapshot-*.tsv"))
        require(len(files)>=2 and [p.name for p in files]==[f"snapshot-{n:03d}.tsv" for n in range(len(files))],"E3A snapshot sequence")
        groups={}
        for p in files:
            data=snapshot(p,sha,name)
            groups.setdefault(data[0]["tenant"],[]).append(data)
        require(len(groups)==(2 if i in (9,11,12) else 1),"wrong tenant count")
        for n,history in enumerate(groups.values()):
            verify_identity(log,history[0][0],"E")
            check_tenant(history,ALLOW.get(i) if n==0 else None)
        entries.append({"method":name,"outcome":ALLOW.get(i,"DENIED"),"tenants":list(groups),
                        "snapshots":[{"file":str(p.relative_to(evidence)),"sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]})
    return {"schema":"RET04-E3A/v1","sha":sha,"status":"PASS","cases":len(entries),
            "surefireSha256":hashlib.sha256(xml.read_bytes()).hexdigest(),"evidence":entries,
            "productionAdmission":"HOLD"}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--xml",type=Path,required=True)
    parser.add_argument("--evidence",type=Path,required=True)
    args=parser.parse_args()
    sha=os.environ.get("GITHUB_SHA","")
    require(len(sha)==40 and sha==subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),"E3A checkout SHA mismatch")
    report=verify(args.xml,args.evidence,sha)
    (args.evidence/"ret04-e3a-manifest.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("RET04-E3A exact-SHA identity evidence PASS:",report["cases"])

if __name__=="__main__":
    main()

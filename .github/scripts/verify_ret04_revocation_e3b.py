#!/usr/bin/env python3
"""E.3B: exact-SHA evidence for ordered resolution/revocation and denied replay."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
from verify_ret04_evidence import snapshot, verify_identity, require, SUITE
from verify_ret04_identity_e3a import check_tenant

METHODS = (
    "qr07R25ConcurrentRet04E25RestockThenWarehouseRevoke",
    "qr07R25ConcurrentRet04E26WarehouseRevokeThenRestock",
    "qr07R25ConcurrentRet04E27DisposeThenWarehouseRevoke",
    "qr07R25ConcurrentRet04E28WarehouseRevokeThenDispose",
    "qr07R25ConcurrentRet04E29RestockThenPermissionRevoke",
    "qr07R25ConcurrentRet04E30PermissionRevokeThenRestock",
    "qr07R25ConcurrentRet04E31DisposeThenPermissionRevoke",
    "qr07R25ConcurrentRet04E32PermissionRevokeThenDispose",
)
PHASES = ("before","blocked","committed","after-replay")
IDS = ("tenant","owner","staff","warehouse","role")
COUNTS = ("scopeVersion","grant","postGrant","otherGrant","warehouseEvents",
          "warehouseOwnerEvents","permissionEvents","permissionOwnerEvents")
KEYS = {"sha","method","phase","permission","scope",*IDS,*COUNTS}


def read_iam(path, sha, method, phase):
    data={}
    for line in path.read_text(encoding="utf-8").splitlines():
        pair=line.split("\t")
        require(len(pair)==2 and pair[0] in KEYS and pair[0] not in data,"invalid IAM evidence field")
        data[pair[0]]=pair[1]
    require(set(data)==KEYS and data["sha"]==sha and data["method"]==method and data["phase"]==phase,
            "IAM provenance mismatch")
    for key in IDS:
        require(str(uuid.UUID(data[key]))==data[key],"invalid IAM UUID")
    for key in COUNTS:
        require(bool(re.fullmatch(r"0|[1-9][0-9]*",data[key])),"invalid IAM count")
        data[key]=int(data[key])
    return data


def check_iam(history, metadata, warehouse, permission, warehouse_revoke):
    first, blocked, committed, replay = history
    stable=("sha","method","permission",*IDS)
    require(all(all(x[k]==first[k] for k in stable) for x in history),"IAM identity changed")
    require(all(first[k]==metadata[k] for k in ("tenant","owner","staff"))
            and first["warehouse"]==warehouse and first["permission"]==permission,"IAM target mismatch")
    def state(x):
        return (x["scope"],*(x[k] for k in COUNTS))
    version=first["scopeVersion"]
    require(state(first)==("ACTIVE",version,1,1,1,0,0,0,0),"invalid IAM baseline")
    require(state(blocked)==state(first),"uncommitted revocation visible")
    expected=("REVOKED",version+1,1,1,1,1,1,0,0) if warehouse_revoke else ("ACTIVE",version,0,1,1,0,0,1,1)
    require(state(committed)==expected,"revocation/audit not exactly once")
    require(state(replay)==state(committed),"replay changed IAM")


def check_order(log, resolution_first, reason):
    outcome="COMMITTED" if resolution_first else "DENIED"
    edges=re.findall(r"(?m)^BSR PG schedule blocker=(\d+) waiter=(\d+) observed=true first=COMMITTED second=(COMMITTED|DENIED)$",log)
    require(len(edges)==1 and edges[0][0]!=edges[0][1] and all(int(p)>0 for p in edges[0][:2])
            and edges[0][2]==outcome,"missing/wrong PostgreSQL locking edge")
    for variant in ("existing-principal","original-bearer","fresh-bearer"):
        marker="RET04E3B replay="+variant+" denied reason="+reason
        require(log.splitlines().count(marker)==1,"missing/duplicate replay denial "+variant)
    return {"blocker":int(edges[0][0]),"waiter":int(edges[0][1]),"secondOutcome":outcome}


def check_business(history, action):
    require(len(history)==7,"wrong business snapshot count")
    check_tenant(history,action)
    require(history[0][1:]==history[1][1:],"uncommitted business mutation visible")
    require(all(x[1:]==history[2][1:] for x in history[2:]),"replay changed committed business data")


def verify(xml,evidence,sha):
    require(bool(re.fullmatch(r"[0-9a-f]{40}",sha)) and
            (evidence/"exact-sha.txt").read_text().strip()==sha,"E3B SHA mismatch")
    root=ET.parse(xml).getroot()
    require(root.tag=="testsuite" and root.get("name")==SUITE and
            all(root.get(k)=="0" for k in ("failures","errors","skipped")),"E3B suite not green")
    all_cases=root.findall("testcase")
    require(len(all_cases)==81 and root.get("tests")=="81","E3B requires 81 named suite tests")
    cases=[x for x in all_cases if x.get("name","").startswith("qr07R25ConcurrentRet04E")]
    require(len(cases)==8 and {x.get("name") for x in cases}==set(METHODS),"E3B method set mismatch")
    business=evidence/"revocation-e3b"
    iam=evidence/"revocation-iam"
    for folder in (business,iam):
        require(folder.is_dir() and {p.name for p in folder.iterdir()}==set(METHODS),"E3B evidence set mismatch")
    # Existing independently verified manifests must belong to the same SHA.
    for file,count in (("ret04-d-manifest.json",24),("ret04-e3a-manifest.json",24)):
        prior=json.loads((evidence/file).read_text())
        require(prior["sha"]==sha and prior["status"]=="PASS" and prior["cases"]==count
                and prior["surefireSha256"]==hashlib.sha256(xml.read_bytes()).hexdigest(),"E3B prior manifest mismatch")
    entries=[]
    for i,name in enumerate(METHODS,25):
        case=next(x for x in cases if x.get("name")==name)
        require(case.get("classname")==SUITE and not any(x.tag in ("failure","error","skipped","flakyFailure","flakyError","rerunFailure","rerunError") for x in case),"E3B testcase not clean")
        restock=i in (25,26,29,30)
        first=i%2==1
        warehouse_revoke=i<=28
        action="RESTOCK" if restock else "DISPOSE"
        permission="INVENTORY_RECEIVE" if restock else "INVENTORY_ADJUST"
        reason="WAREHOUSE_SCOPE_REQUIRED" if warehouse_revoke else "Tenant RBAC permission denied"
        log=case.findtext("system-out","")
        edge=check_order(log,first,reason)
        files=sorted((business/name).iterdir())
        require([p.name for p in files]==[f"snapshot-{n:03d}.tsv" for n in range(7)],"E3B snapshot sequence")
        history=[snapshot(p,sha,name) for p in files]
        metadata=history[0][0]
        verify_identity(log,metadata,"E")
        check_business(history,action if first else None)
        q=history[0][2]["inv_return_quarantine_balance"]
        require(len(q)==1,"E3B baseline warehouse missing")
        iamfiles=[iam/name/(phase+".tsv") for phase in PHASES]
        require({p.name for p in (iam/name).iterdir()}=={p.name for p in iamfiles},"E3B IAM phases mismatch")
        states=[read_iam(p,sha,name,phase) for p,phase in zip(iamfiles,PHASES)]
        check_iam(states,metadata,q[0]["warehouse_id"],permission,warehouse_revoke)
        entries.append({"method":name,"action":action,"resolutionFirst":first,
                        "revocation":"WAREHOUSE" if warehouse_revoke else "PERMISSION","lock":edge,
                        "identity":{"tenant":metadata["tenant"],"staff":metadata["staff"],"owner":metadata["owner"]},
                        "snapshots":[{"file":str(p.relative_to(evidence)),"sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in files+iamfiles]})
    return {"schema":"RET04-E3B/v1","sha":sha,"status":"PASS","cases":8,"totalCoreCases":56,"javaTests":81,
            "surefireSha256":hashlib.sha256(xml.read_bytes()).hexdigest(),"evidence":entries,
            "mappingVerdict":"SUPPORTING_PARTIAL","productionAdmission":"HOLD"}


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--xml",type=Path,required=True)
    p.add_argument("--evidence",type=Path,required=True)
    a=p.parse_args()
    sha=os.environ.get("GITHUB_SHA","")
    require(sha==subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip(),"E3B checkout SHA mismatch")
    report=verify(a.xml,a.evidence,sha)
    (a.evidence/"ret04-e3b-manifest.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("RET04-E3B exact-SHA revocation evidence PASS: 8; CORE-RET-04 56/56; Java 81/81")

if __name__=="__main__":
    main()

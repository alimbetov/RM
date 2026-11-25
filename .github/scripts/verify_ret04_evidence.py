#!/usr/bin/env python3
"""Fail-closed dedicated CORE-RET-04 exact-SHA SQL/Surefire artifact gate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
from decimal import Decimal

SUITE = "kz.rm.sales.QrWarehouseHandoverQualificationTest"
METHODS = (
    "qr07R25CoreRet04RestockFirst",
    "qr07R25CoreRet04DisposeFirst",
    "qr07R25CoreRet04B01RestockDistinctKeys",
    "qr07R25CoreRet04B02DisposeDistinctKeys",
    "qr07R25CoreRet04B03RestockIdenticalReplay",
    "qr07R25CoreRet04B04DisposeIdenticalReplay",
    "qr07R25CoreRet04B05ChangedAction",
    "qr07R25CoreRet04B06ChangedEvidence",
    "qr07R25CoreRet04B07StaleVersionBeforeResolution",
    "qr07R25CoreRet04B08ChangedVersionAfterResolution",
    "qr07R25CoreRet04B09ResolutionInsertRollback",
    "qr07R25CoreRet04B10TerminalEventInsertRollback",
    "qr07R25CoreRet04B11ResolutionInsertSameKeyRetry",
    "qr07R25CoreRet04B12TerminalEventInsertSameKeyRetry",
    "qr07R25CoreRet04C01RestockCrashBeforeCommit",
    "qr07R25CoreRet04C02RestockCrashAfterCommit",
    "qr07R25CoreRet04C03DisposeCrashBeforeCommit",
    "qr07R25CoreRet04C04DisposeCrashAfterCommit",
    "qr07R25CoreRet04C05TwoJvmIdenticalRestock",
    "qr07R25CoreRet04C06TwoJvmRestockVsDispose",
    "qr07R25CoreRet04C07SharedSkuRestockRestock",
    "qr07R25CoreRet04C08SharedSkuDisposeDispose",
    "qr07R25CoreRet04C09SharedSkuRestockDispose",
    "qr07R25CoreRet04C10SharedSkuDisposeRestock",
)
TABLES = frozenset("inv_stock_balance inv_stock_movement inv_stock_reservation inv_reservation_event inv_return_quarantine_balance inv_return_quarantine_movement sales_return_request sales_return_posting sales_return_event sales_return_quarantine_resolution sales_order sales_order_line sales_order_status_event sales_pickup_handover sales_pickup_handover_line sales_pickup_credential fin_invoice".split())
MUTABLE = frozenset("inv_stock_balance inv_stock_movement inv_return_quarantine_balance sales_return_request sales_return_event sales_return_quarantine_resolution".split())
PROJECTED = frozenset("sales_return_request inv_stock_balance inv_return_quarantine_balance sales_return_quarantine_resolution inv_stock_movement sales_return_event".split())


def require(ok, message):
    if not ok:
        raise ValueError(message)


def snapshot(path, sha, method):
    meta, tables, rows = {}, {}, {t: [] for t in PROJECTED}
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t", 3)
        if parts[0] == "table":
            require(len(parts) == 4 and parts[1] in TABLES and parts[1] not in tables
                    and re.fullmatch(r"[0-9a-f]{64}", parts[3]), "invalid table digest")
            count = int(parts[2])
            require(count >= 0, "negative table count")
            tables[parts[1]] = (count, parts[3])
        elif parts[0] == "row":
            require(len(parts) == 3 and parts[1] in PROJECTED, "invalid projection")
            row = json.loads(parts[2], parse_float=Decimal)
            require(isinstance(row, dict), "invalid SQL row")
            rows[parts[1]].append(row)
        else:
            require(len(parts) == 2 and parts[0] in {"sha", "method", "postgres", "tenant", "identity", "owner", "staff", "buyer"}
                    and parts[0] not in meta, "invalid snapshot metadata")
            meta[parts[0]] = parts[1]
    require(meta.get("sha") == sha and meta.get("method") == method
            and re.fullmatch(r"[0-9a-f-]{36}", meta.get("tenant", ""))
            and int(meta.get("postgres", "0")) >= 170000, "snapshot provenance mismatch")
    require(meta.get("identity") == "RS256_LOOPBACK_JWKS", "missing strict identity provenance")
    actors = [meta.get(k, "") for k in ("owner", "staff", "buyer")]
    try:
        canonical = [str(uuid.UUID(a)) for a in actors]
    except ValueError as exc:
        raise ValueError("invalid strict identity actor") from exc
    require(actors == canonical and len(set(actors)) == 3, "invalid strict identity actor")
    require(set(tables) == TABLES, "missing SQL projection digest")
    for t in PROJECTED:
        require(len(rows[t]) == tables[t][0], "projection count mismatch: " + t)
    return meta, tables, rows


def verify_identity(log, metadata, suffix):
    actors = {metadata[k] for k in ("owner", "staff", "buyer")}
    parents = set(re.findall(r"(?m)^RET04E parent signed identity actor=([0-9a-f-]{36})$", log))
    require(actors <= parents, "missing signed parent actor evidence")
    children = re.findall(r"(?m)^RET04E child signed identity pid=(\d+) actor=([0-9a-f-]{36})$", log)
    expected = 2 if suffix.startswith(("C05", "C06")) else 1 if suffix.startswith(("C01", "C02", "C03", "C04")) else 0
    require(len(children) == expected and len({pid for pid, _ in children}) == expected
            and all(int(pid) > 0 and actor == metadata["staff"] for pid, actor in children),
            "missing or invalid signed child actor evidence")
    return {"scheme": metadata["identity"], "owner": metadata["owner"],
            "staff": metadata["staff"], "buyer": metadata["buyer"],
            "childPids": [int(pid) for pid, _ in children]}


def verify_actor_bindings(identity, resolved, events, expected):
    terminal = [x for x in events if x["from_state"] == "QUARANTINED"]
    require(len(resolved) == len(terminal) == expected and
            len({x["return_id"] for x in terminal}) == expected and
            all(x["actor_user_id"] == identity["staff"] for x in terminal),
            "terminal event actor mismatch")
    require({x["return_id"] for x in terminal} == {x["return_id"] for x in resolved},
            "terminal event return mismatch")
    require(all(x["actor_user_id"] == identity["staff"] for x in resolved),
            "resolution actor mismatch")


def verify(xml_path, evidence, sha):
    require(re.fullmatch(r"[0-9a-f]{40}", sha), "invalid SHA")
    require((evidence / "exact-sha.txt").read_text().strip() == sha, "artifact SHA mismatch")
    root = ET.parse(xml_path).getroot()
    require(root.tag == "testsuite" and root.get("name") == SUITE, "wrong suite")
    require(all(root.get(k) == "0" for k in ("errors", "failures", "skipped")), "suite not green")
    cases = root.findall("testcase")
    require(int(root.get("tests", "-1")) == len(cases), "suite count mismatch")
    selected = [c for c in cases if c.get("name", "").startswith("qr07R25CoreRet04")]
    require(len(selected) == len(METHODS) and {c.get("name") for c in selected} == set(METHODS),
            "missing, duplicate or unexpected CORE-RET-04 methods")
    snapshots = evidence / "sql"
    require(snapshots.is_dir() and {p.name for p in snapshots.iterdir()} == set(METHODS), "snapshot method set mismatch")
    report = []
    for case in selected:
        name = case.get("name")
        require(case.get("classname") == SUITE and not any(case.find(k) is not None
                for k in ("failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError")),
                "testcase not clean: " + name)
        log = case.findtext("system-out", "")
        suffix = name.removeprefix("qr07R25CoreRet04")
        if suffix.startswith(("RestockFirst", "DisposeFirst", "B01", "B02", "B03", "B04", "C07", "C08", "C09", "C10")):
            outcome = "COMMITTED" if suffix.startswith(("B03", "B04", "C07", "C08", "C09", "C10")) else "DENIED"
            pattern = r"BSR PG schedule blocker=(\d+) waiter=(\d+) observed=true first=COMMITTED second="+outcome
            match = re.search(pattern, log)
            require(match and match[1] != match[2], "missing PG locking edge: " + name)
        if suffix.startswith(("C05", "C06")):
            match = re.search(r"RET04C JVM schedule blocker=(\d+) waiter=(\d+) observed=true first=COMMITTED second="+
                              ("COMMITTED" if suffix.startswith("C05") else "DENIED"), log)
            require(match and match[1] != match[2], "missing JVM locking edge: " + name)
        if suffix.startswith(("C01", "C02", "C03", "C04")):
            pre = suffix.startswith(("C01", "C03"))
            restock = suffix.startswith(("C01", "C02"))
            marker = f"RET04C crash precommit={str(pre).lower()} restock={str(restock).lower()} exit={86 if pre else 87}"
            require(marker in log, "missing crash marker: " + name)
        if suffix.startswith(("B09", "B10", "B11", "B12")):
            marker = "RET04B_RESOLUTION_FAULT" if suffix.startswith(("B09", "B11")) else "RET04B_TERMINAL_EVENT_FAULT"
            require("RET04D verified SQLSTATE P0001 "+marker in log, "missing fault marker: " + name)
        files = sorted((snapshots / name).glob("snapshot-*.tsv"))
        require(len(files) >= 2 and [p.name for p in files] == [
                f"snapshot-{i:03d}.tsv" for i in range(len(files))], "missing/invalid snapshot sequence: " + name)
        data = [snapshot(p, sha, name) for p in files]
        require(all(d[0] == data[0][0] for d in data), "snapshot identity or tenant changed: " + name)
        first, last = data[0], data[-1]
        identity = verify_identity(log, first[0], suffix)
        require(all(first[1][t] == last[1][t] for t in TABLES - MUTABLE), "immutable SQL evidence changed: " + name)
        if suffix.startswith(("B07", "B09", "B10", "B11", "B12", "C01", "C03")):
            require(data[0][1] == data[1][1] and data[0][2] == data[1][2], "rollback evidence changed: " + name)
        if suffix.startswith(("B07", "B09", "B10")):
            require(first[1:] == last[1:], "denied operation changed SQL evidence: " + name)
        else:
            require(first[1]["sales_return_quarantine_resolution"][0] == 0, "nonempty initial resolution")
            shared = suffix.startswith(("C07", "C08", "C09", "C10"))
            expected = 2 if shared else 1
            require(last[1]["sales_return_quarantine_resolution"][0] == expected, "wrong resolution count")
            require(len(last[2]["sales_return_request"]) == expected and
                    all(x["state"] in ("RESTOCKED", "DISPOSED") and x["row_version"] == 7
                        for x in last[2]["sales_return_request"]), "nonterminal return")
            q1, q2 = first[2]["inv_return_quarantine_balance"], last[2]["inv_return_quarantine_balance"]
            require(len(q1) == len(q2) == 1 and Decimal(q1[0]["quantity"]) == (5 if shared else 2)
                    and Decimal(q2[0]["quantity"]) == 0, "quarantine conservation failed")
            require(last[1]["sales_return_event"][0] == first[1]["sales_return_event"][0]+expected, "wrong event delta")
            resolved = last[2]["sales_return_quarantine_resolution"]
            verify_actor_bindings(identity, resolved, last[2]["sales_return_event"], expected)
            for row in resolved:
                require(Decimal(row["before_quantity"])-Decimal(row["quantity"]) == Decimal(row["after_quantity"])
                        and Decimal(row["after_quantity"]) >= 0
                        and row["action_code"] in ("RESTOCK","DISPOSE")
                        and (row["stock_movement_id"] is not None) == (row["action_code"] == "RESTOCK"), "invalid resolution row")
            credit = sum((Decimal(x["quantity"]) for x in resolved if x["action_code"] == "RESTOCK"), Decimal(0))
            require(sum((Decimal(x["quantity"]) for x in resolved), Decimal(0)) == (5 if shared else 2), "wrong resolved quantity")
            sku, warehouse = q1[0]["item_id"], q1[0]["warehouse_id"]
            def position(d):
                rows = [x for x in d[2]["inv_stock_balance"] if x["item_id"] == sku and x["location_id"] == warehouse]
                require(len(rows) == 1, "missing stock position")
                return rows[0]
            before, after = position(first), position(last)
            require(Decimal(after["on_hand"])-Decimal(before["on_hand"]) == credit and
                    after["reserved"] == before["reserved"], "sellable conservation failed")
            require(last[1]["inv_stock_movement"][0]-first[1]["inv_stock_movement"][0] ==
                    sum(x["action_code"] == "RESTOCK" for x in resolved), "movement delta mismatch")
        if suffix.startswith(("B05","B06","B08","B11","B12","C01","C02","C03","C04","C07","C08","C09","C10")):
            require(data[-2][1:] == data[-1][1:], "replay changed SQL evidence: " + name)
        report.append({"method":name,"identity":identity,"snapshots":[{"file":str(p.relative_to(evidence)),
                      "sha256":hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]})
    return {"schema":"RET04-D/v1","sha":sha,"status":"PASS","cases":len(report),
            "surefireSha256":hashlib.sha256(xml_path.read_bytes()).hexdigest(),"evidence":report,
            "identityProfile":"RS256_LOOPBACK_JWKS","mappingVerdict":"SUPPORTING_PARTIAL","productionAdmission":"HOLD"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--xml", type=Path, required=True)
    args = parser.parse_args()
    sha = os.environ.get("GITHUB_SHA", "")
    require(sha == subprocess.check_output(["git","rev-parse","HEAD"], text=True).strip(), "checkout SHA mismatch")
    report = verify(args.xml, args.evidence, sha)
    (args.evidence / "ret04-d-manifest.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print("RET04-D exact-SHA evidence PASS:", report["cases"])


if __name__ == "__main__":
    main()

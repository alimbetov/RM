#!/usr/bin/env python3
"""Post-run diagnostics only. Never overrides a Java performance gate."""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import re
import subprocess


def percentile(values, q=.95):
    if not values or not 0 < q <= 1:
        raise ValueError("missing samples or invalid percentile")
    return sorted(values)[math.ceil(len(values)*q)-1]/1_000_000


def read_samples(path):
    with path.open(newline="", encoding="utf-8") as f:
        rows=list(csv.DictReader(f))
    if not rows or [int(r["sample"]) for r in rows]!=list(range(1,len(rows)+1)):
        raise ValueError("missing or nonsequential samples: "+path.name)
    values=[int(r["nanos"]) for r in rows]
    if any(x<=0 for x in values):
        raise ValueError("nonpositive sample: "+path.name)
    return values


def stats(values):
    return {"n":len(values),"p50Ms":percentile(values,.5),
            "p95Ms":percentile(values),"maxMs":percentile(values,1)}


def plan_summary(document):
    if not isinstance(document,list) or len(document)!=1:
        raise ValueError("expected one EXPLAIN document")
    top=document[0]
    nodes=[]
    def walk(node, path):
        if "Actual Total Time" not in node or "Actual Loops" not in node:
            raise ValueError("EXPLAIN ANALYZE metrics missing")
        loops=node["Actual Loops"]
        entry={"path":path,"type":node["Node Type"],
               "relation":node.get("Relation Name"),"index":node.get("Index Name"),
               "loops":loops,"actualRows":node["Actual Rows"],
               "estimatedRows":node["Plan Rows"],
               "rowsAcrossLoops":node["Actual Rows"]*loops,
               "inclusiveMsAcrossLoops":node["Actual Total Time"]*loops,
               "sharedHitBlocks":node.get("Shared Hit Blocks",0),
               "sharedReadBlocks":node.get("Shared Read Blocks",0),
               "tempReadBlocks":node.get("Temp Read Blocks",0),
               "tempWrittenBlocks":node.get("Temp Written Blocks",0),
               "rowsRemovedByFilterPerLoop":node.get("Rows Removed by Filter",0),
               "rowsRemovedByJoinFilterPerLoop":node.get("Rows Removed by Join Filter",0),
               "sortMethod":node.get("Sort Method"),
               "sortSpaceType":node.get("Sort Space Type"),
               "hashBatches":node.get("Hash Batches")}
        nodes.append(entry)
        for i,child in enumerate(node.get("Plans",[])):
            walk(child,f"{path}.{i}")
    walk(top["Plan"],"0")
    # Parent/child time and buffers overlap. Never add them to estimate total.
    return {"executionMs":top["Execution Time"],
            "planningMs":top.get("Planning Time"),
            "root":nodes[0],
            "topInclusiveNodes":sorted(nodes,key=lambda n:n["inclusiveMsAcrossLoops"],reverse=True)[:12],
            "nodes":nodes}


def competition(root, samples):
    base=samples["100000-command-baseline"]
    mixed=samples["100000-command-mixed"]
    reader=samples["100000-mixed-reader"]
    with (root/"100000-lock-sampling.csv").open(newline="",encoding="utf-8") as f:
        locks=list(csv.DictReader(f))
    if len(locks)!=6:
        raise ValueError("expected six lock sampling blocks")
    arm={False:[],True:[]}
    blocks=[]
    expected=[False,True,True,False,False,True]
    for i,(row,is_mixed) in enumerate(zip(locks,expected),1):
        if int(row["block"])!=i or row["mixed"]!=str(is_mixed).lower():
            raise ValueError("unexpected workload order")
        matches=[name for name in samples if re.fullmatch(
            rf"100000-command-block{i}(?:-baseline|-mixed)?",name)]
        if len(matches)!=1:
            raise ValueError("missing/ambiguous command block "+str(i))
        name=matches[0]
        if name.endswith("-mixed") and not is_mixed or name.endswith("-baseline") and is_mixed:
            raise ValueError("command block arm mismatch")
        values=samples[name]
        if len(values)!=40:
            raise ValueError("unexpected command sample count")
        lock_samples=int(row.get("samples",row.get("lockSamples","-1")))
        positive=int(row.get("positive",row.get("positiveLockSamples","-1")))
        reads=int(row.get("reads",row.get("completedReads","-1")))
        if not 0<=positive<=lock_samples or lock_samples<=0 or reads<0 or (is_mixed!=(reads>0)):
            raise ValueError("invalid lock sampling evidence")
        arm[is_mixed].extend(values)
        blocks.append({"block":i,"mixed":is_mixed,**stats(values),
                       "lockSamples":lock_samples,"positiveLockSamples":positive,
                       "completedReads":reads})
    if arm[False]!=base or arm[True]!=mixed or sum(x["completedReads"] for x in blocks)!=len(reader):
        raise ValueError("aggregated samples do not match raw blocks")
    delta=(percentile(mixed)/percentile(base)-1)*100
    return {"baseline":stats(base),"mixed":stats(mixed),"reader":stats(reader),
            "commandDegradationPercent":delta,
            "commandWithinBudget":delta<=10,
            "mixedReadWithinBudget":percentile(reader)<=200,
            "readerTailSampleWarning":len(reader)<100,
            "blocks":blocks,
            "lockInterpretation":"Database-wide sampled waits; zero observations do not establish absence of locks."}


def analyze(root, sha):
    runner=(root/"runner.txt").read_text(encoding="utf-8").splitlines()
    if not re.fullmatch(r"[0-9a-f]{40}",sha) or runner.count("sha="+sha)!=1:
        raise ValueError("runner SHA mismatch")
    samples={p.name.removesuffix("-samples.csv"):read_samples(p)
             for p in sorted(root.glob("*-samples.csv"))}
    plans={}
    for path in sorted(root.glob("*-plan.json")):
        key=path.name.removesuffix("-plan.json")
        if key not in samples:
            raise ValueError("plan without samples: "+key)
        plans[key]={"service":stats(samples[key]),**plan_summary(json.loads(path.read_text()))}
    # Reports remain useful even when a Java assertion aborted the workload.
    missing=sorted(k for k in samples if re.match(r"^(1000|10000|100000)-(dense|sparse)-",k)
                   and k not in plans)
    expected=[]
    for size in (1000,10000,100000):
        for density in ("dense","sparse"):
            for basis in (("due","completed-early") if "calendar" in root.name else ("",)):
                for limit in (20,100):
                    expected.append("-".join(x for x in (str(size),density,basis,"limit"+str(limit),"first") if x))
    missing=sorted(set(missing)|{k for k in expected if k not in plans})
    errors=[]
    try:
        mixed=competition(root,samples)
    except (OSError,ValueError,KeyError) as exc:
        mixed=None
        errors.append(str(exc))
    return {"schema":"RM-RADAR-COMPETITION-PROFILE/v1","sha":sha,
            "diagnosticStatus":"COMPLETE" if not missing and not errors else "INCOMPLETE",
            "missingReadEvidence":missing,"diagnosticErrors":errors,
            "readPlans":plans,"competition":mixed,
            "interpretation":"Plan timing is a separate EXPLAIN execution. Node metrics overlap. No admission verdict or causal inference."}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("root",type=Path)
    args=parser.parse_args()
    sha=os.environ.get("GITHUB_SHA","")
    if sha!=subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip():
        raise ValueError("checkout SHA mismatch")
    result=analyze(args.root,sha)
    (args.root/"competition-profile.json").write_text(json.dumps(result,indent=2)+"\n")
    lines=["### RADAR competition profile (diagnostic only)",
           f"SHA: `{sha}`; evidence: {result['diagnosticStatus']}"]
    for label,plan in sorted(result["readPlans"].items(),
            key=lambda item:item[1]["service"]["p95Ms"],reverse=True)[:5]:
        lines.append(f"- {label}: service p95 {plan['service']['p95Ms']:.3f} ms; EXPLAIN {plan['executionMs']:.3f} ms")
        # Print concrete plan operators to the job log as well as the full
        # artifact, so diagnosis is possible without downloading a ZIP.
        for node in plan["topInclusiveNodes"][:5]:
            lines.append(f"  - {node['path']} {node['type']} relation={node['relation']} "
                         f"index={node['index']} loops={node['loops']} "
                         f"rows={node['actualRows']}/{node['estimatedRows']} "
                         f"inclusiveMs={node['inclusiveMsAcrossLoops']:.3f} "
                         f"hit/read={node['sharedHitBlocks']}/{node['sharedReadBlocks']} "
                         f"temp read/write={node['tempReadBlocks']}/{node['tempWrittenBlocks']}")
    if result["competition"] is not None:
        c=result["competition"]
        lines.extend([f"- Command p95 degradation: {c['commandDegradationPercent']:.3f}% (budget 10%)",
                      f"- Mixed reader p95: {c['reader']['p95Ms']:.3f} ms, n={c['reader']['n']}"])
        for block in c["blocks"]:
            lines.append(f"- Block {block['block']} mixed={block['mixed']} p95={block['p95Ms']:.3f} ms; "
                         f"lock samples={block['positiveLockSamples']}/{block['lockSamples']}; "
                         f"completed reads={block['completedReads']}")
        if c["readerTailSampleWarning"]:
            lines.append("- Mixed reader tail estimate has fewer than 100 samples.")
    lines.append("This report does not replace or relax the Java qualification assertions.")
    summary="\n".join(lines)+"\n"
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8") as f:
            f.write(summary)

if __name__=="__main__":
    main()

#!/usr/bin/env python3
"""Fail-closed validation of R4's 29 requirement priority/dependency graph."""
import json
from pathlib import Path

CATALOG = Path("docs/spec/RM-R3-FUNCTIONAL-COVERAGE-v1.json")
BACKLOG = Path("docs/strategy/RM-STRATEGY-R4-GAP-PRIORITY-v1.json")
WAVES = ("W0", "W1", "W2", "W3", "W4")


def verify(root):
    catalog = json.loads((root / CATALOG).read_text(encoding="utf-8"))
    plan = json.loads((root / BACKLOG).read_text(encoding="utf-8"))
    if catalog.get("schema") != "RM-R3-FUNCTIONAL-COVERAGE/v1" or plan.get("schema") != "RM-STRATEGY-R4-GAP-PRIORITY/v1":
        raise ValueError("Unexpected R3/R4 schema")
    domains = catalog["domains"]
    if len(domains) != 16 or len({x["id"] for x in domains}) != 16:
        raise ValueError("16 canonical contexts required")
    originals = {r["id"]: (d["id"], r) for d in domains for r in d["requirements"]}
    planned = {rid for rid, (_, r) in originals.items() if r["delivery"] == "PLANNED"}
    implemented = {rid for rid, (_, r) in originals.items() if r["delivery"] == "IMPLEMENTED"}
    if len(originals) != 75 or len(planned) != 29 or len(implemented) != 46:
        raise ValueError("Changed R3 denominator or implementation baseline")
    rows = plan["requirements"]
    if len(rows) != 29 or len({x["requirementId"] for x in rows}) != 29:
        raise ValueError("Exactly 29 unique prioritized requirements required")
    if {x["requirementId"] for x in rows} != planned:
        raise ValueError("R4 requires exactly the R3 planned IDs")
    if [x["rank"] for x in rows] != list(range(1, 30)):
        raise ValueError("Priority ranks must be contiguous")
    if [x["id"] for x in plan.get("waves", [])] != list(WAVES):
        raise ValueError("All five waves must be declared")
    used = set()
    prior_wave = -1
    for row in rows:
        rid = row["requirementId"]
        did, source = originals[rid]
        if row["domainId"] != did or row["title"] != source["title"] or row["delivery"] != "PLANNED":
            raise ValueError("Source catalogue mismatch: " + rid)
        wave = row["wave"]
        if wave not in WAVES or WAVES.index(wave) < prior_wave:
            raise ValueError("Wave ordering violated: " + rid)
        prior_wave = WAVES.index(wave)
        deps = row["dependsOnPlanned"]
        if not isinstance(deps, list) or len(deps) != len(set(deps)):
            raise ValueError("Duplicate or invalid dependencies: " + rid)
        if any(dep not in used for dep in deps):
            raise ValueError("Forward, unknown or cyclic dependency: " + rid)
        assessment = row["assessment"]
        keys = ("businessValue", "commercialCriticality", "riskReduction", "effortPoints")
        if not all(type(assessment.get(k)) is int and 1 <= assessment[k] <= 5 for k in keys):
            raise ValueError("Unscored or invalid prioritization: " + rid)
        expected = (5*assessment["businessValue"] + 4*assessment["commercialCriticality"] +
                    3*assessment["riskReduction"] - 2*assessment["effortPoints"])
        if row["provisionalScore"] != expected:
            raise ValueError("Invalid computed score: " + rid)
        if not row.get("acceptanceCriterion") or row["readiness"] != "NOT_STARTED" or row["productionAdmission"] != "HOLD":
            raise ValueError("Unjustified implementation/admission claim: " + rid)
        used.add(rid)
    if plan.get("productionAdmission") != "HOLD" or plan.get("gates", {}).get("noNewImplementation") is not True:
        raise ValueError("R4 is prioritization only, not production or development admission")
    return {"schema":"RM-STRATEGY-R4-VALIDATION/v1", "gate":"PRIORITY_GRAPH_VALID_NOT_IMPLEMENTED",
            "requirements":29,"domains":16,
            "waves":{w:sum(row["wave"] == w for row in rows) for w in WAVES},
            "productionAdmission":"HOLD"}


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    print(json.dumps(verify(root), indent=2))

# RM-CI-R3.6A — Workflow Dependency Inventory & Execution Policy

Status: advisory inventory implementation; selective execution NOT ADMITTED.

Baseline 19bcf7f29379f5760b9c3677ebd5635838912325: 24/24 workflow runs successful (GitHub Actions observed).
Inventory: 41 workflow files, including all 12 mandatory Exact-SHA inputs.
The 12 mandatory workflow definitions were reviewed for direct execution shape;
the other 29 have tree identity and a domain hint only. Domain hints are NOT dependency closures.
All 41 remain EXECUTE_REQUIRED under existing triggers; this does not add new triggers.
Registry: .github/qualification/rm-ci-workflow-dependencies.json.

## Execution policy
- Current release gate, trigger conditions and artifact contract unchanged.
- Unknown, shared context, migrations, build, CI policy and environment changes fail closed to full qualification.
- A domain name or Maven test selector does not establish independence.
- No reuse until dependency closure, environment and evidence provenance are qualified.
- NOT_REQUIRED is not PASS. Development admission is separate from release admission.
- Inventory drift requires reviewed registry refresh; no automatic trust of a changed workflow.
- Existing push filters remain diagnostic/existing behavior; registry cannot control execution.

## Next
R3.6B: separate development admission with executed evidence only, no weakening of existing gate.
R3.6C/D: prove dependency closures and selective rules before enabling them.
R3.6E: measure elapsed time and runner minutes; no savings asserted.
Return to RM-INT-SCHED-R1.1B/C/D, R1.2, R1.3, R1.4 after CI qualification.
NIGHT 00:15 tenant-local, INTRADAY every 3 hours, multi-pod durable job queue, scheduler disabled by default, production HOLD.

Run `python3 .github/scripts/verify_rm_ci_workflow_inventory.py` and
`python3 -m unittest discover -s .github/scripts -p 'test_verify_rm_ci_workflow_inventory.py'`.
The existing Exact-SHA Python discovery will execute the tests without a workflow change.

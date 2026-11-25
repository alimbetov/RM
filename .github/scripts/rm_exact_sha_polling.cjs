'use strict';
// GitHub workflow, jobs and artifact API views are not an atomic snapshot.
// Only complete, consistent, exact-SHA evidence is READY. Temporary API
// disagreement never counts as a terminal workflow failure.
function classifyGate(run, jobs, artifacts, expected, sha) {
  const result=(qualificationState,qualificationReason)=>({qualificationState,qualificationReason});
  if (!run) return result('WAIT','WORKFLOW_NOT_VISIBLE');
  if (run.head_sha!==sha) return result('FAIL','WRONG_SHA');
  if (run.status!=='completed') return result('WAIT','WORKFLOW_RUNNING');
  if (!run.conclusion) return result('WAIT','WORKFLOW_CONCLUSION_NOT_VISIBLE');
  if (run.conclusion!=='success') return result('FAIL','WORKFLOW_'+run.conclusion.toUpperCase());
  if (!Number.isInteger(run.run_attempt)||run.run_attempt<1)
    return result('WAIT','RUN_ATTEMPT_NOT_VISIBLE');
  if (!jobs.length || jobs.some(j=>j.status!=='completed'||!j.conclusion))
    return result('WAIT','JOBS_NOT_SETTLED');
  // A successful run and a non-successful job in its own attempt are
  // inconsistent. Retry within the bounded deadline; never admit them.
  if (jobs.some(j=>j.conclusion!=='success'))
    return result('WAIT','WORKFLOW_JOB_STATUS_DISAGREEMENT');
  const ids=new Set();
  for(const name of expected) {
    const matches=artifacts.filter(a=>a.name===name&&!a.expired&&a.size_in_bytes>0);
    if(matches.length>1) return result('FAIL','AMBIGUOUS_ARTIFACT:'+name);
    if(matches.length===0) return result('WAIT','ARTIFACT_NOT_VISIBLE:'+name);
    const id=matches[0].id;
    if(!Number.isSafeInteger(id)||id<=0) return result('WAIT','ARTIFACT_ID_NOT_VISIBLE:'+name);
    if(ids.has(id)) return result('FAIL','DUPLICATE_ARTIFACT_ID');
    ids.add(id);
  }
  return result('READY','EXACT_SHA_JOBS_AND_ARTIFACTS_READY');
}
function pollDecision(evidence, expectedCount) {
  if(evidence.some(e=>e.qualificationState==='FAIL')) return 'TERMINAL_EVIDENCE_FAILURE';
  if(evidence.length===expectedCount&&expectedCount>0&&
      evidence.every(e=>e.qualificationState==='READY')) return 'PASS';
  return 'WAIT';
}
module.exports={classifyGate,pollDecision};

'use strict';
const {execFileSync}=require('node:child_process');
const fs=require('node:fs');
const GATES=[
 'RM Domain Security and PostgreSQL Qualification',
 'CRM R1.2 Populated PostgreSQL Upgrade Qualification',
 'INVENTORY 0081 Populated PostgreSQL Upgrade Qualification',
 'SALES 08.4 Populated PostgreSQL Upgrade Qualification',
 'RustFS Real Infrastructure E2E',
 'RM TASK R2 Service Qualification',
 'RM TASK R3.2A Populated PostgreSQL Upgrade Qualification',
 'RM TASK R32B T00 PostgreSQL Contract Qualification',
 'RM CUR-R2C Policy Schema PostgreSQL Qualification',
 'RM QR-07 R0-R2 Physical Return PostgreSQL Qualification',
 'RM Intelligence A1 PostgreSQL Source Schema',
 'RM MVP W2-W5 Service Journeys PostgreSQL Qualification'
];
const BANK='docs/design/intelligent-commerce/RM-B4.5D.0-BUSINESS-SCENARIO-BANK.md';
const JOURNEY='src/test/java/kz/rm/iam/L1ProjectionJourneyEvidence.java';
const ROLE='docs/operations/RM-FINAL-SEC-GATE-DB-ROLE-QUALIFICATION.sql';
const DEC='docs/design/intelligent-commerce/DEC-B4-GEO-NOM-MULTIDIMENSIONAL.md';
function classify(paths, reliable=true){
 const unique=[...new Set(paths)].sort();
 const full=(reason)=>({mode:'FULL',reason,candidateGates:[...GATES],deferCandidates:[],paths:unique});
 if(!reliable||!unique.length)return full('UNVERIFIED_OR_EMPTY_DIFF');
 // Exact allowlist only. Unknown paths, shared fixtures, migrations, build and
 // workflow changes must not be treated as local by directory heuristics.
 if(unique.every(p=>p===BANK))return {mode:'FAST_DOC_CANDIDATE',reason:'SCENARIO_SPEC_ONLY',candidateGates:[],deferCandidates:[...GATES],paths:unique};
 if(unique.every(p=>[BANK,JOURNEY,ROLE,DEC].includes(p))&&unique.some(p=>p===JOURNEY||p===ROLE)){
  const candidateGates=GATES.filter(g=>/Domain Security|Intelligence A1|MVP W2-W5/.test(g));
  return {mode:'TARGETED_PG_CANDIDATE',reason:'KNOWN_INTELLIGENCE_TEST_OR_ROLE_PROBE',candidateGates,deferCandidates:GATES.filter(g=>!candidateGates.includes(g)),paths:unique};
 }
 return full('UNKNOWN_OR_SHARED_CONTRACT_IMPACT');
}
function git(args,cwd){return execFileSync('git',args,{cwd,encoding:'utf8',maxBuffer:32*1024*1024,stdio:['ignore','pipe','pipe']});}
function inspect(before,head,cwd=process.cwd()){
 let paths=[],reliable=false,detail='VERIFIED_ANCESTOR_DIFF';
 try{
  if(!/^[a-f0-9]{40}$/.test(before)||! /^[a-f0-9]{40}$/.test(head)||/^0+$/.test(before))throw Error('INVALID_SHA');
  if(git(['rev-parse','HEAD'],cwd).trim()!==head)throw Error('HEAD_MISMATCH');
  git(['merge-base','--is-ancestor',before,head],cwd);
  paths=git(['diff','--no-renames','--name-only','-z',before,head,'--'],cwd).split('\0').filter(Boolean);
  reliable=true;
 }catch(e){detail='DIFF_UNAVAILABLE_OR_NON_ANCESTOR';}
 return {schema:'RM-CI-R3-SHADOW/v1',before,head,detail,shadowOnly:true,admission:'UNCHANGED_FULL_REQUIRED',...classify(paths,reliable),requiredExecutedGates:[...GATES]};
}
if(require.main===module){
 const [before,head,out]=process.argv.slice(2);
 if(!out)throw Error('usage: rm_ci_impact_shadow.cjs BEFORE HEAD OUTPUT');
 const report=inspect(before||'',head||'');
 fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
 console.log(JSON.stringify(report,null,2));
}
module.exports={classify,inspect,GATES,BANK,JOURNEY,ROLE,DEC};

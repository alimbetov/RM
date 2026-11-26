'use strict';
const {execFileSync}=require('node:child_process');
const {createHash}=require('node:crypto');
const fs=require('node:fs');
const POLICY='RM-CI-FINGERPRINT/v1';
const GROUPS=['DOMAIN','SCHEMA','SECURITY','INTELLIGENCE','DATASET','SCENARIO','TEST','ENVIRONMENT','CI_POLICY','OTHER'];
const bank='docs/design/intelligent-commerce/RM-B4.5D.0-BUSINESS-SCENARIO-BANK.md';
function groups(path){
 const g=new Set();
 if(path.startsWith('src/main/java/')){
  if(path.startsWith('src/main/java/kz/rm/intelligence/'))g.add('INTELLIGENCE');else g.add('DOMAIN');
  if(/\/(iam|security)\//.test(path))g.add('SECURITY');
 }
 if(path.startsWith('src/main/resources/'))g.add('SCHEMA');
 if(path.startsWith('src/test/')){
  g.add('TEST');
  if(/\/(dataset|scenario|qualification)\//.test(path))g.add('DATASET');
  if(/\/scenario\//.test(path))g.add('SCENARIO');
 }
 if(path===bank)g.add('SCENARIO');
 if(path.startsWith('docs/operations/'))g.add('SECURITY');
 if(path.startsWith('.github/'))g.add('CI_POLICY');
 if(/(^|\/)(pom\.xml|mvnw|mvnw\.cmd|Dockerfile[^/]*|compose[^/]*\.ya?ml)$/.test(path)||path.startsWith('.mvn/'))g.add('ENVIRONMENT');
 if(!g.size)g.add('OTHER');
 return [...g].sort();
}
function sha(value){return createHash('sha256').update(JSON.stringify(value)).digest('hex');}
function git(args,cwd){return execFileSync('git',args,{cwd,encoding:'utf8',maxBuffer:64*1024*1024,stdio:['ignore','pipe','pipe']});}
function tree(commit,cwd=process.cwd()){
 if(!/^[a-f0-9]{40}$/.test(commit))throw Error('INVALID_SHA');
 if(git(['rev-parse',commit+'^{commit}'],cwd).trim()!==commit)throw Error('UNVERIFIED_COMMIT');
 const rows=git(['ls-tree','-r','-z',commit],cwd).split('\0').filter(Boolean).map(row=>{
  const i=row.indexOf('\t');if(i<0)throw Error('INVALID_TREE_ROW');
  const [mode,type,id]=row.slice(0,i).split(' ');const path=row.slice(i+1);
  if(type!=='blob'||!/^100(644|755)$/.test(mode)||! /^[a-f0-9]{40}$/.test(id)||!path||path.includes('\ufffd'))throw Error('UNSUPPORTED_TREE_ENTRY');
  return {path,mode,id};
 }).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
 if(!rows.length||new Set(rows.map(r=>r.path)).size!==rows.length)throw Error('INVALID_TREE');
 return rows;
}
function fingerprint(rows){
 const out={};
 for(const name of GROUPS){
  const selected=rows.filter(r=>groups(r.path).includes(name));
  out[name]={count:selected.length,hash:sha([POLICY,name,selected.map(r=>[r.path,r.mode,r.id])])};
 }
 return {policy:POLICY,groups:out,repositoryHash:sha([POLICY,rows.map(r=>[r.path,r.mode,r.id])])};
}
function compare(before,head,cwd=process.cwd()){
 const blocked=['DEPENDENCY_CLOSURE_UNQUALIFIED','ENVIRONMENT_NOT_IMMUTABLY_PINNED','BASELINE_EVIDENCE_NOT_AUTHENTICATED'];
 const result={schema:POLICY,before,head,shadowOnly:true,admission:'UNCHANGED_FULL_REQUIRED',reuse:'EXECUTE_REQUIRED',blocked};
 try{
  if(git(['rev-parse','HEAD'],cwd).trim()!==head)throw Error('HEAD_MISMATCH');
  if(before===head)throw Error('IDENTICAL_BASELINE_AND_HEAD');
  git(['merge-base','--is-ancestor',before,head],cwd);
  const a=fingerprint(tree(before,cwd)),b=fingerprint(tree(head,cwd));
  result.baseline=a;result.current=b;
  result.changedGroups=GROUPS.filter(g=>a.groups[g].hash!==b.groups[g].hash);
  result.repositoryEqual=a.repositoryHash===b.repositoryHash;
  result.sourceComparison=result.repositoryEqual?'IDENTICAL_TRACKED_TREE':'TRACKED_TREE_CHANGED';
 }catch(e){result.sourceComparison='UNVERIFIED';result.blocked.push('UNVERIFIED_GIT_INPUT');}
 return result;
}
if(require.main===module){
 const [before,head,out]=process.argv.slice(2);
 if(!out)throw Error('OUTPUT_REQUIRED');
 const report=compare(before||'',head||'');fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report,null,2));
}
module.exports={groups,fingerprint,tree,compare,GROUPS};

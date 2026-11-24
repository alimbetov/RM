'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const {classifyGate,pollDecision}=require('./rm_exact_sha_polling.cjs');
const sha='a'.repeat(40);
const run={head_sha:sha,status:'completed',conclusion:'success',run_attempt:1};
const job={status:'completed',conclusion:'success'};
const artifact={name:'evidence',id:1,expired:false,size_in_bytes:100};
const classify=(r=run,j=[job],a=[artifact],names=['evidence'])=>classifyGate(r,j,a,names,sha);
test('completed RustFS workflow with still-running job must wait, then pass',()=>{
  const first=classify(run,[{status:'in_progress',conclusion:null}]);
  assert.equal(first.qualificationReason,'JOBS_NOT_SETTLED');
  assert.equal(pollDecision([first,classify()],2),'WAIT');
  assert.equal(pollDecision([classify(),classify()],2),'PASS');
});
test('missing job list and missing conclusion are retryable',()=>{
  assert.equal(classify(run,[]).qualificationState,'WAIT');
  assert.equal(classify({...run,conclusion:null}).qualificationState,'WAIT');
});
test('job disagreement cannot grant admission',()=>{
  assert.equal(classify(run,[{...job,conclusion:'failure'}]).qualificationState,'WAIT');
});
test('artifact publication lag, expired and empty archives wait',()=>{
  for(const a of [[],[{...artifact,expired:true}],[{...artifact,size_in_bytes:0}]]){
    assert.equal(classify(run,[job],a).qualificationState,'WAIT');
  }
});
test('missing workflow and running workflow wait',()=>{
  assert.equal(classify(null).qualificationState,'WAIT');
  assert.equal(classify({...run,status:'in_progress',conclusion:null}).qualificationState,'WAIT');
});
test('actual terminal failure or cancellation fails closed',()=>{
  for(const conclusion of ['failure','cancelled','timed_out','skipped']){
    const evidence=classify({...run,conclusion});
    assert.equal(pollDecision([evidence,classify()],2),'TERMINAL_EVIDENCE_FAILURE');
  }
});
test('wrong SHA fails even if workflow is green',()=>{
  assert.equal(classify({...run,head_sha:'b'.repeat(40)}).qualificationReason,'WRONG_SHA');
});
test('duplicate artifacts cannot be selected arbitrarily',()=>{
  assert.equal(classify(run,[job],[artifact,{...artifact,id:2}]).qualificationState,'FAIL');
  assert.equal(classify(run,[job],[artifact,{...artifact,name:'other'}],['evidence','other']).qualificationReason,'DUPLICATE_ARTIFACT_ID');
});
test('all gates required and attempt metadata must exist',()=>{
  assert.equal(pollDecision([classify()],2),'WAIT');
  assert.equal(pollDecision([],0),'WAIT');
  assert.equal(classify({...run,run_attempt:undefined}).qualificationState,'WAIT');
});

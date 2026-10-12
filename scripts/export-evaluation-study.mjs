// Rebuild one extensible workbook from public evidence and non-secret usage in selected batches.
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {spawnSync} from 'node:child_process';
import {getHeapStatistics} from 'node:v8';
import {strategyCondition,studyConditionId,summaryKey,indexSentenceGroups} from './evaluation-study-schema.mjs';

const [outputArgument, ...batchArguments] = process.argv.slice(2);
if (!outputArgument || !batchArguments.length) throw new Error('Use OUTPUT_DIRECTORY BATCH_DIRECTORY [BATCH_DIRECTORY ...]');
// Size the bounded heap for final XLSX serialization, which holds the full
// sentence evidence and the serialized package together. Read manifests only.
const exportDebates=(await Promise.all(batchArguments.map(async batch=>JSON.parse((await fs.readFile(path.join(path.resolve(batch),'manifest.json'),'utf8')).replace(/^\uFEFF/,''))))).reduce((sum,m)=>sum+m.cells.length,0);
const exportHeapMb=exportDebates>=90?16384:8192;
if(getHeapStatistics().heap_size_limit<(exportHeapMb/1024-.5)*1024**3) {
  if(process.env.EVALUATION_STUDY_EXPORT_HEAP===String(exportHeapMb)) throw new Error(`Node cannot provide the required ${exportHeapMb/1024} GiB export heap`);
  console.log(`EXPORT_HEAP restarting with a ${exportHeapMb/1024} GiB maximum; no inference occurs in this script`);
  const child=spawnSync(process.execPath,[`--max-old-space-size=${exportHeapMb}`,process.argv[1],...process.argv.slice(2)],{stdio:'inherit',env:{...process.env,EVALUATION_STUDY_EXPORT_HEAP:String(exportHeapMb)}});
  if(child.error) throw child.error;
  process.exit(child.status??1);
}
const exportStarted=performance.now(), timings={};
const stage=name=>{timings[name]=(performance.now()-exportStarted)/1000;console.log(`EXPORT_STAGE ${name} ${timings[name].toFixed(2)}s`);};

const output = path.resolve(outputArgument);
const runtimeRequire = createRequire(path.join(output, 'artifact-loader.cjs'));
const {Workbook, SpreadsheetFile} = await import(pathToFileURL(runtimeRequire.resolve('@oai/artifact-tool')).href);
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const read = async file => JSON.parse((await fs.readFile(file, 'utf8')).replace(/^\uFEFF/,''));
const optional = async file => { try { return await read(file); } catch(e) { if(e.code==='ENOENT') return null; throw e; } };
const metrics = ['consistency','logical_reasoning','responsiveness','relevance','rhetorical_tactics'];
const metricLabels = ['Consistency','Logical reasoning','Responsiveness','Relevance','Disruptive rhetoric'];
const methods = ['vader-sentiment','cardiff-sentiment','deberta-stance'];
const records={runs:[],rubric:[],sentences:[],speeches:[],usage:[],protocol:[],assignments:[]};
const seenRuns=new Set(), sourceBatches=[], groups=new Map(), budgetGroups=new Map();
const strategyByRun=new Map(), strategies=new Map(), seenConditions=new Set();
const conditionByRun=new Map();
let capturedSpeeches=0, capturedRubric=0;
for (const batchArgument of batchArguments) {
  const batch=path.resolve(batchArgument), m=await read(path.join(batch,'manifest.json'));
  const budgetGroup=m.budgetGroupId??m.batchId, budgetCeiling=m.cumulativeBudgetLimitUsd??m.budgetLimitUsd;
  if(budgetGroups.has(budgetGroup) && budgetGroups.get(budgetGroup)!==budgetCeiling) throw new Error('Inconsistent cumulative budget group');
  budgetGroups.set(budgetGroup,budgetCeiling);
  if (String(m.state).startsWith('SYNTHETIC') || batch.includes('fake-matrix')) throw new Error('Synthetic fixtures cannot enter the research workbook');
  const model=m.models['study-judge'];
  const groupHash=sha(JSON.stringify([model,m.frozenSourceHashes['evaluation/rubric.json'],m.frozenSourceHashes['prompts/EvaluatorPrompt.txt'],m.frozenSourceHashes['prompts/EvaluatorCue.txt'],m.frozenSourceHashes['prompts/EvaluatorRepair.txt'],m.frozenSourceHashes['config/llm-evaluation.json']]));
  if(!groups.has(groupHash)) groups.set(groupHash,`G${groups.size+1}`);
  const group=groups.get(groupHash);
  const ledger=await optional(path.join(batch,'operations/usage.json')) || {calls:[]};
  const callsByCell=new Map();
  for(const call of ledger.calls) {const calls=callsByCell.get(call.cellId)??[];calls.push(call);callsByCell.set(call.cellId,calls);}
  const identity=await optional(path.join(batch,'operations/code-identity.json'));
  const replacementIdentity=await optional(path.join(batch,'operations/replacement-code-identity.json'));
  const attempts=new Map();
  sourceBatches.push({batchId:m.batchId,state:m.state,manifest:batchArgument,cells:m.cells.length,budgetGroup,budgetMode:m.budgetMode??'fixed-usd',cumulativeBudgetLimitUsd:budgetCeiling,batchAllowanceUsd:m.budgetLimitUsd,priorAllowanceUsd:m.priorBudgetCommittedUsd??0});
  for (const call of ledger.calls) {
    records.usage.push([m.batchId,call.cellId,call.callId,call.purpose,call.provider,call.model,call.status,call.usage?.inputTokens??null,call.usage?.outputTokens??null,call.inputTokenBound,call.outputTokenCap,call.latencyMillis==null?null:call.latencyMillis/1000,call.standardPriceEstimateUsd??null,call.budgetUsd,call.startedAt?new Date(call.startedAt):null,call.endedAt?new Date(call.endedAt):null,call.requestSha256,call.providerError??null,null,null]);
  }
  for (const c of m.cells) {
    const settings=m.models[c.modelPreset], runId=c.runId??null;
    const strategy=strategyCondition(c,m.frozenSourceHashes);
    const conditionId=studyConditionId(c,settings,strategy,m.frozenSourceHashes['data/hansard/excerpts.json']);
    if(seenConditions.has(conditionId)) throw new Error('Duplicate study condition across selected batches');
    seenConditions.add(conditionId); strategies.set(strategy.id,strategy);
    if(runId) strategyByRun.set(runId,{strategy,conditionId,key:summaryKey(group,`${settings.model} [${settings.provider}]`,c.groundingCount,strategy.id)});
    if(runId) conditionByRun.set(runId,{...c,assignmentBlock:strategy.label==='NONE'?null:c.assignmentBlock??c.repetition,comparisonMode:strategy.label==='NONE'?'cooperative':c.comparisonMode??m.comparisonMode??'matched',modelKey:`${settings.model} [${settings.provider}]`,group,actor:strategy.parties,strategyId:strategy.id});
    for(const assignment of strategy.assignments) for(const assigned of assignment.strategies) records.assignments.push([m.batchId,c.cellId,runId,conditionId,strategy.id,strategy.label,assignment.party,assigned,assigned!=='NONE',strategy.configSha256,settings.model,settings.provider,c.topicKey,c.groundingCount,c.repetition]);
    if(runId && seenRuns.has(runId)) throw new Error('Duplicate run ID across selected batches');
    if(runId) seenRuns.add(runId);
    let transcript=await optional(path.join(batch,'public/transcripts',c.cellId+'.json'));
    if(!transcript && runId) transcript=await optional(path.join(batch,'application/runs',runId,'transcript.json'));
    const local=await optional(path.join(batch,'public/reports',c.cellId+'-local.json'));
    const llm=await optional(path.join(batch,'public/reports',c.cellId+'-rubric.json'));
    const publicText=transcript ? await fs.readFile((await fs.stat(path.join(batch,'public/transcripts',c.cellId+'.json')).catch(()=>null)) ? path.join(batch,'public/transcripts',c.cellId+'.json') : path.join(batch,'application/runs',runId,'transcript.json'),'utf8') : null;
    const transcriptHash=publicText ? sha(publicText) : null;
    for(const report of [local,llm]) if(report && (report.runId!==runId || report.transcriptSha256!==transcriptHash)) throw new Error('Evaluation report source does not match retained public transcript');
    const speechEvents=(transcript?.events??[]).filter(e=>e.type==='SPEECH');
    attempts.set(c.cellId,{runId,startedAt:transcript?.startedAt,previousRunIds:c.previousRunIds??[]});
    const eventById=new Map((transcript?.events??[]).map(e=>[e.id,e]));
    const calls=callsByCell.get(c.cellId)??[];
    const sum=(purpose,key)=>{const selected=calls.filter(x=>x.purpose===purpose);return selected.length && selected.every(x=>x.usage) ? selected.reduce((v,x)=>v+x.usage[key],0):null;};
    const paid=calls.filter(x=>x.provider==='openai');
    const standard=paid.length && paid.every(x=>x.standardPriceEstimateUsd!=null) ? paid.reduce((v,x)=>v+x.standardPriceEstimateUsd,0):paid.length?null:0;
    records.runs.push([c.cellId,runId,settings.model,settings.provider,c.topicKey,c.topicTitle,c.groundingCount,c.repetition,5,speechEvents.length,transcript?.outcome??null,c.localJobState??'PENDING',c.rubricJobState??'PENDING',transcript?.endedAt?(transcript.endedAt-transcript.startedAt)/1000:null,sum('generation','inputTokens'),sum('generation','outputTokens'),sum('rubric','inputTokens'),sum('rubric','outputTokens'),standard,paid.reduce((v,x)=>v+x.budgetUsd,0),settings.temperature,settings.reasoningEffort??(settings.provider==='ollama'?m.localReasoning:null),settings.maxCompletionTokens,model.model,model.reasoningEffort,model.temperature,model.maxCompletionTokens,c.speakingOrder.join(', '),transcriptHash,m.frozenSourceHashes['data/hansard/excerpts.json'],settings.provider==='ollama'?m.observedOllamaModel?.digest??null:null,settings.provider==='ollama'?m.ollamaContextTokens:null,settings.provider==='ollama'?m.observedNativeContextTokens??null:null,c.state,group,m.batchId,1+(c.previousRunIds??[]).length,(c.previousRunIds??[]).join(', '),`${settings.model} [${settings.provider}]`]);
    for(let i=0;i<speechEvents.length;i++) {
      const e=speechEvents[i];
      records.speeches.push([c.cellId,runId,settings.model,c.topicKey,c.groundingCount,c.repetition,e.speaker.party,e.id,Math.floor(i/5)+1,i%5+1,e.text,null,null,null,null,null,null,transcriptHash,group,m.batchId,null,null,transcript.outcome]);
    }
    capturedSpeeches+=speechEvents.length;
    for(const topicResult of llm?.assessments?.topics??[]) {
      for(const participant of transcript?.roster??[]) {
        const assessment=topicResult.assessment?.participants?.find(p=>p.participantId===participant.id);
        for(const metric of metrics) {
          const result=assessment?.metrics?.find(x=>x.metricId===metric);
          const definition=llm.assessments.rubric.metrics.find(x=>x.id===metric);
          if(result?.status==='OK' && (!Number.isInteger(result.score)||result.score<definition.minimum||result.score>definition.maximum)) throw new Error('Invalid saved rubric score');
          for(const ref of result?.evidenceTurnIds??[]) if(!eventById.has(ref)) throw new Error('Invalid rubric evidence join');
          records.rubric.push([c.cellId,runId,settings.model,c.topicKey,c.groundingCount,c.repetition,participant.party,participant.id,metric,result?.status??topicResult.status,result?.status==='OK'?result.score:null,definition.minimum,definition.maximum,result?.explanation??topicResult.error??null,(result?.evidenceTurnIds??[]).join(', '),llm.assessments.model.model,llm.assessments.model.reasoningEffort,llm.assessments.rubric.version,llm.configurationSha256,transcriptHash,group,m.batchId,transcript.outcome]);
          if(result?.status==='OK') capturedRubric++;
        }
      }
    }
    for(const method of local?.methods??[]) {
      for(const b of method.batches??[]) {
        for(const item of b.items??[]) {
          const event=eventById.get(item.turnId);
          if(!event?.speaker) throw new Error('Sentence metric cannot join to a public speech');
          for(const chunk of item.chunks.length?item.chunks:[null]) {
            records.sentences.push([c.cellId,runId,settings.model,c.topicKey,c.groundingCount,c.repetition,event.speaker.party,item.turnId,method.methodId,chunk?.sentenceIndex??null,chunk?.start??null,chunk?.end??null,chunk?.label??null,item.status,item.error,chunk?.compound??null,chunk?.scores.negative??null,chunk?.scores.neutral??null,chunk?.scores.positive??null,chunk?.scores.support??null,chunk?.scores.oppose??null,chunk?.scores.unrelated??null,chunk?.uncertainty?.maxScore??null,chunk?.uncertainty?.margin??null,chunk?.uncertainty?.entropy??null,chunk?.uncertainty?.abstained??null,chunk?.text??null,b.provenance.modelId,b.provenance.revision,b.provenance.implementationVersion,b.provenance.configSha256,transcriptHash,method.status,method.error,group,m.batchId]);
          }
        }
      }
      if(!(method.batches??[]).some(b=>(b.items??[]).length)) records.sentences.push([c.cellId,runId,settings.model,c.topicKey,c.groundingCount,c.repetition,null,null,method.methodId,null,null,null,null,'failed',method.error,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,transcriptHash,method.status,method.error,group,m.batchId]);
    }
  }
  for(const row of records.usage.filter(r=>r[0]===m.batchId)) {
    const attempt=attempts.get(row[1]);
    const archived=attempt?.previousRunIds.length && row[14] && row[14].getTime()<attempt.startedAt;
    row[18]=archived?attempt.previousRunIds[0]:attempt?.runId??null;
    row[19]=archived?'Archived attempt':'Current transcript';
  }
  for(const [label,value] of [
    ['Batch',m.batchId],['Batch state',m.state],['Date and timezone',`${m.dateLocal} ${m.timezone}`],['Expected debates',m.expectedDebates],['Expected speeches',m.expectedSpeeches],['Repetitions',m.repetitions],['Preparation base commit',m.sourceCommit],['Application checkpoint',identity?.ApplicationCheckpoint??'Not recorded'],['Application build SHA256',identity?.ApplicationJarSha256??'Not recorded'],['First-pass collector class SHA256',identity?.CollectorClassSha256??'Not recorded'],['Replacement collector class SHA256',replacementIdentity?.CollectorClassSha256??'No replacement'],['Evaluation group',group],['Judge identity hash',groupHash],
    ...[...new Set(m.cells.map(c=>c.modelPreset))].map(preset=>['Generator: '+preset,JSON.stringify(m.models[preset])]),['Common judge',JSON.stringify(model)],['Qwen thinking',m.localReasoning],['Requested local context',m.ollamaContextTokens],['Observed native local context',m.observedNativeContextTokens??'Not observed'],['Ollama runtime',m.observedOllamaVersion?.version??'Not observed'],['Qwen model digest',m.observedOllamaModel?.digest??'Not observed'],['Ollama environment',JSON.stringify(m.ollamaEnvironment)],['Budget ceiling USD',m.budgetLimitUsd],['Pricing and budget method',m.budgetMethod],['Luna rates source',m.pricingSource],['Corpus SHA256',m.frozenSourceHashes['data/hansard/excerpts.json']],['Rubric SHA256',m.frozenSourceHashes['evaluation/rubric.json']],['Local models configuration SHA256',m.frozenSourceHashes['nlp/config/models.json']],['Replacement attempts',m.cells.filter(c=>c.previousRunIds?.length).map(c=>`${c.cellId}: ${c.previousRunIds.join(', ')} -> ${c.runId}`).join('; ')||'None'],['Public evidence folder',`evidence/${m.batchId}/`]
  ]) records.protocol.push([m.batchId,label,value]);
  records.protocol.push([m.batchId,'Budget group',budgetGroup],[m.batchId,'Prior conservative allowance USD',m.priorBudgetCommittedUsd??0],[m.batchId,'Cumulative ceiling USD',budgetCeiling],[m.batchId,'First repetition',m.firstRepetition??Math.min(...m.cells.map(c=>c.repetition))],[m.batchId,'Last repetition',m.lastRepetition??Math.max(...m.cells.map(c=>c.repetition))]);
  records.protocol.push([m.batchId,'Treatment',m.treatmentId??'baseline'],[m.batchId,'Spending boundary',m.budgetMode==='prepaid-account'?'User-authorized loaded account credit':`Fixed cumulative USD ${budgetCeiling}`]);
  records.protocol.push([m.batchId,'Assignment blocks',JSON.stringify(m.assignmentBlocks??[])],[m.batchId,'Comparison mode',m.comparisonMode??(m.treatmentId==='adversarial-topic-v1'?'matched':'cooperative')],[m.batchId,'Comparison note',m.comparisonNote??'']);
}
const cumulativeBudgetCeilingUsd=[...budgetGroups.values()].some(v=>v==null)?null:[...budgetGroups.values()].reduce((a,b)=>a+b,0);
records.protocol.push(['All batches','Metric grain','Rubric: one participant/topic/metric. Sentiment and stance: sentence chunks. Speeches: mean of available chunks for that one speech.'],['All batches','Separate scales','Rubric 0–4. Higher rhetorical-tactics values mean more disruption. VADER compound −1 to 1. Cardiff/DeBERTa uncalibrated model scores 0–1.'],['All batches','Uncertainty','Abstained transformer chunks retain their scores and an explicit abstained field. Speech means include available raw scores, including abstained chunks.'],['All batches','Missing values','Blank numerical scores mean unavailable or insufficient evidence. They are never changed to zero. Chunk counts are zero when no valid chunks are available. Pending and failed jobs stay visible in Runs.'],['All batches','Research scope','Repetition coverage is listed per model and strategy condition in Summary. Speeches and sentences within a debate are dependent observations. Automated judgments have not been validated against human ratings.'],['All batches','Graph construction','Use RubricScores, SpeechData, SentenceMetrics and StrategyAssignments in Excel PivotTables. Filter by provider, model, topic, Hansard count, repetition, party, strategy condition, speaker strategy, strategy role and evaluation group. Five rubric charts and three local method charts show the first judge group, with separate model/strategy series and pooled available complete repetitions. Each metric retains its original scale.'],['All batches','Append later runs','Regenerate from the original and additional batch directories with this builder. Original run IDs and unique study conditions are retained; duplicates are rejected. Additional models/repetitions/strategy assignments create new rows, not new provider worksheets.'],['All batches','Cost meaning','Usage and Runs token/cost totals include all attempts, including archived failures. Related batches share one cumulative ceiling, with prior allowance reserved before a new batch starts. Standard-price estimate uses reported tokens and uncached Standard rates, excluding cache discounts. Budget allowance uses conservative prices and retains full reservations for failures/unknown usage. Neither is an account invoice.'],['All batches','Summary means','Complete debates only. Rubric means average participant/topic scores. Native method means average per-speech chunk means. Automated ratings and model confidence scores are separate measurements.']);

const headers={
  runs:['Cell ID','Run ID','Model','Provider','Topic key','Topic','Hansard excerpts','Repetition','Rounds','Speeches','Outcome','Local evaluation','Rubric evaluation','Duration (s)','Generation input tokens (all attempts)','Generation output tokens (all attempts)','Judge input tokens (all attempts)','Judge output tokens (all attempts)','Standard estimate (USD)','Budget allowance (USD)','Temperature','Reasoning','Completion cap','Judge model','Judge reasoning','Judge temperature','Judge cap','Speaking order','Transcript SHA256','Corpus SHA256','Local model digest','Requested context','Native context','Cell state','Evaluation group','Batch','Attempt count','Archived run IDs','Model key'],
  rubric:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Participant ID','Metric','Status','Score','Minimum','Maximum','Explanation','Evidence turn IDs','Judge model','Judge reasoning','Rubric version','Judge config SHA256','Transcript SHA256','Evaluation group','Batch','Generation outcome'],
  sentences:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Turn ID','Method','Sentence index','Start offset','End offset','Label','Item status','Item error','VADER compound','Negative','Neutral','Positive','Support','Oppose','Unrelated','Max score','Margin','Entropy','Abstained','Sentence text','Method model','Revision','Implementation','Config SHA256','Transcript SHA256','Method status','Method error','Evaluation group','Batch'],
  speeches:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Turn ID','Round','Speaking position','Speech text','VADER mean compound','Cardiff mean positive','DeBERTa mean support','VADER chunks','Cardiff abstained chunks','DeBERTa abstained chunks','Transcript SHA256','Evaluation group','Batch','Cardiff chunks','DeBERTa chunks','Generation outcome'],
  usage:['Batch','Cell ID','Call ID','Purpose','Provider','Model','Status','Input tokens','Output tokens','Input token bound','Output token cap','Latency (s)','Standard estimate (USD)','Budget allowance (USD)','Started UTC','Ended UTC','Request SHA256','Provider error','Attempt run ID','Transcript selection'],
  protocol:['Batch','Parameter','Value'],
  assignments:['Batch','Cell ID','Run ID','Study condition ID','Strategy condition ID','Strategy condition','Party','Strategy','Assigned adversarial','Strategy config SHA256','Model','Provider','Topic key','Hansard excerpts','Repetition']
};
for(const [key,rows] of Object.entries(records)) for(const row of rows) if(row.length!==headers[key].length) throw new Error(`Wrong row width: ${key}`);
// One stable join key keeps model/turn/method joins responsive and survives native table sorts.
headers.sentences.push('Speech method key');
for(const row of records.sentences) row.push(row[13]==='ok' && row[9]!=null ? `${row[1]}|${row[7]}|${row[8]}` : null);
const runById=new Map(records.runs.map(row=>[row[1],row]));
for(const key of ['rubric','speeches','sentences']) {
  headers[key].push('Provider','Model key');
  for(const row of records[key]) {const run=runById.get(row[1]);row.push(run[3],run[38]);}
}
const strategyHeaders=['Strategy condition','Strategy condition ID','Adversarial parties','Adversarial party count','Adversarial strategies','Strategy count','Party strategy assignments','Strategy config SHA256','Study condition ID','Summary key'];
for(const key of ['runs','rubric','speeches','sentences']) {
  headers[key].push(...strategyHeaders);
  if(key!=='runs') headers[key].push('Speaker strategy','Strategy role');
  for(const row of records[key]) {
    const metadata=strategyByRun.get(row[1]);
    if(!metadata) throw new Error('Missing researcher strategy metadata for run');
    const {strategy,conditionId,key:groupKey}=metadata;
    row.push(strategy.label,strategy.id,strategy.parties,strategy.partyCount,strategy.strategies,strategy.strategyCount,strategy.assignmentJson,strategy.configSha256,conditionId,groupKey);
    if(key!=='runs') {const speaker=row[6]==null?{strategy:null,role:null}:strategy.speaker(row[6]);row.push(speaker.strategy,speaker.role);}
  }
}
const column=(key,label)=>{const index=headers[key].indexOf(label);if(index<0) throw new Error(`Missing column ${key}: ${label}`);return index;};
for(const key of ['runs','rubric','speeches','sentences','assignments']) {
  headers[key].push('Assignment block','Comparison mode');
  for(const row of records[key]) {
    const condition=conditionByRun.get(row[key==='assignments'?2:1]);
    row.push(condition.assignmentBlock,condition.comparisonMode);
  }
}
const descriptiveConditions=[...conditionByRun.values()].filter(c=>c.comparisonMode==='descriptive');
records.protocol.push(['All batches','Assignment blocks and comparisons','Assignment block retains the actor/order block alongside legacy Repetition fields. Cooperative records have no assigned block. Matched and descriptive assigned conditions remain explicit. Descriptive blocks 4–5 have different orders from historical cooperative blocks 1–3; comparisons do not establish causal effects.']);
// Project original chunk scores into their speech row. Row-local formulas remain
// correct when either source table is sorted and avoid repeated full-table scans.
const {groups:sentenceGroups,widths:chunkWidths}=indexSentenceGroups(records.sentences);
const chunkColumns={};
for(let mi=0;mi<methods.length;mi++) {
  const method=methods[mi],width=chunkWidths.get(method)??1;
  chunkColumns[method]={width,scoreStart:headers.speeches.length+1};
  for(let i=1;i<=width;i++) headers.speeches.push(`${['VADER compound','Cardiff Positive','DeBERTa Support'][mi]} chunk ${i}`);
}
for(const method of ['cardiff-sentiment','deberta-stance']) {
  chunkColumns[method].flagStart=headers.speeches.length+1;
  for(let i=1;i<=chunkColumns[method].width;i++) headers.speeches.push(`${method==='cardiff-sentiment'?'Cardiff':'DeBERTa'} abstained chunk ${i}`);
}
for(const row of records.speeches) {
  for(let mi=0;mi<methods.length;mi++) {
    const chunks=sentenceGroups.get(`${row[1]}|${row[7]}|${methods[mi]}`)??[];
    row.push(...Array.from({length:chunkColumns[methods[mi]].width},(_,i)=>chunks[i]?.[[15,18,19][mi]]??null));
  }
  for(const method of ['cardiff-sentiment','deberta-stance']) {
    const chunks=sentenceGroups.get(`${row[1]}|${row[7]}|${method}`)??[];
    row.push(...Array.from({length:chunkColumns[method].width},(_,i)=>chunks[i]?.[25]==null?null:chunks[i][25]?1:0));
  }
}
records.protocol.push(['All batches','Speech chunk projection','Speeches projects original native chunk scores and abstention flags into appended columns. Flags use 1=true, 0=false, blank=unavailable; Sentence metrics retains original booleans. Means/counts use row-local SUM/COUNT formulas; the full Sentence metrics table retains text, offsets, labels, uncertainty and provenance. Sorting either table preserves the speech calculations.']);
records.protocol.push(['All batches','Strategy metadata','Researcher condition labels come from frozen roster assignments and strategy prompt hashes. Strategy assignments lists every party/strategy pair, including NONE. Multiple parties and multiple strategies per party are supported by the export schema. Private prompt text/setup remains excluded from public evidence and blind judging.'],['All batches','Strategy grouping','Summary, repetition coverage and each chart series distinguish strategy condition IDs, including assigned parties and strategy configuration. Speaker strategy and BASELINE/ASSIGNED/OTHER roles allow assignment outcomes and effects on other parties to be analysed separately. These are assignments, not proof a tactic was exhibited.'],['All batches','Export method','Linear-time indexing of calls/chunks and strategy groups, batch formula writes, bounded row-local chunk formulas and numeric chart caches. Stage timing is saved in workbook-verification.json. Rebuild from all selected source batches to append data.']);
records.protocol.push(['All batches','Exporter source SHA256',sha(await fs.readFile(new URL(import.meta.url)))],['All batches','Strategy schema source SHA256',sha(await fs.readFile(new URL('./evaluation-study-schema.mjs',import.meta.url)))],['All batches','Verifier source SHA256',sha(await fs.readFile(new URL('./verify-and-package-evaluation-study.py',import.meta.url)))],['All batches','Export memory',`The exporter uses a bounded ${exportHeapMb/1024} GiB Node heap for SDK XLSX serialization (${exportDebates} selected debates). Fewer than 90 debates retain the earlier 8 GiB bound. Raw source tables use native table styling and only required column/number/text formats; avoid formatting every raw cell with repeated font/alignment properties.`]);
for(const [key,rows] of Object.entries(records)) for(const row of rows) if(row.length!==headers[key].length) throw new Error(`Wrong final row width: ${key}`);
const wb=Workbook.create();
const descriptiveSheet=descriptiveConditions.length?wb.worksheets.add('Batch 2 descriptive'):null;
const summary=wb.worksheets.add('Summary');
const sheetNames={speeches:'Speeches',rubric:'Rubric scores',sentences:'Sentence metrics',runs:'Runs',assignments:'Strategy assignments',usage:'Usage',protocol:'Protocol'};
const tableNames={speeches:'SpeechData',rubric:'RubricScores',sentences:'SentenceMetrics',runs:'StudyRuns',assignments:'StrategyAssignments',usage:'ProviderUsage',protocol:'StudyProtocol'};
const sheets={};
const letter=n=>{let s='';while(n){n--;s=String.fromCharCode(65+n%26)+s;n=Math.floor(n/26);}return s;};
const font='Arial', ink='#233042', navy='#243B64', muted='#657187';
for(const key of Object.keys(sheetNames)) {
  const sheet=wb.worksheets.add(sheetNames[key]); sheets[key]=sheet; sheet.showGridLines=false;
  const rows=records[key];
  sheet.getRange(`A1:${letter(headers[key].length)}${Math.max(2,rows.length+1)}`).values=[headers[key],...(rows.length?rows:[headers[key].map(()=>null)])];
  const head=sheet.getRange(`A1:${letter(headers[key].length)}1`);
  head.format.columnWidth=17;
  head.format={fill:navy,font:{name:font,size:10,bold:true,color:'#FFFFFF'},wrapText:true,rowHeight:48,horizontalAlignment:'center',verticalAlignment:'center'};
  const table=sheet.tables.add(`A1:${letter(headers[key].length)}${Math.max(2,rows.length+1)}`,true,tableNames[key]);
  table.style='TableStyleMedium2'; table.showBandedColumns=false; table.showFilterButton=true;
  sheet.freezePanes.freezeRows(1); sheet.freezePanes.freezeColumns(key==='protocol'?1:2);
  sheet.getRange(`A1:A${Math.max(2,rows.length+1)}`).format.columnWidth=44;
  if(key!=='protocol') sheet.getRange(`B1:B${Math.max(2,rows.length+1)}`).format.columnWidth=39;
  for(let column=0;column<headers[key].length;column++) {
    const label=headers[key][column], range=sheet.getRange(`${letter(column+1)}2:${letter(column+1)}${Math.max(2,rows.length+1)}`);
    if(/SHA256|digest|hash/i.test(label)) range.format.columnWidth=68;
    if(/tokens|context|excerpts|Repetition|Rounds|Speeches|index|offset|cap|Round$|position|chunks|Minimum|Maximum|Score$|Call ID|party count|Strategy count/.test(label)) range.setNumberFormat('0');
    if(/compound|Positive|Negative|Neutral|Support|Oppose|Unrelated|Max score|Margin|Entropy|mean|Temperature|temperature/.test(label)) range.setNumberFormat('0.000');
    if(/USD/.test(label)) range.setNumberFormat('$0.000000');
    if(/\(s\)/.test(label)) range.setNumberFormat('0.0');
    if(/UTC/.test(label)) range.setNumberFormat('yyyy-mm-dd hh:mm:ss');
    if(['Topic','Speech text','Sentence text','Explanation','Value','Speaking order','Evidence turn IDs'].includes(label)) {range.format.columnWidth=110; range.format.wrapText=true;}
    if(/Strategy condition$|Adversarial parties|Adversarial strategies|Speaker strategy/.test(label)) {range.format.columnWidth=35;range.format.wrapText=true;}
    if(/condition ID|Summary key/.test(label)) range.format.columnWidth=74;
    if(label==='Party strategy assignments') range.format.columnWidth=65;
    if(label==='Strategy') range.format.columnWidth=32;
  }
  if(rows.length) sheet.getRange(`A2:${letter(headers[key].length)}${rows.length+1}`).format.autofitRows();
  if(key==='protocol') {sheet.getRange(`B1:B${rows.length+1}`).format.columnWidth=38; sheet.getRange(`C1:C${rows.length+1}`).format.columnWidth=110;sheet.getRange(`C2:C${rows.length+1}`).format.wrapText=true;sheet.getRange(`A2:C${rows.length+1}`).format.autofitRows();}
}
stage('source-tables');
// Rebuilding from added batches extends these ranges; avoid evaluating thousands of empty observations.
const rubEnd=Math.max(2,records.rubric.length+1);
const speechEnd=Math.max(2,records.speeches.length+1);
const runEnd=Math.max(2,records.runs.length+1);
const chunkRange=(method,row,flags=false)=>{const group=chunkColumns[method],start=flags?group.flagStart:group.scoreStart;return `$${letter(start)}${row}:$${letter(start+group.width-1)}${row}`;};
if(records.speeches.length) {
  const formulas=records.speeches.map((_,i)=>{
    const r=i+2, count=method=>`COUNT(${chunkRange(method,r)})`;
    const mean=(method,countColumn)=>`=IF($${countColumn}${r}=0,"",SUM(${chunkRange(method,r)})/$${countColumn}${r})`;
    const abstain=(method,countColumn)=>`=IF($${countColumn}${r}=0,"",SUM(${chunkRange(method,r,true)}))`;
    return [mean('vader-sentiment','O'),mean('cardiff-sentiment','U'),mean('deberta-stance','V'),`=${count('vader-sentiment')}`,abstain('cardiff-sentiment','U'),abstain('deberta-stance','V')];
  });
  sheets.speeches.getRange(`L2:Q${records.speeches.length+1}`).formulas=formulas;
  sheets.speeches.getRange(`U2:V${records.speeches.length+1}`).formulas=records.speeches.map((_,i)=>['cardiff-sentiment','deberta-stance'].map(method=>`=COUNT(${chunkRange(method,i+2)})`));
}
summary.showGridLines=false; summary.tabColor=navy;
summary.getRange('A1:S180').format.font={name:font,size:10,color:ink};
summary.getRange('A1:S180').format.columnWidth=13;summary.getRange('A1:S180').format.rowHeight=23;
summary.getRange('A2').values=[['Debate evaluation']]; summary.getRange('A2').format.font={name:font,size:16,bold:true,color:navy};
summary.getRange('A4').values=[[`Five rounds. Three topics. Hansard excerpts per party. Repetition IDs: ${[...new Set(records.runs.map(r=>r[7]))].sort().join(', ')}.`]];
summary.getRange('A5').values=[['Means include complete debates only. Original attempts are archived in the evidence bundle. Higher disruptive rhetoric means more disruption.']];
summary.getRange('A4').format.font={italic:true,color:muted}; summary.getRange('A5').format.font={italic:true,color:muted};
const runsBySummary=new Map(),rubricBySummary=new Map(),speechesBySummary=new Map();
const add=(map,key,row)=>{const rows=map.get(key)??[];rows.push(row);map.set(key,rows);};
for(const row of records.runs) add(runsBySummary,row[column('runs','Summary key')],row);
for(const row of records.rubric) add(rubricBySummary,row[column('rubric','Summary key')],row);
for(const row of records.speeches) add(speechesBySummary,row[column('speeches','Summary key')],row);
const modelOrder=[...new Set(records.runs.map(row=>row[38]))];
const combinations=[...runsBySummary.values()].map(rows=>{const r=rows[0];return [r[34],r[38],r[6],r[column('runs','Strategy condition ID')]];}).sort((a,b)=>a[0].localeCompare(b[0])||Number(strategies.get(b[3]).label==='NONE')-Number(strategies.get(a[3]).label==='NONE')||modelOrder.indexOf(a[1])-modelOrder.indexOf(b[1])||strategies.get(a[3]).label.localeCompare(strategies.get(b[3]).label)||a[2]-b[2]);
const replicationCoverage=combinations.map(([group,model,h,strategyId])=>[...new Set(runsBySummary.get(summaryKey(group,model,h,strategyId)).filter(row=>row[10]==='COMPLETE').map(row=>row[7]))].sort((a,b)=>a-b));
summary.getRange('A6').values=[[descriptiveConditions.length?'Blocks 4–5 use descriptive historical context. Batch 2 descriptive separates assigned and other MPs by topic; NONE here pools topics.':[...strategies.values()].some(s=>s.label!=='NONE')?'NONE pools three topics; named assignments are topic-specific. Use matched tables or filter Topic for direct comparisons.':'Each model / strategy condition / Hansard level has separate means and repetition coverage. Use source-table filters for topic or party comparisons.']];
summary.getRange('A6').format.font={italic:true,color:muted};
const summaryHead=['Judge group','Model','Strategy condition','Adversarial parties','Hansard excerpts','Complete debates','Consistency','Logical reasoning','Responsiveness','Relevance','Disruptive rhetoric','VADER compound','Cardiff positive','DeBERTa support','Repetition IDs','Repetitions','Strategy condition ID','Strategy config SHA256','Summary key'];
summary.getRange('A7:S7').values=[summaryHead];
summary.getRange('A7:S7').format={fill:navy,font:{name:font,size:10,bold:true,color:'#FFFFFF'},wrapText:true,rowHeight:50,horizontalAlignment:'center',verticalAlignment:'center'};
summary.getRange('B1:B180').format.columnWidth=26;
summary.getRange('C1:C180').format.columnWidth=42;
summary.getRange('D1:D180').format.columnWidth=21;
summary.getRange('I1:I180').format.columnWidth=15;
summary.getRange('Q7:S'+(7+combinations.length)).format.columnWidth=72;
const rrange=col=>`'Rubric scores'!$${col}$2:$${col}$${rubEnd}`;
const srange=col=>`'Speeches'!$${col}$2:$${col}$${speechEnd}`;
const summaryRows=combinations.map(([group,model,h,strategyId],i)=>{const strategy=strategies.get(strategyId);return [group,model,strategy.label,strategy.parties,h,...Array(9).fill(null),replicationCoverage[i].join(', '),replicationCoverage[i].length,strategyId,strategy.configSha256,summaryKey(group,model,h,strategyId)];});
summary.getRange(`A8:S${7+combinations.length}`).values=summaryRows;
const runKeyCol=letter(column('runs','Summary key')+1),rubKeyCol=letter(column('rubric','Summary key')+1),speechKeyCol=letter(column('speeches','Summary key')+1);
const summaryFormulas=combinations.map((_,i)=>{
  const r=i+8;
  const count=`=COUNTIFS('Runs'!$${runKeyCol}$2:$${runKeyCol}$${runEnd},$S${r},'Runs'!$K$2:$K$${runEnd},"COMPLETE")`;
  const criteria=`${rrange(rubKeyCol)},$S${r},${rrange('J')},"OK",${rrange('W')},"COMPLETE",${rrange('I')}`;
  const rubric=metrics.map(metric=>`=IF(COUNTIFS(${criteria},"${metric}")=0,"",SUMIFS(${rrange('K')},${criteria},"${metric}")/COUNTIFS(${criteria},"${metric}"))`);
  const local=[['L','O'],['M','U'],['N','V']].map(([col,countCol])=>`=IF(COUNTIFS(${srange(speechKeyCol)},$S${r},${srange('W')},"COMPLETE",${srange(countCol)},">0")=0,"",SUMIFS(${srange(col)},${srange(speechKeyCol)},$S${r},${srange('W')},"COMPLETE")/COUNTIFS(${srange(speechKeyCol)},$S${r},${srange('W')},"COMPLETE",${srange(countCol)},">0"))`);
  return [count,...rubric,...local];
});
summary.getRange(`F8:N${7+combinations.length}`).formulas=summaryFormulas;
summary.getRange(`G8:N${7+combinations.length}`).setNumberFormat('0.00');
summary.getRange(`A7:P${7+combinations.length}`).format.borders={bottom:{style:'thin',color:'#DCE2EB'}};
summary.getRange(`C8:D${7+combinations.length}`).format.wrapText=true;
summary.getRange(`O8:O${7+combinations.length}`).format.horizontalAlignment='center';
summary.getRange(`A8:P${7+combinations.length}`).format.autofitRows();
const totalRow=10+combinations.length;
summary.getRange(`A${totalRow}:F${totalRow+3}`).values=[['Recorded speeches',null,null,'Scored rubric observations',null,null],['Completed debates',null,null,'Standard API estimate (USD)',null,null],['Requested debates',records.runs.length,null,'Budget allowance used (USD)',null,null],['Batch status',[...new Set(sourceBatches.map(x=>x.state))].join(', '),null,cumulativeBudgetCeilingUsd==null?'Spending boundary':'Budget ceiling (USD)',null,cumulativeBudgetCeilingUsd??'Loaded account credit']];
summary.getRange(`B${totalRow}`).formulas=[[`=SUM('Runs'!J2:J${runEnd})`]];
summary.getRange(`F${totalRow}`).formulas=[[`=COUNTIFS(${rrange('J')},"OK")`]];
summary.getRange(`B${totalRow+1}`).formulas=[[`=COUNTIFS('Runs'!K2:K${runEnd},"COMPLETE")`]];
summary.getRange(`F${totalRow+1}`).formulas=[[`=SUM('Usage'!M2:M${Math.max(2,records.usage.length+1)})`]];
summary.getRange(`F${totalRow+2}`).formulas=[[`=SUM('Usage'!N2:N${Math.max(2,records.usage.length+1)})`]];
summary.getRange(`F${totalRow+1}:F${totalRow+3}`).setNumberFormat('$0.000000');
summary.getRange(`A${totalRow}:A${totalRow+3}`).format.columnWidth=23;
summary.getRange(`D${totalRow}:E${totalRow+3}`).format.columnWidth=17;
// Separate rubric and local charts retain their original scales and condition identities.
let helperRow=totalRow+116;
const chartSources=[];
const firstGroup=[...groups.values()][0];
const chartConditions=[...new Map(combinations.filter(r=>r[0]===firstGroup && (!descriptiveConditions.length || descriptiveConditions.some(c=>c.modelKey===r[1] && (c.strategyId===r[3] || strategies.get(r[3]).label==='NONE')))).map(r=>[JSON.stringify([r[1],r[3]]),{model:r[1],strategyId:r[3]}])).values()];
const multiStrategy=new Set(chartConditions.map(c=>c.strategyId)).size>1;
const shortAssignments={'NZ_FIRST=TOPIC_DERAILMENT':'NZF derail','ACT=STRAW_MAN':'ACT straw man','GREEN=PROCEDURAL_MANIPULATION':'Green procedural','ACT=TOPIC_DERAILMENT':'ACT derail','NATIONAL=TOPIC_DERAILMENT':'National derail','GREEN=STRAW_MAN':'Green straw man','LABOUR=STRAW_MAN':'Labour straw man','NZ_FIRST=PROCEDURAL_MANIPULATION':'NZF procedural','ACT=PROCEDURAL_MANIPULATION':'ACT procedural'};
const chartLabels=chartConditions.map(c=>{
  if(!multiStrategy) return c.model;
  const model=c.model==='qwen3:8b [ollama]'?'Qwen':c.model==='gpt-6-luna [openai]'?'Luna':c.model;
  const label=strategies.get(c.strategyId).label;
  return model+': '+(shortAssignments[label]??label);
});
const chartColors=['#315A96','#CB7435','#357A50','#7056A2','#2C8796','#9B466D','#B0453B','#96782D'];
const chartDefinitions=[...metrics.map((metric,i)=>({metric,title:metricLabels[i]+' (0–4)',column:7+i,min:0,max:4,section:'rubric',row:totalRow+7+Math.floor(i/2)*18,side:i%2})),...['VADER mean compound (−1 to 1)','Cardiff mean positive (0–1)','DeBERTa mean support (0–1)'].map((title,i)=>({metric:methods[i],title,column:12+i,min:i===0?-1:0,max:1,section:'local',row:totalRow+67+Math.floor(i/2)*18,side:i%2}))];
summary.getRange(`A${totalRow+5}`).values=[[descriptiveConditions.length?'Luna blocks 4–5 and pooled historical cooperative context — descriptive, all five MPs.':'LLM rubric scores — '+firstGroup+'; separate model / strategy series, pooled across topics and completed repetitions.']];
summary.getRange(`A${totalRow+5}`).format.font={name:font,size:14,bold:true,color:navy};
summary.getRange(`A${totalRow+64}`).values=[['Local sentiment and stance scores']];
summary.getRange(`A${totalRow+64}`).format.font={name:font,size:14,bold:true,color:navy};
summary.getRange(`A${totalRow+65}`).values=[['Means average per-speech chunk scores; available abstained transformer scores are included. Sentiment and stance measure distinct properties.']];
summary.getRange(`A${totalRow+65}`).format.font={italic:true,color:muted};
for(const definition of chartDefinitions) {
  const helper=[['Excerpts',...chartLabels],...['0','10','100'].map(h=>[`${h} excerpts`,...chartLabels.map(()=>null)])];
  summary.getRange(`A${helperRow}:${letter(chartConditions.length+1)}${helperRow+3}`).values=helper;
  summary.getRange(`B${helperRow+1}:${letter(chartConditions.length+1)}${helperRow+3}`).formulas=[0,10,100].map(h=>chartConditions.map(c=>{
    const index=combinations.findIndex(r=>r[0]===firstGroup && r[1]===c.model && r[2]===h && r[3]===c.strategyId);
    return index<0?'=""':`=IF(ISNUMBER(${letter(definition.column)}${index+8}),${letter(definition.column)}${index+8},"")`;
  }));
  const chart=summary.charts.add('line',summary.getRange(`A${helperRow}:${letter(chartConditions.length+1)}${helperRow+3}`));
  chartSources.push({chart,helperRow,...definition});
  chart.title=definition.title;chart.titleTextStyle.typeface=font;chart.titleTextStyle.fontSize=14;
  chart.legend={position:'top',textStyle:{typeface:font,fontSize:11}};
  chart.xAxis={axisType:'textAxis',textStyle:{typeface:font,fontSize:11}};
  chart.yAxis={min:definition.min,max:definition.max,numberFormatCode:'0.0',numberFormatSourceLinked:false,textStyle:{typeface:font,fontSize:11}};
  chart.setPosition((definition.side?'H':'A')+definition.row,(definition.side?'P':'G')+(definition.row+15));
  chart.series.items.forEach((series,i)=>{series.line={fill:chartColors[i%chartColors.length],style:'solid',width:2};});
  helperRow+=6;
}
const descriptiveHeaders=['Assignment block','Topic','Assigned party','Hansard excerpts','Metric / method','Scale','Assigned n','Assigned mean','Historical same-party n','Historical same-party mean','Other MPs n','Other MPs mean','Historical other MPs n','Historical other MPs mean','Assigned run ID','Model key','Topic key','Metric / method ID'];
const descriptiveRows=[], descriptiveSources=[];
if(descriptiveSheet) {
  const definitions=[...metrics.map((id,i)=>({key:'rubric',id,label:metricLabels[i],scale:'0–4',value:'Score'})),
    ...methods.map((id,i)=>({key:'speeches',id,label:['VADER compound','Cardiff positive','DeBERTa support'][i],scale:i===0?'−1 to 1':'0–1',value:['VADER mean compound','Cardiff mean positive','DeBERTa mean support'][i],chunks:['VADER chunks','Cardiff chunks','DeBERTa chunks'][i]}))];
  const sorted=descriptiveConditions.slice().sort((a,b)=>a.assignmentBlock-b.assignmentBlock || ['retirement','four_day_week','nuclear_power'].indexOf(a.topicKey)-['retirement','four_day_week','nuclear_power'].indexOf(b.topicKey) || a.groundingCount-b.groundingCount || a.modelKey.localeCompare(b.modelKey));
  for(const c of sorted) for(const d of definitions) {
    descriptiveRows.push([c.assignmentBlock,{retirement:'Retirement age',four_day_week:'Four-day workweek',nuclear_power:'Nuclear power'}[c.topicKey]??c.topicTitle,c.actor,c.groundingCount,d.label,d.scale,...Array(8).fill(null),c.runId,c.modelKey,c.topicKey,d.id]);
    descriptiveSources.push({condition:c,definition:d});
  }
  const end=7+descriptiveRows.length;
  descriptiveSheet.showGridLines=false;descriptiveSheet.tabColor=navy;
  descriptiveSheet.getRange(`A1:R${end}`).format={font:{name:font,size:10,color:ink},columnWidth:14,rowHeight:24,verticalAlignment:'center'};
  descriptiveSheet.getRange('A2').values=[['Adversarial testing batch 2 — Luna']];
  descriptiveSheet.getRange('A2').format.font={name:font,size:16,bold:true,color:navy};
  descriptiveSheet.getRange('A4').values=[[`${descriptiveConditions.length} new debates; ${descriptiveConditions.length*25} speeches. Assignment blocks ${[...new Set(descriptiveConditions.map(c=>c.assignmentBlock))].join(', ')}.`]];
  descriptiveSheet.getRange('A5').values=[['Descriptive context: historical cooperative blocks 1–3 have different speaking orders. No matched controls or causal effects are claimed.']];
  descriptiveSheet.getRange('A6').values=[['Rubric n counts participant-topic ratings; local n counts speeches. Scores retain their separate scales; higher disruptive rhetoric means more disruption.']];
  descriptiveSheet.getRange('A4:A6').format.font={name:font,size:10,italic:true,color:muted};
  descriptiveSheet.getRange(`A7:R${end}`).values=[descriptiveHeaders,...descriptiveRows];
  descriptiveSheet.getRange('A7:R7').format={fill:navy,font:{name:font,size:10,bold:true,color:'#FFFFFF'},wrapText:true,rowHeight:62,horizontalAlignment:'center',verticalAlignment:'center'};
  const widths=[12,24,16,12,22,12,12,13,16,17,12,13,17,18,40,27,22,27];
  widths.forEach((width,i)=>{descriptiveSheet.getRange(`${letter(i+1)}1:${letter(i+1)}${end}`).format.columnWidth=width;});
  descriptiveSheet.tables.add(`A7:R${end}`,true,'DescriptiveComparisons').style='TableStyleMedium2';
  descriptiveSheet.freezePanes.freezeRows(7);descriptiveSheet.freezePanes.freezeColumns(4);
  const quoted=text=>`"${text.replaceAll('"','""')}"`;
  const formulas=descriptiveSources.map(({condition:c,definition:d},i)=>{
    const r=i+8, key=d.key, source=sheetNames[key], last=key==='rubric'?rubEnd:speechEnd;
    const range=label=>`'${source}'!$${letter(column(key,label)+1)}$2:$${letter(column(key,label)+1)}$${last}`;
    const common=[[range('Model key'),`$P${r}`],[range('Topic key'),`$Q${r}`],[range('Hansard excerpts'),`$D${r}`],[range('Generation outcome'),'"COMPLETE"'],[range('Evaluation group'),quoted(c.group)],
      ...(key==='rubric'?[[range('Status'),'"OK"'],[range('Metric'),`$R${r}`]]:[[range(d.chunks),'">0"']])];
    const subsets=[[[range('Run ID'),`$O${r}`],[range('Strategy role'),'"ASSIGNED"']],[[range('Strategy condition'),'"NONE"'],[range('Party'),`$C${r}`]],
      [[range('Run ID'),`$O${r}`],[range('Strategy role'),'"OTHER"']],[[range('Strategy condition'),'"NONE"'],[range('Party'),`"<>"&$C${r}`]]];
    return subsets.flatMap((subset,j)=>{const criteria=[...common,...subset].flat().join(','),countCol=letter(7+j*2);return [`=COUNTIFS(${criteria})`,`=IF($${countCol}${r}=0,"",SUMIFS(${range(d.value)},${criteria})/$${countCol}${r})`];});
  });
  descriptiveSheet.getRange(`G8:N${end}`).formulas=formulas;
  for(const col of ['G','I','K','M']) descriptiveSheet.getRange(`${col}8:${col}${end}`).setNumberFormat('0');
  for(const col of ['H','J','L','N']) descriptiveSheet.getRange(`${col}8:${col}${end}`).setNumberFormat('0.000');
}
stage('formulas-and-charts');
wb.recalculate();
const summaryValues=summary.getRange(`F8:N${7+combinations.length}`).values;
for(const row of summaryValues) for(const value of row) if(value!==null && value!=='' && (typeof value!=='number'||!Number.isFinite(value))) throw new Error('Unexpected nonnumeric summary calculation: '+JSON.stringify(summaryValues));
const computedSpeechRows=records.speeches.length?sheets.speeches.getRange(`L2:V${records.speeches.length+1}`).values:[];
for(let i=0;i<records.speeches.length;i++) {
  const row=records.speeches[i], actual=computedSpeechRows[i].slice(0,3);
  for(let mi=0;mi<methods.length;mi++) {
    const chunks=sentenceGroups.get(`${row[1]}|${row[7]}|${methods[mi]}`)??[];
    const expected=chunks.length?chunks.reduce((v,x)=>v+x[[15,18,19][mi]],0)/chunks.length:'';
    if(expected==='' ? actual[mi]!=='' && actual[mi]!==null : typeof actual[mi]!=='number' || Math.abs(actual[mi]-expected)>1e-9) throw new Error('Speech mean disagrees with native sentence scores');
    if(mi>0 && chunks.length && computedSpeechRows[i][mi+3]!==chunks.filter(chunk=>chunk[25]===true).length) throw new Error('Speech abstention count disagrees with native flags');
  }
  row.splice(11,6,...computedSpeechRows[i].slice(0,6));
  row.splice(20,2,...computedSpeechRows[i].slice(9,11));
}
for(let i=0;i<combinations.length;i++) {
  const [group,model,h,strategyId]=combinations[i],key=summaryKey(group,model,h,strategyId);
  const selectedRubric=(rubricBySummary.get(key)??[]).filter(row=>row[9]==='OK' && row[22]==='COMPLETE');
  const selectedSpeeches=(speechesBySummary.get(key)??[]).filter(row=>row[22]==='COMPLETE');
  const mean=values=>values.length?values.reduce((a,b)=>a+b,0)/values.length:'';
  const expected=[runsBySummary.get(key).filter(row=>row[10]==='COMPLETE').length,...metrics.map(metric=>mean(selectedRubric.filter(row=>row[8]===metric).map(row=>row[10]))),...[11,12,13].map(col=>mean(selectedSpeeches.map(row=>row[col]).filter(value=>typeof value==='number')))];
  expected.forEach((value,j)=>{if(value===''?summaryValues[i][j]!=='' && summaryValues[i][j]!==null:Math.abs(summaryValues[i][j]-value)>1e-9) throw new Error('Summary mean disagrees with public scores');});
}
if(descriptiveSheet) {
  const actual=descriptiveSheet.getRange(`G8:N${7+descriptiveRows.length}`).values;
  descriptiveSources.forEach(({condition:c,definition:d},i)=>{
    const key=d.key, rows=records[key], at=label=>column(key,label);
    const valid=rows.filter(r=>r[at('Model key')]===c.modelKey && r[at('Topic key')]===c.topicKey && r[at('Hansard excerpts')]===c.groundingCount && r[at('Generation outcome')]==='COMPLETE' && r[at('Evaluation group')]===c.group && (key==='rubric'?r[at('Status')]==='OK' && r[at('Metric')]===d.id:r[at(d.chunks)]>0));
    const subsets=[valid.filter(r=>r[at('Run ID')]===c.runId && r[at('Strategy role')]==='ASSIGNED'),valid.filter(r=>r[at('Strategy condition')]==='NONE' && r[at('Party')]===c.actor),
      valid.filter(r=>r[at('Run ID')]===c.runId && r[at('Strategy role')]==='OTHER'),valid.filter(r=>r[at('Strategy condition')]==='NONE' && r[at('Party')]!==c.actor)];
    const expected=subsets.flatMap(selected=>[selected.length,selected.length?selected.reduce((sum,r)=>sum+r[at(d.value)],0)/selected.length:'']);
    expected.forEach((value,j)=>{if(value===''?actual[i][j]!=='' && actual[i][j]!==null:typeof actual[i][j]!=='number' || Math.abs(actual[i][j]-value)>1e-9) throw new Error('Descriptive comparison disagrees with public evidence');});
    descriptiveRows[i].splice(6,8,...actual[i]);
  });
}
// Supply saved numeric chart caches as well as editable source references.
for(const {chart,helperRow} of chartSources) for(let i=0;i<chart.series.items.length;i++) {
  const series=chart.series.items[i], source=series.formula, categories=series.categoryFormula;
  const values=summary.getRange(`${letter(i+2)}${helperRow+1}:${letter(i+2)}${helperRow+3}`).values.map(row=>row[0]);
  if(values.every(value=>typeof value==='number' && Number.isFinite(value))) series.values=values;
  series.categories=['0 excerpts','10 excerpts','100 excerpts'];
  if(series.formula!==source || series.categoryFormula!==categories) throw new Error('Saved chart cache disconnected its source cells');
}
const errors=await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:20},maxChars:2000});
console.log('FORMULA_SCAN '+errors.ndjson);
const view=await wb.inspect({kind:'table',range:`Summary!A7:P${7+combinations.length}`,include:'values,formulas',tableMaxRows:8,tableMaxCols:16,maxChars:2500});
console.log('SUMMARY '+view.ndjson);
if(summary.getRange(`B${totalRow}`).values[0][0]!==capturedSpeeches || summary.getRange(`F${totalRow}`).values[0][0]!==capturedRubric) throw new Error('Workbook counts do not reconcile to saved public evidence');
stage('recalculation-and-checks');
await fs.mkdir(output,{recursive:true});
for(const batchArgument of batchArguments) {
  const batch=path.resolve(batchArgument), m=await read(path.join(batch,'manifest.json'));
  for(const directory of ['transcripts','reports','raw-evaluation','archived-attempts']) {
    const source=path.join(batch,'public',directory), destination=path.join(output,'evidence',m.batchId,directory);
    const copyPublic=async (from,to)=>{
      await fs.mkdir(to,{recursive:true});
      for(const entry of await fs.readdir(from,{withFileTypes:true}).catch(e=>e.code==='ENOENT'?[]:Promise.reject(e))) {
        if(entry.isDirectory()) await copyPublic(path.join(from,entry.name),path.join(to,entry.name));
        else if(entry.isFile() && /\.(json|txt)$/.test(entry.name)) {
          const sourceFile=path.join(from,entry.name),destinationFile=path.join(to,entry.name);
          const sourceBytes=await fs.readFile(sourceFile);
          const existingBytes=await fs.readFile(destinationFile).catch(e=>e.code==='ENOENT'?null:Promise.reject(e));
          if(existingBytes==null || sha(sourceBytes)!==sha(existingBytes)) await fs.copyFile(sourceFile,destinationFile);
        }
      }
    };
    await copyPublic(source,destination);
  }
  await fs.writeFile(path.join(output,'evidence',m.batchId,'index.json'),JSON.stringify({batchId:m.batchId,state:m.state,cells:m.cells.map(c=>({cellId:c.cellId,runId:c.runId,previousRunIds:c.previousRunIds??[],topic:c.topicTitle,groundingCount:c.groundingCount,repetition:c.repetition,assignmentBlock:c.assignmentBlock??(c.settings.members.some(v=>v.strategy!=='NONE')?c.repetition:null),comparisonMode:c.comparisonMode??m.comparisonMode??(c.settings.members.every(v=>v.strategy==='NONE')?'cooperative':'matched'),model:m.models[c.modelPreset],judge:m.models['study-judge'],thinkingEnabled:m.ollamaThinkingEnabled,localJobId:c.localJobId,rubricJobId:c.rubricJobId,previousLocalJobId:c.previousLocalJobId,previousRubricJobId:c.previousRubricJobId,previousLocalJobIds:c.previousLocalJobIds??[]}))},null,2));
}
if(descriptiveRows.length) {
  const quote=value=>value==null?'':typeof value==='string'?`"${value.replaceAll('"','""')}"`:String(value);
  await fs.writeFile(path.join(output,'descriptive-comparisons.csv'),[descriptiveHeaders,...descriptiveRows].map(row=>row.map(quote).join(',')).join('\r\n'),'utf8');
  const preview=await wb.render({sheetName:'Batch 2 descriptive',range:'A1:N18',scale:1.25,format:'png'});
  await fs.writeFile(path.join(output,'preview-Batch-2-descriptive.png'),new Uint8Array(await preview.arrayBuffer()));
}
for(const [key,rows] of Object.entries(records)) {
  const quote=value=>value==null?'':typeof value==='string'?`"${value.replaceAll('"','""')}"`:value instanceof Date?value.toISOString():String(value);
  await fs.writeFile(path.join(output,key+'.csv'),[headers[key],...rows].map(row=>row.map(quote).join(',')).join('\r\n'),'utf8');
}
for(const sheetName of ['Summary',...Object.values(sheetNames)]) {
  const range=sheetName==='Summary'?`A1:P${totalRow+4}`:sheetName==='Protocol'?'A1:C12':sheetName==='Speeches'?'C1:Q7':sheetName==='Rubric scores'?'C1:M9':sheetName==='Sentence metrics'?'C1:Z7':sheetName==='Runs'?'C1:T7':sheetName==='Strategy assignments'?'D1:O7':'C1:N9';
  const preview=await wb.render({sheetName,range,scale:1.25,format:'png'});
  await fs.writeFile(path.join(output,`preview-${sheetName.replaceAll(' ','-')}.png`),new Uint8Array(await preview.arrayBuffer()));
}
if(descriptiveRows.length) for(const key of ['runs','rubric','speeches','sentences','assignments']) {
  const start=letter(column(key,'Assignment block')+1),end=letter(column(key,'Comparison mode')+1);
  const preview=await wb.render({sheetName:sheetNames[key],range:`${start}1:${end}7`,scale:1.5,format:'png'});
  await fs.writeFile(path.join(output,`preview-blocks-${key}.png`),new Uint8Array(await preview.arrayBuffer()));
}
for(const row of [...new Set(chartDefinitions.map(c=>c.row))]) {
  const preview=await wb.render({sheetName:'Summary',range:`A${row}:P${row+15}`,scale:1,format:'png'});
  await fs.writeFile(path.join(output,`preview-charts-${row}.png`),new Uint8Array(await preview.arrayBuffer()));
}
stage('previews-and-evidence');
const file=await SpreadsheetFile.exportXlsx(wb);
await file.save(path.join(output,'debate-evaluation.xlsx'));
stage('xlsx-saved');
const result={state:'EXPORTED',batches:sourceBatches,records:Object.fromEntries(Object.entries(records).map(([k,v])=>[k,v.length])),headers,capturedSpeeches,capturedRubric,summaryHeaders:summaryHead,summaryCombinations:combinations,summaryValues,replicationCoverage,strategyConditions:[...strategies.values()].map(({speaker,...strategy})=>strategy),descriptiveHeaders,descriptiveRows,descriptiveDebates:descriptiveConditions.length,cumulativeBudgetCeilingUsd,charts:chartDefinitions.length,chartDefinitions,chartConditions,chartLabels,chartColors,exportStageSeconds:timings,generatedAt:new Date().toISOString(),workbook:path.join(output,'debate-evaluation.xlsx'),xlsxSha256:sha(await fs.readFile(path.join(output,'debate-evaluation.xlsx')))};
await fs.writeFile(path.join(output,'workbook-verification.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify({state:result.state,records:result.records,charts:result.charts,exportStageSeconds:timings,workbook:result.workbook,xlsxSha256:result.xlsxSha256}));

// Rebuild one extensible workbook from public evidence and non-secret usage in selected batches.
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const [outputArgument, ...batchArguments] = process.argv.slice(2);
if (!outputArgument || !batchArguments.length) throw new Error('Use OUTPUT_DIRECTORY BATCH_DIRECTORY [BATCH_DIRECTORY ...]');
const output = path.resolve(outputArgument);
const runtimeRequire = createRequire(path.join(output, 'artifact-loader.cjs'));
const {Workbook, SpreadsheetFile} = await import(pathToFileURL(runtimeRequire.resolve('@oai/artifact-tool')).href);
const sha = value => crypto.createHash('sha256').update(value).digest('hex');
const read = async file => JSON.parse((await fs.readFile(file, 'utf8')).replace(/^\uFEFF/,''));
const optional = async file => { try { return await read(file); } catch(e) { if(e.code==='ENOENT') return null; throw e; } };
const metrics = ['consistency','logical_reasoning','responsiveness','relevance','rhetorical_tactics'];
const metricLabels = ['Consistency','Logical reasoning','Responsiveness','Relevance','Disruptive rhetoric'];
const methods = ['vader-sentiment','cardiff-sentiment','deberta-stance'];
const records={runs:[],rubric:[],sentences:[],speeches:[],usage:[],protocol:[]};
const seenRuns=new Set(), sourceBatches=[], groups=new Map(), budgetGroups=new Map();
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
  const identity=await optional(path.join(batch,'operations/code-identity.json'));
  const replacementIdentity=await optional(path.join(batch,'operations/replacement-code-identity.json'));
  const attempts=new Map();
  sourceBatches.push({batchId:m.batchId,state:m.state,manifest:batchArgument,cells:m.cells.length,budgetGroup,cumulativeBudgetLimitUsd:budgetCeiling,batchAllowanceUsd:m.budgetLimitUsd,priorAllowanceUsd:m.priorBudgetCommittedUsd??0});
  for (const call of ledger.calls) {
    records.usage.push([m.batchId,call.cellId,call.callId,call.purpose,call.provider,call.model,call.status,call.usage?.inputTokens??null,call.usage?.outputTokens??null,call.inputTokenBound,call.outputTokenCap,call.latencyMillis==null?null:call.latencyMillis/1000,call.standardPriceEstimateUsd??null,call.budgetUsd,call.startedAt?new Date(call.startedAt):null,call.endedAt?new Date(call.endedAt):null,call.requestSha256,call.providerError??null,null,null]);
  }
  for (const c of m.cells) {
    const settings=m.models[c.modelPreset], runId=c.runId??null;
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
    const calls=ledger.calls.filter(x=>x.cellId===c.cellId);
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
}
records.protocol.push(['All batches','Metric grain','Rubric: one participant/topic/metric. Sentiment and stance: sentence chunks. Speeches: mean of available chunks for that one speech.'],['All batches','Separate scales','Rubric 0–4. Higher rhetorical-tactics values mean more disruption. VADER compound −1 to 1. Cardiff/DeBERTa uncalibrated model scores 0–1.'],['All batches','Uncertainty','Abstained transformer chunks retain their scores and an explicit abstained field. Speech means include available raw scores, including abstained chunks.'],['All batches','Missing values','Blank numerical scores mean unavailable or insufficient evidence. They are never changed to zero. Chunk counts are zero when no valid chunks are available. Pending and failed jobs stay visible in Runs.'],['All batches','Research scope','Repetition coverage is listed per model in Summary. Speeches and sentences within a debate are dependent observations. Automated judgments have not been validated against human ratings.'],['All batches','Graph construction','Use the named RubricScores, SpeechData or SentenceMetrics tables in Excel PivotTables. Filter by provider, model, topic, Hansard count, repetition, party and evaluation group. Keep each metric and method on its own scale. The five example charts show the first judge group and pool available complete repetitions.'],['All batches','Append later runs','Regenerate from the original and additional batch directories with this builder. Original run IDs are retained and duplicates are rejected. Additional models/repetitions create new rows, not new provider worksheets.'],['All batches','Cost meaning','Usage and Runs token/cost totals include all attempts, including archived failures. Related batches share one cumulative ceiling, with prior allowance reserved before a new batch starts. Standard-price estimate uses reported tokens and uncached Standard rates, excluding cache discounts. Budget allowance uses conservative prices and retains full reservations for failures/unknown usage. Neither is an account invoice.'],['All batches','Summary means','Complete debates only. Rubric means average participant/topic scores. Native method means average per-speech chunk means. Automated ratings and model confidence scores are separate measurements.']);

const headers={
  runs:['Cell ID','Run ID','Model','Provider','Topic key','Topic','Hansard excerpts','Repetition','Rounds','Speeches','Outcome','Local evaluation','Rubric evaluation','Duration (s)','Generation input tokens (all attempts)','Generation output tokens (all attempts)','Judge input tokens (all attempts)','Judge output tokens (all attempts)','Standard estimate (USD)','Budget allowance (USD)','Temperature','Reasoning','Completion cap','Judge model','Judge reasoning','Judge temperature','Judge cap','Speaking order','Transcript SHA256','Corpus SHA256','Local model digest','Requested context','Native context','Cell state','Evaluation group','Batch','Attempt count','Archived run IDs','Model key'],
  rubric:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Participant ID','Metric','Status','Score','Minimum','Maximum','Explanation','Evidence turn IDs','Judge model','Judge reasoning','Rubric version','Judge config SHA256','Transcript SHA256','Evaluation group','Batch','Generation outcome'],
  sentences:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Turn ID','Method','Sentence index','Start offset','End offset','Label','Item status','Item error','VADER compound','Negative','Neutral','Positive','Support','Oppose','Unrelated','Max score','Margin','Entropy','Abstained','Sentence text','Method model','Revision','Implementation','Config SHA256','Transcript SHA256','Method status','Method error','Evaluation group','Batch'],
  speeches:['Cell ID','Run ID','Model','Topic key','Hansard excerpts','Repetition','Party','Turn ID','Round','Speaking position','Speech text','VADER mean compound','Cardiff mean positive','DeBERTa mean support','VADER chunks','Cardiff abstained chunks','DeBERTa abstained chunks','Transcript SHA256','Evaluation group','Batch','Cardiff chunks','DeBERTa chunks','Generation outcome'],
  usage:['Batch','Cell ID','Call ID','Purpose','Provider','Model','Status','Input tokens','Output tokens','Input token bound','Output token cap','Latency (s)','Standard estimate (USD)','Budget allowance (USD)','Started UTC','Ended UTC','Request SHA256','Provider error','Attempt run ID','Transcript selection'],
  protocol:['Batch','Parameter','Value']
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
// Project original chunk scores into their speech row. Row-local formulas remain
// correct when either source table is sorted and avoid repeated full-table scans.
const sentenceGroups=new Map();
for(const row of records.sentences) if(row[13]==='ok' && row[9]!=null) {const key=JSON.stringify([row[1],row[7],row[8]]);if(!sentenceGroups.has(key)) sentenceGroups.set(key,[]);sentenceGroups.get(key).push(row);}
const chunkColumns={};
for(let mi=0;mi<methods.length;mi++) {
  const method=methods[mi],width=Math.max(1,...[...sentenceGroups.entries()].filter(([key])=>JSON.parse(key)[2]===method).map(([,rows])=>rows.length));
  chunkColumns[method]={width,scoreStart:headers.speeches.length+1};
  for(let i=1;i<=width;i++) headers.speeches.push(`${['VADER compound','Cardiff Positive','DeBERTa Support'][mi]} chunk ${i}`);
}
for(const method of ['cardiff-sentiment','deberta-stance']) {
  chunkColumns[method].flagStart=headers.speeches.length+1;
  for(let i=1;i<=chunkColumns[method].width;i++) headers.speeches.push(`${method==='cardiff-sentiment'?'Cardiff':'DeBERTa'} abstained chunk ${i}`);
}
for(const row of records.speeches) {
  for(let mi=0;mi<methods.length;mi++) {
    const chunks=sentenceGroups.get(JSON.stringify([row[1],row[7],methods[mi]]))??[];
    row.push(...Array.from({length:chunkColumns[methods[mi]].width},(_,i)=>chunks[i]?.[[15,18,19][mi]]??null));
  }
  for(const method of ['cardiff-sentiment','deberta-stance']) {
    const chunks=sentenceGroups.get(JSON.stringify([row[1],row[7],method]))??[];
    row.push(...Array.from({length:chunkColumns[method].width},(_,i)=>chunks[i]?.[25]==null?null:chunks[i][25]?1:0));
  }
}
records.protocol.push(['All batches','Speech chunk projection','Speeches projects original native chunk scores and abstention flags into appended columns. Flags use 1=true, 0=false, blank=unavailable; Sentence metrics retains original booleans. Means/counts use row-local SUM/COUNT formulas; the full Sentence metrics table retains text, offsets, labels, uncertainty and provenance. Sorting either table preserves the speech calculations.']);
for(const [key,rows] of Object.entries(records)) for(const row of rows) if(row.length!==headers[key].length) throw new Error(`Wrong final row width: ${key}`);
const wb=Workbook.create();
const summary=wb.worksheets.add('Summary');
const sheetNames={speeches:'Speeches',rubric:'Rubric scores',sentences:'Sentence metrics',runs:'Runs',usage:'Usage',protocol:'Protocol'};
const tableNames={speeches:'SpeechData',rubric:'RubricScores',sentences:'SentenceMetrics',runs:'StudyRuns',usage:'ProviderUsage',protocol:'StudyProtocol'};
const sheets={};
const letter=n=>{let s='';while(n){n--;s=String.fromCharCode(65+n%26)+s;n=Math.floor(n/26);}return s;};
const font='Arial', ink='#233042', navy='#243B64', muted='#657187';
for(const key of Object.keys(sheetNames)) {
  const sheet=wb.worksheets.add(sheetNames[key]); sheets[key]=sheet; sheet.showGridLines=false;
  const rows=records[key];
  sheet.getRange(`A1:${letter(headers[key].length)}${Math.max(2,rows.length+1)}`).values=[headers[key],...(rows.length?rows:[headers[key].map(()=>null)])];
  const used=sheet.getUsedRange(); used.format.font={name:font,size:10,color:ink}; used.format.verticalAlignment='center'; used.format.rowHeight=22;
  used.format.columnWidth=17;
  const head=sheet.getRange(`A1:${letter(headers[key].length)}1`);
  head.format={fill:navy,font:{name:font,size:10,bold:true,color:'#FFFFFF'},wrapText:true,rowHeight:48,horizontalAlignment:'center',verticalAlignment:'center'};
  const table=sheet.tables.add(`A1:${letter(headers[key].length)}${Math.max(2,rows.length+1)}`,true,tableNames[key]);
  table.style='TableStyleMedium2'; table.showBandedColumns=false; table.showFilterButton=true;
  sheet.freezePanes.freezeRows(1); sheet.freezePanes.freezeColumns(key==='protocol'?1:2);
  sheet.getRange(`A1:A${Math.max(2,rows.length+1)}`).format.columnWidth=44;
  if(key!=='protocol') sheet.getRange(`B1:B${Math.max(2,rows.length+1)}`).format.columnWidth=39;
  for(let column=0;column<headers[key].length;column++) {
    const label=headers[key][column], range=sheet.getRange(`${letter(column+1)}2:${letter(column+1)}${Math.max(2,rows.length+1)}`);
    if(/SHA256|digest|hash/i.test(label)) range.format.columnWidth=68;
    if(/tokens|context|excerpts|Repetition|Rounds|Speeches|index|offset|cap|Round$|position|chunks|Minimum|Maximum|Score$|Call ID/.test(label)) range.setNumberFormat('0');
    if(/compound|Positive|Negative|Neutral|Support|Oppose|Unrelated|Max score|Margin|Entropy|mean|Temperature|temperature/.test(label)) range.setNumberFormat('0.000');
    if(/USD/.test(label)) range.setNumberFormat('$0.000000');
    if(/\(s\)/.test(label)) range.setNumberFormat('0.0');
    if(/UTC/.test(label)) range.setNumberFormat('yyyy-mm-dd hh:mm:ss');
    if(['Topic','Speech text','Sentence text','Explanation','Value','Speaking order','Evidence turn IDs'].includes(label)) {range.format.columnWidth=110; range.format.wrapText=true;}
  }
  if(rows.length) sheet.getRange(`A2:${letter(headers[key].length)}${rows.length+1}`).format.autofitRows();
  if(key==='protocol') {sheet.getRange(`B1:B${rows.length+1}`).format.columnWidth=38; sheet.getRange(`C1:C${rows.length+1}`).format.columnWidth=110;sheet.getRange(`C2:C${rows.length+1}`).format.wrapText=true;sheet.getRange(`A2:C${rows.length+1}`).format.autofitRows();}
}
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
summary.getRange('A1:R94').format.font={name:font,size:10,color:ink};
summary.getRange('A1:R94').format.columnWidth=14;summary.getRange('A1:R94').format.rowHeight=23;
summary.getRange('A2').values=[['Debate evaluation']]; summary.getRange('A2').format.font={name:font,size:16,bold:true,color:navy};
summary.getRange('A4').values=[[`Five rounds. Three topics. Hansard excerpts per party. Repetition IDs: ${[...new Set(records.runs.map(r=>r[7]))].sort().join(', ')}.`]];
summary.getRange('A5').values=[['Means include complete debates only. Original attempts are archived in the evidence bundle. Higher disruptive rhetoric means more disruption.']];
summary.getRange('A4').format.font={italic:true,color:muted}; summary.getRange('A5').format.font={italic:true,color:muted};
const combinations=[];
for(const group of groups.values()) for(const model of [...new Set(records.runs.filter(r=>r[34]===group).map(r=>r[38]))]) for(const h of [...new Set(records.runs.filter(r=>r[34]===group && r[38]===model).map(r=>r[6]))].sort((a,b)=>a-b)) combinations.push([group,model,h]);
const replicationCoverage=combinations.map(([group,model,h])=>[...new Set(records.runs.filter(row=>row[34]===group && row[38]===model && row[6]===h && row[10]==='COMPLETE').map(row=>row[7]))].sort((a,b)=>a-b));
summary.getRange('A6').values=[['Summary means pool available complete repetitions; coverage differs by model and is listed in the last two columns.']];
summary.getRange('A6').format.font={italic:true,color:muted};
const summaryHead=['Judge group','Model','Hansard excerpts','Complete debates','Consistency','Logical reasoning','Responsiveness','Relevance','Disruptive rhetoric','VADER compound','Cardiff positive','DeBERTa support','Repetition IDs','Repetitions'];
summary.getRange('A7:N7').values=[summaryHead];
summary.getRange('A7:N7').format={fill:navy,font:{name:font,size:10,bold:true,color:'#FFFFFF'},wrapText:true,rowHeight:46,horizontalAlignment:'center',verticalAlignment:'center'};
summary.getRange('B1:B94').format.columnWidth=26;
const rrange=col=>`'Rubric scores'!$${col}$2:$${col}$${rubEnd}`;
const srange=col=>`'Speeches'!$${col}$2:$${col}$${speechEnd}`;
for(let i=0;i<combinations.length;i++) {
  const r=i+8; summary.getRange(`A${r}:C${r}`).values=[combinations[i]];
  summary.getRange(`M${r}:N${r}`).values=[[replicationCoverage[i].join(', '),replicationCoverage[i].length]];
  summary.getRange(`D${r}`).formulas=[[`=COUNTIFS('Runs'!$AM$2:$AM$${runEnd},$B${r},'Runs'!$G$2:$G$${runEnd},$C${r},'Runs'!$K$2:$K$${runEnd},"COMPLETE",'Runs'!$AI$2:$AI$${runEnd},$A${r})`]];
  const criteria=`${rrange('Y')},$B${r},${rrange('E')},$C${r},${rrange('U')},$A${r},${rrange('J')},"OK",${rrange('W')},"COMPLETE",${rrange('I')}`;
  summary.getRange(`E${r}:I${r}`).formulas=[metrics.map(metric=>`=IF(COUNTIFS(${criteria},"${metric}")=0,"",SUMIFS(${rrange('K')},${criteria},"${metric}")/COUNTIFS(${criteria},"${metric}"))`)];
  summary.getRange(`J${r}:L${r}`).formulas=[[['L','O'],['M','U'],['N','V']].map(([col,countCol])=>`=IF(COUNTIFS(${srange('Y')},$B${r},${srange('E')},$C${r},${srange('S')},$A${r},${srange('W')},"COMPLETE",${srange(countCol)},">0")=0,"",SUMIFS(${srange(col)},${srange('Y')},$B${r},${srange('E')},$C${r},${srange('S')},$A${r},${srange('W')},"COMPLETE")/COUNTIFS(${srange('Y')},$B${r},${srange('E')},$C${r},${srange('S')},$A${r},${srange('W')},"COMPLETE",${srange(countCol)},">0"))`)];
}
summary.getRange(`E8:L${7+combinations.length}`).setNumberFormat('0.00');
summary.getRange(`A7:N${7+combinations.length}`).format.borders={bottom:{style:'thin',color:'#DCE2EB'}};
const totalRow=10+combinations.length;
summary.getRange(`A${totalRow}:F${totalRow+3}`).values=[['Recorded speeches',null,null,'Scored rubric observations',null,null],['Completed debates',null,null,'Standard API estimate (USD)',null,null],['Requested debates',records.runs.length,null,'Budget allowance used (USD)',null,null],['Batch status',[...new Set(sourceBatches.map(x=>x.state))].join(', '),null,'Budget ceiling (USD)',null,[...budgetGroups.values()].reduce((a,b)=>a+b,0)]];
summary.getRange(`B${totalRow}`).formulas=[[`=SUM('Runs'!J2:J${runEnd})`]];
summary.getRange(`F${totalRow}`).formulas=[[`=COUNTIFS(${rrange('J')},"OK")`]];
summary.getRange(`B${totalRow+1}`).formulas=[[`=COUNTIFS('Runs'!K2:K${runEnd},"COMPLETE")`]];
summary.getRange(`F${totalRow+1}`).formulas=[[`=SUM('Usage'!M2:M${Math.max(2,records.usage.length+1)})`]];
summary.getRange(`F${totalRow+2}`).formulas=[[`=SUM('Usage'!N2:N${Math.max(2,records.usage.length+1)})`]];
summary.getRange(`F${totalRow+1}:F${totalRow+3}`).setNumberFormat('$0.000000');
summary.getRange(`A${totalRow}:A${totalRow+3}`).format.columnWidth=23;
summary.getRange(`D${totalRow}:E${totalRow+3}`).format.columnWidth=17;
// Five independent metric charts. Cell references stay editable; no overall-quality chart.
let helperRow=totalRow+70, chartIndex=0;
const chartSources=[];
const firstGroup=[...groups.values()][0];
const chartModels=[...new Set(combinations.filter(r=>r[0]===firstGroup).map(r=>r[1]))];
for(let mi=0;mi<metrics.length;mi++) {
  const helper=[['Excerpts',...chartModels],...['0','10','100'].map(h=>[`${h} excerpts`,...chartModels.map(()=>null)])];
  summary.getRange(`A${helperRow}:${letter(chartModels.length+1)}${helperRow+3}`).values=helper;
  for(let j=0;j<3;j++) for(let k=0;k<chartModels.length;k++) {
    const index=combinations.findIndex(r=>r[0]===firstGroup && r[1]===chartModels[k] && r[2]===Number(['0','10','100'][j]));
    if(index>=0) summary.getRange(`${letter(k+2)}${helperRow+j+1}`).formulas=[[`=IF(ISNUMBER(${letter(mi+5)}${index+8}),${letter(mi+5)}${index+8},"")`]];
  }
  const chart=summary.charts.add('line',summary.getRange(`A${helperRow}:${letter(chartModels.length+1)}${helperRow+3}`));
  chartSources.push({chart,helperRow});
  chart.title=metricLabels[mi]+' (0–4)';chart.titleTextStyle.typeface=font;chart.titleTextStyle.fontSize=14;
  chart.legend={position:'top',textStyle:{typeface:font,fontSize:11}};
  chart.xAxis={axisType:'textAxis',textStyle:{typeface:font,fontSize:11}};
  chart.yAxis={min:0,max:4,numberFormatCode:'0.0',numberFormatSourceLinked:false,textStyle:{typeface:font,fontSize:11}};
  const chartRow=totalRow+7+Math.floor(chartIndex/2)*18;
  chart.setPosition(chartIndex%2?'J'+chartRow:'A'+chartRow,chartIndex%2?'R'+(chartRow+15):'I'+(chartRow+15));
  const colors=['#315A96','#CB7435','#669966','#9066A5'];
  chart.series.items.forEach((series,i)=>{series.line={fill:colors[i%colors.length],style:'solid',width:2};});
  helperRow+=6;chartIndex++;
}
wb.recalculate();
const summaryValues=summary.getRange(`D8:L${7+combinations.length}`).values;
for(const row of summaryValues) for(const value of row) if(value!==null && value!=='' && (typeof value!=='number'||!Number.isFinite(value))) throw new Error('Unexpected nonnumeric summary calculation: '+JSON.stringify(summaryValues));
const computedSpeechRows=records.speeches.length?sheets.speeches.getRange(`L2:V${records.speeches.length+1}`).values:[];
for(let i=0;i<records.speeches.length;i++) {
  const row=records.speeches[i], actual=computedSpeechRows[i].slice(0,3);
  for(let mi=0;mi<methods.length;mi++) {
    const chunks=sentenceGroups.get(JSON.stringify([row[1],row[7],methods[mi]]))??[];
    const expected=chunks.length?chunks.reduce((v,x)=>v+x[[15,18,19][mi]],0)/chunks.length:'';
    if(expected==='' ? actual[mi]!=='' && actual[mi]!==null : typeof actual[mi]!=='number' || Math.abs(actual[mi]-expected)>1e-9) throw new Error('Speech mean disagrees with native sentence scores');
    if(mi>0 && chunks.length && computedSpeechRows[i][mi+3]!==chunks.filter(chunk=>chunk[25]===true).length) throw new Error('Speech abstention count disagrees with native flags');
  }
  row.splice(11,6,...computedSpeechRows[i].slice(0,6));
  row.splice(20,2,...computedSpeechRows[i].slice(9,11));
}
for(let i=0;i<combinations.length;i++) {
  const [group,model,h]=combinations[i];
  const selectedRubric=records.rubric.filter(row=>row[24]===model && row[4]===h && row[20]===group && row[9]==='OK' && row[22]==='COMPLETE');
  const selectedSpeeches=records.speeches.filter(row=>row[24]===model && row[4]===h && row[18]===group && row[22]==='COMPLETE');
  const mean=values=>values.length?values.reduce((a,b)=>a+b,0)/values.length:'';
  const expected=[records.runs.filter(row=>row[38]===model && row[6]===h && row[34]===group && row[10]==='COMPLETE').length,...metrics.map(metric=>mean(selectedRubric.filter(row=>row[8]===metric).map(row=>row[10]))),...[11,12,13].map(col=>mean(selectedSpeeches.map(row=>row[col]).filter(value=>typeof value==='number')))];
  expected.forEach((value,j)=>{if(value===''?summaryValues[i][j]!=='' && summaryValues[i][j]!==null:Math.abs(summaryValues[i][j]-value)>1e-9) throw new Error('Summary mean disagrees with public scores');});
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
const view=await wb.inspect({kind:'table',range:`Summary!A7:L${7+combinations.length}`,include:'values,formulas',tableMaxRows:10,tableMaxCols:12,maxChars:3500});
console.log('SUMMARY '+view.ndjson);
if(summary.getRange(`B${totalRow}`).values[0][0]!==capturedSpeeches || summary.getRange(`F${totalRow}`).values[0][0]!==capturedRubric) throw new Error('Workbook counts do not reconcile to saved public evidence');
await fs.mkdir(output,{recursive:true});
for(const batchArgument of batchArguments) {
  const batch=path.resolve(batchArgument), m=await read(path.join(batch,'manifest.json'));
  for(const directory of ['transcripts','reports','raw-evaluation','archived-attempts']) {
    const source=path.join(batch,'public',directory), destination=path.join(output,'evidence',m.batchId,directory);
    const copyPublic=async (from,to)=>{
      await fs.mkdir(to,{recursive:true});
      for(const entry of await fs.readdir(from,{withFileTypes:true}).catch(e=>e.code==='ENOENT'?[]:Promise.reject(e))) {
        if(entry.isDirectory()) await copyPublic(path.join(from,entry.name),path.join(to,entry.name));
        else if(entry.isFile() && /\.(json|txt)$/.test(entry.name)) await fs.copyFile(path.join(from,entry.name),path.join(to,entry.name));
      }
    };
    await copyPublic(source,destination);
  }
  await fs.writeFile(path.join(output,'evidence',m.batchId,'index.json'),JSON.stringify({batchId:m.batchId,state:m.state,cells:m.cells.map(c=>({cellId:c.cellId,runId:c.runId,previousRunIds:c.previousRunIds??[],topic:c.topicTitle,groundingCount:c.groundingCount,repetition:c.repetition,model:m.models[c.modelPreset],judge:m.models['study-judge'],thinkingEnabled:m.ollamaThinkingEnabled,localJobId:c.localJobId,rubricJobId:c.rubricJobId,previousLocalJobId:c.previousLocalJobId,previousRubricJobId:c.previousRubricJobId,previousLocalJobIds:c.previousLocalJobIds??[]}))},null,2));
}
for(const [key,rows] of Object.entries(records)) {
  const quote=value=>value==null?'':typeof value==='string'?`"${value.replaceAll('"','""')}"`:value instanceof Date?value.toISOString():String(value);
  await fs.writeFile(path.join(output,key+'.csv'),[headers[key],...rows].map(row=>row.map(quote).join(',')).join('\r\n'),'utf8');
}
for(const sheetName of ['Summary',...Object.values(sheetNames)]) {
  const range=sheetName==='Summary'?`A1:N${totalRow+4}`:sheetName==='Protocol'?'A1:C12':sheetName==='Speeches'?'C1:Q7':sheetName==='Rubric scores'?'C1:M9':sheetName==='Sentence metrics'?'C1:Z7':sheetName==='Runs'?'C1:T7':'C1:N9';
  const preview=await wb.render({sheetName,range,scale:1.25,format:'png'});
  await fs.writeFile(path.join(output,`preview-${sheetName.replaceAll(' ','-')}.png`),new Uint8Array(await preview.arrayBuffer()));
}
const chartPreview=await wb.render({sheetName:'Summary',range:`A${totalRow+7}:R${totalRow+22}`,scale:1,format:'png'});
await fs.writeFile(path.join(output,'preview-charts.png'),new Uint8Array(await chartPreview.arrayBuffer()));
for(const offset of [18,36]) {
  const preview=await wb.render({sheetName:'Summary',range:`A${totalRow+7+offset}:R${totalRow+22+offset}`,scale:1,format:'png'});
  await fs.writeFile(path.join(output,`preview-charts-${offset}.png`),new Uint8Array(await preview.arrayBuffer()));
}
const file=await SpreadsheetFile.exportXlsx(wb);
await file.save(path.join(output,'debate-evaluation.xlsx'));
const result={state:'EXPORTED',batches:sourceBatches,records:Object.fromEntries(Object.entries(records).map(([k,v])=>[k,v.length])),capturedSpeeches,capturedRubric,summaryCombinations:combinations,summaryValues,replicationCoverage,cumulativeBudgetCeilingUsd:[...budgetGroups.values()].reduce((a,b)=>a+b,0),charts:5,generatedAt:new Date().toISOString(),workbook:path.join(output,'debate-evaluation.xlsx'),xlsxSha256:sha(await fs.readFile(path.join(output,'debate-evaluation.xlsx')))};
await fs.writeFile(path.join(output,'workbook-verification.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result));

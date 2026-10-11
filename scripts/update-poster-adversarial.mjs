// T82: focused native-object revision of the supplied A1 poster, without inference.
// Usage: node scripts/update-poster-adversarial.mjs FACTS_JSON BUILD_DIR OUTPUT_DIR REVISION
import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const runtime=process.env.RUNTIME_NODE_MODULES ?? 'C:/Users/kiera/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const {FileBlob,PresentationFile}=await import(pathToFileURL(path.join(runtime,'@oai/artifact-tool/dist/artifact_tool.mjs')).href);
try {
const [factsPath,buildArg,outArg,revision='1']=process.argv.slice(2);
if (!factsPath || !buildArg || !outArg || !/^\d+$/.test(revision)) throw new Error('Supply facts, build, output and numeric revision');
const facts=JSON.parse(await fs.readFile(factsPath,'utf8'));
const BUILD=path.resolve(buildArg),OUT=path.resolve(outArg),ROOT=process.cwd();
const SKILL=process.env.PRESENTATION_SKILL_DIR ?? 'C:/Users/kiera/.codex/plugins/cache/openai-primary-runtime/presentations/26.1007.11041/skills/presentations';
const PYTHON=process.env.RUNTIME_PYTHON ?? 'C:/Users/kiera/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
process.env.RUNTIME_NODE_MODULES=runtime;
await fs.mkdir(BUILD,{recursive:true}); await fs.mkdir(OUT,{recursive:true});
const p=await PresentationFile.importPptx(await FileBlob.load(facts.source));
const s=p.slides.getItem(0);
if (p.slides.items.length!==1) throw new Error('Expected the supplied single-slide poster');
const inspect=await p.inspect({select:'$.slides[0].elements[*]',include:['id','name','text','kind','position']});
const sourceElements=inspect.records;
const byNumber=n=>p.resolve(sourceElements.find(e=>e.name===`Google Shape;${n};p1`).id);
const mm=n=>n*96/25.4,pt=n=>n*96/72;
const C={heading:'#1F4D36',text:'#1F2A24',muted:'#56615B',red:'#A92F24',paper:'#F4F1EA',pale:'#E6EFE9',line:'#D6D0C2'};
const changes=[];
function move(n,x,y,w,h){const o=byNumber(n);o.position={left:mm(x),top:mm(y),width:mm(w),height:mm(h)};return o;}
function edit(n,value,x,y,w,h,size=25,opts={}){
  const o=move(n,x,y,w,h);o.text=value;
  o.text.style={typeface:opts.font??'Calibri',fontSize:pt(size),color:opts.color??C.text,bold:opts.bold??false,italic:opts.italic??false,alignment:opts.align??'left',verticalAlignment:'top',insets:0,wrap:'square',autoFit:'none',lineSpacing:opts.spacing??1.08};
  changes.push({number:n,value,x,y,w,h,size});return o;
}
function add(name,value,x,y,w,h,size,opts={}){
  const o=s.shapes.add({name,geometry:'textbox',position:{left:mm(x),top:mm(y),width:mm(w),height:mm(h)},fill:'none',line:{fill:'none',width:0}});
  o.text=value;o.text.style={typeface:opts.font??'Calibri',fontSize:pt(size),color:opts.color??C.text,bold:opts.bold??false,alignment:'left',verticalAlignment:'top',insets:0,wrap:'square',autoFit:'none',lineSpacing:1.08};
  changes.push({name,value,x,y,w,h,size});return o;
}

add('Backend engine explanation',"At run start, the backend combines each MP's party profile and selected real Hansard excerpts [1] in a private system prompt, adding any assigned tactic. The scheduler takes turns sending that MP's prompt, public debate history and a turn cue to its model's chat completion endpoint. Each returned speech joins the public record for the next MP. Other agents cannot read the private prompts.",24.13,380.5,557.74,33,25.5);
const cards=[
  [24,60,61,'Real Hansard','500 reviewed excerpts\n0/10/100 per party'],
  [22,62,63,'Private setup','Frozen prompts\nand hidden tactics'],
  [20,64,65,'MP agents','Party personas\non cloud or local LLMs'],
  [17,66,67,'Debate engine','Scheduled turns\nand chat requests'],
  [18,68,69,'Saved evidence','Public transcript\nand separate reports'],
];
for (const [i,[box,heading,body,title,copy]] of cards.entries()){
  const x=24.13+i*114.5;
  move(box,x,418,99.74,39);
  edit(heading,title,x+3,421,93.74,11,27,{font:'Cambria',bold:true,color:C.heading,align:'center'});
  edit(body,copy,x+3.5,433.5,92.74,20,22.5,{color:C.muted,align:'center',spacing:1.02});
}
for (const [n,i] of [[23,0],[21,1],[19,2],[16,3]]) move(n,123.87+i*114.5,437.5,14.76,0);
for (const [head,body,x] of [[70,71,24.13],[72,73,214.13],[74,75,404.13]]){
  move(head,x,463,177.74,12);move(body,x,478,177.74,20);
}
// Restore an apostrophe damaged in the supplied file while preserving its definition.
edit(73,"Misrepresent an opponent's claim,\nthen attack the distorted version.",214.13,478,177.74,20,23.5);
p.delete(sourceElements.find(e=>e.name==='Google Shape;76;p1').id);

move(77,24.13,502,19.8,19.8);
edit(78,'Grounding and adversarial testing',51,500.5,530,25,53,{font:'Cambria',bold:true,color:C.heading});
const design=edit(79,'72 debates (1,800 speeches): 54 cooperative baselines (three repeats), plus 18 adversarial runs (one repeat, one assigned MP). GPT-6 Luna and Qwen3:8b [2] debate retirement, nuclear power and a four-day workweek at 0/10/100 excerpts, with five parties, five rounds and no interruptions.',24.13,529,557.74,23,24.5);
design.text.get('72 debates (1,800 speeches)').bold=true;
design.text.get('54 cooperative baselines').bold=true;
design.text.get('18 adversarial runs').bold=true;
const chartElements=sourceElements.filter(e=>e.kind==='chart').sort((a,b)=>a.position.left-b.position.left);
const {applyPresentationChartFont}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
p.delete(chartElements.map(e=>e.id));
const metrics=[['consistency','Consistency'],['logical_reasoning','Logical reasoning'],['responsiveness','Responsiveness']];
const displayAxes={consistency:{min:3.5,max:4.1,majorUnit:0.1},logical_reasoning:{min:2,max:3.5,majorUnit:0.5},responsiveness:{min:2,max:4,majorUnit:0.5}};
for (const [i,[metric,title]] of metrics.entries()){
  const series=[];
  for(const [model,name,color,pale,symbol] of [['gpt-6-luna','Luna',C.heading,'#8C9F91','circle'],['qwen3:8b','Qwen','#1E4F9B','#6386B9','square']]){
    for(const [key,label,stroke,width,size] of [['baseline','all baselines','solid',2,7],['cooperative','cooperative','solid',4,11],['adversarial','adversarial','dashed',4,12]]){
      const values=['0','10','100'].map(h=>key==='baseline'?facts.baselineValues[model][h][metric]:facts.matchedValues[model][h][metric][key]);
      const fill=key==='baseline'?pale:color;
      series.push({name:`${name}: ${label}`,values:values.map(v=>Number(v.toPrecision(15))),line:{style:stroke,fill,width},marker:{symbol,size,fill:key==='adversarial'?C.paper:fill,line:{fill,width:2}}});
    }
  }
  const chart=s.charts.add('line',{name:`Cooperative and adversarial ${metric}`,title,titleTextStyle:{fontSize:pt(26),bold:true,fill:C.heading},titlePlacement:'aboveChart',position:{left:mm(24.13+i*188),top:mm(555),width:mm(181.74),height:mm(100)},categories:['0','10','100'],series,hasLegend:true,legend:{position:'bottom',overlay:false,textStyle:{fontSize:pt(17),fill:C.text}},xAxis:{visible:true,position:'bottom',title:{text:'Hansard excerpts per party',textStyle:{fontSize:pt(17),fill:C.muted}},textStyle:{fontSize:pt(18),fill:C.text},line:{fill:C.line,width:1},majorGridlines:null},yAxis:{visible:true,position:'left',...displayAxes[metric],numberFormatCode:'0.0',textStyle:{fontSize:pt(18),fill:C.text},line:{fill:'none',width:0},majorGridlines:{style:'solid',fill:C.line,width:1}},lineOptions:{grouping:'standard',smooth:false},chartFill:C.paper,chartLine:{fill:'none',width:0},plotAreaFill:C.paper,plotAreaLine:{fill:'none',width:0}});
  applyPresentationChartFont(chart,{fontFamily:'Calibri'});
}
add('Truncated axis disclosure','Vertical axes are truncated. Rubric scores still run from 0 to 4.',24.13,659,557.74,8,20.5,{color:C.muted});
const baseline=edit(83,'Baseline: More Hansard gives no consistent gain. Qwen consistency falls 3.93 to 3.62 from 0 to 100 excerpts. Relevance endpoints stay at 4.00 (Luna) and 3.98 (Qwen), with disruptive rhetoric near zero (0.00-0.04).',24.13,670,557.74,20,24.5,{color:C.heading});
baseline.text.get('Baseline:').bold=true;
const assigned=add('Adversarial findings','Adversarial: Qwen ACT scores 2/4 for disruptive rhetoric only at 0 excerpts. Matched Qwen Green changes range from -1 to +1, and all assigned Luna MPs score 0. Higher rhetoric means more disruption. Assignment alone does not demonstrate successful disruption.',24.13,694,557.74,23,24.5,{color:C.red});assigned.text.get('Adversarial:').bold=true;
edit(84,'Each matched point averages all five MPs across 3 debates (15 scores). Pale baselines average 9 debates (45 scores). Assignments: retirement/NZ First/derailment, workweek/ACT/straw man, nuclear/Green/procedural manipulation. One assigned repetition ties topic, tactic and party. Common Luna ratings are dependent and lack human validation.',24.13,722,557.74,18,20.5,{italic:true,color:C.muted});

move(85,24.13,747,19.8,19.8);move(86,51,745.5,530,25);
const outcome=edit(87,'Platform: One backend runs private MP agents and saves reproducible public evidence. Baseline scores show no consistent gain from extra Hansard.',24.13,774,178,30,21.5,{spacing:1.02});outcome.text.get('Platform:').bold=true;
const next=edit(88,'Next: Repeat assigned conditions and rotate parties. Human reviewers should assess disruption, reasoning and concessions before wider conclusions.',216,774,175,30,21.5,{spacing:1.02});next.text.get('Next:').bold=true;
const credit=edit(89,'Albert Sun: frontend, live chamber,\ninitial CLI and Hansard grounding.\nKieran Joe: architecture, agent engine\nand supervisor coordination.',405,774,177,30,21.5,{spacing:1});credit.text.get('Albert Sun:').bold=true;credit.text.get('Kieran Joe:').bold=true;

s.speakerNotes.text=[
  'Sources: supplied Poster_123 (1).pptx for original branding, header, screenshot, interface annotations, references, names and acknowledgements. Original screenshot is a fixed DemoChatManager demonstration with National assigned STRAW_MAN. It does not establish genuine model inference or successful disruption.',
  'Backend wording: src/main/java/engine/application/DebateApplication.java (configuration snapshot, party/Hansard prompt construction, private setup); engine/prompt/PromptManager.java; engine/agent/Agent.java and PrivateAgentContext.java (recipient-specific private system prompt plus public messages); engine/debate/DebateManager.java (serial scheduled turns and public publication); engine/provider/OpenAIChatManager.java and OllamaChatManager.java (chat endpoints). Private prompts never become a public state before isolation.',
  'Evidence: completed T80 public delivery, outputs/01a11f04-d4c3-7b51-8f85-f996967db913/adversarial-batch1/runs.csv, rubric.csv, matched-rubric.csv and 72 retained public rubric reports. 54 all-NONE baseline debates / 1350 speeches, three topics, three grounding levels, three repetitions, two generators. 18 assigned debates / 450 speeches, one assigned repetition, one assigned party per debate and four NONE parties, zero interruptions. The 18 matched controls belong to the 54 baseline debates and do not add another 18 observations.',
  'Original three baseline chart means remain exact. Each point averages 45 party/topic ratings from 9 debates. Consistency, logical reasoning and responsiveness retain separate 0-4 rubric score scales. The displayed vertical axes are truncated: consistency 3.5-4.1 at 0.1 intervals, reasoning 2.0-3.5 at 0.5 intervals, responsiveness 2.0-4.0 at 0.5 intervals. Each axis uses explicit decimal labels. Consistency has 0.1 visual headroom above the maximum valid score of 4. Relevance and disruptive rhetoric remain separate measures, without an overall quality score.',
  'Each graph includes cooperative and adversarial means together for both generators. Luna uses green shades and Qwen contrasting blue shades throughout. All cooperative series are solid: darker filled-marker series are matched repetition-one cooperative controls and lighter thin series retain all three baseline repetitions. Dashed hollow-marker series are assigned conditions. Each matched point averages all five MPs equally over the three topics: 15 participant/topic metric scores in three debates, 75 ordinary speeches. Each assigned debate has one assigned MP and four NONE MPs. It does not isolate the assigned actor. Each pale baseline point averages 45 scores in nine debates. Matched controls have identical frozen LLM settings, corpus, topic, grounding and speaking order to their assigned runs.',
  'The disruptive-rhetoric paragraph describes ONLY the assigned participants, a separate metric from the three plotted metrics. Qwen ACT control/assigned scores at 0,10,100 excerpts are 0/2, 0/0, 0/0. Qwen Green control/assigned scores are 0/1, 0/0, 1/0. All Luna assigned/control rhetoric values and Qwen NZ First values are zero. Higher rhetoric means greater observed disruption, not better quality.',
  'Both generators retain frozen study settings and one common GPT-6 Luna judge that scores public evidence only. Topic, tactic and assigned party are confounded, with a single assigned repetition. Ratings within each debate are dependent. No human validation, causal effect, significance, successful tactic enactment, model availability or adversarial-robustness claim follows from this poster. Sentiment and stance are not combined with rubric scores. No model inference is performed for this revision.',
  `Public data hashes: ${JSON.stringify(facts.sourceHashes)}. Supplied poster SHA256: ${facts.sourceSha256}. Print geometry: A1 portrait trim 594 x 841 mm, 6 mm bleed per edge, 606 x 853 mm canvas.`
].join('\n\n');
await fs.writeFile(path.join(BUILD,`authored-layout-r${revision}.json`),await (await s.export({format:'layout'})).text());
await fs.writeFile(path.join(BUILD,`preview-r${revision}.png`),new Uint8Array(await (await s.export({format:'png',scale:0.7})).arrayBuffer()));
await fs.writeFile(path.join(BUILD,`changes-r${revision}.json`),JSON.stringify({changes,metrics,displayAxes},null,2));
const candidate=path.join(BUILD,`candidate-r${revision}.pptx`);
await (await PresentationFile.exportPptx(p)).save(candidate);
const {finalizePresentation}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
await fs.mkdir(path.join(BUILD,'finalized'),{recursive:true});
const finalPath=path.join(BUILD,'finalized',`Poster_123_Updated_r${revision}.pptx`);
const result=await finalizePresentation({workspaceDir:ROOT,candidatePath:candidate,finalPath,pythonExecutable:PYTHON,integrityValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_package_integrity.py'),layoutValidatorPath:path.join(SKILL,'container_tools/inspect_presentation_layout_geometry.py'),layoutArgs:['--expected-slide-size-emu','21816000,30708000','--validate-heading-fit'],explicitTotalSlideCount:1,requiredNativeChartOwnerSlides:[1],requiredNativeTableOwnerSlides:[],materializeLiteralChartWorkbooks:true,nativeChartTargetApplication:'portable',fontPolicy:{basis:'reference',families:['Calibri','Cambria'],referencePath:facts.source,referenceSha256:facts.sourceSha256},verifyArtifactToolImport:true,receiptPath:path.join(BUILD,`finalization-r${revision}.json`)});
console.log(JSON.stringify({finalPath,bytes:result.byteCount,layoutFindings:result.presentationLayout.findingCount,roundTrip:result.firstPartyImport.passed}));
const finalDeck=await PresentationFile.importPptx(await FileBlob.load(finalPath));const finalSlide=finalDeck.slides.getItem(0);
await fs.writeFile(path.join(BUILD,`final-layout-r${revision}.json`),await (await finalSlide.export({format:'layout'})).text());
await fs.writeFile(path.join(BUILD,`final-preview-r${revision}.png`),new Uint8Array(await (await finalSlide.export({format:'png',scale:0.7})).arrayBuffer()));
await fs.writeFile(path.join(BUILD,`print-300dpi-r${revision}.png`),new Uint8Array(await (await finalSlide.export({format:'png',scale:300/96})).arrayBuffer()));
} catch(error) {
  console.error(`${error.name}: ${error.message}`);
  console.error(String(error.stack).split('\n').filter(l=>l.startsWith('    at')).slice(-6).join('\n'));
  process.exitCode=1;
}

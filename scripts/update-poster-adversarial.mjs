// T82–T87: focused native-object revision of the supplied A1 poster, without inference.
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

for (const n of [29,30,31]) {
  byNumber(n).text.alignment='center';
  changes.push({number:n,property:'alignment',value:'center'});
}
byNumber(30).text='Cooperative debate and hidden disruptive agents';
edit(32,'Real MPs can derail debate, distort claims and exploit procedure. AI multi-agent debate has been studied to improve reasoning [1]. We built a virtual NZ House of AI MPs, then privately tasked some with sabotage and tested the effects of grounding agents with Hansard.',24.13,104,557.74,29,29);
edit(44,'One MP represents each chosen party.\nSpeakers are highlighted when speaking.',360,176.5,221.87,20,24.5,{color:C.muted});
edit(56,'Public JSON and text exports support local\nsentiment/stance analysis and separate rubric scores.',360,324.5,221.87,20,24.5);
edit(57,'Fig. 1 A demonstration sitting with a National straw-man assigned adversarial agent.\nWeb browser, guided command, and terminal interfaces all connect to the debate engine backend.',24.13,330,310,22,20.5,{italic:true,color:C.muted,spacing:1.02});

add('Backend engine explanation',"At run start, the backend combines each MP's party profile and selected real parliamentary debate excerpts [2] in a private system prompt, adding any assigned tactic. The scheduler takes turns sending that MP's prompt, public debate history and a turn cue to its model's chat completion endpoint. Each returned speech joins the public record for the next MP. Other agents cannot read the private prompts.",24.13,380.5,557.74,33,25.5);
const cards=[
  [24,60,61,'Real Hansard','500 reviewed excerpts\n0/10/100 per party'],
  [22,62,63,'Private setup','Frozen prompts\nand hidden tactics'],
  [20,64,65,'MP agents','Party personas\non cloud or local LLMs'],
  [17,66,67,'Debate engine','Scheduled turns\nand chat requests'],
  [18,68,69,'Saved evidence','Public transcript\nand separate reports'],
];
for (const [i,[box,heading,body,title,copy]] of cards.entries()){
  const x=24.13+i*114.5;
  move(box,x,418,99.74,39).line={style:'solid',fill:'#8C9E92',width:2};
  edit(heading,title,x+3,421,93.74,11,27,{font:'Cambria',bold:true,color:C.heading,align:'center'});
  edit(body,copy,x+3.5,433.5,92.74,20,22.5,{color:C.muted,align:'center',spacing:1.02});
}
for (const [n,i] of [[23,0],[21,1],[19,2],[16,3]]) move(n,123.87+i*114.5,437.5,14.76,0);
for (const [head,body,x] of [[70,71,24.13],[72,73,214.13],[74,75,404.13]]){
  move(head,x,463,177.74,12);move(body,x,478,177.74,20);
}
// Restore an apostrophe damaged in the supplied file while preserving its definition.
edit(73,"Misrepresent an opponent's claim,\nthen attack the distorted version.",214.13,478,177.74,20,23.5);
for (const [head,body,x] of [[70,71,52],[72,73,242],[74,75,432]]) {
  byNumber(head).text.alignment='center';
  move(body,x,478,122,20).text.alignment='right';
}
p.delete(sourceElements.find(e=>e.name==='Google Shape;76;p1').id);

move(77,24.13,502,19.8,19.8);
edit(78,'Grounding and adversarial testing',51,500.5,530,25,53,{font:'Cambria',bold:true,color:C.heading});
const design=edit(79,'72 debates (1,800 speeches): 54 cooperative baselines (three repeats), plus 18 adversarial runs (one repeat, one assigned MP). GPT-6 Luna and Qwen3:8b debate retirement, nuclear power and a four-day workweek at 0/10/100 excerpts, with five parties, five rounds and no interjections.',24.13,529,557.74,23,24.5);
design.text.get('72 debates (1,800 speeches)').bold=true;
design.text.get('54 cooperative baselines').bold=true;
design.text.get('18 adversarial runs').bold=true;
const chartElements=sourceElements.filter(e=>e.kind==='chart').sort((a,b)=>a.position.left-b.position.left);
const {applyPresentationChartFont}=await import(pathToFileURL(path.join(SKILL,'container_tools/artifact_tool_utils.mjs')).href);
p.delete(chartElements.map(e=>e.id));
const metrics=[['consistency','Consistency'],['logical_reasoning','Logical reasoning'],['responsiveness','Responsiveness']];
const displayAxes=facts.displayAxes;
const curves=[['cooperative','cooperative','solid','circle'],['topic_derailment','derailment','dashed','triangle'],['straw_man','straw man','dashed','square'],['procedural_manipulation','procedural','dashed','diamond']];
for (const [i,[metric,title]] of metrics.entries()){
  const series=[];
  for(const [model,name] of [['gpt-6-luna','Luna'],['qwen3:8b','Qwen']]){
    for(const [key,label,stroke,symbol] of curves){
      const values=['0','10','100'].map(h=>facts.seriesValues[model][key][h][metric]);
      const fill=facts.modelColourFamilies[model][key];
      series.push({name:`${name}: ${label}`,values:values.map(v=>Number(v.toPrecision(15))),line:{style:stroke,fill,width:key==='cooperative'?4:3},marker:{symbol,size:10,fill}});
    }
  }
  const chart=s.charts.add('line',{name:`Cooperative and adversarial ${metric}`,title,titleTextStyle:{fontSize:pt(26),bold:true,fill:C.heading},titlePlacement:'aboveChart',position:{left:mm(24.13+i*188),top:mm(555),width:mm(181.74),height:mm(100)},categories:['0','10','100'],series,hasLegend:true,legend:{position:'bottom',overlay:false,textStyle:{fontSize:pt(17),fill:C.text}},xAxis:{visible:true,position:'bottom',title:{text:'Hansard excerpts per party',textStyle:{fontSize:pt(17),fill:C.muted}},textStyle:{fontSize:pt(18),fill:C.text},line:{fill:C.line,width:1},majorGridlines:null},yAxis:{visible:true,position:'left',...displayAxes[metric],numberFormatCode:'0.0',textStyle:{fontSize:pt(18),fill:C.text},line:{fill:'none',width:0},majorGridlines:{style:'solid',fill:C.line,width:1}},lineOptions:{grouping:'standard',smooth:false},chartFill:C.paper,chartLine:{fill:'none',width:0},plotAreaFill:C.paper,plotAreaLine:{fill:'none',width:0}});
  applyPresentationChartFont(chart,{fontFamily:'Calibri'});
}
add('Truncated axis disclosure','Vertical axes are truncated. Rubric scores range from 0 to 4. Each co-operative point averages 9 debates (45 MP ratings per metric); each strategy point averages five MPs. GPT-6 Luna used a consistent rating prompt. Topic, party and repeats limit comparison.',24.13,659,557.74,18,20.5,{color:C.muted});
const findings=edit(83,"Co-operative agents: Consistency stays high, but Qwen falls from 3.93 to 3.62. Luna's reasoning stays near 3.0; Qwen's stays between 2.62 and 2.67. Responsiveness changes little for either model. More Hansard gives no consistent gain.",24.13,683,557.74,23,24.5,{color:C.heading});
findings.text.get('Co-operative agents:').bold=true;
const assigned=add('Adversarial findings',"Adversarial agents: Qwen's reasoning varies from 2.0 to 2.8; Luna's procedural responsiveness rises from 3.2 to 4.0. Disruption scores are 2/4 for Qwen ACT only at 0 excerpts and 0 for adversarial Luna MPs; Qwen Green's paired changes span -1 to +1. Adversarial assignment alone is insufficient for disruption.",24.13,709,557.74,23,24.5,{color:C.red});assigned.text.get('Adversarial agents:').bold=true;
p.delete(sourceElements.find(e=>e.name==='Google Shape;84;p1').id);

move(85,24.13,747,19.8,19.8);move(86,51,745.5,530,25);
const outcome=edit(87,'Outcomes: An AI debate engine with replaceable components and a browser-based frontend.\nWe found no consistent gain in performance metrics from extra Hansard excerpts.',24.13,774,178,35,21.5,{spacing:1.02});outcome.text.get('Outcomes:').bold=true;
const next=edit(88,'Next Steps: Expand testing with more providers and higher-performance language models.\nHost the web browser platform on a server accessible through the internet.',216,774,175,35,21.5,{spacing:1.02});next.text.get('Next Steps:').bold=true;
const credit=edit(89,'Albert Sun — frontend, live chamber,\ninitial CLI and Hansard grounding.\nKieran Joe — engine, architecture and\nsupervisor coordination.',405,774,177,35,21.5,{spacing:1});credit.text.get('Albert Sun').bold=true;credit.text.get('Kieran Joe').bold=true;
move(90,0,814,606,39);
move(91,24.13,817,177.74,10).text.alignment='center';
edit(92,'[1] Du et al. (2024). Multiagent Debate.\n[2] Rauh & Schwalbach (2020). ParlSpeech V2.',24.13,829,177.74,17,20,{color:C.muted,spacing:1.02,align:'center'});
edit(93,'Acknowledgements',214.13,817,177.74,10,22,{bold:true,color:C.heading,align:'center'});
edit(94,'Ioannis Ziogas, McCourt School of Public Policy,\nGeorgetown University',228,829,150,17,20,{color:C.muted,align:'center',spacing:1.02});
move(95,404.13,817,177.74,10).text.alignment='center';
move(96,404.13,829,177.74,17).text.alignment='center';

s.speakerNotes.text=[
  'Sources: supplied Poster_123 (1).pptx for original branding, header, screenshot, interface annotations, references, names and acknowledgements. Original screenshot is a fixed DemoChatManager demonstration with National assigned STRAW_MAN. It does not establish genuine model inference or successful disruption.',
  'Reference [1]: Du, Y., Li, S., Torralba, A., Tenenbaum, J. B., and Mordatch, I. (2024). Improving Factuality and Reasoning in Language Models through Multiagent Debate. ICML 2024, Proceedings of Machine Learning Research 235:11733-11763. https://proceedings.mlr.press/v235/du24e.html . This paper supports the specific background statement that multi-agent debate has been studied to improve reasoning; it does not establish that most AI debate research has the same focus. Reference [2]: Rauh and Schwalbach (2020), ParlSpeech V2, retains the supplied Hansard-source attribution. The supplied Qwen attribution, Qwen Team (2025), Qwen3 Technical Report, remains original-source context rather than support for that debate-literature claim.',
  'Backend wording: src/main/java/engine/application/DebateApplication.java (configuration snapshot, party/Hansard prompt construction, private setup); engine/prompt/PromptManager.java; engine/agent/Agent.java and PrivateAgentContext.java (recipient-specific private system prompt plus public messages); engine/debate/DebateManager.java (serial scheduled turns and public publication); engine/provider/OpenAIChatManager.java and OllamaChatManager.java (chat endpoints). Private prompts never become a public state before isolation.',
  'Evidence: completed T80 public delivery, outputs/01a11f04-d4c3-7b51-8f85-f996967db913/adversarial-batch1/runs.csv, rubric.csv, matched-rubric.csv and 72 retained public rubric reports. 54 all-NONE baseline debates / 1350 speeches, three topics, three grounding levels, three repetitions, two generators. 18 assigned debates / 450 speeches, one assigned repetition, one assigned party per debate and four NONE parties, zero interruptions. The 18 matched controls belong to the 54 baseline debates and do not add another 18 observations.',
  'Original three baseline chart means remain exact in the single cooperative series per model. Each cooperative point averages 45 party/topic ratings from 9 debates, covering three topics and three repetitions. Consistency, logical reasoning and responsiveness retain separate 0-4 rubric score scales. The displayed vertical axes are truncated: consistency 3.5-4.1 at 0.1 intervals, reasoning 1.5-3.5 at 0.5 intervals, responsiveness 2.0-4.1 at 0.5 intervals. Explicit decimal labels and boundary headroom keep markers readable. Relevance and disruptive rhetoric remain separate measures, without an overall quality score.',
  'Each graph has exactly eight series: cooperative plus topic derailment, straw man and procedural manipulation for each model. Luna uses green shades and Qwen blue shades. Cooperative lines are solid and each strategy line dashed. All markers have opaque coloured fills, with circle/triangle/square/diamond identifying the four conditions. There is no separate all-baselines or pooled-adversarial series. Some curves coincide exactly, particularly consistency and Luna reasoning. Each strategy point averages all five MPs in one assigned debate: five participant/topic metric scores and 25 speeches. The assignment is one MP with four NONE MPs. These are full-debate condition means, not only the assigned actor. Because the single cooperative reference pools the three topics and three repetitions while each strategy is tied to one topic/party/first repetition, the graph is descriptive rather than a matched causal-effect comparison. The separate matched CSV evidence remains retained.',
  'The disruptive-rhetoric paragraph describes ONLY the assigned participants, a separate metric from the three plotted metrics. Qwen ACT control/assigned scores at 0,10,100 excerpts are 0/2, 0/0, 0/0. Qwen Green control/assigned scores are 0/1, 0/0, 1/0. All Luna assigned/control rhetoric values and Qwen NZ First values are zero. Higher rhetoric means greater observed disruption, not better quality.',
  'Both generators retain frozen study settings and one common GPT-6 Luna judge that scores public evidence only with a consistent evaluation prompt. Topic, tactic and assigned party are confounded, with a single assigned repetition. Ratings within each debate are dependent, and no human validation is provided. The descriptive findings refer to the displayed full-debate means: Luna reasoning 2.9778-3.0667 in cooperative debates and 3.0 in each assigned condition; Qwen strategy reasoning 2.0-2.8; Luna procedural responsiveness 3.2, 3.8, 4.0 at 0, 10, 100 excerpts. No causal effect, significance, successful tactic enactment, model availability or adversarial-robustness claim follows. Sentiment and stance remain separate from rubric scores.',
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

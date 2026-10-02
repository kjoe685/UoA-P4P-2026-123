'use strict';

/* =========================================================================
   Virtual Parliament: web frontend
   Talks to engine.web.ApiHandler:
     GET  /api/config                 parties, strategies, defaults
     POST /api/debates                start a sitting
     GET  /api/debates/{id}/events    Server-Sent Events stream of the sitting
     POST /api/debates/{id}/speaker   ruling from the chair
     POST /api/debates/{id}/adjourn   stop the sitting
   ========================================================================= */

const PARTY_STYLE = {
  LABOUR: { color: 'var(--party-labour)', ink: '#ffffff', abbr: 'LAB' },
  NATIONAL: { color: 'var(--party-national)', ink: '#ffffff', abbr: 'NAT' },
  GREEN: { color: 'var(--party-green)', ink: '#ffffff', abbr: 'GRN' },
  ACT: { color: 'var(--party-act)', ink: '#1a1a1a', abbr: 'ACT' },
  NZ_FIRST: { color: 'var(--party-nzfirst)', ink: '#ffffff', abbr: 'NZF' },
};
const FALLBACK_PARTY_STYLE = { color: '#6a7770', ink: '#ffffff', abbr: '?' };

const STRATEGY_LABELS = {
  NONE: 'Cooperative',
  TOPIC_DERAILMENT: 'Topic derailment',
  STRAW_MAN: 'Straw man',
  PROCEDURAL_MANIPULATION: 'Procedural manipulation',
};

const SUGGESTED_TOPICS = [
  'Raising the age of eligibility for NZ Superannuation',
  'Introducing a capital gains tax',
  'Lowering the voting age to 16',
  'Moving to a four-year parliamentary term',
  'Pricing agricultural greenhouse gas emissions',
  'Improving housing affordability',
  'Easing the cost of living',
];

const QUICK_RULINGS = [
  'Order! Order!',
  'The member will return to the question before the House.',
  'That is not a point of order. The member will resume their seat.',
  'The member will withdraw and apologise.',
  'Members will direct their remarks through the chair.',
];

const SETUP_STORAGE_KEY = 'virtual-parliament.setup';
const SITTING_STORAGE_KEY = 'virtual-parliament.sitting';

/* ---------- Small helpers ---------- */

async function readJsonFile(file) {
  const bytes = await file.arrayBuffer();
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
  catch { throw new Error('Choose a JSON file encoded as valid UTF-8.'); }
}

const $ = (selector) => document.querySelector(selector);

/** Creates an element. `props` may hold className, text, attrs, dataset, style, on (event handlers). */
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  if (props.className) node.className = props.className;
  if (props.text != null) node.textContent = props.text;
  for (const [name, value] of Object.entries(props.attrs || {})) {
    if (value === false || value == null) continue;
    node.setAttribute(name, value === true ? '' : value);
  }
  for (const [name, value] of Object.entries(props.style || {})) node.style.setProperty(name, value);
  for (const [name, handler] of Object.entries(props.on || {})) node.addEventListener(name, handler);
  for (const child of children) {
    if (child != null && child !== false) node.append(child);
  }
  return node;
}

function svgEl(tag, attrs = {}, ...children) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, value);
  node.append(...children);
  return node;
}

function readStore(storage, key) {
  try {
    return JSON.parse(storage.getItem(key));
  } catch {
    return null;
  }
}

function writeStore(storage, key, value) {
  try {
    if (value == null) storage.removeItem(key);
    else storage.setItem(key, JSON.stringify(value));
  } catch {
    // Storage is unavailable (private mode, blocked cookies); the app works without it.
  }
}

function partyStyle(partyId) {
  return PARTY_STYLE[partyId] || FALLBACK_PARTY_STYLE;
}

function strategyLabel(strategyId) {
  return STRATEGY_LABELS[strategyId]
    || strategyId.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

function seatBadge(partyId) {
  const style = partyStyle(partyId);
  return el('span', {
    className: 'seat-badge',
    text: style.abbr,
    attrs: { 'aria-hidden': 'true' },
    style: { '--party': style.color, '--party-ink': style.ink },
  });
}

function formatTime(millis) {
  return new Date(millis).toLocaleTimeString('en-NZ', { hour: 'numeric', minute: '2-digit' });
}

function formatDate(millis) {
  return new Date(millis).toLocaleDateString('en-NZ', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
  });
}

async function api(path, options = {}) {
  const init = { method: options.method || 'GET', headers: {} };
  if (options.body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(options.body);
  }
  let response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new Error('Could not reach the Virtual Parliament server. Is it still running?');
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}

function setStatus(state, text) {
  $('#status').dataset.state = state;
  $('#status-text').textContent = text;
}

/* =========================================================================
   Setup view
   ========================================================================= */

let config = null;
const setup = { topics: [], policyTargets: [], rounds: 3, members: {} };

async function initSetup(suppliedSettings = null) {
  try {
    config = await api('/api/config');
  } catch (error) {
    showConfigNotice(true, 'The server could not be reached.', error.message);
    $('#convene-btn').disabled = true;
    return;
  }

  showConfigNotice(false, 'The demonstration is ready without credentials.', config.credentialHelp);

  const saved = suppliedSettings || readStore(localStorage, SETUP_STORAGE_KEY) || {};
  setup.agentModelPreset = config.models[saved.agentModelPreset] ? saved.agentModelPreset : config.agentModelPreset;
  setup.evaluatorModelPreset = config.models[saved.evaluatorModelPreset] ? saved.evaluatorModelPreset : config.evaluatorModelPreset;
  setup.policyTargets = Array.isArray(saved.policyTargets) ? saved.policyTargets : [];
  setup.groundingCount = Number.isInteger(saved.groundingCount) ? saved.groundingCount : -1;
  $('#grounding-count').value=setup.groundingCount;
  const evaluatorOverride=$('#analysis-llm-model').value;
  const localPreset=$('#ollama-model-preset').value;
  const localModels=Object.entries(config.models).filter(([,model])=>model.provider==='ollama');
  $('#ollama-model-preset').replaceChildren(...localModels.map(([id,model])=>el('option',{text:`${id} · ${model.model}`,attrs:{value:id,selected:localPreset===id}})));
  $('#download-ollama-model').disabled=localModels.length===0;
  $('#analysis-llm-model').replaceChildren(el('option',{text:"Sitting's saved evaluator",attrs:{value:''}}),
    ...Object.entries(config.models).filter(([,model])=>model.provider!=='demo').map(([id,model])=>el('option',{text:`${id} · ${model.provider}`,attrs:{value:id,selected:evaluatorOverride===id}})));
  for (const [selector, key] of [['#agent-model', 'agentModelPreset'], ['#evaluator-model', 'evaluatorModelPreset']]) {
    $(selector).replaceChildren(...Object.entries(config.models).map(([id, model]) => el('option', {
      text: model.provider === 'demo' ? 'Deterministic demonstration · no model calls' : `${id} · ${model.provider}`,
      attrs: { value: id, selected: setup[key] === id },
    })));
  }
  setup.topics = Array.isArray(saved.topics) && saved.topics.length ? saved.topics : [config.defaultTopic];
  setup.rounds = clampRounds(saved.rounds ?? config.defaultRounds);
  for (const party of config.parties) {
    const savedMember = saved.members && saved.members[party.id];
    const strategyIsKnown = savedMember && config.strategies.some((s) => s.id === savedMember.strategy);
    setup.members[party.id] = {
      included: savedMember ? savedMember.included !== false : true,
      strategy: strategyIsKnown ? savedMember.strategy : 'NONE',
      modelPreset: config.models[savedMember?.modelPreset] ? savedMember.modelPreset : '',
      groundingCount: Number.isInteger(savedMember?.groundingCount) ? savedMember.groundingCount : null,
    };
  }

  renderTopics();
  renderSuggestions();
  renderParties();
  renderRounds();
}

function showConfigNotice(isError, title, detail) {
  const notice = $('#config-notice');
  notice.replaceChildren(el('strong', { text: title }), el('span', { text: detail || '' }));
  notice.classList.toggle('is-error', isError);
  notice.hidden = false;
}

function saveSetup() {
  writeStore(localStorage, SETUP_STORAGE_KEY, setup);
  renderSummary();
}

function clampRounds(value) {
  const max = config ? config.maxRounds : 10;
  const number = Number.parseInt(value, 10);
  return Number.isFinite(number) ? Math.min(max, Math.max(1, number)) : 3;
}

/* Order Paper */

function renderTopics() {
  const list = $('#topic-list');
  list.replaceChildren(...setup.topics.map((topic, index) => el('li', { className: 'topic-item' },
    el('span', { className: 'topic-num', text: String(index + 1), attrs: { 'aria-hidden': 'true' } }),
    el('input', {
      attrs: { type: 'text', value: topic, maxlength: 300, 'aria-label': `Order Paper item ${index + 1}` },
      on: {
        input: (event) => { setup.topics[index] = event.target.value; saveSetup(); },
      },
    }),
    el('input', {
      className: 'policy-target',
      attrs: { type: 'text', value: setup.policyTargets[index] || '', maxlength: 2000,
        placeholder: 'Optional policy proposition for stance evaluation', 'aria-label': `Policy proposition for item ${index + 1}` },
      on: { input: event => { setup.policyTargets[index] = event.target.value; saveSetup(); } },
    }),
    el('button', {
      className: 'icon-btn',
      text: '↑',
      attrs: { type: 'button', title: 'Move up', 'aria-label': `Move item ${index + 1} up`, disabled: index === 0 },
      on: { click: () => moveTopic(index, -1) },
    }),
    el('button', {
      className: 'icon-btn',
      text: '×',
      attrs: { type: 'button', title: 'Remove', 'aria-label': `Remove item ${index + 1}` },
      on: { click: () => removeTopic(index) },
    }),
  )));
  renderSuggestions();
  saveSetup();
}

function addTopic(text) {
  const topic = text.trim();
  if (!topic) return;
  if (setup.topics.length >= config.maxTopics) {
    showFormError(`The Order Paper can hold at most ${config.maxTopics} items.`);
    return;
  }
  // Replace a lone empty item rather than adding next to it.
  if (setup.topics.length === 1 && !setup.topics[0].trim()) { setup.topics = []; setup.policyTargets = []; }
  setup.topics.push(topic);
  setup.policyTargets.push('');
  showFormError('');
  renderTopics();
}

function removeTopic(index) {
  setup.topics.splice(index, 1);
  setup.policyTargets.splice(index, 1);
  renderTopics();
}

function moveTopic(index, delta) {
  const target = index + delta;
  if (target < 0 || target >= setup.topics.length) return;
  [setup.topics[index], setup.topics[target]] = [setup.topics[target], setup.topics[index]];
  [setup.policyTargets[index], setup.policyTargets[target]] = [setup.policyTargets[target], setup.policyTargets[index]];
  renderTopics();
}

function renderSuggestions() {
  const onPaper = new Set(setup.topics.map((t) => t.trim().toLowerCase()));
  $('#suggestion-list').replaceChildren(...SUGGESTED_TOPICS
    .filter((topic) => !onPaper.has(topic.toLowerCase()))
    .map((topic) => el('button', {
      className: 'chip',
      text: `+ ${topic}`,
      attrs: { type: 'button' },
      on: { click: () => addTopic(topic) },
    })));
}

/* Members */

function renderParties() {
  $('#party-grid').replaceChildren(...config.parties.map((party) => {
    const member = setup.members[party.id];
    const style = partyStyle(party.id);
    const checkboxId = `party-${party.id}`;
    const selectId = `strategy-${party.id}`;
    const strategy = config.strategies.find((s) => s.id === member.strategy);
    const isAdversarial = member.strategy !== 'NONE';

    return el('div', {
      className: `party-card${member.included ? ' is-included' : ''}${member.included && isAdversarial ? ' is-adversarial' : ''}`,
      style: { '--party': style.color, '--party-ink': style.ink },
    },
    member.included && isAdversarial ? el('span', { className: 'disruptor-tag', text: 'Disruptor' }) : null,
    el('label', { className: 'party-toggle', attrs: { for: checkboxId } },
      el('input', {
        attrs: { type: 'checkbox', id: checkboxId, checked: member.included },
        on: { change: (event) => { member.included = event.target.checked; renderParties(); } },
      }),
      seatBadge(party.id),
      el('span', { className: 'party-name', text: party.name })),
    el('p', { className: 'party-ideology', text: capitalise(party.ideology) }),
    el('label',{className:'field-label',text:`Grounding count override (${party.groundingAvailable} available)`,attrs:{for:`grounding-${party.id}`}}),
    el('input',{attrs:{id:`grounding-${party.id}`,type:'number',min:-1,max:1000,step:1,value:member.groundingCount ?? '',placeholder:'Shared count',disabled:!member.included},
      on:{input:event=>{ member.groundingCount=event.target.value==='' ? null : Number(event.target.value); saveSetup(); }}}),
    el('div', {},
      el('label', { className: 'field-label', text: 'Model override', attrs: { for: `model-${party.id}` } }),
      el('select', { attrs: { id: `model-${party.id}`, disabled: !member.included },
        on: { change: event => { member.modelPreset = event.target.value; saveSetup(); } } },
      el('option', { text: 'Shared agent model', attrs: { value: '', selected: !member.modelPreset } }),
      ...Object.keys(config.models).map(id => el('option', { text: id, attrs: { value: id, selected: member.modelPreset === id } })))),
    el('div', {},
      el('label', { className: 'field-label', text: 'Behaviour', attrs: { for: selectId } }),
      el('select', {
        attrs: { id: selectId, disabled: !member.included },
        on: { change: (event) => { member.strategy = event.target.value; renderParties(); } },
      }, ...config.strategies.map((s) => el('option', {
        text: s.id === 'NONE' ? 'Cooperative' : `Disruptor: ${strategyLabel(s.id)}`,
        attrs: { value: s.id, selected: s.id === member.strategy },
      })))),
    member.included && isAdversarial && strategy
      ? el('p', { className: 'strategy-desc', text: strategy.instruction })
      : null);
  }));
  saveSetup();
}

function capitalise(text) {
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : text;
}

/* Length */

function renderRounds() {
  $('#rounds-value').textContent = String(setup.rounds);
  $('#rounds-down').disabled = setup.rounds <= 1;
  $('#rounds-up').disabled = setup.rounds >= config.maxRounds;
  saveSetup();
}

function renderSummary() {
  if (!config) return;
  const members = selectedMembers();
  const topics = cleanTopics();
  const itemCount = Math.max(1, topics.length);
  const speeches = members.length * setup.rounds * itemCount;
  const disruptors = members.filter((m) => m.strategy !== 'NONE').length;

  const summary = $('#sitting-summary');
  summary.replaceChildren(
    el('strong', { text: `${members.length} ${members.length === 1 ? 'member' : 'members'}` }), ' × ',
    el('strong', { text: `${setup.rounds} ${setup.rounds === 1 ? 'round' : 'rounds'}` }), ' × ',
    el('strong', { text: `${itemCount} ${itemCount === 1 ? 'item' : 'items'}` }), ' = ',
    el('strong', { text: `${speeches} scheduled ${speeches === 1 ? 'speech' : 'speeches'}` }),
    ', plus random interjections',
    disruptors ? ` (more with ${disruptors} ${disruptors === 1 ? 'disruptor' : 'disruptors'} seated)` : '',
    config.models[setup.agentModelPreset]?.provider === 'demo' && members.every(member => !member.modelPreset || config.models[member.modelPreset]?.provider === 'demo')
      ? '. The demonstration makes no model calls.' : '. Each generated speech calls the selected provider.',
  );
}

function selectedMembers() {
  return config.parties
    .filter((party) => setup.members[party.id].included)
    .map((party) => ({ party: party.id, strategy: setup.members[party.id].strategy,
      ...(setup.members[party.id].modelPreset ? { modelPreset: setup.members[party.id].modelPreset } : {}),
      ...(setup.members[party.id].groundingCount!=null ? {groundingCount:setup.members[party.id].groundingCount} : {}) }));
}

function cleanTopics() {
  return setup.topics.map((t) => t.trim()).filter(Boolean);
}

function showFormError(message) {
  $('#form-error').textContent = message;
}

async function convene(event) {
  event.preventDefault();
  const members = selectedMembers();
  if (!members.length) {
    showFormError('Select at least one party to take their seats.');
    return;
  }
  // Pick up an item typed into the "add" box but not yet added.
  const pending = $('#new-topic').value.trim();
  if (pending) {
    addTopic(pending);
    $('#new-topic').value = '';
  }

  const button = $('#convene-btn');
  button.disabled = true;
  showFormError('');
  try {
    const { id } = await api('/api/debates', {
      method: 'POST',
      body: currentSettings(),
    });
    openSitting(id);
  } catch (error) {
    showFormError(error.message);
  } finally {
    button.disabled = false;
  }
}

/* =========================================================================
   Chamber view: a sitting in progress
   ========================================================================= */

function currentSettings() {
  return { topics: setup.topics.map((title, index) => ({ title: title.trim(), policyTarget: setup.policyTargets[index] || null })).filter(topic => topic.title),
    rounds: setup.rounds, members: selectedMembers(), agentModelPreset: setup.agentModelPreset, evaluatorModelPreset: setup.evaluatorModelPreset, groundingCount:setup.groundingCount };
}

async function refreshSettings() {
  const names = await api('/api/settings');
  $('#saved-settings').replaceChildren(el('option', { text: 'Choose settings', attrs: { value: '' } }),
    ...names.map(name => el('option', { text: name, attrs: { value: name } })));
}

async function loadSettings() {
  try {
    const name = $('#saved-settings').value;
    if (!name) throw new Error('Choose saved settings to load.');
    const saved = await api(`/api/settings/${encodeURIComponent(name)}`);
    const values = { topics: saved.topics.map(topic => topic.title), policyTargets: saved.topics.map(topic => topic.policyTarget || ''),
      rounds: saved.rounds, agentModelPreset: saved.agentModelPreset, evaluatorModelPreset: saved.evaluatorModelPreset, groundingCount:saved.groundingCount, members: {} };
    for (const party of config.parties) {
      const member = saved.members.find(item => item.party === party.id);
      values.members[party.id] = { included: !!member, strategy: member?.strategy || 'NONE', modelPreset: member?.modelPreset || '', groundingCount:member?.groundingCount ?? null };
    }
    writeStore(localStorage, SETUP_STORAGE_KEY, values);
    await initSetup(values);
    $('#settings-name').value = name;
    $('#settings-status').textContent = `Loaded ${name}. Review the setup before starting.`;
  } catch (error) { $('#settings-status').textContent = error.message; }
}

async function saveSettings() {
  try {
    const name = $('#settings-name').value.trim();
    if (!name) throw new Error('Enter a settings name.');
    await api(`/api/settings/${encodeURIComponent(name)}`, { method: 'POST', body: currentSettings() });
    await refreshSettings(); $('#saved-settings').value = name;
    $('#settings-status').textContent = `Saved ${name}. Existing settings with this name are replaced.`;
  } catch (error) { $('#settings-status').textContent = error.message; }
}

async function readAsset() {
  try {
    const value = await api(`/api/assets?path=${encodeURIComponent($('#asset-path').value)}`);
    $('#asset-text').value = value.text; $('#asset-status').textContent = `Loaded ${value.path}.`;
    $('#asset-text').dataset.path = value.path;
  } catch (error) { $('#asset-status').textContent = error.message; }
}

let corpusCandidate = null;
let corpusValidation = null;
function showCorpusSummary(selector,result) {
  const table=el('table',{},el('thead',{},el('tr',{},...['Party','Excerpts','Speakers','Date range'].map(text=>el('th',{text})))),
    el('tbody',{},...result.parties.map(party=>el('tr',{},...[
      party.name,party.excerpts,party.speakers,`${party.earliestDate} – ${party.latestDate}`].map(text=>el('td',{text}))))));
  $(selector).replaceChildren(el('p',{text:`${result.totalExcerpts} excerpts across ${result.parties.length} parties.`}),table,
    el('details',{},el('summary',{text:'Source, selection and validation details'}),el('pre',{text:JSON.stringify(result,null,2)})));
}
async function refreshCorpus() {
  try { showCorpusSummary('#corpus-summary',await api('/api/corpus')); }
  catch(error) { $('#corpus-status').textContent=error.message; }
}
async function selectCorpus(event) {
  corpusCandidate=null; corpusValidation=null;
  $('#validate-corpus').disabled=true; $('#import-corpus').disabled=true; $('#corpus-candidate').textContent='';
  const file=event.target.files[0]; if (!file) return;
  try {
    if (file.size>1500000) throw new Error('Choose a corpus JSON file up to 1.5 MB (one million characters).');
    corpusCandidate=await readJsonFile(file); $('#validate-corpus').disabled=false;
    $('#corpus-status').textContent=`Loaded ${file.name}. Validate before importing.`;
  } catch(error) { $('#corpus-status').textContent=error.message; }
}
async function updateCorpus(save) {
  const text=corpusCandidate;
  if (!text || save && !corpusValidation) return;
  $('#import-corpus').disabled=true; $('#validate-corpus').disabled=true;
  try {
    const body={text,save}; if (save) body.expectedSha256=corpusValidation.previousSha256;
    const result=await api('/api/corpus',{method:'POST',body});
    if (corpusCandidate!==text) return;
    showCorpusSummary('#corpus-candidate',result);
    corpusValidation=save ? null : result;
    $('#corpus-status').textContent=save ? 'Imported validated corpus. New sittings use this revision.' : 'Candidate is valid. Review its summary before importing.';
    if (save) { await initSetup(); await refreshCorpus(); }
  } catch(error) { corpusValidation=null; $('#corpus-status').textContent=error.message; }
  finally { $('#validate-corpus').disabled=!corpusCandidate; $('#import-corpus').disabled=!corpusValidation; }
}

async function updateAsset(save) {
  try {
    const path = $('#asset-path').value;
    if ($('#asset-text').dataset.path !== $('#asset-path').value) throw new Error('Load the selected asset before editing.');
    await api('/api/assets', { method: 'POST', body: { path: $('#asset-path').value, text: $('#asset-text').value, save } });
    if (save) { await initSetup(); if (path==='data/hansard/excerpts.json') await refreshCorpus(); }
    const future = path === 'nlp/config/models.json' || path.includes('evaluation') || path.startsWith('prompts/Evaluator')
      ? 'New analyses use this revision; active jobs retain captured settings.' : 'New sittings use this revision.';
    $('#asset-status').textContent = save ? `Saved validated contents. ${future}` : 'Contents are valid. Nothing saved yet.';
  } catch (error) { $('#asset-status').textContent = error.message; }
}

let sitting = null;
async function refreshPilots(selected=null) {
  try {
    const current=selected || $('#pilot-id').value, pilots=await api('/api/pilots');
    $('#pilot-id').replaceChildren(el('option',{text:'Choose pilot',attrs:{value:''}}),...pilots.map(pilot=>el('option',{
      text:`${pilot.sourceTitle} · ${pilot.evidenceType} · ${pilot.items} items · ${pilot.id.slice(0,8)}`,attrs:{value:pilot.id,selected:pilot.id===current}})));
    selectPilot();
  } catch(error) { $('#pilot-status').textContent=error.message; }
}
function selectPilot() {
  const id=$('#pilot-id').value, link=$('#download-pilot'); link.hidden=!id;
  if (id) { link.href=`/api/pilots/${encodeURIComponent(id)}?download=1`; link.download=`pilot-${id}.json`; }
}
async function importPilot(event) {
  const file=event.target.files[0]; if (!file) return;
  try {
    if (file.size>1500000) throw new Error('Choose pilot JSON up to 1.5 MB (one million characters).');
    const result=await api('/api/pilots',{method:'POST',body:JSON.parse(await readJsonFile(file))});
    await refreshPilots(result.id); $('#pilot-status').textContent=`Imported ${result.items} ${result.evidenceType} items. ${result.reviewStatus}.`;
  } catch(error) { $('#pilot-status').textContent=error.message; }
  finally { event.target.value=''; }
}
async function preparePilot() {
  try {
    const id=$('#pilot-id').value; if (!id) throw new Error('Choose a source pilot.');
    const result=await api(`/api/pilots/${encodeURIComponent(id)}/prepare`,{method:'POST',body:{seed:Number($('#pilot-seed').value)}});
    await refreshPilots(result.id); $('#pilot-status').textContent='Prepared 200 items with blank labels. Download, obtain real human review, then import the completed dataset.';
  } catch(error) { $('#pilot-status').textContent=error.message; }
}
async function evaluatePilot() {
  try {
    const pilot=$('#pilot-id').value; if (!pilot) throw new Error('Choose a reviewed pilot.');
    const methods=Array.from(document.querySelectorAll('input[name="analysis-method"]:checked')).map(input=>input.value);
    const {id}=await api(`/api/pilots/${encodeURIComponent(pilot)}/evaluate`,{method:'POST',body:{methods}});
    $('#pilot-status').textContent=`Pilot analysis job ${id} queued. Open its report in Local analysis.`; await refreshJobs();
  } catch(error) { $('#pilot-status').textContent=error.message; }
}

function openSitting(id) {
  closeSitting();
  writeStore(sessionStorage, SITTING_STORAGE_KEY, id);

  sitting = {
    id,
    members: [],
    topics: [],
    startedAt: Date.now(),
    currentTopic: 0,
    counts: new Map(),
    rulings: 0,
    pendingRulings: [],
    finished: false,
    receivedAny: false,
    log: [],
    source: null,
  };

  $('#transcript').replaceChildren(el('p', { className: 'empty-state', text: 'The House is assembling…' }));
  $('#floor').hidden = true;
  $('#jump-latest').hidden = true;
  followLatest = true;
  $('#ruling-status').textContent = '';
  setChairControlsEnabled(true);
  $('#adjourn-btn').hidden = false;
  $('#new-sitting-btn').hidden = true;

  $('#setup-view').hidden = true;
  $('#chamber-view').hidden = false;
  setStatus('sitting', 'House sitting');
  window.scrollTo({ top: 0 });

  const source = new EventSource(`/api/debates/${encodeURIComponent(id)}/events`);
  sitting.source = source;
  source.onmessage = (message) => {
    if (!sitting || sitting.source !== source) return;
    sitting.receivedAny = true;
    handleEvent(JSON.parse(message.data));
  };
  source.onerror = () => {
    if (!sitting || sitting.source !== source) return;
    if (!sitting.receivedAny && source.readyState === EventSource.CLOSED) {
      // Unknown sitting, e.g. a reload after the server restarted.
      closeSitting();
      showSetup();
    } else if (!sitting.finished) {
      setStatus('suspended', 'Reconnecting…');
    }
  };
}

function closeSitting() {
  if (sitting && sitting.source) sitting.source.close();
  sitting = null;
  writeStore(sessionStorage, SITTING_STORAGE_KEY, null);
  refreshSavedRuns();
}

function showSetup() {
  $('#chamber-view').hidden = true;
  $('#setup-view').hidden = false;
  setStatus('idle', 'House not sitting');
  document.title = 'Virtual Parliament · Pāremata Aotearoa';
}

function handleEvent(event) {
  if (!sitting.finished && $('#status').dataset.state !== 'sitting') setStatus('sitting', 'House sitting');

  switch (event.type) {
    case 'sitting': return onSitting(event);
    case 'topic': return onTopic(event);
    case 'calling': return onCalling(event);
    case 'speech': return onSpeech(event);
    case 'chair': return onChair(event);
    case 'error': return onError(event);
    case 'adjourned': return onAdjourned(event);
    default: return undefined;
  }
}

function onSitting(event) {
  event.members = event.members.map(member => ({ ...member, strategy: member.strategy || 'NONE' }));
  sitting.members = event.members;
  sitting.topics = event.topics;
  sitting.startedAt = event.startedAt || Date.now();
  for (const member of event.members) sitting.counts.set(member.name, { speeches: 0, interjections: 0 });

  $('#sitting-title').textContent = `Sitting of ${formatDate(sitting.startedAt)}`;
  document.title = `${event.topics[0]} · Virtual Parliament`;
  $('#transcript').replaceChildren();

  renderChamber();
  renderMemberList();
  renderAgenda();
  renderTally();

  sitting.log.push(
    'VIRTUAL PARLIAMENT: SIMULATED HANSARD',
    'Pāremata Aotearoa · AI House of Representatives',
    `Sitting of ${formatDate(sitting.startedAt)}, from ${formatTime(sitting.startedAt)}`,
    '',
    'Members present:',
    ...event.members.map((m) => `  ${m.name} (${m.partyName})`),
    '',
    event.demonstration ? 'Fixed demonstration speeches. No model calls were made.' : 'This is a simulated debate, not a record of the New Zealand Parliament.',
  );
}

function onTopic(event) {
  sitting.currentTopic = event.number;
  renderAgenda();
  appendEntry(el('div', { className: 'entry order-item' },
    el('p', { className: 'eyebrow', text: `Order Paper · Item ${event.number} of ${event.total}` }),
    el('h3', { text: event.topic })));
  sitting.log.push('', '', `ORDER PAPER, ITEM ${event.number} OF ${event.total}: ${event.topic.toUpperCase()}`);
}

function onCalling(event) {
  const style = partyStyle(event.party);
  $('#floor-seat').style.background = style.color;
  $('#floor-text').textContent = event.interjection
    ? `${event.name} rises to interject`
    : `${event.name} has the call`;
  $('#floor').hidden = false;
  setActiveMember(event.name, event.interjection);
  if (followLatest) scrollToLatest();
}

function onSpeech(event) {
  event.strategy = sitting.members.find(member => member.memberId === event.memberId)?.strategy || 'NONE';
  $('#floor').hidden = true;
  setActiveMember(null);

  const counts = sitting.counts.get(event.name);
  if (counts) counts[event.interjection ? 'interjections' : 'speeches'] += 1;
  renderTally();

  const style = partyStyle(event.party);
  const speakerLine = el('span', { className: 'speaker-line' },
    el('span', { className: 'speaker-name', text: event.name }),
    el('span', { className: 'speaker-affil', text: event.interjection ? `(${event.partyName}, interjecting)` : `(${event.partyName})` }),
    event.strategy !== 'NONE'
      ? el('span', { className: 'tag tag-disruptor', text: strategyLabel(event.strategy) })
      : null);

  appendEntry(el('div', {
    className: `entry ${event.interjection ? 'interjection' : 'speech'}`,
    style: { '--party': style.color },
  }, speakerLine, el('p', { text: event.text })));

  sitting.log.push('', event.interjection
    ? `    ${event.name} (interjecting): ${event.text}`
    : `${event.name.toUpperCase()} (${event.partyName}): ${event.text}`);
}

function onChair(event) {
  // Rulings typed by the user are echoed back when the engine reads them to the House.
  const pendingIndex = sitting.pendingRulings.indexOf(event.text);
  const isUserRuling = event.ruling === true || pendingIndex !== -1;
  if (pendingIndex !== -1) {
    sitting.pendingRulings.splice(pendingIndex, 1);
  }
  if (isUserRuling) {
    sitting.rulings += 1;
    renderTally();
    updateRulingStatus();
  }

  appendEntry(el('div', { className: 'entry chair-entry' },
    el('span', { className: 'speaker-line' },
      el('span', { className: 'speaker-name', text: 'Speaker' }),
      isUserRuling ? el('span', { className: 'tag tag-you', text: 'Your ruling' }) : null),
    el('p', { text: event.text })));
  flashChair();
  sitting.log.push('', `SPEAKER: ${event.text}`);
}

function onError(event) {
  appendEntry(el('div', { className: 'entry system-entry is-error' },
    el('strong', { text: 'The sitting was suspended because of an error.' }),
    el('span', { text: event.message })));
  sitting.log.push('', `[The sitting was suspended because of an error: ${event.message}]`);
}

function onAdjourned(event) {
  sitting.finished = true;
  sitting.source.close();
  $('#floor').hidden = true;
  setActiveMember(null);
  if (sitting.currentTopic && event.outcome === 'complete') sitting.currentTopic = sitting.topics.length + 1;
  renderAgenda();

  const time = formatTime(event.at || Date.now());
  const text = {
    complete: `The House adjourned at ${time}.`,
    adjourned: `The Speaker adjourned the House at ${time}.`,
    error: `The House rose at ${time}.`,
    interrupted: `The sitting was interrupted before ${time}. Saved speeches are retained.`,
  }[event.outcome] || `The House adjourned at ${time}.`;
  appendEntry(el('p', { className: 'entry system-entry', text }));
  sitting.log.push('', text);

  if (event.outcome === 'error') setStatus('suspended', 'Sitting suspended');
  else setStatus('adjourned', 'House adjourned');

  setChairControlsEnabled(false);
  $('#adjourn-btn').hidden = true;
  $('#new-sitting-btn').hidden = false;
  if (sitting.pendingRulings.length) {
    sitting.pendingRulings = [];
    $('#ruling-status').textContent = 'The House adjourned before your last ruling could be read.';
  }
  writeStore(sessionStorage, SITTING_STORAGE_KEY, null);
  refreshSavedRuns();
}

/* Transcript scrolling: follow new speeches unless the reader has scrolled up to read. */

let followLatest = true;

function isNearBottom() {
  const scroller = $('#transcript-scroller');
  return scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
}

function appendEntry(node) {
  const empty = $('#transcript .empty-state');
  if (empty) empty.remove();
  $('#transcript').append(node);
  if (followLatest) scrollToLatest();
  else $('#jump-latest').hidden = false;
}

function scrollToLatest() {
  const scroller = $('#transcript-scroller');
  scroller.scrollTop = scroller.scrollHeight;
}

/* Chamber seating plan */

const CHAMBER = { cx: 160, cy: 96, r: 104 };

function seatPosition(index, count) {
  const span = Math.min(140, (count - 1) * 36);
  const degrees = count === 1 ? 90 : 90 + span / 2 - (index * span) / (count - 1);
  const radians = (degrees * Math.PI) / 180;
  return { x: CHAMBER.cx + CHAMBER.r * Math.cos(radians), y: CHAMBER.cy + CHAMBER.r * Math.sin(radians) };
}

function arcPoint(degrees, radius) {
  const radians = (degrees * Math.PI) / 180;
  return `${(CHAMBER.cx + radius * Math.cos(radians)).toFixed(1)} ${(CHAMBER.cy + radius * Math.sin(radians)).toFixed(1)}`;
}

function benchArc(radius, fromDeg, toDeg) {
  return `M${arcPoint(fromDeg, radius)} A${radius} ${radius} 0 0 0 ${arcPoint(toDeg, radius)}`;
}

function renderChamber() {
  const svg = $('#chamber-svg');
  const { cx, cy, r } = CHAMBER;

  const seats = sitting.members.map((member, index) => {
    const { x, y } = seatPosition(index, sitting.members.length);
    const style = partyStyle(member.party);
    const group = svgEl('g', { class: 'seat', 'data-member': member.name },
      svgEl('title', {}, `${member.name}${member.strategy !== 'NONE' ? ` (disruptor: ${strategyLabel(member.strategy)})` : ''}`),
      svgEl('circle', { class: 'seat-ring', cx: x, cy: y, r: 20 }),
      svgEl('circle', { class: 'seat-circle', cx: x, cy: y, r: 17, fill: style.color }),
      svgEl('text', {
        class: 'seat-abbr', x, y: y + 3.5, 'text-anchor': 'middle', fill: style.ink,
      }, style.abbr));
    if (member.strategy !== 'NONE') {
      group.append(
        svgEl('circle', { class: 'disruptor-mark', cx: x + 13, cy: y - 13, r: 6 }),
        svgEl('text', { class: 'disruptor-bang', x: x + 13, y: y - 10, 'text-anchor': 'middle' }, '!'),
      );
    }
    return group;
  });

  svg.replaceChildren(
    svgEl('title', {}, 'Seating plan of the debating chamber'),
    svgEl('ellipse', { class: 'floor', cx, cy: cy + 8, rx: r - 26, ry: r - 22 }),
    svgEl('path', { class: 'bench', d: benchArc(r, 172, 8), 'stroke-width': 40 }),
    svgEl('path', { class: 'bench-inner', d: benchArc(r + 13, 170, 10), 'stroke-width': 1.5 }),
    // The Table, with the mace lying on it
    svgEl('rect', { class: 'table', x: cx - 17, y: cy - 36, width: 34, height: 70, rx: 4 }),
    svgEl('line', { class: 'mace', x1: cx, y1: cy - 26, x2: cx, y2: cy + 20 }),
    svgEl('circle', { class: 'mace-head', cx, cy: cy - 27, r: 4.5 }),
    // The Speaker's chair
    svgEl('rect', { class: 'chair-glow', x: cx - 21, y: 3, width: 42, height: 34, rx: 8 }),
    svgEl('rect', { class: 'chair-back', x: cx - 15, y: 6, width: 30, height: 20, rx: 6 }),
    svgEl('rect', { class: 'chair-seat', x: cx - 17, y: 22, width: 34, height: 11, rx: 3 }),
    svgEl('text', { class: 'label', x: cx + 26, y: 24 }, 'Speaker'),
    ...seats,
  );
}

function renderMemberList() {
  $('#member-list').replaceChildren(...sitting.members.map((member) => el('li', { attrs: { 'data-member': member.name } },
    seatBadge(member.party),
    el('span', {},
      el('span', { className: 'member-name', text: member.name }),
      el('span', {
        className: `member-role${member.strategy !== 'NONE' ? ' is-disruptor' : ''}`,
        text: member.assignmentKnown === false ? 'Assignment unknown · imported transcript'
          : member.strategy === 'NONE' ? 'Cooperative' : `Disruptor: ${strategyLabel(member.strategy)}`,
      })))));
}

function setActiveMember(name, interjection = false) {
  for (const seat of document.querySelectorAll('#chamber-svg .seat')) {
    const active = seat.dataset.member === name;
    seat.classList.toggle('is-speaking', active && !interjection);
    seat.classList.toggle('is-interjecting', active && interjection);
  }
  for (const item of document.querySelectorAll('#member-list li')) {
    item.classList.toggle('is-active', item.dataset.member === name);
  }
}

function flashChair() {
  const svg = $('#chamber-svg');
  svg.classList.remove('chair-speaking');
  // Force a reflow so the animation restarts for back-to-back rulings.
  void svg.getBoundingClientRect();
  svg.classList.add('chair-speaking');
}

function renderAgenda() {
  $('#agenda').replaceChildren(...sitting.topics.map((topic, index) => {
    const number = index + 1;
    const state = number < sitting.currentTopic ? 'is-done' : number === sitting.currentTopic ? 'is-current' : '';
    return el('li', { className: state, attrs: { 'aria-current': state === 'is-current' ? 'step' : null } },
      el('span', { text: topic }));
  }));
}

function renderTally() {
  $('#tally-body').replaceChildren(...sitting.members.map((member) => {
    const counts = sitting.counts.get(member.name) || { speeches: 0, interjections: 0 };
    return el('tr', {},
      el('th', { attrs: { scope: 'row' } },
        el('span', { className: 'dot', style: { background: partyStyle(member.party).color } }),
        member.partyName),
      el('td', { text: String(counts.speeches) }),
      el('td', { text: String(counts.interjections) }));
  }));
  $('#tally-rulings').textContent = String(sitting.rulings);
}

/* The Speaker's chair */

function renderQuickRulings() {
  $('#quick-rulings').replaceChildren(...QUICK_RULINGS.map((ruling) => el('button', {
    className: 'chip',
    text: ruling,
    attrs: { type: 'button' },
    on: { click: () => issueRuling(ruling) },
  })));
}

function setChairControlsEnabled(enabled) {
  for (const control of document.querySelectorAll('#quick-rulings button, #ruling-input, #ruling-btn')) {
    control.disabled = !enabled;
  }
}

async function issueRuling(text) {
  const ruling = text.trim();
  if (!ruling || !sitting || sitting.finished) return;
  const status = $('#ruling-status');
  status.classList.remove('is-error');
  try {
    // Record it before posting: the engine can echo it back before the POST response arrives.
    sitting.pendingRulings.push(ruling);
    await api(`/api/debates/${encodeURIComponent(sitting.id)}/speaker`, { method: 'POST', body: { message: ruling } });
    $('#ruling-input').value = '';
    updateRulingStatus();
  } catch (error) {
    const index = sitting.pendingRulings.indexOf(ruling);
    if (index !== -1) sitting.pendingRulings.splice(index, 1);
    status.textContent = error.message;
    status.classList.add('is-error');
  }
}

function updateRulingStatus() {
  const pending = sitting.pendingRulings.length;
  $('#ruling-status').textContent = pending
    ? `${pending === 1 ? 'Your ruling' : `${pending} rulings`} will be read to the House once the current member finishes.`
    : (sitting.rulings ? 'The House has heard your ruling.' : '');
}

async function adjourn() {
  if (!sitting || sitting.finished) return;
  if (!window.confirm('Adjourn the House? The debate will stop and cannot be resumed.')) return;
  const button = $('#adjourn-btn');
  button.disabled = true;
  setStatus('sitting', 'Adjourning…');
  try {
    await api(`/api/debates/${encodeURIComponent(sitting.id)}/adjourn`, { method: 'POST', body: {} });
  } catch (error) {
    $('#ruling-status').textContent = error.message;
    $('#ruling-status').classList.add('is-error');
  } finally {
    button.disabled = false;
  }
}

async function downloadTranscript() {
  if (!sitting) return;
  const link = el('a', {
    attrs: {
      href: `/api/debates/${encodeURIComponent(sitting.id)}/export?download=1`,
      download: `parliament-${sitting.id}.txt`,
    },
  });
  document.body.append(link);
  link.click();
  link.remove();
}

/* =========================================================================
   Wiring
   ========================================================================= */

async function refreshSavedRuns() {
  try {
    const runs = await api('/api/debates');
    $('#saved-runs').replaceChildren(...runs.slice(0, 30).map(run => el('div', { className: 'saved-row' },
      el('span', { text: `${run.topics.map(topic => topic.title).join(' · ')} — ${run.outcome.toLowerCase()}, ${run.speeches} contributions` }),
      el('button', { className: 'btn btn-secondary', text: 'Open', attrs: { type: 'button' }, on: { click: () => openSitting(run.id) } }))));
    if (!runs.length) $('#saved-runs').textContent = 'No saved sittings yet.';
    $('#saved-status').textContent = '';
  } catch (error) { $('#saved-status').textContent = error.message; }
}

async function importTranscript(event) {
  const file = event.target.files[0];
  if (!file) return;
  try {
    if (file.size > 2 * 1024 * 1024) throw new Error('Transcript must be at most 2 MiB.');
    const { id } = await api('/api/debates/import', { method: 'POST', body: JSON.parse(await readJsonFile(file)) });
    await refreshSavedRuns();
    openSitting(id);
  } catch (error) { $('#saved-status').textContent = `Import failed: ${error.message}`; }
  finally { event.target.value = ''; }
}

async function downloadJson() {
  if (!sitting) return;
  const link = el('a', { attrs: { href: `/api/debates/${encodeURIComponent(sitting.id)}/transcript?download=1`, download: `parliament-${sitting.id}.json` } });
  document.body.append(link); link.click(); link.remove();
}

async function refreshJobs() {
  try {
    const jobs = await api('/api/jobs');
    $('#analysis-jobs').replaceChildren(...jobs.slice(0,30).map(job => el('div', { className:'saved-row' },
      el('span', { text: `${job.kind} · ${job.state.toLowerCase()} · ${job.progress}` }),
      el('button', { className:'btn btn-secondary', text:'Open report', attrs:{ type:'button' }, on:{click:()=>openReport(job.id)} }),
      ['QUEUED','RUNNING'].includes(job.state) ? el('button', { className:'btn btn-secondary', text:'Cancel job', attrs:{type:'button'},
        on:{click:async()=>{ try { await api(`/api/jobs/${encodeURIComponent(job.id)}/cancel`,{method:'POST',body:{}}); await refreshJobs(); }
          catch(error){$('#analysis-status').textContent=error.message;} }} }) : null)));
  } catch(error) { $('#analysis-status').textContent=error.message; }
}

async function refreshLocalReadiness() {
  try {
    const state=await api('/api/local-readiness');
    $('#local-readiness').textContent=state.running ? 'Local service is running. Methods load when selected.'
      : state.installed ? 'Dependencies installed. Evaluation starts the local service when needed.' : 'Local dependencies are not installed. Set up VADER to begin.';
    if (state.running && state.service?.readiness) $('#local-readiness').textContent += ' ' + Object.entries(state.service.readiness)
      .map(([method, details]) => `${method}: ${!details.dependenciesPresent ? 'dependencies missing' : !details.filesCached ? 'files missing' : details.loaded ? 'loaded' : 'files cached, not loaded'}`).join('; ') + '.';
  } catch(error) { $('#local-readiness').textContent=error.message; }
}

async function refreshOllamaReadiness() {
  try {
    const state=await api('/api/ollama-readiness');
    $('#ollama-readiness').textContent=state.running ? `Ollama ${state.version} responds (${state.owned ? 'started by this backend' : 'existing service'}). Context budget: ${state.settings.contextTokens} tokens.`
      : state.installed ? 'Portable runtime installed. Using a local model starts its service when needed.' : 'Portable runtime is not installed. Set up the runtime, then cache a local model.';
    $('#ollama-runtime-size').textContent=`Runtime ${state.runtimePackage.version}: ${(state.runtimePackage.bytes/1e6).toFixed(0)} MB download if not already installed. Model weights are separate.`;
    $('#ollama-models').replaceChildren(...state.models.map(model=>el('p',{className:'hint',text:`${model.name} · ${(model.bytes/1e9).toFixed(2)} GB · ${model.local ? 'local files cached' : 'remote alias; cannot be used for local generation'}`})));
  } catch(error) { $('#ollama-readiness').textContent=error.message; }
}

async function setupOllama(downloadModel=false) {
  const button=$(downloadModel ? '#download-ollama-model' : '#setup-ollama'); button.disabled=true;
  try {
    const {id}=await api(downloadModel ? '/api/ollama-model-setup' : '/api/ollama-setup',
      {method:'POST',body:downloadModel ? {modelPreset:$('#ollama-model-preset').value} : {}});
    $('#ollama-status').textContent=`${downloadModel ? 'Model download' : 'Runtime setup'} job ${id} queued. Open Background jobs to inspect progress or cancel.`;
    await refreshJobs();
  } catch(error) { $('#ollama-status').textContent=error.message; }
  finally { button.disabled=downloadModel && $('#ollama-model-preset').options.length===0; }
}

async function setupLocal() {
  try {
    const {id}=await api('/api/local-setup',{method:'POST',body:{}});
    $('#analysis-status').textContent=`Setup job ${id} queued.`; await refreshJobs();
  } catch(error) { $('#analysis-status').textContent=error.message; }
}

async function setupLocalModel(method) {
  try {
    const {id}=await api('/api/local-model-setup',{method:'POST',body:{method}});
    $('#analysis-status').textContent=`Model setup job ${id} queued. Downloads remain explicit.`; await refreshJobs();
  } catch(error) { $('#analysis-status').textContent=error.message; }
}

async function evaluateSitting() {
  if (!sitting) return;
  try {
    const methods=Array.from(document.querySelectorAll('input[name="analysis-method"]:checked')).map(input=>input.value);
    if (!methods.length) throw new Error('Select at least one analysis method.');
    const {id}=await api(`/api/debates/${encodeURIComponent(sitting.id)}/evaluate`,{method:'POST',body:{methods}});
    $('#analysis-status').textContent=`Analysis job ${id} captures the speeches saved so far.`; await refreshJobs();
  } catch(error) { $('#analysis-status').textContent=error.message; }
}

async function openReport(id) {
  try {
    const job=await api(`/api/jobs/${encodeURIComponent(id)}`), result=job.result;
    $('#analysis-status').textContent=job.progress;
    if (!result) { $('#analysis-report').replaceChildren(); return; }
    const contents=[el('h3',{text:`${job.kind} · ${job.state.toLowerCase()}`})];
    if (job.kind.startsWith('ollama-')) {
      if (result.message) contents.push(el('p',{text:result.message}));
      if (result.running!==undefined) contents.push(el('p',{text:result.running ? `Ollama ${result.version} responds. ${result.owned ? 'This backend owns its service.' : 'An existing service is reused.'}` : 'Local service is unavailable or incompatible.'}));
      if (result.totalBytes!=null) contents.push(el('p',{text:`Download progress: ${result.completedBytes || 0} / ${result.totalBytes} bytes.`}));
      for (const model of result.models || []) contents.push(el('p',{text:`${model.name}: ${model.local ? 'local files cached' : 'remote alias refused'} · ${(model.bytes/1e9).toFixed(2)} GB. Cached files have not been behaviourally verified.`}));
    }
    if (result.assessments) {
      const assessment=result.assessments;
      contents.push(el('p',{text:`Evaluator: ${assessment.model.provider} / ${assessment.model.model}. Rubric: ${assessment.rubric.version}. Scores remain separate; rhetorical tactics measures disruption.`}));
      for (const topic of assessment.topics) {
        const section=el('details',{},el('summary',{text:`${topic.topicId} · ${topic.status}${topic.error ? ` · ${topic.error}` : ''}`}));
        for (const participant of topic.assessment?.participants || []) {
          section.append(el('h4',{text:participant.participantId}));
          for (const metric of participant.metrics) section.append(el('p',{text:`${metric.metricId}: ${metric.score==null ? metric.status : metric.score} — ${metric.explanation} Evidence: ${metric.evidenceTurnIds.join(', ') || 'none'}.`}));
        }
        contents.push(section);
      }
    }
    if (result.pilotId) {
      contents.push(el('p',{text:`${result.evidenceType}: ${result.interpretation}`}));
      for (const method of result.methods) {
        const metric=method.metrics;
        contents.push(el('details',{},el('summary',{text:`${method.methodId} · ${method.split} · ${metric.count}/${method.plannedItems} completed items`}),
          el('p',{text:`Macro-F1 ${metric.macroF1.toFixed(3)} · coverage ${(100*metric.coverage).toFixed(1)}% · failures ${metric.failures} · abstentions ${metric.abstentions} · mean ${metric.meanLatencyMillis.toFixed(1)} ms · p95 ${metric.p95LatencyMillis.toFixed(1)} ms`}),
          el('pre',{text:JSON.stringify(metric.confusionMatrix,null,2)})));
      }
    }
    if (result.methods && !result.pilotId) for (const method of result.methods) {
      const section=el('details',{},el('summary',{text:`${method.methodId} · ${method.status}${method.error ? ` · ${method.error}` : ''}`}));
      contents.push(section);
      for (const batch of method.batches) for (const item of batch.items) {
        section.append(el('p',{text:`${item.turnId}${item.targetId ? ` → ${item.targetId}` : ''}: ${item.status}${item.error ? ` · ${item.error}` : ''}`}));
        for (const chunk of item.chunks) section.append(el('p',{className:'analysis-chunk',text:`${chunk.text} — ${chunk.label}${chunk.compound!=null ? ` (compound ${chunk.compound})` :
          ` (${Object.entries(chunk.scores).map(([label,score])=>`${label} ${score.toFixed(3)}`).join(', ')}; ${chunk.uncertainty?.abstained ? 'abstained' : 'uncalibrated scores'})`}`}));
      }
    }
    contents.push(el('a',{text:'Download report JSON',attrs:{href:`/api/jobs/${encodeURIComponent(id)}/report?download=1`,download:`report-${id}.json`}}));
    contents.push(el('details',{},el('summary',{text:job.kind.startsWith('ollama-') ? 'Local setup details' : 'Evidence, provenance and separate scores'}),el('pre',{text:JSON.stringify(result,null,2)})));
    $('#analysis-report').replaceChildren(...contents);
  } catch(error) { $('#analysis-status').textContent=error.message; }
}

function wireEvents() {
  $('#refresh-ollama').addEventListener('click',refreshOllamaReadiness);
  $('#setup-ollama').addEventListener('click',()=>setupOllama());
  $('#download-ollama-model').addEventListener('click',()=>setupOllama(true));
  $('#setup-local').addEventListener('click',setupLocal);
  $('#pilot-file').addEventListener('change',importPilot);
  $('#pilot-id').addEventListener('change',selectPilot);
  $('#refresh-pilots').addEventListener('click',()=>refreshPilots());
  $('#prepare-pilot').addEventListener('click',preparePilot);
  $('#evaluate-pilot').addEventListener('click',evaluatePilot);
  $('#setup-cardiff').addEventListener('click',()=>setupLocalModel('cardiff-sentiment'));
  $('#setup-deberta').addEventListener('click',()=>setupLocalModel('deberta-stance'));
  $('#refresh-jobs').addEventListener('click',async()=>{await refreshJobs(); await refreshLocalReadiness(); await refreshOllamaReadiness();});
  $('#evaluate-btn').addEventListener('click',evaluateSitting);
  $('#evaluate-llm-btn').addEventListener('click',async()=>{
    if (!sitting) return;
    try {
      const preset=$('#analysis-llm-model').value;
      const {id}=await api(`/api/debates/${encodeURIComponent(sitting.id)}/evaluate-llm`,{method:'POST',body:preset ? {modelPreset:preset} : {}});
      $('#analysis-status').textContent=`LLM evaluation job ${id} captures public evidence and evaluator settings.`;
      await refreshJobs();
    } catch(error) { $('#analysis-status').textContent=error.message; }
  });
  $('#load-settings').addEventListener('click', loadSettings);
  $('#save-settings').addEventListener('click', saveSettings);
  $('#read-asset').addEventListener('click', readAsset);
  $('#validate-asset').addEventListener('click', () => updateAsset(false));
  $('#save-asset').addEventListener('click', () => updateAsset(true));
  $('#refresh-corpus').addEventListener('click',refreshCorpus);
  $('#corpus-file').addEventListener('change',selectCorpus);
  $('#validate-corpus').addEventListener('click',()=>updateCorpus(false));
  $('#import-corpus').addEventListener('click',()=>updateCorpus(true));
  $('#agent-model').addEventListener('change', event => { setup.agentModelPreset = event.target.value; saveSetup(); });
  $('#grounding-count').addEventListener('input',event=>{ setup.groundingCount=Number(event.target.value); saveSetup(); });
  $('#evaluator-model').addEventListener('change', event => { setup.evaluatorModelPreset = event.target.value; saveSetup(); });
  $('#refresh-runs').addEventListener('click', refreshSavedRuns);
  $('#import-transcript').addEventListener('change', importTranscript);
  $('#export-json-btn').addEventListener('click', downloadJson);
  $('#setup-form').addEventListener('submit', convene);
  $('#add-topic').addEventListener('click', () => {
    addTopic($('#new-topic').value);
    $('#new-topic').value = '';
    $('#new-topic').focus();
  });
  $('#new-topic').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      $('#add-topic').click();
    }
  });
  $('#rounds-down').addEventListener('click', () => { setup.rounds = clampRounds(setup.rounds - 1); renderRounds(); });
  $('#rounds-up').addEventListener('click', () => { setup.rounds = clampRounds(setup.rounds + 1); renderRounds(); });

  $('#ruling-form').addEventListener('submit', (event) => {
    event.preventDefault();
    issueRuling($('#ruling-input').value);
  });
  $('#ruling-input').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      issueRuling($('#ruling-input').value);
    }
  });
  $('#adjourn-btn').addEventListener('click', adjourn);
  $('#new-sitting-btn').addEventListener('click', () => { closeSitting(); showSetup(); });
  $('#export-btn').addEventListener('click', downloadTranscript);

  $('#transcript-scroller').addEventListener('scroll', () => {
    followLatest = isNearBottom();
    if (followLatest) $('#jump-latest').hidden = true;
  });
  $('#jump-latest').addEventListener('click', () => {
    $('#jump-latest').hidden = true;
    followLatest = true;
    scrollToLatest();
  });
}

async function main() {
  wireEvents();
  renderQuickRulings();
  await initSetup();
  try {
    await refreshSettings();
    const paths = await api('/api/assets');
    $('#asset-path').replaceChildren(...paths.map(path => el('option', { text: path, attrs: { value: path } })));
  } catch (error) { $('#settings-status').textContent = error.message; }
  await refreshSavedRuns();
  await refreshCorpus();
  await refreshPilots();
  await refreshJobs(); await refreshLocalReadiness(); await refreshOllamaReadiness();
  setInterval(refreshJobs,3000);

  // Rejoin a sitting in progress after a page reload.
  const savedSitting = readStore(sessionStorage, SITTING_STORAGE_KEY);
  if (savedSitting && config) openSitting(savedSitting);
}

main();

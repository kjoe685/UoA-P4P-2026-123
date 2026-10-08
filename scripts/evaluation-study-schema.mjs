// Researcher condition metadata only. Never copy private prompts into evidence.
import crypto from 'node:crypto';

export const digest = value => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex');

export function strategyCondition(cell, frozenHashes) {
  const members = cell.settings?.members;
  if (!Array.isArray(members) || !members.length) throw new Error('Strategy assignments must be explicitly recorded');
  const assignments = members.map(member => {
    const strategies = [...new Set(Array.isArray(member.strategy) ? member.strategy : [member.strategy])].sort();
    if (!member.party || !strategies.length || strategies.some(s => typeof s !== 'string' || !/^[A-Z][A-Z0-9_]*$/.test(s))) throw new Error('Invalid strategy assignment');
    if (strategies.length > 1 && strategies.includes('NONE')) throw new Error('NONE cannot accompany an adversarial strategy');
    return {party: member.party, strategies};
  }).sort((a,b) => a.party.localeCompare(b.party));
  if (new Set(assignments.map(a => a.party)).size !== assignments.length) throw new Error('Duplicate party assignment');
  const resources = Object.entries(frozenHashes).filter(([name]) => name.startsWith('prompts/strategies/')).sort(([a],[b]) => a.localeCompare(b));
  if (!resources.length) throw new Error('Missing frozen strategy prompt provenance');
  const configSha256 = digest(resources);
  const active = assignments.filter(a => a.strategies.some(s => s !== 'NONE'));
  const strategies = [...new Set(active.flatMap(a => a.strategies))].sort();
  const label = active.length ? active.map(a => `${a.party}=${a.strategies.join('+')}`).join('; ') : 'NONE';
  const id = 'SC-' + digest([assignments, configSha256]);
  const speaker = party => {
    const assignment = assignments.find(a => a.party === party);
    if (!assignment) throw new Error('Speaker has no strategy assignment');
    return {strategy: assignment.strategies.join('+'), role: !active.length ? 'BASELINE' : assignment.strategies.includes('NONE') ? 'OTHER' : 'ASSIGNED'};
  };
  return {id,label,assignments,assignmentJson:JSON.stringify(assignments),parties:active.map(a => a.party).join(', '),partyCount:active.length,strategies:strategies.join(', '),strategyCount:strategies.length,configSha256,speaker};
}

export function studyConditionId(cell, model, strategy, corpusSha256) {
  return 'DC-' + digest([cell.topicKey,cell.policyTarget,cell.groundingCount,cell.repetition,cell.settings.rounds,cell.speakingOrder,model,strategy.id,corpusSha256]);
}

export function summaryKey(group, modelKey, grounding, strategyId) {
  return 'SG-' + digest([group,modelKey,grounding,strategyId]);
}

// Linear-time source indexing; avoid rescanning every sentence for every speech.
export function indexSentenceGroups(rows) {
  const groups = new Map(), widths = new Map();
  for (const row of rows) {
    if (row[13] !== 'ok' || row[9] == null) continue;
    const key = `${row[1]}|${row[7]}|${row[8]}`;
    const items = groups.get(key) ?? [];
    items.push(row); groups.set(key,items);
    widths.set(row[8],Math.max(widths.get(row[8]) ?? 1,items.length));
  }
  return {groups,widths};
}

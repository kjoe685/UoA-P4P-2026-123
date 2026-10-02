/* Developer regression: actual browser replay callbacks with epoch/long metadata. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const names = ['formatDate', 'formatTime', 'onSitting', 'onAdjourned'];
const definitions = names.map(name => {
  const match = source.match(new RegExp(`function ${name}\\([^\\n]*\\) \\{[\\s\\S]*?\\r?\\n\\}`));
  assert.ok(match, `Actual browser callback ${name} must be available`);
  return match[0];
});
const now = 1924992000000;
class FixedDate extends Date { static now() { return now; } }
let nodes, entries, closed;
const context = vm.createContext({
  Date: FixedDate,
  $: selector => nodes[selector] ||= { textContent: '', hidden: false, replaceChildren() {} },
  document: {},
  renderChamber() {}, renderMemberList() {}, renderAgenda() {}, renderTally() {},
  setActiveMember() {}, setStatus() {}, setChairControlsEnabled() {},
  writeStore() {}, refreshSavedRuns() {}, sessionStorage: {}, SITTING_STORAGE_KEY: 'fixture',
  el: (tag, props) => ({ text: props.text }), appendEntry: entry => entries.push(entry.text),
});
vm.runInContext(definitions.join('\n'), context);
function reset() {
  nodes = {}; entries = []; closed = 0;
  context.sitting = { counts: new Map(), log: [], pendingRulings: [], currentTopic: 1, source: { close() { closed++; } } };
}
function start(timestamp) {
  context.onSitting({ startedAt: timestamp, members: [{ name: 'Māori synthetic member', partyName: 'Labour' }], topics: ['Housing'], demonstration: false });
}
const checks = [];
function check(name, test) { checks.push({ name, test }); }
check('epoch zero retains its public start time and date', () => {
  start(0);
  assert.equal(context.sitting.startedAt, 0);
  assert.equal(nodes['#sitting-title'].textContent, `Sitting of ${context.formatDate(0)}`);
  assert.match(context.sitting.log.join('\n'), /1970/);
});
check('epoch zero retains its public end time', () => {
  start(0); context.onAdjourned({ at: 0, outcome: 'complete' });
  assert.equal(entries[0], `The House adjourned at ${context.formatTime(0)}.`);
  assert.equal(closed, 1); assert.equal(context.sitting.finished, true);
});
check('ordinary replay uses the supplied time rather than the current time', () => {
  const supplied = 1790928000000;
  start(supplied); context.onAdjourned({ at: supplied + 60000, outcome: 'adjourned' });
  assert.equal(context.sitting.startedAt, supplied);
  assert.equal(nodes['#sitting-title'].textContent, `Sitting of ${context.formatDate(supplied)}`);
  assert.equal(entries[0], `The Speaker adjourned the House at ${context.formatTime(supplied + 60000)}.`);
});
check('missing legacy timestamps use the existing current-time fallback', () => {
  start(undefined); context.onAdjourned({ outcome: 'error' });
  assert.equal(context.sitting.startedAt, now);
  assert.equal(entries[0], `The House rose at ${context.formatTime(now)}.`);
});
check('accepted long metadata outside Date range has a readable replay', () => {
  const timestamp = Number(9007199254740993n);
  start(timestamp);
  assert.equal(context.sitting.startedAt, timestamp);
  assert.equal(nodes['#sitting-title'].textContent, 'Sitting of the House');
  assert.match(context.sitting.log.join('\n'), /date and time unavailable/i);
  for (const outcome of ['complete', 'adjourned', 'error', 'interrupted']) {
    context.onAdjourned({ at: timestamp, outcome });
    assert.match(entries.at(-1), /time unavailable/i);
    assert.doesNotMatch(entries.at(-1), /Invalid Date|NaN|null|undefined/);
  }
  assert.doesNotMatch(context.sitting.log.join('\n'), /Invalid Date|NaN|null|undefined/);
});
check('the inclusive JavaScript Date limit still formats', () => {
  const limit = 8640000000000000;
  start(limit); context.onAdjourned({ at: limit, outcome: 'complete' });
  assert.doesNotMatch(nodes['#sitting-title'].textContent, /unavailable|Invalid Date/);
  assert.doesNotMatch(entries[0], /unavailable|Invalid Date/);
});
let passed = 0;
for (const { name, test } of checks) {
  reset();
  try { test(); console.log(`PASS ${name}`); passed++; }
  catch (error) { console.error(`FAIL ${name}: ${error.message}`); process.exitCode = 1; }
}
console.log(`${passed}/${checks.length} browser replay date checks passed`);

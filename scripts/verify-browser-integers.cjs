/* Optional developer check: actual numeric controls/functions with controlled DOM/fetch/storage. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const required = ['api', 'renderParties', 'selectedMembers', 'currentSettings', 'preparePilot', 'showFormError'];
const definitions = [];
for (const name of [...required, 'integerText', 'groundingInput', 'updateGroundingInput']) {
  const match = source.match(new RegExp(`(?:async )?function ${name}\\([^\\n]*\\) \\{[\\s\\S]*?\\r?\\n\\}`));
  if (required.includes(name)) assert.ok(match, `Frontend function ${name} must be available`);
  if (match) definitions.push(match[0]);
}
const globalBinding = source.match(/^\s*\$\('#grounding-count'\)\.addEventListener\('input',[^\n]+\);/m);
assert.ok(globalBinding, 'Shared grounding input must be bound');
let nodes, requests, saves, refreshed;
function node() {
  return { value: '', textContent: '', events: {}, validity: '',
    addEventListener(type, callback) { this.events[type] = callback; },
    setCustomValidity(message) { this.validity = message; },
    replaceChildren(...children) { this.children = children; } };
}
function selected(selector) { return nodes[selector] ||= node(); }
const context = vm.createContext({
  $: selected,
  partyStyle: () => ({ color: '#000', ink: '#fff' }), seatBadge: () => null,
  capitalise: value => value, saveSetup: () => { saves++; },
  el: (tag, props = {}, ...children) => {
    const element = props.attrs?.id ? selected('#' + props.attrs.id) : node();
    if (props.attrs?.value !== undefined) element.value = String(props.attrs.value);
    Object.assign(element.events, props.on || {}); element.children = children;
    return element;
  },
  fetch: async (url, init) => {
    requests.push({ url, ...init }); return { ok: true, json: async () => ({ id: 'prepared-fixture' }) };
  },
  refreshPilots: async () => { refreshed++; },
});
vm.runInContext(definitions.join('\n'), context);
function reset() {
  nodes = {}; requests = []; saves = 0; refreshed = 0;
  context.config = { parties: [{ id: 'LABOUR', name: 'Labour', ideology: 'Fixture', groundingAvailable: 100 }],
    models: { demo: {} }, strategies: [{ id: 'NONE' }] };
  context.setup = { topics: ['Housing'], policyTargets: [null], rounds: 1, groundingCount: -1,
    agentModelPreset: 'demo', evaluatorModelPreset: 'demo',
    members: { LABOUR: { included: true, strategy: 'NONE', modelPreset: '', groundingCount: null } } };
  selected('#grounding-count').value = '-1'; selected('#pilot-id').value = 'source-fixture';
  context.renderParties(); vm.runInContext(globalBinding[0], context); saves = 0;
}
function input(selector, value) {
  const element = selected(selector); element.value = value;
  element.events.input({ target: element }); return element;
}
const checks = [];
function check(name, test) { checks.push({ name, test }); }
for (const [selector, read] of [
  ['#grounding-count', () => context.setup.groundingCount],
  ['#grounding-LABOUR', () => context.setup.members.LABOUR.groundingCount],
]) {
  check(`${selector} rejects rounded/fractional/exponent text without saving drafts`, () => {
    const previous = read();
    for (const value of ['1.0000000000000000001', '1e-400', '-1e-400', '1.0', '1e0', '-2', '1001']) {
      const element = input(selector, value);
      assert.equal(read(), previous); assert.equal(saves, 0); assert.equal(requests.length, 0);
      assert.match(element.validity, /Grounding count/); assert.match(selected('#form-error').textContent, /Grounding count/);
      assert.throws(() => context.currentSettings(), /Grounding count/, 'Saving/starting must recheck the current control');
    }
  });
  check(`${selector} retains whole all/zero/maximum values and clears validity`, () => {
    for (const value of ['-1', '0', '1', '1000']) {
      const element = input(selector, value); assert.equal(read(), Number(value)); assert.equal(element.validity, '');
      const settings = context.currentSettings();
      assert.equal(selector === '#grounding-count' ? settings.groundingCount : settings.members[0].groundingCount, Number(value));
    }
    assert.equal(saves, 4);
  });
}
check('blank member override is shared; blank global grounding is invalid', () => {
  input('#grounding-LABOUR', ''); assert.equal(context.setup.members.LABOUR.groundingCount, null);
  assert.equal(Object.hasOwn(context.currentSettings().members[0], 'groundingCount'), false);
  saves = 0; input('#grounding-count', ''); assert.equal(context.setup.groundingCount, -1); assert.equal(saves, 0);
  assert.throws(() => context.currentSettings(), /Grounding count/);
});
for (const value of ['123', '9007199254740993', '-9007199254740993', '9223372036854775807', '-9223372036854775808']) {
  check(`preparation seed retains exact signed integer ${value}`, async () => {
    selected('#pilot-seed').value = value; await context.preparePilot();
    assert.equal(requests.length, 1); assert.equal(requests[0].url, '/api/pilots/source-fixture/prepare');
    assert.equal(requests[0].body, `{"seed":${value}}`); assert.equal(refreshed, 1);
  });
}
check('invalid preparation seeds make no request or refresh', async () => {
  for (const value of ['', '1.0000000000000000001', '1e-400', '1.0', '1e0',
    '9223372036854775808', '-9223372036854775809', 'PRIVATE_INPUT_SENTINEL']) {
    selected('#pilot-seed').value = value; await context.preparePilot();
    assert.equal(requests.length, 0); assert.equal(refreshed, 0);
    assert.match(selected('#pilot-status').textContent, /Preparation seed/);
    assert.equal(selected('#pilot-status').textContent.includes('PRIVATE_INPUT_SENTINEL'), false);
  }
});
check('integer seed normalization preserves a canonical numeric token', async () => {
  selected('#pilot-seed').value = '+000123'; await context.preparePilot();
  assert.equal(requests[0].body, '{"seed":123}');
});

(async () => {
  let failures = 0;
  for (const { name, test } of checks) {
    reset();
    try { await test(); console.log(`PASS ${name}`); }
    catch (error) { failures++; console.error(`FAIL ${name}: ${error.message}`); }
  }
  console.log(`Browser integer controls: ${checks.length - failures}/${checks.length} passed; controlled DOM/fetch/storage, no native browser claim.`);
  if (failures) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });

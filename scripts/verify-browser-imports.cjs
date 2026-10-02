/* Optional developer check: actual frontend functions, native Blob/TextDecoder, controlled DOM/fetch. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const functions = ['readJsonFile', 'api', 'importPilot', 'importTranscript'].map(name => {
  const match = source.match(new RegExp(`async function ${name}\\([^\\n]*\\) \\{[\\s\\S]*?\\r?\\n\\}`));
  assert.ok(match, `Frontend function ${name} must be available`);
  return match[0];
});

let requests, nodes, refreshed, opened, failResponse;
const context = vm.createContext({
  TextDecoder,
  $: selector => nodes[selector] ||= { textContent: '' },
  fetch: async (url, init) => {
    requests.push({ url, ...init });
    return { ok: !failResponse, json: async () => failResponse
      ? { error: 'Invalid JSON for Transcript' }
      : { id: 'fixture-id', items: 200, reviewStatus: 'unreviewed' } };
  },
  refreshPilots: async () => { refreshed++; },
  refreshSavedRuns: async () => { refreshed++; },
  openSitting: () => { opened++; },
});
vm.runInContext(functions.join('\n'), context);
function reset() { requests = []; nodes = {}; refreshed = 0; opened = 0; failResponse = false; }
async function importFile(method, file) {
  const event = { target: { files: [file], value: 'selected-file' } };
  await context[method](event);
  assert.equal(event.target.value, '', 'File selection resets after success/refusal');
}
function sentExactly(url, text) {
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, url); assert.equal(requests[0].method, 'POST');
  assert.equal(requests[0].headers['Content-Type'], 'application/json');
  assert.equal(requests[0].body, text, 'Decoded JSON source must reach strict backend validation unchanged');
}
const checks = [];
function check(name, test) { checks.push({ name, test }); }

check('large Unicode transcript exceeds 2 MiB without frontend refusal', async () => {
  const text = '{"text":"' + 'Māori 😀 e\u0301 '.repeat(180000) + '"}';
  const file = new Blob([text]); assert.ok(file.size > 2 * 1024 * 1024);
  await importFile('importTranscript', file); sentExactly('/api/debates/import', text);
  assert.equal(refreshed, 1); assert.equal(opened, 1);
});
check('transcript duplicate keys and exact numeric tokens are preserved', async () => {
  const text = '{"schemaVersion":2,"schemaVersion":2.0000000000000000001,"startedAt":9007199254740993,"text":"Tēnā 😀"}';
  await importFile('importTranscript', new Blob([text])); sentExactly('/api/debates/import', text);
});
check('pilot duplicate keys and exact numeric tokens are preserved', async () => {
  const text = '{"seed":9007199254740993,"seed":1.0000000000000000001,"text":"Tēnā 😀"}';
  await importFile('importPilot', new Blob([text])); sentExactly('/api/pilots', text);
});
check('pilot chooser follows the ordinary 2 MiB backend ceiling', async () => {
  const text = '{"text":"' + 'ā'.repeat(800000) + '"}'; const file = new Blob([text]);
  assert.ok(file.size > 1500000 && file.size < 2 * 1024 * 1024);
  await importFile('importPilot', file); sentExactly('/api/pilots', text);
});
for (const [method, limit, status] of [
  ['importTranscript', 64 * 1024 * 1024, '#saved-status'], ['importPilot', 2 * 1024 * 1024, '#pilot-status'],
]) {
  check(`${method} refuses oversized files before decoding/request`, async () => {
    await importFile(method, { size: limit + 1, arrayBuffer: async () => assert.fail('Reject before reading bytes') });
    assert.equal(requests.length, 0); assert.match(nodes[status].textContent, /(?:64|2) MiB/);
    assert.equal(refreshed, 0); assert.equal(opened, 0);
  });
  check(`${method} accepts the inclusive chooser boundary`, async () => {
    const text = '{"text":"Māori 😀"}'; const blob = new Blob([text]);
    await importFile(method, { size: limit, arrayBuffer: () => blob.arrayBuffer() });
    sentExactly(method === 'importTranscript' ? '/api/debates/import' : '/api/pilots', text);
  });
  check(`${method} refuses malformed UTF-8 before a request`, async () => {
    for (const bytes of [[0xc3, 0x28], [0xed, 0xa0, 0x80], [0xf0, 0x9f], [0xff]]) {
      await importFile(method, new Blob([new Uint8Array(bytes)]));
      assert.equal(requests.length, 0); assert.match(nodes[status].textContent, /valid UTF-8/);
    }
  });
  check(`${method} reports backend refusal without refreshing/opening`, async () => {
    failResponse = true;
    await importFile(method, new Blob(['{"invalid":true}']));
    assert.equal(requests.length, 1); assert.match(nodes[status].textContent, /Invalid JSON for Transcript/);
    assert.equal(refreshed, 0); assert.equal(opened, 0);
  });
}
check('BOM removal preserves decoded Unicode JSON text', async () => {
  const text = '{"text":"Tēnā 😀 e\u0301 \ufffd"}';
  await importFile('importTranscript', new Blob([new Uint8Array([0xef, 0xbb, 0xbf]), text]));
  sentExactly('/api/debates/import', text);
});
check('ordinary object API bodies still serialize once', async () => {
  await context.api('/api/debates/fixture/speaker', { method: 'POST', body: { message: 'Order ā 😀' } });
  sentExactly('/api/debates/fixture/speaker', '{"message":"Order ā 😀"}');
});

(async () => {
  let failures = 0;
  for (const { name, test } of checks) {
    reset();
    try { await test(); console.log(`PASS ${name}`); }
    catch (error) { failures++; console.error(`FAIL ${name}: ${error.message}`); }
  }
  console.log(`Browser import functions: ${checks.length - failures}/${checks.length} passed; controlled DOM/fetch, no native browser claim.`);
  if (failures) process.exitCode = 1;
})().catch(error => { console.error(error); process.exitCode = 1; });

/* Optional developer check: native Blob/TextDecoder file behavior, no browser upload automation. */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const helper = source.match(/async function readJsonFile\(file\) \{[\s\S]*?\r?\n\}/);
assert.ok(helper, 'Browser file reader must be available');
const context = vm.createContext({ TextDecoder });
vm.runInContext(helper[0] + '\nthis.readFile = readJsonFile;', context);
(async () => {
  const text = JSON.stringify({ text: 'Tēnā 😀 e\u0301 \ufffd' });
  assert.equal(await context.readFile(new Blob([text])), text);
  assert.equal(await context.readFile(new Blob([new Uint8Array([0xef, 0xbb, 0xbf]), text])), text);
  for (const invalid of [[0xc3, 0x28], [0xed, 0xa0, 0x80], [0xf0, 0x9f], [0xff]])
    await assert.rejects(context.readFile(new Blob([new Uint8Array(invalid)])), /valid UTF-8/);
  console.log('Browser file decoding: Unicode/BOM preserved, four malformed byte forms rejected.');
})().catch(error => { console.error(error); process.exitCode = 1; });

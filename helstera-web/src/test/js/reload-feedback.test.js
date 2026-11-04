const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/web/index.html'), 'utf8');

async function runHandler(id, response) {
  const start = html.indexOf(`$('${id}').onclick = async () => {`);
  assert.notEqual(start, -1);
  const end = html.indexOf('\n};', start);
  assert.notEqual(end, -1);
  const elements = new Map();
  const messages = [];
  const context = {
    $: key => {
      if (!elements.has(key)) elements.set(key, { value: 'test', dataset: { touched: '1' } });
      return elements.get(key);
    },
    api: async () => response,
    toast: (message, kind) => messages.push({ message, kind }),
    setOut: (element, message) => { element.output = message; },
    loadLoot: async () => {},
    loadSpawners: async () => {},
    JSON
  };
  vm.runInNewContext(html.slice(start, end + 3), context);
  await elements.get(id).onclick();
  return { elements, messages };
}

test('save operations show reload failures and preserve unsaved editor state', async () => {
  for (const [button, output, editor] of [['bSaveLoot', 'lootOut', 'lootText'], ['bSaveSp', 'spOut2', 'spText']]) {
    const result = await runHandler(button, { ok: false, message: 'saved but reload failed' });
    assert.match(result.elements.get(output).output, /saved but reload failed/);
    assert.equal(result.messages[0].message, 'saved but reload failed');
    assert.equal(result.messages[0].kind, 'err');
    assert.equal(result.elements.get(editor).dataset.touched, '1');
  }
});

test('manual spawner reload displays failure as an error', async () => {
  const result = await runHandler('bSpReload', { ok: false, message: 'reload failed' });
  assert.equal(result.messages[0].message, 'reload failed');
  assert.equal(result.messages[0].kind, 'err');
});

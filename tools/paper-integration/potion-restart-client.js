'use strict';

const assert = require('node:assert/strict');
const mineflayer = require('mineflayer');
const { snapshot, validateSnapshot, validatePackets } = require('./potion-restart-contract');
const minecraftData = require('minecraft-data')('1.21.1');

const [host = '127.0.0.1', rawPort = '25731', mode = 'prepare', serverDirectory, owner, watcher, cow] = process.argv.slice(2);
const port = Number(rawPort);
assert.ok(Number.isInteger(port) && port > 0 && port < 65536, 'Invalid Paper port');
assert.ok(['prepare', 'verify-clear', 'clean'].includes(mode), 'Invalid restart stage');
assert.ok(serverDirectory && (mode === 'prepare' || (owner && watcher && cow)), 'Missing saved restart fixture identity');
const identities = { owner, watcher, cow };
const saved = mode === 'prepare' ? null : snapshot(serverDirectory, identities);
if (saved) validateSnapshot(saved, identities, mode === 'clean');

const events = [];
const bots = new Map();
const ready = new Set();
const done = new Set();
let complete = false;
const timer = setTimeout(() => finish(new Error('Timed out waiting for restart probe')), 120000);

function record(event) {
  events.push(event);
  console.log(`RESTART_EVENT ${JSON.stringify(event)}`);
}

function finish(error) {
  if (complete) return;
  complete = true;
  clearTimeout(timer);
  if (!error) {
    try {
      if (mode === 'prepare') validatePreparedPackets();
      else {
        const contractMode = mode === 'verify-clear' ? 'cleared' : 'clean';
        validatePackets(events, identities, saved, contractMode);
      }
      console.log(`PAPER_POTION_RESTART_CLIENT passed=true stage=${mode} roles=2 packets=${events.filter(e => e.type === 'packet').length}`);
    } catch (failure) { error = failure; }
  }
  if (error) console.error(`PAPER_POTION_RESTART_CLIENT passed=false stage=${mode} error=${error.stack || error.message}`);
  for (const bot of bots.values()) bot.quit();
  setTimeout(() => process.exit(error ? 1 : 0), 250);
}

function validatePreparedPackets() {
  for (const role of ['owner', 'watcher']) {
    const records = events.filter(event => event.type === 'packet' && event.role === role);
    assert.equal(records.filter(event => event.packetType === 'login').length, 1, 'Missing initial login');
    assert.equal(events.filter(event => event.type === 'identity' && event.role === role).length, 1, 'Missing client identity');
    const cows = records.filter(event => event.packetType === 'spawn_entity'
      && event.data.type === minecraftData.entitiesByName.cow.id);
    assert.ok(cows.length, `${role} did not track the persistent recipient Cow`);
    const cowIds = new Set(cows.map(event => event.data.entityId));
    const cowMetadata = records.filter(event => event.packetType === 'entity_metadata' && cowIds.has(event.data.entityId));
    assert.ok(cowMetadata.some(event => event.data.metadata.some(field => field.key === 0 && (field.value & 32) !== 0)),
      `${role} did not receive invisible Cow metadata`);
    const effects = records.filter(event => event.packetType === 'entity_effect');
    assert.ok(effects.every(event => role === 'owner' && event.data.entityId === records.find(item => item.packetType === 'login').data.entityId),
      'An observer received a watched-entity effect packet');
    if (role === 'owner') assert.deepEqual(effects.map(event => event.data.effectId).sort((a, b) => a - b), [2, 13]);
  }
}

function connect(role) {
  const username = role === 'owner' ? 'SyncOwner' : 'SyncWatcher';
  const bot = mineflayer.createBot({ host, port, username, version: '1.21.1', auth: 'offline' });
  bot.physicsEnabled = false;
  bots.set(role, bot);

  for (const type of ['login', 'spawn_entity', 'entity_metadata', 'entity_effect', 'remove_entity_effect']) {
    bot._client.on(type, original => {
      if (complete) return;
      let data = original;
      if (type === 'login') data = { entityId: original.entityId };
      else if (type === 'spawn_entity') data = {
        entityId: original.entityId, objectUUID: original.objectUUID, type: original.type
      };
      else if (type === 'entity_metadata') data = {
        entityId: original.entityId,
        metadata: original.metadata.filter(entry => [0, 10, 11].includes(entry.key))
      };
      if (type === 'entity_metadata' && !data.metadata.length) return;
      record({ type: 'packet', role, packetType: type, data });
    });
  }

  bot.once('spawn', () => record({ type: 'identity', role, uuid: bot.player.uuid }));
  bot.on('message', message => {
    if (complete) return;
    const text = message.toString();
    if (!text.startsWith('POTION_RESTART ')) return;
    record({ type: 'message', role, text });
    const readyMatch = /^POTION_RESTART READY mode=(prepare|verify-clear|clean)$/.exec(text);
    if (readyMatch) {
      assert.equal(readyMatch[1], mode, 'Server announced the wrong restart stage');
      if (!ready.has(role)) {
        ready.add(role);
        bot.chat(`/potionrestart ${mode}`);
      }
      return;
    }
    const doneMatch = /^POTION_RESTART DONE mode=(prepare|verify-clear|clean) passed=(true|false)$/.exec(text);
    if (!doneMatch) return;
    assert.equal(doneMatch[1], mode, 'Server completed the wrong restart stage');
    if (doneMatch[2] !== 'true') {
      finish(new Error('Paper restart probe reported failure'));
      return;
    }
    done.add(role);
    if (done.size === 2) finish(null);
  });
  bot.on('error', error => finish(error));
  bot.on('kicked', reason => finish(new Error(`Kicked ${role}: ${String(reason)}`)));
  bot.on('end', () => { if (!complete) finish(new Error(`Unexpected disconnect: ${role}`)); });
}

try {
  connect('owner');
  connect('watcher');
} catch (error) { finish(error); }

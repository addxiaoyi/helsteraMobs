'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { PotionSyncContract, serverAssertions, validateServerLog } = require('./potion-sync-contract');
const { validateEvidence } = require('./potion-sync-validate');

function fixture() {
  const events = [];
  let owner = 41, watcher = 42;
  const cow = 43;
  const packet = (role, packetType, data) => events.push({ type: 'packet', role, packetType, data });
  const connect = role => {
    events.push({ type: 'connect', role });
    packet(role, 'login', { entityId: role === 'owner' ? owner : watcher });
    packet(role, 'spawn_entity', { entityId: cow });
    if (role === 'watcher') packet(role, 'spawn_entity', { entityId: owner });
  };
  const message = (role, text) => events.push({ type: 'message', role, text });
  const both = text => { message('owner', text); message('watcher', text); };
  const add = (effectId, duration, amplifier, flags) => packet('owner', 'entity_effect', {
    entityId: owner, effectId, duration, amplifier, flags
  });
  const remove = effectId => packet('owner', 'remove_entity_effect', { entityId: owner, effectId });
  connect('owner'); connect('watcher');
  for (const backend of ['native', 'imported']) {
    for (const stage of ['live', 'watcher-rejoin', 'owner-rejoin', 'changed', 'hidden', 'cleared', 'clean-rejoin']) {
      const marker = command => `POTION_SYNC ${command} backend=${backend} stage=${stage} owner=${owner} cow=${cow}`;
      both(marker('BEGIN'));
      if (stage.endsWith('rejoin')) {
        both(marker('RECONNECT'));
        const role = stage === 'watcher-rejoin' ? 'watcher' : 'owner';
        if (role === 'owner') {
          const old = owner;
          owner += 10;
          packet('watcher', 'entity_destroy', { entityIds: [old] });
          packet('watcher', 'spawn_entity', { entityId: owner });
        } else watcher += 10;
        connect(role);
        if (stage === 'owner-rejoin') { add(2, 1000, 2, 3); add(13, 1000, 0, 1); }
      } else if (stage === 'live') { add(2, 1200, 2, 11); add(13, 1200, 0, 9); }
      else if (stage === 'changed' || stage === 'hidden') {
        remove(2); add(2, 1200, 1, stage === 'changed' ? 14 : 8);
      } else { remove(2); remove(13); }
      for (const role of ['owner', 'watcher']) for (const id of role === 'watcher' ? [cow, owner] : [cow]) {
        packet(role, 'entity_metadata', { entityId: id, metadata: [
          { key: 0, type: 'byte', value: ['cleared', 'clean-rejoin'].includes(stage) ? 0 : 32 },
          { key: 10, type: 'particles', value: ['hidden', 'cleared', 'clean-rejoin'].includes(stage)
            ? [] : [{ type: 'entity_effect', data: stage === 'changed' ? -2506685 : 651804739 }] },
          { key: 11, type: 'boolean', value: stage !== 'changed' && !(stage === 'clean-rejoin' && id === owner) }
        ] });
      }
      both(marker('CHECK haste=990 invisible=990'));
    }
  }
  both('POTION_SYNC DONE passed=true');
  return events;
}

function replay(events) {
  const contract = new PotionSyncContract();
  let result;
  for (const event of events) {
    if (event.type === 'connect') contract.connect(event.role);
    else if (event.type === 'packet') contract.packet(event.role, event.packetType, event.data);
    else result = contract.message(event.role, event.text) || result;
  }
  return result;
}
const serverLog = () => ['native', 'imported'].flatMap(backend => ['haste', 'invisibility'].flatMap(effect => [
  `[自检] potion-sync-duration backend=${backend} stage=owner-rejoin effect=${effect} quit=1001 join=1000`,
  `[自检] potion-sync-duration backend=${backend} stage=clean-rejoin effect=${effect} quit=0 join=0`
])).join('\n') + `\n[自检] potion-sync ${serverAssertions().map(name => `${name}=true`).join(' ')} assertions=${serverAssertions().length} passed=true`;
const clientLog = events => events.map(event => 'SYNC_EVENT ' + JSON.stringify(event)).join('\n') +
  '\nPAPER_POTION_SYNC_CLIENT passed=true comparisons=14 checkpoints=28 reconnections=6';

test('native/imported two-client reconnect fixture and evidence replay pass', () => {
  assert.deepEqual(replay(fixture()), { comparisons: 14, checkpoints: 28, reconnections: 6 });
  assert.equal(validateEvidence(serverLog(), clientLog(fixture())).serverAssertions, serverAssertions().length);
});
for (const [name, mutate] of [
  ['lost observer metadata', e => e.type === 'packet' && e.packetType === 'entity_metadata' ? null : e],
  ['wrong invisible flag', e => {
    if (e.packetType === 'entity_metadata') e.data.metadata[0].value ^= 32;
    return e;
  }],
  ['wrong particle color', e => {
    if (e.packetType === 'entity_metadata' && e.data.metadata[1].value.length) e.data.metadata[1].value[0].data = 0;
    return e;
  }],
  ['wrong ambient state', e => {
    if (e.packetType === 'entity_metadata') e.data.metadata[2].value = !e.data.metadata[2].value;
    return e;
  }],
  ['wrong metadata serializer', e => {
    if (e.packetType === 'entity_metadata') e.data.metadata[1].type = 'int';
    return e;
  }],
  ['duration reset on login', e => {
    if (e.packetType === 'entity_effect' && e.data.flags === 3) e.data.duration = 1200;
    return e;
  }],
  ['wrong login blend', e => {
    if (e.packetType === 'entity_effect' && e.data.flags === 3) e.data.flags = 11;
    return e;
  }],
  ['lost effect removal', e => e.packetType === 'remove_entity_effect' ? null : e],
  ['missing tracking spawn', e => e.packetType === 'spawn_entity' ? null : e],
  ['missing old entity removal', e => e.packetType === 'entity_destroy' ? null : e]
]) test(`rejects ${name}`, () => assert.throws(() => replay(fixture().map(mutate).filter(Boolean))));
test('ordinary watcher must not receive watched-entity effect duration packets', () => {
  const events = fixture();
  const index = events.findIndex(e => e.packetType === 'entity_effect');
  events.splice(index + 1, 0, { ...events[index], role: 'watcher' });
  assert.throws(() => replay(events), /ordinary observer/);
});
test('aggregate success, duplicated and missing checkpoints cannot pass', () => {
  const events = fixture();
  assert.throws(() => replay(events.filter(e => e.type !== 'message' || !e.text.includes(' CHECK '))));
  assert.throws(() => replay([{ type: 'connect', role: 'owner' }, { type: 'message', role: 'owner', text: 'POTION_SYNC DONE passed=true' }]));
  const index = events.findIndex(e => e.type === 'message' && e.text.includes(' CHECK '));
  events.splice(index, 0, events[index]);
  assert.throws(() => replay(events));
});
test('server assertion names, cleanup and individual false values are mandatory', () => {
  assert.equal(validateServerLog(serverLog()).count, serverAssertions().length);
  for (const bad of [serverLog().replace('cleanup=true ', ''), serverLog().replace('cleanup=true', 'cleanup=false'),
    serverLog().replace('source-imported=true', 'source-identical=true'),
    serverLog().replace('native-owner-rejoin-persisted-duration=true', 'native-owner-rejoin-persisted-duration=false'),
    serverLog().replace('quit=1001 join=1000', 'quit=1001 join=1200'),
    serverLog().replace('effect=invisibility quit=1001 join=1000', 'effect=invisibility quit=1001 join=1200'),
    serverLog().replace('[自检] potion-sync-duration backend=native stage=clean-rejoin effect=haste quit=0 join=0', '')]) {
    assert.throws(() => validateServerLog(bad));
  }
});
test('truncated client log and conflicting failure marker cannot be archived as passing', () => {
  assert.throws(() => validateEvidence(serverLog(), 'PAPER_POTION_SYNC_CLIENT passed=true comparisons=14 checkpoints=28 reconnections=6'));
  assert.throws(() => validateEvidence(serverLog(), clientLog(fixture()) + '\nPAPER_POTION_SYNC_CLIENT passed=false'));
});
test('unrelated Display metadata with other serializers is ignored', () => {
  const events = fixture();
  events.splice(2, 0, { type: 'packet', role: 'owner', packetType: 'entity_metadata', data: {
    entityId: 999, metadata: [{ key: 10, type: 'int', value: 0 }, { key: 11, type: 'vector3', value: { x: 1, y: 1, z: 1 } }]
  } });
  assert.equal(replay(events).checkpoints, 28);
});
